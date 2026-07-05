# Phase 4d — Settings UI + Sync Triggers + Provider Router — Design

**Status:** Approved 2026-07-05
**Phase:** 4d (fourth of 4a/4b/4c/4d)
**Predecessors:** 4a (foundation, v0.18.12), 4b (Google Drive, v0.18.13), 4c (Dropbox, v0.18.14).
**Successor:** 4e (later — multi-account, receipt binaries, periodic WorkManager sync, Dropbox refresh-token flow).

## Goal

Make cloud sync **usable** end-to-end. After 4d:

1. Users see a "Cloud sync" section in Settings showing status, last-synced
   time, active provider, and sign-in/Sign-out/Sync-now buttons.
2. A provider selector (Dropbox / Google Drive) is persisted in Settings and
   switches routing on the fly.
3. Sync runs automatically on app launch and on transaction mutations
   (debounced); a "Sync now" button forces an immediate run.
4. A manual-merge screen resolves `PullResult.Conflict` ties (per the
   project design doc: last-write-wins by default, ties break via UI).
5. The latent tokensProvider lambda wiring bug from 4c is fixed so
   `CloudSyncRepository` is constructible by Hilt.

## Out of scope (locked for 4e)

- Multi-account / per-provider-account picker.
- Receipt binaries in cloud backup (still JSON only).
- Periodic WorkManager sync (app-launch + debounced mutation covers it).
- Dropbox refresh-token flow (current 4-hour access tokens are used as-is).
- Granular per-row conflict diff (`ConflictScreen` resolves at the snapshot
  level with two buttons).
- Telemetry / analytics.
- Migration of pre-sync users' data into cloud.

## Architecture

```
                                 ┌──────────────────────────┐
SettingsScreen ──► SettingsVM ───► CloudSyncRepository       │
                                          │ ▲                │ pull() success
                                          ▼ │                ▼
                              RoutingCloudSyncRepository   BackupManager
                                          │                .applyBackupBodyToDb(body)
                                          │                    │
                          ┌───────────────┴───────────┐        ▼
                          ▼                           ▼     Local DB
            GoogleDriveCloudSyncRepo     DropboxCloudSyncRepo
                          │                           │
                          ▼                           ▼
                DriveApiClientImpl        DropboxApiClientImpl
                SyncTokensRepository      DropboxSyncTokensRepository
                DriveApiException         DropboxApiException


TransactionMutationBus (SharedFlow<Unit>)
   │  emits on successful add/edit/delete in:
   ▼
AddEditTransactionVM, AddReceiptVM, AddEditAccountVM
   │
   ▼  (collects with debounce(5s))
RoutingCloudSyncRepository.syncOnce()
```

The router owns a `MutableStateFlow<SyncProviderId>` (`DROPBOX` by
default). Every method (`signIn`/`handleSignInResult`/`signOut`/`push`/
`pull`/`syncOnce`/`signInIntent`/`state`/`isSignedIn`/
`lastSyncedAtEpochMillis`) delegates to whichever concrete impl matches.
The router mirrors the active repo's `state` and `lastSyncedAtEpochMillis`
flows into its own `MutableStateFlow`s so the SettingsViewModel observes
one source.

## Data model

### SyncProviderId

```kotlin
// sync/SyncProviderId.kt
enum class SyncProviderId(val displayKey: String) {
    DROPBOX("dropbox"),
    GOOGLE_DRIVE("google_drive"),
    ;
    companion object {
        fun fromKey(key: String?): SyncProviderId =
            entries.firstOrNull { it.displayKey == key } ?: DROPBOX
    }
}
```

### Settings additions

```kotlin
// preferences/SettingsRepository.kt — additions
private val _syncProvider: MutableStateFlow<SyncProviderId> by lazy {
    MutableStateFlow(loadSyncProvider())
}
open val syncProvider: StateFlow<SyncProviderId> = _syncProvider.asStateFlow()

open fun setSyncProvider(value: SyncProviderId) {
    prefs.edit { putString(KEY_SYNC_PROVIDER, value.displayKey) }
    _syncProvider.value = value
}

private fun loadSyncProvider(): SyncProviderId =
    SyncProviderId.fromKey(prefs.getString(KEY_SYNC_PROVIDER, null))

// companion additions
const val KEY_SYNC_PROVIDER = "sync_provider"
```

