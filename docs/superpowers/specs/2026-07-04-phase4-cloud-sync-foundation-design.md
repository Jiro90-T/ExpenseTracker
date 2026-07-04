# Phase 4a — Cloud Sync Foundation — Design

**Status:** Approved 2026-07-04
**Phase:** 4a (first of 4a/4b/4c/4d)
**Predecessor:** `backup/` package (v4 JSON envelope, see `BackupFormat.FORMAT_VERSION = 4`).
**Successors:** 4b (Drive provider), 4c (Dropbox provider), 4d (UI + triggers + manual merge).

## Goal

Add the structural foundation for cloud sync — the data model, repository
contract, snapshot codec, and Hilt wiring — so that 4b and 4c can drop in
concrete Drive/Dropbox implementations by replacing a single Hilt binding.

Out of scope (intentional, deferred): Drive/Dropbox SDKs, OAuth, push/pull
triggers, UI changes, conflict-resolution screens, encryption at rest beyond
checksum, multi-account. None of these touch 4a's code; they sit on top.

## Architecture

A new `sync/` package sits alongside the existing `backup/` package.
`backup/` owns the *shape of the data* (v4 envelope: accounts + categories +
transactions). `sync/` owns the *shape of the cloud payload* (v4 envelope +
sync header: device id + last-modified timestamp + SHA-256 checksum) and the
repository contract callers use.

The split lets `backup/` keep evolving (v5, v6) without forcing every sync
provider to track schema bumps separately, and lets `sync/` evolve its
metadata (encryption headers, compression flags) without breaking the
restore flow.

```
ViewModels / UI
    │
    ▼
CloudSyncRepository  ◄── interface, StateFlow state, suspend push/pull
    │
    ├──── NoOpCloudSyncRepository   (4a — every method is a placeholder)
    │
    ├──── GoogleDriveCloudSyncRepository   (4b — future)
    │
    └──── DropboxCloudSyncRepository      (4c — future)

Push / pull payloads are encoded by SyncSnapshotCodec:
    SyncSnapshot ──► SyncSnapshotCodec.encode ──► String (JSON)
    String ──► SyncSnapshotCodec.decode ──► SyncSnapshot
```

## Data model

```kotlin
// SyncSnapshot.kt
data class SyncSnapshot(
    val body: BackupBody,                  // wraps the v4 envelope
    val lastModifiedEpochMillis: Long,
    val deviceId: String,                  // stable UUID per install
    val schemaVersion: Int = BackupFormat.FORMAT_VERSION,
    val checksum: String,                  // SHA-256 hex of body.toJsonString()
)

// SyncState.kt
sealed class SyncState {
    object SignedOut : SyncState()
    data class SignedIn(val providerId: String) : SyncState()
    data class Syncing(val operation: Operation) : SyncState()
    data class Error(val message: String, val cause: Throwable? = null) : SyncState()
}
enum class Operation { PUSH, PULL }

// PullResult.kt + siblings
data class PushResult(val pushedAtEpochMillis: Long)

sealed class PullResult<out T> {
    data class Success<T>(val snapshot: T, val pulledAtEpochMillis: Long) : PullResult<T>()
    object NoRemoteSnapshot : PullResult<Nothing>()
    data class Conflict(val remote: SyncSnapshot, val local: SyncSnapshot) : PullResult<Nothing>()
    data class Failed(val message: String, val cause: Throwable? = null) : PullResult<Nothing>()
}

sealed class SyncResult {
    data class Pushed(val pushedAtEpochMillis: Long) : SyncResult()
    data class Pulled(val snapshot: SyncSnapshot, val pulledAtEpochMillis: Long) : SyncResult()
    object NoRemoteSnapshot : SyncResult()
    data class Failed(val message: String, val cause: Throwable? = null) : SyncResult()
}

sealed class SignInResult {
    object Success : SignInResult()
    data class Failed(val message: String, val cause: Throwable? = null) : SignInResult()
}

// SyncException.kt
enum class SyncErrorCode { MALFORMED, CHECKSUM_MISMATCH, SCHEMA_INCOMPATIBLE }
data class SyncException(
    val code: SyncErrorCode,
    override val message: String,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)
```

`BackupBody` is a new thin wrapper around the v4 envelope that carries the
arrays without exposing raw `JSONArray` types to the sync layer. It exposes
`accounts: List<AccountRow>`, `categories: List<CategoryRow>`,
`transactions: List<TransactionRow>` (the row data classes already defined
in `BackupFormat.kt`) plus `serialize(): String` and a companion
`deserialize(json: String)` factory. Concrete conversion is delegated to
the existing `accountEntityToJson` / `accountFromJson` etc. helpers in
`BackupFormat.kt`.

