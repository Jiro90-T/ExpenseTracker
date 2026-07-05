# Phase 4b — Google Drive Provider — Design

**Status:** Approved 2026-07-04
**Phase:** 4b (second of 4a/4b/4c/4d)
**Predecessor:** `sync/` package from Phase 4a (`CloudSyncRepository` interface, `SyncSnapshot`/`SyncState`/`PullResult`/`SyncResult`/`SignInResult`, `SyncSnapshotCodec`, `DeviceIdProvider`, `NoOpCloudSyncRepository`).
**Successors:** 4c (Dropbox provider — same shape), 4d (UI + triggers + manual-merge).

## Goal

Replace `NoOpCloudSyncRepository` with a real `GoogleDriveCloudSyncRepository`
that drives the user's Google Drive via the OAuth flow and the Drive REST v3
API. 4b is everything 4a deferred: real auth, real token storage, real I/O.
4b is still backend-only — no Settings UI, no trigger, no WorkManager.

Out of scope (intentional, deferred): Settings "Sign in" entry, sync indicator,
push/pull triggers, manual-merge UI, Dropbox, encryption at rest beyond the
SHA-256 checksum, multi-account, receipt binaries in the cloud.

## Architecture

```
ViewModel / Activity (4d)
    │  ActivityResultLauncher<Intent>
    ▼
GoogleDriveCloudSyncRepository (4b) — implements CloudSyncRepository
    ├──── GoogleAuth                (Play Services Auth wrapper)
    ├──── DriveApiClient            (OkHttp + Drive REST v3)
    ├──── SyncTokensRepository      (DataStore + Android Keystore AES-GCM)
    └──── SyncSnapshotCodec         (already exists, 4a)

SyncModule @Binds: NoOpCloudSyncRepository → GoogleDriveCloudSyncRepository
```

The split mirrors 4a: each piece has one job, they communicate through small
interfaces, and the wire-level surface (Drive REST) is the only thing that
talks to the network.

## OAuth entry-point pattern

`CloudSyncRepository.signIn(): SignInResult` is `suspend`, which means the
repo can't directly launch an `ActivityResultLauncher<Intent>` from inside
it. 4b splits the sign-in flow into two calls:

```kotlin
internal interface CloudSyncRepository {
    val signInIntent: Intent                    // NEW (4b): pre-built intent
    suspend fun handleSignInResult(data: Intent?): SignInResult  // NEW (4b)
    suspend fun signIn(): SignInResult          // 4a: silent path (cached token)
    // ... rest unchanged
}
```

Wait — modifying the existing `CloudSyncRepository` interface is a breaking
change for 4a's `NoOpCloudSyncRepository` and any future consumers. Instead,
4b adds a *separate* interface that Drive implements:

```kotlin
internal interface DriveCloudSignInLauncher {
    val signInIntent: Intent
    suspend fun handleSignInResult(data: Intent?): SignInResult
}

internal interface CloudSyncRepository {
    // 4a — unchanged
    suspend fun signIn(): SignInResult
    // ...
}
```

`GoogleDriveCloudSyncRepository` implements both interfaces. ViewModels in
4d inject `CloudSyncRepository` for the silent path and additionally
`DriveCloudSignInLauncher` (or do a runtime cast — see "4d wiring" below)
when they need the interactive path.

Actually — let's reconsider. We can put the interactive method on
`CloudSyncRepository` itself because 4a is the only consumer so far, and
4a's `NoOpCloudSyncRepository` can implement it trivially (returns a no-op
intent + always-`Success` result). Modifying the interface is cheaper than
introducing a second interface. **Decision: amend `CloudSyncRepository` in
4b, update `NoOpCloudSyncRepository` in the same commit.**

Final interface (4b):

