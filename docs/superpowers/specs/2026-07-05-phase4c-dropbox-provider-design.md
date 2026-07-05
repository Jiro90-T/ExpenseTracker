# Phase 4c — Dropbox Provider — Design

**Status:** Draft (pending review)
**Phase:** 4c (third of 4a/4b/4c/4d)
**Predecessor:** Phase 4a (sync contract, NoOp repository), Phase 4b (Google Drive provider via Play Services Auth + Drive REST v3).
**Successor:** Phase 4d (UI + triggers + manual-merge + provider selector).

## Goal

Replace `GoogleDriveCloudSyncRepository`'s sibling with a real `DropboxCloudSyncRepository`
that drives the user's Dropbox via AppAuth-Android (PKCE) and the Dropbox HTTP API v2.
4c is everything 4b did for Drive, repeated for Dropbox:
real auth, real token storage, real I/O. Same backend-only scope — no UI.

Out of scope (intentional, deferred to 4d): provider selector UI, sync triggers,
sync indicator, manual-merge UI, multi-account support, Dropbox folder picker,
receipt binaries in cloud backups.

## Resolved decisions

- **OAuth library:** AppAuth-Android `0.11.1`. Zero custom OAuth flow code;
  RFC-compliant; well-maintained; widely used in production Android apps.
- **Token storage:** Bridge AppAuth's auth state into our own `DropboxSyncTokensRepository`
  protected by `KeystoreTokenCrypto` (AES-GCM). Configure AppAuth with
  `setTokenStore(NoopTokenStore())` so AppAuth never writes plaintext tokens.
- **File location:** App folder type. Snapshot lives at `/ExpenseTracker-sync.json`
  inside the App folder (the user sees it as `/Apps/ExpenseTracker/ExpenseTracker-sync.json`).
- **Code structure:** Mirror 4b. New files under `sync/dropbox/` — same file layout
  as `sync/google/`. No shared refactor in 4c; that comes in a future phase.
- **Hilt collision:** Both Drive and Dropbox will be bound. 4d adds a
  `selectedProviderId` preference and a wrapper that delegates to one.
  For 4c, we ship Dropbox-only by overriding the `CloudSyncRepository`
  binding in `SyncModule` (replace Drive's binding with Dropbox's).

## Architecture

```
ViewModel / Activity (4d)
    │  ActivityResultLauncher<Intent>
    ▼
DropboxCloudSyncRepository (4c) — implements CloudSyncRepository
    ├──── DropboxAuth                  (AppAuth-Android wrapper)
    ├──── DropboxApiClient             (OkHttp + Dropbox HTTP API v2)
    ├──── DropboxSyncTokensRepository  (SharedPreferences + Android Keystore AES-GCM)
    └──── SyncSnapshotCodec            (already exists, 4a)

SyncModule @Binds: GoogleDriveCloudSyncRepository → DropboxCloudSyncRepository
```

Each piece has one job. The split mirrors 4b and 4a — the wire-level surface
(Dropbox HTTP API) is the only thing that talks to the network.

## OAuth entry-point pattern

`CloudSyncRepository.signIn(): SignInResult` is `suspend`, so we can't directly
launch an `ActivityResultLauncher<Intent>` from inside it. 4b split the flow
into two calls by widening the interface:

```kotlin
internal interface CloudSyncRepository {
    val signInIntent: Intent                    // 4b
    suspend fun handleSignInResult(data: Intent?): SignInResult  // 4b
    suspend fun signIn(): SignInResult          // 4a
    // ... rest unchanged
}
```

**4c reuses the same pattern.** `DropboxCloudSyncRepository` exposes its
own `signInIntent` (built by `AppAuthDropboxAuth.buildAuthIntent()` which
returns a `CustomTabsIntent`-backed Intent). The Activity's launcher calls
`handleSignInResult(data)` with the redirect Intent.

The redirect URI scheme is `io.github.jiro.expensetracker:/oauth2redirect`
(declared in Dropbox app console and in our AndroidManifest as a deep-link
intent-filter on MainActivity).

## Components

### DropboxAuth interface