### TokensProvider refactor (latent bug fix)

`DropboxApiClientImpl` and `DriveApiClientImpl` take a non-default
`() -> Tokens?` lambda that Hilt can't construct. Both classes already
hold a `@Inject` on `tokens: *SyncTokensRepository`? **No** — they do
not. The lambda was the original design to avoid taking the repo. 4d
changes both:

```kotlin
// Dropbox side — Drive side mirrors
internal class DropboxApiClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokens: DropboxSyncTokensRepository,
    private val contentHost: String = HOST_CONTENT,
    private val apiHost: String = HOST_API,
) : DropboxApiClient {
    private suspend fun accessToken(): String =
        tokens.load()?.accessToken ?: error("No Dropbox token available")
    ...
}
```

Each suspend API method (`upload`/`download`/`getRev` for Dropbox;
`upload`/`download` for Drive) becomes:

```kotlin
val req = Request.Builder()
    .url("...")
    .header("Authorization", "Bearer ${accessToken()}")
    ...
```

The orchestrators already inject the same repo (`@Inject constructor(...,
private val tokens: DropboxSyncTokensRepository, ...)`), so they share
nothing extra. Tests for the orchestrators are unchanged because they
construct via constructor; the `FakeApiClient` is independent and
unaffected. ApiClient tests update their `@Inject constructor` smoke
call to pass a fake repo (`FakeSyncTokensRepository`) instead of a lambda.

The lambda parameter disappears from both `DropboxApiClientImpl` and
`DriveApiClientImpl` and from their tests.

### RoutingCloudSyncRepository

```kotlin
// sync/RoutingCloudSyncRepository.kt
@Singleton
internal class RoutingCloudSyncRepository @Inject constructor(
    private val googleDriveRepo: GoogleDriveCloudSyncRepository,
    private val dropboxRepo: DropboxCloudSyncRepository,
    private val backupManager: BackupManager,
    private val settings: SettingsRepository,
) : CloudSyncRepository {

    private val activeRepo: CloudSyncRepository
        get() = when (settings.syncProvider.value) {
            SyncProviderId.GOOGLE_DRIVE -> googleDriveRepo
            SyncProviderId.DROPBOX -> dropboxRepo
        }

    override val state: StateFlow<SyncState> = ...
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = ...
    override val isSignedIn: StateFlow<Boolean> = ...

    override val signInIntent: Intent
        get() = activeRepo.signInIntent

    override suspend fun signIn(): SignInResult = activeRepo.signIn()
    override suspend fun handleSignInResult(data: Intent?): SignInResult =
        activeRepo.handleSignInResult(data)
    override suspend fun signOut() = activeRepo.signOut()
    override suspend fun push(snapshot: SyncSnapshot): PushResult =
        activeRepo.push(snapshot)
    override suspend fun pull(): PullResult<SyncSnapshot> = activeRepo.pull()

    override suspend fun syncOnce(): SyncResult {
        val active = activeRepo
        val result = active.syncOnce()
        if (result is SyncResult.Pulled) {
            // Apply pulled snapshot to local DB.
            backupManager.applyBackupBodyToDb(result.snapshot.body)
        }
        return result
    }
}
```

`state`/`lastSyncedAtEpochMillis`/`isSignedIn` are computed by
`combine(activeFlow, currentProvider)` and a derived `MutableStateFlow`
that updates when the active provider changes (uses
`MutableStateFlow<CloudSyncRepository>` updated by collecting
`settings.syncProvider` once in `init`, plus per-flow mirroring inside a
single scope started by the constructor — see
`/sync/RoutingCloudSyncRepositoryMirror.kt` for the explicit code).

When `settings.syncProvider` flips: the mirror scope swaps the active
source flows, and any in-flight push/pull by the previous repo is
allowed to finish (the previous repo's `mutex.withLock` serializes per
repo). Hilt constructs both concrete repos even when one is unused, so
the switch is purely declarative.