```kotlin
internal interface CloudSyncRepository {
    val state: StateFlow<SyncState>
    val lastSyncedAtEpochMillis: StateFlow<Long?>
    val isSignedIn: StateFlow<Boolean>

    val signInIntent: Intent                                    // NEW
    suspend fun handleSignInResult(data: Intent?): SignInResult  // NEW
    suspend fun signIn(): SignInResult                           // existing (now: silent)
    suspend fun signOut()                                        // existing
    suspend fun push(snapshot: SyncSnapshot): PushResult         // existing
    suspend fun pull(): PullResult<SyncSnapshot>                 // existing
    suspend fun syncOnce(): SyncResult                           // existing
}
```

`signIn()` is now the *silent* path: returns `Success` if a cached token is
still valid, `Failed` if not. The interactive path is `signInIntent` +
`handleSignInResult`.

`NoOpCloudSyncRepository` updates to satisfy the new interface:
- `signInIntent`: returns `Intent()` (no-op)
- `handleSignInResult`: returns `SignInResult.Success` always

This keeps `NoOpCloudSyncRepository` usable as a test stub and as a
fallback if Drive wiring is disabled.

## GoogleAuth — Play Services Auth wrapper

```kotlin
internal interface GoogleAuth {
    suspend fun getLastSignedInAccount(): GoogleAccountSnapshot?
    fun buildSignInIntent(): Intent
    suspend fun extractAccountFromResult(data: Intent?): GoogleAccountSnapshot?
}

internal data class GoogleAccountSnapshot(
    val email: String,
    val serverAuthCode: String?,
    val idToken: String?,
)
```

`GoogleSignInAuthImpl` is the production impl. It wraps
`GoogleSignIn.getClient(context, googleSignInOptions)`. Tests use
`FakeGoogleAuth` which returns scripted snapshots.

`GoogleSignInOptions` is built with:
- `requestServerAuthCode(webClientId)` — gives us the one-time code we
  exchange for an access token via REST.
- `requestEmail()` — for `accountEmail` storage.
- `requestIdToken(webClientId)` — optional; verified only if present.

We do NOT request `requestProfile()`. Email is enough.

## DriveApiClient — OkHttp + Drive REST v3

```kotlin
internal interface DriveApiClient {
    suspend fun upload(fileId: String?, body: String, mimeType: String): String
    suspend fun download(fileId: String): String?
}
```

Implementations:
- `upload(fileId=null)` → POST to `https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart` with multipart body containing metadata JSON + the encoded snapshot. Returns the new file's ID.
- `upload(fileId="abc")` → PATCH to `https://www.googleapis.com/upload/drive/v3/files/abc?uploadType=multipart` with same multipart shape. Returns "abc".
- `download(fileId="abc")` → GET to `https://www.googleapis.com/drive/v3/files/abc?alt=media`. Returns the body string, or null on 404.

The multipart format is `multipart/related` with two parts: a JSON metadata
part and a binary body part. We build it manually with OkHttp's
`MultipartBody.Builder()`.

Auth: every call adds `Authorization: Bearer <accessToken>` from
`SyncTokensRepository`. If 401 returned → throw `DriveApiException.AuthRevoked`.

Retries: 429 with `Retry-After` header → wait and retry (max 3). 5xx → 1s
backoff, retry once. After max attempts → throw `DriveApiException`.

```kotlin
internal sealed class DriveApiException(message: String, cause: Throwable? = null)
    : RuntimeException(message, cause) {
    object AuthRevoked : DriveApiException("Auth revoked")
    object QuotaExceeded : DriveApiException("Drive quota exceeded")
    object RateLimited : DriveApiException("Drive rate limit exceeded")
    data class ServerError(val code: Int) : DriveApiException("Drive server error $code")
    object NotFound : DriveApiException("File not found")
    data class Generic(val code: Int, msg: String) : DriveApiException("Drive rejected: $code $msg")
}
```

## SyncTokensRepository — DataStore + Android Keystore AES-GCM

```kotlin
internal data class SyncTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val accountEmail: String,
    val snapshotFileId: String?,        // null until first push
)

internal interface SyncTokensRepository {
    suspend fun load(): SyncTokens?
    suspend fun save(tokens: SyncTokens)
    suspend fun clear()
}
```