## Repository interface

```kotlin
// CloudSyncRepository.kt
interface CloudSyncRepository {
    val state: StateFlow<SyncState>
    val lastSyncedAtEpochMillis: StateFlow<Long?>
    val isSignedIn: StateFlow<Boolean>

    suspend fun signIn(): SignInResult
    suspend fun signOut()
    suspend fun push(snapshot: SyncSnapshot): PushResult
    suspend fun pull(): PullResult<SyncSnapshot>
    suspend fun syncOnce(): SyncResult
}
```

**NoOpCloudSyncRepository** is the 4a implementation:

- `signIn()` → flips `state` to `SignedIn("noop")`, returns `Success`.
- `signOut()` → flips `state` back to `SignedOut`, returns Unit.
- `push(snapshot)` → throws `NotImplementedError("push not available in 4a")`.
  Contract exists so callers compile.
- `pull()` → returns `PullResult.NoRemoteSnapshot`.
- `syncOnce()` → returns `SyncResult.NoRemoteSnapshot`.
- `state` and `isSignedIn` flows derive from a single `MutableStateFlow<SyncState>`.
- `lastSyncedAtEpochMillis` stays `null` forever (no real sync happens).

When 4b/4c land, we swap the `@Binds` in `SyncModule` to point at the real
implementation and nothing in the interface or above changes.

## SyncSnapshotCodec

```kotlin
// SyncSnapshotCodec.kt
object SyncSnapshotCodec {
    fun encode(snapshot: SyncSnapshot): String {
        val bodyJson = snapshot.body.serialize()
        val checksum = sha256Hex(bodyJson)
        val outer = JSONObject().apply {
            put("schemaVersion", snapshot.schemaVersion)
            put("lastModifiedEpochMillis", snapshot.lastModifiedEpochMillis)
            put("deviceId", snapshot.deviceId)
            put("checksum", checksum)
            put("body", JSONObject(bodyJson))
        }
        return outer.toString()
    }

    fun decode(json: String): SyncSnapshot {
        val outer = JSONObject(json)
        val schemaVersion = outer.optInt("schemaVersion", -1)
        if (schemaVersion !in 1..BackupFormat.FORMAT_VERSION) {
            throw SyncException(
                SyncErrorCode.SCHEMA_INCOMPATIBLE,
                "Snapshot schema version $schemaVersion not supported (expected 1..${BackupFormat.FORMAT_VERSION})",
            )
        }
        val expected = outer.optString("checksum")
        if (expected.isEmpty()) {
            throw SyncException(SyncErrorCode.CHECKSUM_MISMATCH, "Snapshot has no checksum")
        }
        val bodyObj = outer.optJSONObject("body")
            ?: throw SyncException(SyncErrorCode.MALFORMED, "Snapshot missing body")
        val bodyJson = bodyObj.toString()
        val actual = sha256Hex(bodyJson)
        if (!expected.equals(actual, ignoreCase = true)) {
            throw SyncException(SyncErrorCode.CHECKSUM_MISMATCH, "Snapshot checksum mismatch")
        }
        val body = BackupBody.deserialize(bodyJson)
        return SyncSnapshot(
            body = body,
            lastModifiedEpochMillis = outer.getLong("lastModifiedEpochMillis"),
            deviceId = outer.getString("deviceId"),
            schemaVersion = schemaVersion,
            checksum = expected,
        )
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
```

The wrapper is the only on-the-wire format for 4a; the codec stays in
`sync/` and never reaches into `backup/` internals beyond calling
`BackupBody.serialize` / `deserialize` and the public row data classes.

## Hilt wiring + DeviceIdProvider

```kotlin
// sync/DeviceIdProvider.kt
interface DeviceIdProvider {
    fun getOrCreate(): String
}

@Singleton
internal class DefaultDeviceIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceIdProvider {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    override fun getOrCreate(): String {
        prefs.getString(KEY, null)?.let { return it }
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, newId).apply()
        return newId
    }

    private companion object { const val KEY = "device_id" }
}
```

```kotlin
// sync/di/SyncModule.kt
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds @Singleton
    abstract fun bindCloudSyncRepository(
        impl: NoOpCloudSyncRepository
    ): CloudSyncRepository

    @Binds @Singleton
    abstract fun bindDeviceIdProvider(
        impl: DefaultDeviceIdProvider
    ): DeviceIdProvider
}
```

`NoOpCloudSyncRepository` is a `@Singleton class` with `@Inject constructor()`
(rather than a Kotlin `object`) so Hilt can construct it. `signIn()` /
`signOut()` / `pull()` / `syncOnce()` mutate a private
`MutableStateFlow<SyncState>`; `state` and `isSignedIn` are derived
exposed read-only flows.