If the user is signed in to Dropbox and flips to "Google Drive" without
ever signing in to Drive, the router is `SignedOut` (it mirrors each
repo's state and selects on `settings.syncProvider`). Switching back
restores the Dropbox sign-in. No automatic sign-out happens.

### SettingsViewModel additions

```kotlin
data class CloudSyncSessionState(
    val providerId: SyncProviderId,
    val state: SyncState,
    val lastSyncedAtEpochMillis: Long?,
    val accountEmail: String?,        // null if signed out
    val conflictPending: Boolean,     // sticky from syncOnce() Conflict
)
```

The VM combines `cloudSyncRepository.state`,
`cloudSyncRepository.lastSyncedAtEpochMillis`,
`settings.syncProvider`, and the conflict-pending flag (a
`MutableStateFlow<Boolean>` updated by the `syncOnce()` observer) into
a single `StateFlow<CloudSyncSessionState>`.

New VM methods:
- `fun setSyncProvider(id: SyncProviderId)` → calls `settings.setSyncProvider`.
- `fun onSyncNow()` → launches `viewModelScope.launch { val r = repo.syncOnce(); show snackbar based on result }`.
- `fun onSignInClick()` → triggers UI sign-in (ActivityResultLauncher
  in the screen launches `repo.signInIntent`, result forwards back via
  `onSignInResult(intent)`).
- `fun onSignInResult(intent: Intent?)` → launches VM scope → `repo.handleSignInResult(intent)`, surfaces snackbar.
- `fun onSignOutClick()` → launches VM scope → `repo.signOut()`.
- `fun onConflictResolved(useCloud: Boolean)` → see Conflict resolution below.

### Mutation bus

```kotlin
// sync/TransactionMutationBus.kt
@Singleton
class TransactionMutationBus @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val events: SharedFlow<Unit> = _events.asSharedFlow()
    suspend fun emit() { _events.emit(Unit) }
    fun tryEmit(): Boolean = _events.tryEmit(Unit)
}
```

(Renamed from `events: SharedFlow<Unit>` to avoid confusion; `emit()`
isn't strictly `Unit`-typed but everything in the system produces only
that one value.)

`RoutingCloudSyncRepository` collects in an internal scope:

```kotlin
init {
    val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    mirrorScope.launch {
        TransactionMutationBus.events
            .debounce(5_000)
            .collect { syncOnce() }   // ignore result; errors log
    }
}
```

(Implementation detail: the actual flow lives in a separate `mirror`
class `RoutingCloudSyncRepositoryMirror` for testability, the router
holds a reference and forwards `init` mutations. Final coding detail —
either is acceptable.)

Each mutation VM gains a `bus: TransactionMutationBus` injected
dependency and calls `bus.tryEmit()` after successful DAO writes:

- `AddEditTransactionViewModel.save()` (success path)
- `AddReceiptViewModel.save()` (success path)
- `AddEditAccountViewModel.save()` (success path)
- `TransactionsListViewModel.onDeleteConfirmed()` (success path)
- `AccountRepository.deleteAccount(...)` (success path) — routed via a
  thin extension since the VM is owner-agnostic.

The bus is `@Singleton`, so Hilt auto-injects.

## Conflict resolution

`PullResult.Conflict(remote, local)` is surfaced by the orchestrator's
`pull()` (4a added the sealed arm; nothing currently populates it). 4d
makes it actually reachable:

1. `syncOnce()` pulls, then calls `pull().decide(localSnapshot, onConflictResult)`
   helper:
   - `PullResult.Success` → apply body via `BackupManager.applyBackupBodyToDb`, return `SyncResult.Pulled`.
   - `PullResult.NoRemoteSnapshot` → push local snapshot, return `SyncResult.Pushed` or `SyncResult.Failed`.
   - `PullResult.Failed` → return `SyncResult.Failed`.
   - `PullResult.Conflict(remote, local)` → return
     `SyncResult.ConflictPending(remote, local)` (new variant). VM sets
     `_conflictPending = true` and surfaces a banner. No auto-merge.

2. From `syncOnce()`'s new `ConflictPending` return, the Settings VM
   flips the banner flag. The banner stays sticky until the user
   resolves or kills the app.

3. Tapping the banner opens `ConflictScreen`:
   - Two cards: "This device — modified at HH:mm" / "Cloud — modified at HH:mm".
   - Each card summarizes: tx count, account count, last-tx date.
   - Two action buttons:
     - **Use cloud** → call `BackupManager.applyBackupBodyToDb(remote.body)`, then `repo.push(local)` to mark this device's claim (no — see below).
     - **Use this device** → call `repo.push(local)` (overwrites remote with local snapshot; DropBox / Drive both have `update` semantics in 4b/4c).
   - Banner clears on either action; screen pops back to Settings.

4. The "Use cloud" path simplifies to **apply remote body, then drop
   push** — keeping the LWW clause for the next push. The conflict is
   cleared because we now match cloud. The Settings VM's
   `_conflictPending` flips to `false` after either action.

`SyncResult` gains one new variant:

```kotlin
sealed class SyncResult {
    data class Pushed(...) : SyncResult()
    data class Pulled(...) : SyncResult()
    object NoRemoteSnapshot : SyncResult()
    data class ConflictPending(
        val remote: SyncSnapshot,
        val local: SyncSnapshot,
    ) : SyncResult()
    data class Failed(...) : SyncResult()
}
```

`RoutingCloudSyncRepository.syncOnce()` maps both repo `Conflict`s to
`ConflictPending` here, so the per-repo orchestrators are untouched.

## Sync triggers — concrete wiring

- **App launch:** `ExpenseTrackerApp.onCreate()` injects `CloudSyncRepository`
  (via `@EntryPoint` — Hilt can't `@Inject` into the `Application` class
  for non-`@HiltAndroidApp` reasons… actually `ExpenseTrackerApp` IS
  `@HiltAndroidApp`, so plain `@Inject lateinit var cloudSyncRepository`
  works). New code:

  ```kotlin
  appScope.launch {
      if (cloudSyncRepository.isSignedIn.value) {
          runCatching { cloudSyncRepository.syncOnce() }
              .onFailure { Log.w(TAG, "Launch sync failed", it) }
      }
  }
  ```

  Failure is logged, never surfaced. Push/pull boundaries handle their
  own state. The `appScope` already exists (for the recurring worker
  setup).

- **Mutation:** `TransactionMutationBus` debounce collector in
  `RoutingCloudSyncRepository.init` (or its mirror). All successful
  writes mentioned above emit. Errors are silent — surface only via the
  Settings sync status when the user opens it.

- **Manual:** `SettingsViewModel.onSyncNow()` calls `repo.syncOnce()` in
  `viewModelScope`. Surfaces `PushResult`/`SyncResult` outcome via the
  existing snackbar pattern (new strings: `sync_now_done`, `sync_now_failed`,
  `sync_now_no_remote`).

  When `result is SyncResult.ConflictPending`, the VM sets
  `_conflictPending = true` and shows the snackbar `sync_now_conflict`.

## Settings UI

A new `CloudSyncSection` composable added between the existing "Data" and
"Home currency" sections of `SettingsScreen`. Compose-only changes; no XML
layouts added.

`CloudSyncSessionState` is collected via
`viewModel.cloudSyncSession.collectAsStateWithLifecycle()`. The section
renders conditionally based on state:

```
When state is SignedOut and provider = DROPBOX:
   ┌─ CLOUD SYNC ────────────────────────────────────┐
   │ Status: Not signed in                            │
   │ Provider: [ Dropbox | Google Drive ]             │
   │ [ Sign in with Dropbox ]                         │
   │ [ Sync now ] (disabled, dimmed)                  │
   └──────────────────────────────────────────────────┘

When state is SignedIn:
   ┌─ CLOUD SYNC ────────────────────────────────────┐
   │ Status: Signed in as user@example.com (Dropbox)  │
   │ Last synced: 2 min ago                           │
   │ Provider: [ Dropbox | Google Drive ]             │
   │ [ Sign out ]                                     │
   │ [ Sync now ]                                     │
   └────────────────────────────────────────────────┘

When conflictPending (any state):
   [overlapping banner above the section]
   ⚠ Conflict — sync tie. Tap to resolve.
```

OAuth `ActivityResultLauncher`:

```kotlin
val signInLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
) { result ->
    viewModel.onSignInResult(result.data)
}

// click → signInLauncher.launch(viewModel.signInIntent())
```

The `signInIntent` is exposed via `cloudSyncRepository.signInIntent`
(already on `CloudSyncRepository`). The VM passes it through to the
composable so the launcher calls the freshest binding.

## BackupManager extension

```kotlin
// backup/BackupManager.kt — addition
fun applyBackupBodyToDb(body: BackupBody) {
    // Same write logic as importFromUri's "no-media" path: apply accounts
    // → categories → transactions in order, skipping rows whose primary
    // key would collide (per the existing import behavior). Receipts
    // skipped (no media in cloud).
}
```

The body of `importFromUri(appContext, uri)` is refactored so that the
"apply parsed BackupBody to local DB" block (after `BackupBody.deserialize`
and receipt copy) moves into `applyBackupBodyToDb(body)`. The non-URI
callers (cloud pull → apply) reuse it. A small `applyBackupBodyWithReceipts`
helper stays for the URI path with attached media extraction.

Receipt copy from cloud is **out of scope** — `applyBackupBodyToDb`
ignores the `receiptPath` column and writes only the row data. A future
4e task can add a "cloud receipts" sub-envelope.

## Edge cases + error handling

- **Provider flip while syncing.** The previous repo's `mutex.withLock`
  serializes its own queue; the router doesn't preempt an in-flight
  push/pull. State mirrors may briefly show stale "Syncing" — the next
  state emission corrects it. Acceptable for v1.
- **Provider flip with active auth.** Switching from Dropbox to Drive
  doesn't sign the user out of Dropbox. Tokens stay on disk. Switching
  back restores them. Tokens stay valid for 4h (Dropbox) / 60 min
  (Drive); expired-on-load falls through to `signIn() → Failed("Not
  signed in")` and the UI surfaces the empty state.
- **Provider unavailable in build.** If `BuildConfig.DROPBOX_CLIENT_ID`
  is empty / `"changeme"` / unset, the Dropbox chip is greyed out with
  tooltip "Provider not configured". Same for `DEFAULT_WEB_CLIENT_ID`.
- **App-launch sync failure.** Logged at warn level; no toast. The user
  can retry via Settings → Sync now.
- **Mutation debounce vs. conflicts.** If a debounced push collides with
  a pull that produced a `Conflict`, the conflict takes precedence
  (router returns `ConflictPending` immediately, push is dropped that
  round and surfaced next round).
- **Cancellation.** Each `sync*` rethrows `CancellationException` at the
  top. `TransactionMutationBus.events.collect { syncOnce() }` is bounded
  by the router's internal scope, which is cancelled when the singleton
  dies (process death — fine).
- **`BackupBody` apply failure.** Snackbar `sync_now_failed` with the
  exception message; existing import failure strings are reused.
- **Conflict screen visibility across process death.** In-memory flag is
  lossy; on relaunch the conflict is gone. Acceptable: the local DB
  already reflects one of the two snapshots; reconciling again would
  re-`syncOnce()`. Document this in the smoke doc.

## File inventory

### New files (production)

- `app/src/main/java/io/github/jiro/expensetracker/sync/SyncProviderId.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepository.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryMirror.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/TransactionMutationBus.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncSessionState.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ConflictScreen.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModel.kt`

### New files (tests)

- `app/src/test/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/SyncProviderIdTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/CloudSyncSessionStateTest.kt` (assembler logic only)
- `app/src/test/java/io/github/jiro/expensetracker/sync/TransactionMutationBusTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModelTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/ui/settings/CloudSyncSectionTest.kt` (compose UI test against the section)

### Modified files (production)

- `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt` — add `SyncResult.ConflictPending(remote, local)` (sealed arm addition; **non-breaking** because callers `when`-exhaustive).
- `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt` — replace `tokensProvider: () -> DropboxSyncTokens?` with `tokens: DropboxSyncTokensRepository`. Drop the `requireToken()` helper; inline `tokens.load()?.accessToken ?: throw AuthRevoked()` at each call site.
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt` — same refactor.
- `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt` — change `@Binds bindCloudSyncRepository(impl: DropboxCloudSyncRepository)` to `RoutingCloudSyncRepository`.
- `app/src/main/java/io/github/jiro/expensetracker/backup/BackupManager.kt` — extract `applyBackupBodyToDb(body: BackupBody)`. `importFromUri` delegates to it after URI parse.
- `app/src/main/java/io/github/jiro/expensetracker/preferences/SettingsRepository.kt` — add `syncProvider` field, `setSyncProvider()`, `loadSyncProvider()`, `KEY_SYNC_PROVIDER` constant.
- `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt` — inject `cloudSyncRepository: CloudSyncRepository` + `transactionMutationBus: TransactionMutationBus`; add `cloudSyncSession: StateFlow<CloudSyncSessionState>`, `onSyncNow()`, `onSignInResult(intent)`, `onSignOutClick()`, `setSyncProvider(id)`, `onConflictResolved(useCloud)`, plus `signInIntent` passthrough.
- `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt` — add `CloudSyncSection` composable between existing sections; add `ActivityResultLauncher` plumbing.
- `app/src/main/java/io/github/jiro/expensetracker/ExpenseTrackerApp.kt` — inject `CloudSyncRepository`; fire silent `syncOnce()` if signed-in.
- `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt` — register new `Conflict` destination (route name `conflict`).
- `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt` — inject `TransactionMutationBus`, `bus.tryEmit()` after success.
- `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt` — same.
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt` — same.
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModel.kt` — same on delete.
- `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt` — same on delete (or VM-level emit).
- `app/src/main/res/values/strings.xml` — add strings for the new section.
- `app/src/main/AndroidManifest.xml` — no changes (Sign-in already triggers the existing OAuth callback activity).

### Modified files (tests)

- `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImplTest.kt` (if exists in 4c) — replace lambda arg with `FakeDropboxSyncTokensRepository`.
- `app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImplTest.kt` (if exists in 4b) — same on Drive side.
- Existing orchestrator tests (`DropboxCloudSyncRepositoryTest`,
  `GoogleDriveCloudSyncRepositoryTest`) — unchanged; tests already
  construct the impl manually.

## New strings

Append to `app/src/main/res/values/strings.xml`:

```xml
<!-- Phase 4d: Cloud sync UI -->
<string name="settings_sync_section_title">Cloud sync</string>
<string name="settings_sync_status_signed_out">Not signed in</string>
<string name="settings_sync_status_signed_in">Signed in as %1$s (%2$s)</string>
<string name="settings_sync_provider_label">Provider</string>
<string name="settings_sync_provider_dropbox">Dropbox</string>
<string name="settings_sync_provider_google_drive">Google Drive</string>
<string name="settings_sync_provider_unavailable">Not configured in this build</string>
<string name="settings_sync_action_sign_in">Sign in</string>
<string name="settings_sync_action_sign_out">Sign out</string>
<string name="settings_sync_action_sync_now">Sync now</string>
<string name="settings_sync_last_synced_format">Last synced: %1$s</string>
<string name="settings_sync_last_synced_never">Never synced</string>
<string name="settings_sync_conflict_banner">Conflict — sync tie. Tap to resolve.</string>

<string name="sync_now_done">Sync complete</string>
<string name="sync_now_failed">Sync failed: %1$s</string>
<string name="sync_now_no_remote">No remote snapshot — pushed local</string>
<string name="sync_now_conflict">Conflict detected — open Settings to resolve</string>
<string name="sync_sign_in_cancelled">Sign-in cancelled</string>
<string name="sync_sign_in_failed">Sign-in failed: %1$s</string>
<string name="sync_signed_out">Signed out</string>

<string name="conflict_screen_title">Resolve sync conflict</string>
<string name="conflict_local_card_title">This device</string>
<string name="conflict_remote_card_title">Cloud</string>
<string name="conflict_summary_format">%1$d transactions, %2$d accounts</string>
<string name="conflict_modified_format">modified at %1$s</string>
<string name="conflict_action_use_cloud">Use cloud</string>
<string name="conflict_action_use_local">Use this device</string>
<string name="conflict_resolved">Conflict resolved</string>
```

## Testing

### Unit tests (no Android instrumentation)

**`RoutingCloudSyncRepositoryTest`** (hand-rolled fakes for both repos):
- `state_mirrorsActiveRepo`
- `lastSyncedAtEpochMillis_mirrorsActiveRepo`
- `isSignedIn_mirrorsActiveRepo`
- `signInIntent_returnsActiveRepointent`
- `signIn_delegatesToActiveRepo`
- `handleSignInResult_delegatesToActiveRepo`
- `signOut_delegatesToActiveRepo`
- `push_delegatesToActiveRepo`
- `pull_delegatesToActiveRepo`
- `syncOnce_passesThroughSuccess_appliesBodyToDb`
- `syncOnce_passesThroughNoRemoteSnapshot_returnsNoRemoteSnapshot`
- `syncOnce_mapsConflictToPending_doesNotApplyBody`
- `syncOnce_passesThroughFailed`
- `changingProvider_changesActiveRepo`
- `changingProvider_doesNotCorruptMirrorWhileActiveRepoIdles`

**`SyncProviderIdTest`**:
- `fromKey_returnsDropbox_default`
- `fromKey_returnsDropbox_whenKeyIsDropbox`
- `fromKey_returnsGoogleDrive_whenKeyIsGoogleDrive`
- `fromKey_returnsDropbox_whenKeyIsUnknown`

**`TransactionMutationBusTest`**:
- `emit_emitsValue`
- `tryEmit_returnsTrue_whenBuffered`
- `multipleSubscribers_allReceive`

**`CloudSyncSessionStateTest`** (assembler):
- `combine_producesExpectedSnapshot`
- `conflictFlag_flipSurfacedInCombined`

**`CloudSyncSectionTest`** (compose UI):
- `signedOut_showsSignInButton`
- `signedIn_showsSignedOutButton_onlyForActiveProvider`
- `conflictPending_showsBanner`
- `google_drive_chip_disabled_whenClientIdMissing`

**`ConflictViewModelTest`**:
- `useCloud_appliesRemoteBody_clearsPendingConflict_dropsPush`
- `useLocal_pushesLocalOverRemote_clearsPendingConflict`

**`DropboxApiClientImplTest` (4c-test update)**:
- Constructor now passes `FakeDropboxSyncTokensRepository` instead of
  a lambda. Same assertions; one new test:
  `upload_throwsAuthRevoked_whenTokenRepoReturnsNull`.

**`DriveApiClientImplTest` (4b-test update)**: analogous.

**`BackupManagerTest`** (new method coverage):
- `applyBackupBodyToDb_writesAccountsAndCategoriesAndTransactions`
- `applyBackupBodyToDb_skipsRowsWithExistingPrimaryKey`

### VM-level tests

- `SettingsViewModelTest` (extend existing): `sign_in_cancelled_sets_error`,
  `sign_in_succeeded_sets_signed_in`, `setSyncProvider_persists_and_updates_session`,
  `onSyncNow_returns_done_on_success`, `onSyncNow_sets_error_on_failure`,
  `onSyncNow_conflict_pending_sets_bannerFlag`.
- `ConflictViewModelTest` — see above.

### Manual smoke

Documented in `docs/superpowers/testdata/phase-4d-settings-sync.md`
(same shape as 4b/4c smoke docs): build + install, Dropbox sign-in → push → pull, provider switch (Dropbox → Drive signs in / back flips), conflict flow (forced via two devices or test seed), sign-out, app restart preserves state. New tests count: ~14 (router, bus, ID enum, VM, conflict VM, UI test) + existing tests updated.

## Pre-merge verification checklist

1. `./gradlew testDebugUnitTest` — all green; count grows from 485 to
   ~499 (4d adds ~14 tests).
2. `./gradlew :app:assembleDebug` — debug APK builds; Hilt graph
   resolves with the new `RoutingCloudSyncRepository` binding.
3. Token-lambda refactor validated: any pre-4d lambda construction site
   updated. (Per the 4c review there are zero — the lambda was only at
   the constructor site and tests construct manually.)
4. Smoke doc (`phase-4d-settings-sync.md`) committed.
5. Tagged `v0.18.15` and pushed to `origin` (project convention:
   direct-to-master + tag, no PR).