Persistence:
- A single DataStore Preferences file (`sync_tokens.preferences_pb`).
- Values are stored as base64-encoded ciphertext: `access_token_b64`,
  `refresh_token_b64`, etc.
- Encryption: AES-256-GCM with a key from Android Keystore
  (`expensetracker_sync_key`), no user auth required
  (`setUserAuthenticationRequired(false)`).
- Each value is encrypted independently (separate IV per value).

On `load()`:
- Read all 5 ciphertext strings from DataStore.
- Decrypt each. If decryption throws `KeyPermanentlyInvalidatedException` (user
  changed lock screen) → wipe DataStore, return null, log a warning.
- If decryption throws anything else → wipe DataStore, return null, log
  ERROR.

On `save()`:
- Encrypt all 5 fields and write to DataStore atomically.

On `clear()`:
- Delete the DataStore file. Subsequent `load()` returns null.

The Keystore key is created lazily on first `save()`. `KeyGenParameterSpec`:
- `keySize = 256`
- `blockModes = GCM`
- `encryptionPaddings = NONE` (GCM doesn't pad)
- `randomizedEncryptionRequired = true`

No biometric / device-credential gate. The Keystore-backed key is already
stronger than `EncryptedSharedPreferences` because the key never leaves the
TEE/StrongBox on supported devices.

## GoogleDriveCloudSyncRepository — the orchestrator

```kotlin
@Singleton
internal class GoogleDriveCloudSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googleAuth: GoogleAuth,
    private val api: DriveApiClient,
    private val tokens: SyncTokensRepository,
    private val deviceIds: DeviceIdProvider,
) : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val signInIntent: Intent = googleAuth.buildSignInIntent()

    override suspend fun signIn(): SignInResult {
        // Silent path: cached token still valid?
        val cached = tokens.load() ?: return SignInResult.Failed("Not signed in")
        if (cached.expiresAtEpochMillis > System.currentTimeMillis() + 60_000) {
            _state.value = SyncState.SignedIn("google_drive")
            return SignInResult.Success
        }
        // Try silent refresh via GoogleSignIn.getLastSignedInAccount
        val account = googleAuth.getLastSignedInAccount() ?: return SignInResult.Failed("Session expired")
        return refreshTokens(account)
    }

    override suspend fun handleSignInResult(data: Intent?): SignInResult {
        val account = googleAuth.extractAccountFromResult(data)
            ?: return SignInResult.Failed("Sign-in cancelled")
        val code = account.serverAuthCode
            ?: return SignInResult.Failed("Could not get auth code")
        return exchangeCode(code, account.email)
    }

    private suspend fun exchangeCode(code: String, email: String): SignInResult {
        return try {
            val response = httpClient.newCall(
                Request.Builder()
                    .url("https://oauth2.googleapis.com/token")
                    .post(/* form-urlencoded body */)
                    .build()
            ).execute()
            // parse {access_token, refresh_token, expires_in} from response
            // save to tokens repo
            // flip state to SignedIn
            SignInResult.Success
        } catch (e: Exception) {
            SignInResult.Failed("Token exchange failed: ${e.message}", e)
        }
    }

    override suspend fun signOut() {
        tokens.clear()
        _state.value = SyncState.SignedOut
    }

    override suspend fun push(snapshot: SyncSnapshot): PushResult {
        if (_state.value !is SyncState.SignedIn) {
            return PushResult(...) // compile error: PushResult has no Failure variant
        }
        // ...
    }

    // pull(), syncOnce() — see below
}
```

Wait — `PushResult` in 4a is `data class PushResult(val pushedAtEpochMillis: Long)`. It has no failure variant. Push failures need a return path. Options:
1. Add `PushResult.Failed(message, cause)` to the sealed class. (4b widens the 4a contract.)
2. Throw on push failure, let the caller catch.
3. Repurpose `PullResult.Failed` and add a `PushResult.Failed` mirror.

**Decision: option 1.** Add `PushResult.Failed(message, cause)` in 4b. Update
`NoOpCloudSyncRepository` to satisfy it (its `push` still throws
`NotImplementedError` since 4a had no failure path).

This is the same kind of minor widening that 4a's visibility cascade caused:
the 4b implementation genuinely needs more result types than 4a planned for.

## Error handling matrix (Drive repo behavior)

| Failure | Surfaced as | State transition |
|---|---|---|
| `signIn` cached token expired + `getLastSignedInAccount` returns null | `SignInResult.Failed("Session expired")` | stays `SignedOut` |
| `handleSignInResult` returns null (user cancelled) | `SignInResult.Failed("Sign-in cancelled")` | stays `SignedOut` |
| `handleSignInResult` returns account with no `serverAuthCode` | `SignInResult.Failed("Could not get auth code")` | stays `SignedOut` |
| Token exchange HTTP error (400 invalid_grant) | `SignInResult.Failed("Auth code expired — please try again", cause)` | stays `SignedOut` |
| Token refresh HTTP error (400 invalid_grant) | `SignInResult.Failed("Session expired", cause)` | stays `SignedOut` |
| Token storage throws `KeyPermanentlyInvalidatedException` | `SignInResult.Failed("Secure storage was revoked — please sign in again", cause)` | stays `SignedOut` |
| Token storage throws anything else | `SignInResult.Failed("Secure storage unavailable", cause)` | stays `SignedOut` |
| `push` while not `SignedIn` | `PushResult.Failed("Not signed in", null)` | unchanged |
| `push` HTTP 401 mid-request | `PushResult.Failed("Session expired — please sign in again", cause)` | `SignedIn → SignedOut` (tokens cleared) |
| `push` HTTP 403 | `PushResult.Failed("Drive storage full", cause)` | unchanged |
| `push` HTTP 429 → 3 retries exhausted | `PushResult.Failed("Drive rate limit exceeded", cause)` | unchanged |
| `push` HTTP 5xx → 1 retry exhausted | `PushResult.Failed("Drive server error", cause)` | unchanged |
| `push` other 4xx | `PushResult.Failed("Drive rejected the request", cause)` | unchanged |
| `push` network (`UnknownHostException`, etc.) | `PushResult.Failed("Network unreachable", cause)` | unchanged |
| `push` `KeyPermanentlyInvalidatedException` on token load | `PushResult.Failed("Secure storage was revoked", cause)` | `SignedIn → SignedOut` |
| `pull` while not `SignedIn` | `PullResult.Failed("Not signed in", null)` | unchanged |
| `pull` with `snapshotFileId == null` | `PullResult.NoRemoteSnapshot` | unchanged |
| `pull` HTTP 404 | `PullResult.NoRemoteSnapshot` (remote file deleted) | unchanged |
| `pull` HTTP 401 | `PullResult.Failed("Session expired", cause)` | `SignedIn → SignedOut` |
| `pull` HTTP 429 → retries exhausted | `PullResult.Failed("Drive rate limit exceeded", cause)` | unchanged |
| `pull` HTTP 5xx → retries exhausted | `PullResult.Failed("Drive server error", cause)` | unchanged |
| `pull` `SyncException(CHECKSUM_MISMATCH)` from codec | `PullResult.Failed("Remote snapshot failed integrity check", cause)` | unchanged |
| `pull` `SyncException(SCHEMA_INCOMPATIBLE)` | `PullResult.Failed("Remote snapshot was written by a newer app version", cause)` | unchanged |
| `pull` `SyncException(MALFORMED)` | `PullResult.Failed("Remote snapshot is corrupted", cause)` | unchanged |
| `syncOnce` returns `PullResult.NoRemoteSnapshot` → tries push | propagates push result | depends on push outcome |
| `syncOnce` returns `PullResult.Success` | `SyncResult.Pulled(snapshot, pulledAtEpochMillis)` | updates `lastSyncedAtEpochMillis` |
| `syncOnce` returns `PullResult.Failed` | `SyncResult.Failed(message, cause)` | unchanged |
| `syncOnce` returns `PullResult.NoRemoteSnapshot` and push succeeds | `SyncResult.Pushed(pushedAtEpochMillis)` | updates `lastSyncedAtEpochMillis` |

## syncOnce() — what it actually does

`syncOnce()` is "pull and report". It does NOT consult the local DB. It does
NOT auto-resolve LWW. The caller (4d ViewModel) owns the LWW policy:
- Read `lastSyncedAtEpochMillis`.
- Pull → compare to local DB's last-write timestamp (via a new repo
  method in 4d: `getLocalLastModifiedEpochMillis()`).