```kotlin
internal interface DropboxAuth {
    /** Build a CustomTabs-backed OAuth Intent. Caller launches it. */
    suspend fun buildAuthIntent(): Intent

    /** Parse the OAuth redirect Intent returned by the launcher. */
    suspend fun handleAuthResult(data: Intent?): DropboxAccountSnapshot?

    /** Return cached account if a valid token is available, else null. */
    suspend fun getLastAuthState(): DropboxAccountSnapshot?
}

internal data class DropboxAccountSnapshot(
    val email: String,
    val accessToken: String,
)
```

### AppAuthDropboxAuth implementation

Wraps `net.openid.appauth.AuthorizationService`. PKCE flow uses `clientId`
from `BuildConfig.DROPBOX_CLIENT_ID` (registered at Dropbox app console).

```kotlin
@Singleton
internal class AppAuthDropboxAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val clientId: String = BuildConfig.DROPBOX_CLIENT_ID,
    private val redirectUri: String = "io.github.jiro.expensetracker:/oauth2redirect",
) : DropboxAuth {
    private val authService: AuthorizationService by lazy {
        AuthorizationService(context)
    }

    override suspend fun buildAuthIntent(): Intent = withContext(Dispatchers.IO) {
        val request = AuthorizationRequest.Builder(
            AuthorizationConfiguration(
                AuthorizationEndpoint(TOKEN_ENDPOINT_URI),  // see below
                clientId,
                ResponseTypeValues.CODE,
                Uri.parse(redirectUri),
            ),
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri),
        )
            .setScope("account_info.read files.content.read files.content.write")
            .build()
        authService.getAuthorizationRequestIntent(request)
    }

    override suspend fun handleAuthResult(data: Intent?): DropboxAccountSnapshot? =
        withContext(Dispatchers.IO) {
            val resp = AuthorizationResponse.fromIntent(data ?: return@withContext null)
                ?: return@withContext null
            val tokenReq = resp.createTokenExchangeRequest()
            val tokenResp = try {
                authService.performTokenRequest(tokenReq).getOrThrow()
            } catch (e: Exception) {
                return@withContext null
            }
            val email = fetchAccountEmail(tokenResp.accessToken ?: return@withContext null)
            DropboxAccountSnapshot(email = email, accessToken = tokenResp.accessToken!!)
        }

    override suspend fun getLastAuthState(): DropboxAccountSnapshot? =
        withContext(Dispatchers.IO) {
            // We don't persist tokens in AppAuth's store (NoopTokenStore). After restart,
            // we fall back to our Keystore-protected DropboxSyncTokensRepository — caller
            // should check that first via repo.signIn() before calling getLastAuthState().
            null
        }

    private suspend fun fetchAccountEmail(accessToken: String): String? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://api.dropboxapi.com/2/users/get_current_account")
                .header("Authorization", "Bearer $accessToken")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                JSONObject(body).optString("email").takeIf { it.isNotEmpty() }
            }
        }

    private companion object {
        val TOKEN_ENDPOINT_URI = Uri.parse("https://api.dropboxapi.com/oauth2/token")
    }
}
```

### DropboxApiClient interface

```kotlin
internal interface DropboxApiClient {
    /**
     * Upload the snapshot body. Returns the new `rev` (Dropbox's optimistic
     * concurrency token). If `existingRev == null`, creates the file; otherwise
     * performs an update with `mode: {".tag": "update", "update": existingRev}`.
     */
    suspend fun upload(existingRev: String?, body: String): String

    /**
     * Download the snapshot body. Returns null if the file doesn't exist (404).
     */
    suspend fun download(): String?

    /**
     * Return the current `rev` of the snapshot file, or null if it doesn't exist.
     */
    suspend fun getRev(): String?
}

internal sealed class DropboxApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthRevoked : DropboxApiException("Auth revoked")
    class NotFound : DropboxApiException("Not found")
    class Conflict(val serverRev: String?) : DropboxApiException("Conflict")
    class RateLimited : DropboxApiException("Rate limited")
    class ServerError : DropboxApiException("Server error")
    class Generic(message: String, cause: Throwable? = null) : DropboxApiException(message, cause)
}
```

### DropboxApiClientImpl — OkHttp

Uses Dropbox HTTP API v2 RPC-style endpoints. The metadata goes in the
`Dropbox-API-Arg` header (JSON); the file body is the request body.