## Edge cases + error handling

- **Empty DB snapshot.** `BackupBody` with empty arrays is valid; encode/
  decode succeed. Consumers must distinguish `PullResult.Success(empty body)`
  from `PullResult.NoRemoteSnapshot`.
- **Malformed JSON.** Top-level not JSON → `MALFORMED`. Missing required
  wrapper keys → `MALFORMED`. `body` sub-object malformed → `MALFORMED`.
- **Missing or wrong checksum.** Absent or empty `checksum` field →
  `CHECKSUM_MISMATCH`. We treat "no checksum" the same as "wrong checksum"
  — never trust an unsigned payload.
- **Future schema version.** `schemaVersion > FORMAT_VERSION` or
  `schemaVersion < 1` → `SCHEMA_INCOMPATIBLE`.
- **Concurrent writes.** Not a 4a concern — no writers exist. 4b will handle
  "I started a push, another instance pushed in the meantime" via LWW on
  `lastModifiedEpochMillis`.
- **Clock skew between devices.** Snapshots carry the writer's clock;
  LWW picks whichever is higher. Skew is accepted per the project design
  doc.
- **NoOp concurrency.** All repo methods are `suspend`; dispatching is the
  caller's job. NoOp does no I/O, just mutates a `MutableStateFlow` — safe
  from any dispatcher.
- **Error taxonomy.** Single `SyncException` + `SyncErrorCode` enum
  (`MALFORMED`, `CHECKSUM_MISMATCH`, `SCHEMA_INCOMPATIBLE`). The codec
  throws; repository implementations wrap their IO errors as
  `SyncResult.Failed` / `PullResult.Failed`.

## Testing

Three new test classes. No Android instrumentation; everything is pure or
Robolectric-runnable.

**`SyncSnapshotCodecTest`** (pure):
- `encode_then_decode_returnsEquivalentSnapshot` — round-trip with one
  account, two categories, three transactions.
- `encode_producesValidChecksum` — recompute SHA-256 of body independently,
  assert equal.
- `decode_throwsOnChecksumMismatch` — flip a byte in body, expect
  `SyncException(CHECKSUM_MISMATCH)`.
- `decode_throwsOnSchemaTooNew` — set `schemaVersion = FORMAT_VERSION + 1`,
  expect `SyncException(SCHEMA_INCOMPATIBLE)`.
- `decode_throwsOnMalformedJson` — pass `"not json"`, expect `MALFORMED`.
- `decode_throwsOnMissingBody` — strip the body key, expect `MALFORMED`.
- `decode_throwsOnMissingChecksum` — strip checksum key, expect
  `CHECKSUM_MISMATCH`.

**`NoOpCloudSyncRepositoryTest`** (pure, hand-rolled fake):
- `state_startsAsSignedOut`
- `isSignedIn_isFalse_initially`
- `signIn_transitionsStateToSignedIn`
- `signOut_transitionsStateToSignedOut`
- `push_throwsNotImplementedError`
- `pull_returnsNoRemoteSnapshot`
- `syncOnce_returnsNoRemoteSnapshot`
- `lastSyncedAtEpochMillis_remainsNull`

**`DefaultDeviceIdProviderTest`** (Robolectric):
- `getOrCreate_returnsSameIdOnSecondCall`
- `getOrCreate_persistsAcrossInstances` — two providers sharing a Context
  return the same ID.
- `getOrCreate_generatesUuidFormat` — matches UUID regex.

Existing `BackupFormatTest` continues to pass unchanged — the v4 envelope
is untouched.

## Out of scope (locked for 4b/4c/4d)

- Drive or Dropbox SDKs (Play Services Auth, Dropbox Core SDK).
- OAuth flow, token storage, `SecureSettingsRepository`.
- UI changes — no Settings "Sign in" entry, no sync indicator, no
  manual-merge screen.
- Push / pull triggers — no debounced-on-mutation, no app-start hook, no
  WorkManager job, no "Sync now" button.
- Background sync scheduling.
- Conflict-resolution UI. LWW only; ties resolve to remote per the project
  design doc.
- Telemetry / analytics.
- Migration of existing users' data into sync.
- Multi-account support.
- Encryption at rest beyond the SHA-256 checksum.

## File inventory

New files (production):
- `app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshot.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/SyncState.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/SyncException.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodec.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/BackupBody.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/DeviceIdProvider.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/di/SyncModule.kt`

New files (tests):
- `app/src/test/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodecTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/DeviceIdProviderTest.kt`

Modified files: **none.** 4a is purely additive; the `backup/` package,
existing tests, and every feature module are untouched.