- If remote newer → apply pull (4d responsibility, not 4b's).
- If local newer → push the local snapshot (4d calls `push()`).

`syncOnce()` therefore returns one of:
- `SyncResult.Pulled(snapshot, pulledAtEpochMillis)` — pull succeeded
- `SyncResult.NoRemoteSnapshot` — nothing to pull
- `SyncResult.Failed(message, cause)` — pull failed

This is intentionally narrower than the 4a spec's "auto-LWW" suggestion. The
4d layer is the right place for the policy because:
1. 4d has access to the Room DB and can build a real local snapshot.
2. 4d can show a "syncing now" UI indicator while syncOnce runs.
3. 4d can surface the conflict to the user when LWW is ambiguous.

`PullResult.Conflict` and `SyncResult.Conflict` stay in the type system for
future use (e.g., 4d's manual-merge UI), but `GoogleDriveCloudSyncRepository`
never returns them.

## State transitions

```
        signInIntent + handleSignInResult (success)
SignedOut ──────────────────────────────────────────► SignedIn("google_drive")
   ▲                                                       │
   │ signOut                                               │ signOut
   └───────────────────────────────────────────────────────┘
                                                            │
                                                            │ push / pull / syncOnce
                                                            ▼
                                                       Syncing(PUSH|PULL)
                                                            │
                                                            │ HTTP 401
                                                            ▼
                                                       SignedOut (tokens cleared)
```

`state` is a single `MutableStateFlow<SyncState>` guarded by `compareAndSet`.
Concurrent `push()` / `pull()` calls: the second one sees `Syncing` and
returns `Failed("Sync already in progress")`. We serialize within the repo
via a `Mutex`.

Actually — `compareAndSet` on `MutableStateFlow` doesn't help with
serialization. Two callers can both observe `SignedIn` before either
transitions to `Syncing`. The fix is a `Mutex`:

```kotlin
private val mutex = Mutex()

override suspend fun push(snapshot: SyncSnapshot): PushResult = mutex.withLock {
    val current = _state.value
    if (current !is SyncState.SignedIn) {
        return@withLock PushResult.Failed("Not signed in", null)
    }
    _state.value = SyncState.Syncing(Operation.PUSH)
    try {
        // ... HTTP call ...
    } finally {
        _state.value = SyncState.SignedIn("google_drive")
    }
}
```

Same for `pull()` and `syncOnce()`.

## Dependency changes

```toml
# gradle/libs.versions.toml — new entries
okhttp = "4.12.0"
playServicesAuth = "21.2.0"
playServicesBase = "18.5.0"

okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
play-services-auth = { group = "com.google.android.gms", name = "play-services-auth", version.ref = "playServicesAuth" }
play-services-base = { group = "com.google.android.gms", name = "play-services-base", version.ref = "playServicesBase" }
```

```kotlin
// app/build.gradle.kts — new lines
implementation(libs.okhttp)
implementation(libs.play.services.auth)
implementation(libs.play.services.base)
```

Test-only dep additions:
```toml
mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
```

```kotlin
testImplementation(libs.mockwebserver)
```

## Manifest changes

```xml
<!-- AndroidManifest.xml — single new permission -->
<uses-permission android:name="android.permission.INTERNET" />
```

No new `<activity>`. Play Services hosts the OAuth consent screen.

## Strings to add

```xml
<string name="default_web_client_id">…paste-from-google-cloud-console…</string>
<string name="google_drive_provider_id">google_drive</string>
```

`default_web_client_id` is the OAuth 2.0 client ID of the Web application
in Google Cloud Console. Set up by the developer (not generated by tooling).
The value lives in `local.properties` (not committed) and is injected into
`build.gradle.kts` via `buildConfigField`.

```kotlin
// app/build.gradle.kts — read from local.properties
android {
    defaultConfig {
        val localProps = Properties().apply {
            load(rootProject.file("local.properties").inputStream())
        }
        buildConfigField(
            "String",
            "DEFAULT_WEB_CLIENT_ID",
            "\"${localProps.getProperty("google.web.client.id", "")}\""
        )
    }
}
```

Then `GoogleSignInAuthImpl` reads `BuildConfig.DEFAULT_WEB_CLIENT_ID` at
construction time. Empty string in v1 means the OAuth flow will fail with a
clear "Google Drive not configured" error; 4d can show a setup hint.

## File inventory

New files (production):
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAuth.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleSignInAuthImpl.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAccountSnapshot.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClient.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiException.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokens.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepository.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/TokenCrypto.kt` (Keystore wrapper)
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/di/GoogleDriveModule.kt`

New files (tests):
- `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeGoogleAuth.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeDriveApiClient.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepositoryTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepositoryTest.kt`

Modified files:
- `gradle/libs.versions.toml` — add OkHttp + Play Services Auth + Play Services Base.
- `app/build.gradle.kts` — add dependencies + `buildConfigField` for client ID.
- `app/src/main/AndroidManifest.xml` — add `INTERNET` permission.
- `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt` — add `signInIntent` + `handleSignInResult`.
- `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt` — add `PushResult.Failed`.
- `app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt` — satisfy new interface members.
- `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt` — flip `@Binds` to `GoogleDriveCloudSyncRepository`.
- `app/src/main/res/values/strings.xml` — add 2 strings.
- `app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt` — add 2 tests for new members.

## Testing

Production: ~700 lines across 10 files.
Tests: ~500 lines across 5 files (1 test fake file plus 4 test classes).

- `DriveApiClientTest` (8 tests, MockWebServer) — round-trip upload with null ID, patch with set ID, 404 returns null, 401 surfaces AuthRevoked, 429 honors Retry-After, 500 retries once, 5xx gives up after retry, generic 4xx surface.
- `SyncTokensRepositoryTest` (3 tests, Robolectric) — round-trip save+load, clear wipes everything, KeyPermanentlyInvalidatedException triggers wipe.
- `FakeGoogleAuth` + `FakeDriveApiClient` — test doubles. Live in `test/` so they don't ship with the APK.
- `GoogleDriveCloudSyncRepositoryTest` (12 tests, Robolectric) — signInIntent is the Play Services intent, handleSignInResult persists tokens on success, handleSignInResult returns Failed when cancelled, push creates file when no ID, push patches when ID exists, pull returns Success on valid remote, pull returns NoRemoteSnapshot when no ID, pull returns NoRemoteSnapshot on 404, pull returns Failed on checksum mismatch, syncOnce returns Pulled on success, push returns Failed when not SignedIn, signOut clears tokens and flips state.
- Existing `NoOpCloudSyncRepositoryTest` gets 2 new tests for `signInIntent` + `handleSignInResult`.

Manual smoke test in `docs/superpowers/testdata/phase-4b-google-drive.md`:
1. Build + test pass.
2. Manual sign-in via debug-only entry point.
3. Manual push: file appears in Drive.
4. Manual pull: round-trip works.
5. Manual sign-out: tokens wiped.

## Out of scope (locked for 4c/4d)

- Dropbox implementation (4c — same shape as 4b).
- Settings "Sign in" entry, sync indicator, manual-merge UI (4d).
- Push/pull triggers — debounced-on-mutation, app-start hook, WorkManager job (4d).
- Migration of existing users' data into sync (4d or later).
- Multi-account support (later).
- Encrypting the snapshot body at rest beyond the SHA-256 checksum (later).
- Receipt binaries in the cloud backup (later — 4b syncs the JSON manifest only).
- Branded OAuth consent screen (Play Services default for v1).
- Web OAuth fallback.
- iOS / desktop clients.
- Telemetry / analytics on sync events.