```kotlin
internal class DropboxApiClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokensProvider: () -> SyncTokens?,  // injected closure, not stored
) : DropboxApiClient {

    override suspend fun upload(existingRev: String?, body: String): String =
        withContext(Dispatchers.IO) {
            val arg = JSONObject().apply {
                put("path", "/ExpenseTracker-sync.json")
                put("mode", "overwrite")  // safe: single-device is the 4c norm
                put("autorename", false)
                put("mute", true)
            }
            val req = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/upload")
                .header("Authorization", "Bearer ${tokensProvider()?.accessToken ?: error("no token")}")
                .header("Dropbox-API-Arg", arg.toString())
                .header("Content-Type", "application/octet-stream")
                .post(body.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> JSONObject(resp.body?.string() ?: "{}").getString("rev")
                    401, 403 -> throw DropboxApiException.AuthRevoked()
                    409 -> throw DropboxApiException.Conflict(serverRev = parseRevFromError(resp))
                    429 -> throw DropboxApiException.RateLimited()
                    in 500..599 -> throw DropboxApiException.ServerError()
                    else -> throw DropboxApiException.Generic("HTTP ${resp.code}")
                }
            }
        }

    override suspend fun download(): String? = withContext(Dispatchers.IO) {
        val arg = JSONObject().put("path", "/ExpenseTracker-sync.json")
        val req = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/download")
            .header("Authorization", "Bearer ${tokensProvider()?.accessToken ?: error("no token")}")
            .header("Dropbox-API-Arg", arg.toString())
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> resp.body?.string()
                401, 403 -> throw DropboxApiException.AuthRevoked()
                404, 409 -> null  // both treated as "no remote snapshot"
                429 -> throw DropboxApiException.RateLimited()
                in 500..599 -> throw DropboxApiException.ServerError()
                else -> throw DropboxApiException.Generic("HTTP ${resp.code}")
            }
        }
    }

    override suspend fun getRev(): String? = withContext(Dispatchers.IO) {
        val arg = JSONObject().put("path", "/ExpenseTracker-sync.json")
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/get_metadata")
            .header("Authorization", "Bearer ${tokensProvider()?.accessToken ?: error("no token")}")
            .header("Dropbox-API-Arg", arg.toString())
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> JSONObject(resp.body?.string() ?: "{}").optString("rev").takeIf { it.isNotEmpty() }
                401, 403 -> throw DropboxApiException.AuthRevoked()
                404, 409 -> null
                429 -> throw DropboxApiException.RateLimited()
                in 500..599 -> throw DropboxApiException.ServerError()
                else -> throw DropboxApiException.Generic("HTTP ${resp.code}")
            }
        }
    }

    private fun parseRevFromError(resp: Response): String? = try {
        JSONObject(resp.body?.string() ?: "{}").optJSONObject("error")
            ?.optJSONObject("conflict")?.optString("path_conflict")?.substringAfterLast("rev=")?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }
}
```

**Retry strategy:** Wrapper around `OkHttpClient` with `RetryAndFollowUpInterceptor`-style
logic. On 429, read `Retry-After` header (seconds), back off, retry up to 3 times
with exponential delay (1s, 2s, 4s capped at `Retry-After`). On 5xx, retry up to 3 times
with exponential delay. 401/403/404/409 do NOT retry — they map to specific exceptions.

### DropboxSyncTokens

```kotlin
internal data class DropboxSyncTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
    val accountEmail: String,
    val snapshotRev: String?,  // Dropbox's `rev` instead of Drive's `fileId`
)
```

### DropboxSyncTokensRepository + TokenCrypto + KeystoreTokenCrypto

Byte-for-byte parallel to Google's (see `sync/google/SyncTokensRepository.kt`,
`sync/google/TokenCrypto.kt`, `sync/google/KeystoreTokenCrypto.kt`). Duplicated
under `sync/dropbox/` package. A future phase will extract shared types to
`sync/core/` — out of scope for 4c.

SharedPreferences name: `dropbox_sync_tokens` (separate from Drive's `sync_tokens`).
Key alias: `expensetracker_dropbox_sync_key` (separate from Drive's
`expensetracker_sync_key`).

### DropboxCloudSyncRepository

```kotlin
@Singleton
internal class DropboxCloudSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dropboxAuth: DropboxAuth,
    private val api: DropboxApiClient,
    private val tokens: DropboxSyncTokensRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)
    private val mutex = Mutex()

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val signInIntent: Intent by lazy { runBlocking { dropboxAuth.buildAuthIntent() } }

    override suspend fun signIn(): SignInResult = withContext(Dispatchers.IO) {
        val cached = tokens.load()
        if (cached != null && cached.expiresAtEpochMillis > nowProvider() + 60_000L) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            return@withContext SignInResult.Success
        }
        SignInResult.Failed("Not signed in")
    }

    override suspend fun handleSignInResult(data: Intent?): SignInResult = withContext(Dispatchers.IO) {
        try {
            val account = dropboxAuth.handleAuthResult(data)
                ?: return@withContext SignInResult.Failed("Sign-in cancelled")
            tokens.save(
                DropboxSyncTokens(
                    accessToken = account.accessToken,
                    refreshToken = null,  // AppAuth PKCE doesn't return refresh; tokens last 4h
                    expiresAtEpochMillis = nowProvider() + 4 * 60 * 60 * 1000L,
                    accountEmail = account.email,
                    snapshotRev = null,
                ),
            )
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            SignInResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SignInResult.Failed("Sign-in failed: ${e.message}", e)
        }
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        tokens.clear()
        _state.value = SyncState.SignedOut
    }

    override suspend fun push(snapshot: SyncSnapshot): PushResult = mutex.withLock {
        val current = _state.value
        if (current !is SyncState.SignedIn) {
            return@withLock PushResult.Failed("Not signed in", null)
        }
        _state.value = SyncState.Syncing(Operation.PUSH)
        try {
            val body = SyncSnapshotCodec.encode(snapshot)
            val cached = tokens.load()
            val existingRev = cached?.snapshotRev
            val newRev = api.upload(existingRev, body)
            if (cached != null) {
                tokens.save(cached.copy(snapshotRev = newRev))
            }
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PushResult.Pushed(pushedAtEpochMillis = snapshot.lastModifiedEpochMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DropboxApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PushResult.Failed("Session expired — please sign in again", e)
        } catch (e: DropboxApiException) {
            PushResult.Failed(e.message ?: "Dropbox error", e)
        } catch (e: Exception) {
            PushResult.Failed("Push failed: ${e.message}", e)
        }
    }

    override suspend fun pull(): PullResult<SyncSnapshot> = mutex.withLock {
        val cached = tokens.load()
        val fileRev = cached?.snapshotRev
        if (fileRev == null) {
            return@withLock PullResult.NoRemoteSnapshot
        }
        _state.value = SyncState.Syncing(Operation.PULL)
        try {
            val body = api.download()
                ?: return@withLock PullResult.NoRemoteSnapshot.also { _state.value = SyncState.SignedIn(PROVIDER_ID) }
            val snapshot = SyncSnapshotCodec.decode(body)
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PullResult.Success(snapshot, pulledAtEpochMillis = nowProvider())
        } catch (e: CancellationException) {
            throw e
        } catch (e: DropboxApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PullResult.Failed("Session expired", e)
        } catch (e: DropboxApiException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(e.message ?: "Dropbox error", e)
        } catch (e: SyncException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(
                when (e.code) {
                    SyncErrorCode.CHECKSUM_MISMATCH -> "Remote snapshot failed integrity check"
                    SyncErrorCode.SCHEMA_INCOMPATIBLE -> "Remote snapshot was written by a newer app version"
                    SyncErrorCode.MALFORMED -> "Remote snapshot is corrupted"
                },
                e,
            )
        } catch (e: Exception) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed("Pull failed: ${e.message}", e)
        }
    }

    override suspend fun syncOnce(): SyncResult = withContext(Dispatchers.IO) {
        when (val result = pull()) {
            is PullResult.Success<*> -> SyncResult.Pulled(
                snapshot = result.snapshot as SyncSnapshot,
                pulledAtEpochMillis = result.pulledAtEpochMillis,
            )
            PullResult.NoRemoteSnapshot -> SyncResult.NoRemoteSnapshot
            is PullResult.Failed -> SyncResult.Failed(result.message, result.cause)
            is PullResult.Conflict -> SyncResult.Failed("Conflict on remote", null)
        }
    }

    private companion object {
        const val PROVIDER_ID = "dropbox"
    }
}
```

## Data flow

### Sign-in

1. Activity calls `repo.signInIntent` (Intent from AppAuth launching Chrome Custom Tab)
2. User grants in Dropbox web → redirects to `io.github.jiro.expensetracker:/oauth2redirect`
3. Android manifest intent-filter routes the deep link to MainActivity
4. Activity calls `repo.handleSignInResult(data)` with the redirect Intent
5. Orchestrator: `AppAuthDropboxAuth.handleAuthResult(data)` parses redirect → exchanges code for token → fetches `/2/users/get_current_account` for email
6. Save `DropboxSyncTokens` (encrypted) → `_state = SignedIn("dropbox")` → return Success

### Push

1. `push(snapshot)` — check SignedIn, set Syncing(PUSH), encode body
2. Load cached tokens, get `snapshotRev` (null if first push)
3. `api.upload(existingRev, body)` — Dropbox returns new `rev` (we use `mode: overwrite` for v1)
4. Save new `rev` in tokens (parallel to how Drive stores `fileId`)
5. Return `PushResult.Pushed(pushedAtEpochMillis = snapshot.lastModifiedEpochMillis)`

**Note on Dropbox conflict semantics:** In v1 (single device), `mode: overwrite`
is safe. In v2 with multi-device, we'd switch to `mode: update` with `existingRev`
to enforce optimistic concurrency. The orchestrator already supports this — the
impl just needs to flip from `overwrite` to `update`. Deferred to 4d.

### Pull

1. `pull()` — check SignedIn (returns NoRemoteSnapshot if no rev cached), set Syncing(PULL), load cached tokens
2. If `snapshotRev == null`: return `NoRemoteSnapshot` (no file ever pushed)
3. `api.download()` → returns file body
4. Decode with `SyncSnapshotCodec.decode(body)` → wrap in `PullResult.Success(snapshot, pulledAtEpochMillis = now)`
5. On 404 (file deleted remotely): `PullResult.NoRemoteSnapshot`
6. On 409 (rev mismatch): `PullResult.Failed` (4d multi-device will surface as `PullResult.Conflict`)

## Error handling

| Status | Exception | Orchestrator response |
|--------|-----------|------------------------|
| 200 | — | Success path |
| 401, 403 | `AuthRevoked` | Clear tokens, flip to SignedOut, return Failed |
| 404 | `NotFound` | Treat as NoRemoteSnapshot |
| 409 | `Conflict(serverRev)` | For push: retry with serverRev (single-device: rare); for pull: Failed |
| 429 | `RateLimited` | Retry up to 3x with `Retry-After` backoff (1s, 2s, 4s) |
| 5xx | `ServerError` | Retry up to 3x with exponential backoff |
| Other | `Generic(msg)` | Don't retry, return Failed |

**SyncException from codec:** map `SyncErrorCode.*` to user-facing messages
(same as Drive). **CancellationException:** always rethrow (preserves structured
concurrency contract).

**Token refresh:** AppAuth handles this transparently — its `performTokenRequest()`
is called automatically when token is near expiry. Our orchestrator calls
`auth.getValidToken()` (lazy refresh) before each Dropbox call.

## Testing

### Unit tests (~22 new tests, ~5 new test files)

- `DropboxApiClientTest` (~8 tests, MockWebServer):
  1. `upload_createsFile_returnsNewRev`
  2. `upload_updatesFile_returnsUpdatedRev`
  3. `download_returnsBody_onSuccess`
  4. `download_returnsNull_on404`
  5. `download_returnsNull_on409`
  6. `download_throwsAuthRevoked_on401`
  7. `download_retriesWithBackoff_on429`
  8. `download_throwsServerError_on500`

- `DropboxSyncTokensRepositoryTest` (~4 tests, Robolectric + FakeTokenCrypto):
  1. `load_returnsNull_whenPrefsEmpty`
  2. `save_thenLoad_roundTrips`
  3. `load_wipesPrefs_onDecryptFailure`
  4. `clear_removesAllEntries`

- `DropboxCloudSyncRepositoryTest` (~10 tests, Robolectric + fakes):
  1. `signInIntent_isNotNull`
  2. `handleSignInResult_persistsTokens_onSuccess`
  3. `handleSignInResult_returnsFailed_whenCancelled`
  4. `push_createsFile_whenNoSnapshotRev`
  5. `push_updatesFile_whenSnapshotRevExists`
  6. `pull_returnsSuccess_whenRemoteSnapshotDecodes`
  7. `pull_returnsNoRemoteSnapshot_whenRevNull`
  8. `pull_returnsNoRemoteSnapshot_whenHttp404`
  9. `pull_returnsFailed_whenChecksumMismatch`
  10. `signOut_clearsTokens_andFlipsState`

### Test infrastructure

Reuse 4b's Robolectric setup. Use `FakeDropboxAuth` and `FakeDropboxApiClient`
test doubles (parallel to 4b's fakes).

## Hilt wiring

**`SyncModule.kt` change:** Replace `bindCloudSyncRepository(impl: GoogleDriveCloudSyncRepository)`
with `bindCloudSyncRepository(impl: DropboxCloudSyncRepository)`. (4d will add
the active-provider switch.)

**`dropbox/di/DropboxModule.kt` (NEW):**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DropboxModule {
    @Binds @Singleton
    abstract fun bindDropboxAuth(impl: AppAuthDropboxAuth): DropboxAuth

    @Binds @Singleton
    abstract fun bindDropboxApiClient(impl: DropboxApiClientImpl): DropboxApiClient

    @Binds @Singleton
    abstract fun bindDropboxSyncTokensRepository(impl: DefaultDropboxSyncTokensRepository): DropboxSyncTokensRepository

    companion object {
        @Provides @Singleton
        fun provideTokenCrypto(): TokenCrypto = KeystoreTokenCrypto()
    }
}
```

## Manifest changes

**`AndroidManifest.xml` additions:**
- `<uses-permission android:name="android.permission.INTERNET" />` (already added in 4b)
- Deep-link intent-filter on MainActivity:
  ```xml
  <activity android:name=".MainActivity" ...>
      <intent-filter>
          <action android:name="android.intent.action.VIEW" />
          <category android:name="android.intent.category.DEFAULT" />
          <category android:name="android.intent.category.BROWSABLE" />
          <data android:scheme="io.github.jiro.expensetracker" android:host="oauth2redirect" />
      </intent-filter>
  </activity>
  ```

## Build configuration

**`gradle/libs.versions.toml`:**
```toml
appauth = "0.11.1"
# ...existing versions

[libraries]
appauth = { group = "net.openid", name = "appauth", version.ref = "appauth" }
```

**`app/build.gradle.kts`:**
```kotlin
implementation(libs.appauth)

buildConfigField("String", "DROPBOX_CLIENT_ID", "\"${project.findProperty("dropbox.client.id") ?: ""}\"")
```

## Implementation outline (for the plan)

Tasks:
1. Add AppAuth + json dep to `libs.versions.toml` + `app/build.gradle.kts` (buildConfigField for DROPBOX_CLIENT_ID)
2. Create `DropboxAuth` interface + `DropboxAccountSnapshot`
3. Create `DropboxApiClient` interface + `DropboxApiException` sealed class
4. Create `DropboxApiClientImpl` (OkHttp, RPC-style) + 8 MockWebServer tests
5. Create `DropboxSyncTokens` + `TokenCrypto` + `KeystoreTokenCrypto` + `DropboxSyncTokensRepository` + 4 Robolectric tests
6. Create `AppAuthDropboxAuth` + `FakeDropboxAuth` (test fake)
7. Create `FakeDropboxApiClient` (test fake)
8. Create `DropboxCloudSyncRepository` (orchestrator) + 10 Robolectric tests
9. Manifest deep-link + strings + `DropboxModule` + flip `SyncModule` `@Binds` + smoke test + tag v0.18.14

Total: ~14 new production files, ~5 new test files, ~22 new tests, ~600 LOC. Target v0.18.14.

## What this phase does NOT add

- Settings UI for provider selection or sign-in (4d).
- Sync status indicator (4d).
- WorkManager sync job (4d).
- Manual-merge UI (4d).
- Multi-account support (later).
- Receipt binaries in cloud backup (later).
- Switching between Drive and Dropbox at runtime — 4c ships with Dropbox bound by default; 4d adds the selector.
- Dropbox folder picker (4c uses fixed path `/ExpenseTracker-sync.json`).
- Dropbox `mode: update` optimistic concurrency (deferred — v1 uses `mode: overwrite` since single-device is the norm).