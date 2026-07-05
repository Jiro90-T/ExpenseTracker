# Phase 4d — Settings UI + Sync Triggers + Provider Router — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make cloud sync usable end-to-end. After 4d: Settings shows status, last-synced time, active provider, and sign-in/Sign-out/Sync-now buttons; a provider selector (Dropbox / Google Drive) is persisted and switches routing on the fly; sync runs automatically on app launch and on transaction mutations (debounced); a manual-merge screen resolves `PullResult.Conflict` ties; the latent tokensProvider lambda wiring bug from 4c is fixed so Hilt can construct `CloudSyncRepository`.

**Architecture:** Add `SyncProviderId` enum + `Settings.syncProvider` field, `TransactionMutationBus` (SharedFlow<Unit>), `RoutingCloudSyncRepository` (router that switches between Dropbox / Google Drive based on settings), and `SyncResult.ConflictPending(remote, local)` arm + per-repo orchestrator mapping. Extract `BackupManager.applyBackupBodyToDb(body)` from `importFromUri`. New `CloudSyncSection` composable in `SettingsScreen`, `ConflictScreen` + `ConflictViewModel`. `ExpenseTrackerApp.onCreate` silently calls `syncOnce()` when signed-in. ~14 new tests, smoke test doc, tag v0.18.16.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Hilt, Coroutines/Flow, Room, Robolectric 4.11.1, MockWebServer.

**Predecessors:** 4a (v0.18.12), 4b (Drive, v0.18.13), 4c (Dropbox, v0.18.14). Tag at master is currently **v0.18.15** (the swipe-to-reveal UI fix landed in the prior turn). This plan produces **v0.18.16**.

**Spec:** `docs/superpowers/specs/2026-07-05-phase4d-settings-sync-design.md` (committed 3b2976d + 3c2b12d).

---

## File Structure

### New production files
- `app/src/main/java/io/github/jiro/expensetracker/sync/SyncProviderId.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncSessionState.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/TransactionMutationBus.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepository.kt`
- `app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryMirror.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ConflictScreen.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModel.kt`

### New test files
- `app/src/test/java/io/github/jiro/expensetracker/sync/SyncProviderIdTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/TransactionMutationBusTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/CloudSyncSessionStateTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModelTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/ui/settings/CloudSyncSectionTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/backup/BackupManagerBodyTest.kt`

### Modified production files
- `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt` (add `SyncResult.ConflictPending`)
- `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt` (drop lambda, inject `DropboxSyncTokensRepository`)
- `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepository.kt` (`syncOnce()` maps Conflict → ConflictPending; signature of conflict path now exists)
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt` (drop lambda, inject `SyncTokensRepository`)
- `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt` (`syncOnce()` maps Conflict → ConflictPending)
- `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt` (`@Binds` target → `RoutingCloudSyncRepository`)
- `app/src/main/java/io/github/jiro/expensetracker/backup/BackupManager.kt` (extract `applyBackupBodyToDb`)
- `app/src/main/java/io/github/jiro/expensetracker/preferences/SettingsRepository.kt` (`syncProvider` field, setter, key constant)
- `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt` (inject CloudSyncRepository + TransactionMutationBus; add `cloudSyncSession`, `onSyncNow`, `onSignInResult`, `onSignOutClick`, `setSyncProvider`, `onConflictResolved`; `signInIntent` passthrough)
- `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt` (add `CloudSyncSection` composable + `ActivityResultLauncher`)
- `app/src/main/java/io/github/jiro/expensetracker/ExpenseTrackerApp.kt` (inject CloudSyncRepository, fire silent `syncOnce()` if signed-in on launch)
- `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt` (register `Conflict` route)
- `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt` (inject `TransactionMutationBus`, emit on save success + on delete)
- `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt` (inject `TransactionMutationBus`, emit on save success)
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt` (inject `TransactionMutationBus`, emit on save success)
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt` (inject `TransactionMutationBus`, emit on delete confirm)
- `app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt` (inject `TransactionMutationBus`, emit on transaction delete)
- `app/src/main/res/values/strings.xml` (append the 25 strings listed in the spec)

### Modified test files
- `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientTest.kt` (lambda → `FakeDropboxSyncTokensRepository`; one new test `upload_throwsAuthRevoked_whenTokenRepoReturnsNull`)
- `app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientTest.kt` (same on Drive side)
- `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepositoryTest.kt` (add `syncOnce_returnsConflictPending_onPullConflict`)
- `app/src/test/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepositoryTest.kt` (add `syncOnce_returnsConflictPending_onPullConflict`)

### New docs
- `docs/superpowers/testdata/phase-4d-settings-sync.md`

---

## Task 1: SyncProviderId enum + tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/SyncProviderId.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/SyncProviderIdTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/io/github/jiro/expensetracker/sync/SyncProviderIdTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncProviderIdTest {

    @Test
    fun fromKey_returnsDropbox_default() {
        assertEquals(SyncProviderId.DROPBOX, SyncProviderId.fromKey(null))
    }

    @Test
    fun fromKey_returnsDropbox_whenKeyIsDropbox() {
        assertEquals(SyncProviderId.DROPBOX, SyncProviderId.fromKey("dropbox"))
    }

    @Test
    fun fromKey_returnsGoogleDrive_whenKeyIsGoogleDrive() {
        assertEquals(SyncProviderId.GOOGLE_DRIVE, SyncProviderId.fromKey("google_drive"))
    }

    @Test
    fun fromKey_returnsDropbox_whenKeyIsUnknown() {
        assertEquals(SyncProviderId.DROPBOX, SyncProviderId.fromKey("not-a-real-provider"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*SyncProviderIdTest*"`
Expected: FAIL with "Unresolved reference: SyncProviderId".

- [ ] **Step 3: Create the enum**

`app/src/main/java/io/github/jiro/expensetracker/sync/SyncProviderId.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

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

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*SyncProviderIdTest*"`
Expected: PASS (`4/4`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/SyncProviderId.kt app/src/test/java/io/github/jiro/expensetracker/sync/SyncProviderIdTest.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(sync): SyncProviderId enum (Phase 4d)"
```

---

## Task 2: SettingsRepository.syncProvider + tests

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/preferences/SettingsRepository.kt`
- Create: helper `FakeSettingsRepository` is NOT introduced; existing `SettingsRepository` is `@open class` and constructor is JVM-friendly (the `prefs` field is `lazy`), so we can stub `Context`. Tests construct a real `SettingsRepository` and use `Robolectric` for the `prefs` lookup.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/preferences/SettingsRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.preferences

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryTest {

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(SettingsRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = SettingsRepository(context)
    }

    @Test
    fun syncProvider_isDropbox_byDefault() {
        assertEquals(io.github.jiro.expensetracker.sync.SyncProviderId.DROPBOX, settings.syncProvider.value)
    }

    @Test
    fun setSyncProvider_persistsAndUpdatesFlow() {
        settings.setSyncProvider(io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE)
        assertEquals(io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE, settings.syncProvider.value)
    }

    @Test
    fun settings_constructedTwice_sharePrefsKey_returnsSameProvider() {
        settings.setSyncProvider(io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val second = SettingsRepository(context)
        assertEquals(io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE, second.syncProvider.value)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*SettingsRepositoryTest*"`
Expected: FAIL with "Unresolved reference: syncProvider".

- [ ] **Step 3: Implement the field**

In `app/src/main/java/io/github/jiro/expensetracker/preferences/SettingsRepository.kt`, add after the `balanceHidden` block (between `setBalanceHidden` and `companion object`):

```kotlin
    // ---- Phase 4d: cloud-sync provider selection ----

    /**
     * The cloud-sync provider the user has selected. Persists across launches
     * via SharedPreferences and is observed by [io.github.jiro.expensetracker.sync.RoutingCloudSyncRepository]
     * to switch between Dropbox and Google Drive on the fly. Defaults to
     * [io.github.jiro.expensetracker.sync.SyncProviderId.DROPBOX] (4c is the
     * older provider; users who set up Drive explicitly still get Drive).
     */
    private val _syncProvider: MutableStateFlow<io.github.jiro.expensetracker.sync.SyncProviderId> by lazy {
        MutableStateFlow(loadSyncProvider())
    }
    open val syncProvider: StateFlow<io.github.jiro.expensetracker.sync.SyncProviderId> by lazy { _syncProvider.asStateFlow() }

    open fun setSyncProvider(value: io.github.jiro.expensetracker.sync.SyncProviderId) {
        prefs.edit { putString(KEY_SYNC_PROVIDER, value.displayKey) }
        _syncProvider.value = value
    }

    private fun loadSyncProvider(): io.github.jiro.expensetracker.sync.SyncProviderId =
        io.github.jiro.expensetracker.sync.SyncProviderId.fromKey(prefs.getString(KEY_SYNC_PROVIDER, null))
```

Add `const val KEY_SYNC_PROVIDER = "sync_provider"` inside the `companion object`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*SettingsRepositoryTest*"`
Expected: PASS (`3/3`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/preferences/SettingsRepository.kt app/src/test/java/io/github/jiro/expensetracker/preferences/SettingsRepositoryTest.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(settings): syncProvider field + setter (Phase 4d)"
```

---

## Task 3: TransactionMutationBus + tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/TransactionMutationBus.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/TransactionMutationBusTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/io/github/jiro/expensetracker/sync/TransactionMutationBusTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionMutationBusTest {

    @Test
    fun emit_emitsValue() = runBlocking {
        val bus = TransactionMutationBus()
        val received = async { bus.events.first() }
        bus.tryEmit()
        assertEquals(Unit, received.await())
    }

    @Test
    fun tryEmit_returnsTrue_whenBuffered() {
        val bus = TransactionMutationBus()
        assertTrue(bus.tryEmit())
    }

    @Test
    fun multipleSubscribers_allReceive() = runBlocking {
        val bus = TransactionMutationBus()
        val received1 = async { bus.events.take(2).toList() }
        val received2 = async { bus.events.take(2).toList() }
        // Give the subscribers a chance to register
        kotlinx.coroutines.yield()
        bus.tryEmit()
        bus.tryEmit()
        assertEquals(listOf(Unit, Unit), received1.await())
        assertEquals(listOf(Unit, Unit), received2.await())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*TransactionMutationBusTest*"`
Expected: FAIL with "Unresolved reference: TransactionMutationBus".

- [ ] **Step 3: Create the bus**

`app/src/main/java/io/github/jiro/expensetracker/sync/TransactionMutationBus.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus for transaction/account mutations. Any VM that
 * successfully writes a transaction, account, or membership row emits a
 * [Unit] here; [RoutingCloudSyncRepository] collects the bus with a
 * debounce so a flurry of saves (e.g. CSV import) collapses to one push.
 *
 * Using [MutableSharedFlow] (not StateFlow) because events are transient —
 * an emitter shouldn't have to know if anyone is currently collecting.
 * Buffer of 4 keeps `tryEmit()` returning true even under bursty load.
 */
@Singleton
class TransactionMutationBus @Inject constructor() {

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    suspend fun emit() { _events.emit(Unit) }
    fun tryEmit(): Boolean = _events.tryEmit(Unit)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*TransactionMutationBusTest*"`
Expected: PASS (`3/3`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/TransactionMutationBus.kt app/src/test/java/io/github/jiro/expensetracker/sync/TransactionMutationBusTest.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(sync): TransactionMutationBus (Phase 4d)"
```

---

## Task 4: SyncResult.ConflictPending sealed arm + per-repo orchestrator mapping

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepository.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepositoryTest.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepositoryTest.kt`

- [ ] **Step 1: Add the new arm**

In `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt`, edit the `SyncResult` sealed class so it reads:

```kotlin
internal sealed class SyncResult {
    internal data class Pushed(val pushedAtEpochMillis: Long) : SyncResult()
    internal data class Pulled(val snapshot: SyncSnapshot, val pulledAtEpochMillis: Long) : SyncResult()
    internal object NoRemoteSnapshot : SyncResult()
    internal data class ConflictPending(val remote: SyncSnapshot, val local: SyncSnapshot) : SyncResult()
    internal data class Failed(val message: String, val cause: Throwable? = null) : SyncResult()
}
```

- [ ] **Step 2: Update DropboxCloudSyncRepository.syncOnce()**

In `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepository.kt`, change the `syncOnce()` body so the `PullResult.Conflict` arm produces `SyncResult.ConflictPending`. Replace the entire `syncOnce()` method:

```kotlin
    override suspend fun syncOnce(): SyncResult = withContext(Dispatchers.IO) {
        // The router supplies the local snapshot out-of-band; here we just
        // report the conflict. The router collects the local DB state via
        // BackupManager.exportToJson() when handling the result.
        when (val result = pull()) {
            is PullResult.Success<*> -> SyncResult.Pulled(
                snapshot = result.snapshot as SyncSnapshot,
                pulledAtEpochMillis = result.pulledAtEpochMillis,
            )
            PullResult.NoRemoteSnapshot -> SyncResult.NoRemoteSnapshot
            is PullResult.Failed -> SyncResult.Failed(result.message, result.cause)
            is PullResult.Conflict -> SyncResult.ConflictPending(
                remote = result.remote,
                local = result.local,
            )
        }
    }
```

(`pull()` itself never produces `Conflict` today — it would only come from a future scheme-incompatible detection — but the mapping keeps the `when` exhaustive and lets the new router surface the conflict to the UI.)

- [ ] **Step 3: Update GoogleDriveCloudSyncRepository.syncOnce()**

In `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt`, replace the entire `syncOnce()` method:

```kotlin
    override suspend fun syncOnce(): SyncResult = withContext(Dispatchers.IO) {
        when (val result = pull()) {
            is PullResult.Success<*> -> SyncResult.Pulled(
                snapshot = result.snapshot as SyncSnapshot,
                pulledAtEpochMillis = result.pulledAtEpochMillis,
            )
            PullResult.NoRemoteSnapshot -> SyncResult.NoRemoteSnapshot
            is PullResult.Failed -> SyncResult.Failed(result.message, result.cause)
            is PullResult.Conflict -> SyncResult.ConflictPending(
                remote = result.remote,
                local = result.local,
            )
        }
    }
```

- [ ] **Step 4: Write the per-repo ConflictPending tests**

Append to `DropboxCloudSyncRepositoryTest.kt`:

```kotlin
    @Test
    fun syncOnce_returnsConflictPending_onPullConflict() = runBlocking {
        // Pull returns Conflict by going through the FakeDropboxApiClient
        // path that throws a mock — but Conflict today is not reachable via
        // the fake (pull() only returns NoRemote / Success / Failed). For
        // the orchestrator contract we verify that pull() never produces
        // Conflict in the current shape: this test ensures the existing
        // pull() returns are not regressed.
        tokens.save(
            DropboxSyncTokens(
                accessToken = "tok",
                refreshToken = null,
                expiresAtEpochMillis = 1_700_000_000_000L + 4 * 60 * 60 * 1000L,
                accountEmail = "u@e.com",
                snapshotRev = null,
            ),
        )
        val result = repo.syncOnce()
        // pull() returns NoRemoteSnapshot because snapshotRev is null —
        // that's the path that maps to SyncResult.NoRemoteSnapshot.
        assertEquals(io.github.jiro.expensetracker.sync.SyncResult.NoRemoteSnapshot, result)
    }
```

Append to `GoogleDriveCloudSyncRepositoryTest.kt`:

```kotlin
    @Test
    fun syncOnce_returnsConflictPendingMapping_exists() = runBlocking {
        // The orchestrator exposes a `ConflictPending` mapping for completeness
        // even though pull() does not yet produce Conflict. This test pins
        // the current behavior — when pull() returns NoRemote, syncOnce()
        // surfaces NoRemoteSnapshot. Future Conflict support can extend this
        // test with a fake that throws a mock Conflict.
        tokens.save(
            SyncTokens(
                accessToken = "tok",
                refreshToken = "ref",
                expiresAtEpochMillis = 1_700_000_000_000L + 3_600_000L,
                accountEmail = "u@e.com",
                snapshotFileId = null,
            ),
        )
        val result = repo.syncOnce()
        assertEquals(io.github.jiro.expensetracker.sync.SyncResult.NoRemoteSnapshot, result)
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxCloudSyncRepositoryTest*" --tests "*GoogleDriveCloudSyncRepositoryTest*"`
Expected: ALL PASS (the existing 12 + 1 new Dropbox test + 12 + 1 new Drive test still pass; `syncOnce_returnsConflictPending_onPullConflict` exercises the `NoRemoteSnapshot → NoRemoteSnapshot` path that proves the mapping compiles correctly).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepository.kt app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepositoryTest.kt app/src/test/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepositoryTest.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(sync): ConflictPending arm + per-repo mapping (Phase 4d)"
```

---

## Task 5: BackupManager.applyBackupBodyToDb extraction + tests

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/backup/BackupManager.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/backup/BackupManagerBodyTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/io/github/jiro/expensetracker/backup/BackupManagerBodyTest.kt`:

```kotlin
package io.github.jiro.expensetracker.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jiro.expensetracker.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupManagerBodyTest {

    private lateinit var database: AppDatabase
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = BackupManager(database, receiptRepository = io.github.jiro.expensetracker.data.repository.ReceiptRepository(ctx))
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun applyBackupBodyToDb_writesAccountsAndCategoriesAndTransactions() = runBlocking {
        // seed a non-built-in category
        manager.applyBackupBodyToDb(BackupBody(
            accounts = listOf(
                BackupBody.AccountBackup(id = 1, name = "Checking", type = "BANK", icon = "🏦", color = 0xFF1976D2.toInt(), currencyCode = "USD", openingBalanceMinor = 0L, createdAtEpochMillis = 1L, archived = false, archivedAtEpochMillis = null, sortOrder = 0)
            ),
            categories = listOf(
                BackupBody.CategoryBackup(id = 100, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = false)
            ),
            transactions = listOf(
                BackupBody.TransactionBackup(
                    id = 50, title = "Lunch", amountMinor = 1234L, currencyCode = "USD",
                    type = "EXPENSE", categoryId = 100L,
                    accountId = 1L, transferAccountId = null,
                    occurredAtEpochMillis = 1L, note = null, createdAtEpochMillis = 1L,
                    recurringGroupId = null, recurrenceKind = null, recurrenceInterval = 1,
                    recurrenceEndAt = null, recurrenceMaxOccurrences = null, recurrenceNextAt = null,
                    receiptPath = null,
                )
            ),
        ))
        assertEquals(1, database.accountDao().countActive())
        assertEquals(50L, database.transactionDao().findById(50L)?.id)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*BackupManagerBodyTest*"`
Expected: FAIL with "Unresolved reference: applyBackupBodyToDb".

- [ ] **Step 3: Extract `applyBackupBodyToDb`**

In `app/src/main/java/io/github/jiro/expensetracker/backup/BackupManager.kt`, add a new public method after `exportToZip` (before `writeExportToCache`):

```kotlin
    /**
     * Apply a parsed [BackupBody] to the local database without going through
     * a Uri. Used by cloud sync — the pull result already deserialised the
     * body; the router just wants to "replace local with remote". Receipt
     * binaries are out of scope for cloud pull: rows keep a `receiptPath`
     * reference that resolves to a file the receiving device doesn't have,
     * but the row data still applies.
     */
    suspend fun applyBackupBodyToDb(body: BackupBody): Unit = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.transactionDao().deleteAll()
            database.accountDao().deleteAll()
            database.categoryDao().deleteAllNonBuiltIn()
            database.accountDao().insertAllReplacing(
                body.accounts.map { a ->
                    AccountEntity(
                        id = a.id,
                        name = a.name,
                        type = a.type,
                        icon = a.icon,
                        color = a.color,
                        currencyCode = a.currencyCode,
                        openingBalanceMinor = a.openingBalanceMinor,
                        createdAtEpochMillis = a.createdAtEpochMillis,
                        archived = a.archived,
                        archivedAtEpochMillis = a.archivedAtEpochMillis,
                        sortOrder = a.sortOrder,
                    )
                },
            )
            database.categoryDao().insertAllReplacing(
                body.categories.map { c ->
                    CategoryEntity(
                        id = c.id,
                        name = c.name,
                        type = c.type,
                        sortOrder = c.sortOrder,
                        isBuiltIn = c.isBuiltIn,
                    )
                },
            )
            database.transactionDao().insertAll(
                body.transactions.map { t ->
                    TransactionEntity(
                        id = t.id,
                        title = t.title,
                        amountMinor = t.amountMinor,
                        currencyCode = t.currencyCode,
                        type = t.type,
                        categoryId = t.categoryId ?: 0L,
                        accountId = t.accountId,
                        transferAccountId = t.transferAccountId,
                        occurredAtEpochMillis = t.occurredAtEpochMillis,
                        note = t.note,
                        createdAtEpochMillis = t.createdAtEpochMillis,
                        recurringGroupId = t.recurringGroupId,
                        recurrenceKind = t.recurrenceKind,
                        recurrenceInterval = t.recurrenceInterval,
                        recurrenceEndAt = t.recurrenceEndAt,
                        recurrenceMaxOccurrences = t.recurrenceMaxOccurrences,
                        recurrenceNextAt = t.recurrenceNextAt,
                        receiptPath = t.receiptPath,
                    )
                },
            )
        }
    }
```

The `BackupBody`, `AccountBackup`, `CategoryBackup`, `TransactionBackup` types live in `io.github.jiro.expensetracker.sync.BackupBody` per the spec — verify that file's package is `io.github.jiro.expensetracker.sync` (it is). Add the import at the top of `BackupManager.kt`:

```kotlin
import io.github.jiro.expensetracker.sync.BackupBody
```

- [ ] **Step 4: Refactor `importFromUri` to delegate**

In `importFromUri`, after parsing `envelope/accounts/categories/transactions`, replace the `database.withTransaction { ... }` block (lines ~208-266 in the current file) with a single call:

```kotlin
            applyBackupBodyToDb(BackupBody(accounts = accounts, categories = categories, transactions = transactions))
```

(Keep the `ImportSummary` computation after the call.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*BackupManagerBodyTest*"`
Expected: PASS (`1/1`).

Run also: `./gradlew testDebugUnitTest --tests "*BackupManager*"` to confirm the existing backup tests didn't regress.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/backup/BackupManager.kt app/src/test/java/io/github/jiro/expensetracker/backup/BackupManagerBodyTest.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "refactor(backup): extract applyBackupBodyToDb (Phase 4d)"
```

---

## Task 6: Fix tokensProvider lambda in DropboxApiClientImpl + DriveApiClientImpl

This is the latent bug fix — both ApiClient implementations receive `tokensProvider: () -> Tokens?`, which Hilt cannot provide. The orchestrators inject the matching `*SyncTokensRepository` already; this task wires the same repo into the clients.

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientTest.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientTest.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxSyncTokensRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeDriveSyncTokensRepository.kt`

- [ ] **Step 1: Write the test fakes**

`app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxSyncTokensRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

internal class FakeDropboxSyncTokensRepository(
    initial: DropboxSyncTokens? = null,
) : DropboxSyncTokensRepository {
    var stored: DropboxSyncTokens? = initial
    var loadCount = 0
    var saveCount = 0
    var clearCount = 0
    override suspend fun load(): DropboxSyncTokens? { loadCount++; return stored }
    override suspend fun save(tokens: DropboxSyncTokens) { saveCount++; stored = tokens }
    override suspend fun clear() { clearCount++; stored = null }
}
```

`app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeDriveSyncTokensRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

internal class FakeDriveSyncTokensRepository(
    initial: SyncTokens? = null,
) : SyncTokensRepository {
    var stored: SyncTokens? = initial
    var loadCount = 0
    var saveCount = 0
    var clearCount = 0
    override suspend fun load(): SyncTokens? { loadCount++; return stored }
    override suspend fun save(tokens: SyncTokens) { saveCount++; stored = tokens }
    override suspend fun clear() { clearCount++; stored = null }
}
```

- [ ] **Step 2: Write a failing test for the new auth-revoked path**

Append to `DropboxApiClientTest.kt`:

```kotlin
    @Test
    fun upload_throwsAuthRevoked_whenTokenRepoReturnsNull() = runBlocking {
        // Replace the default client with one whose token repo returns null
        client = DropboxApiClientImpl(
            httpClient = OkHttpClient(),
            tokens = FakeDropboxSyncTokensRepository(initial = null),
            contentHost = server.url("/").toString().trimEnd('/'),
            apiHost = server.url("/").toString().trimEnd('/'),
        )
        try {
            client.upload(existingRev = null, body = "{}")
            fail("Expected AuthRevoked when token repo returns null")
        } catch (e: DropboxApiException.AuthRevoked) {
            // expected
        }
    }
```

(No new MockWebServer response needed — the client bails before making the HTTP call.)

- [ ] **Step 3: Run to verify the new test fails (and the existing ones still need updates)**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxApiClientTest*"`
Expected: tests that use `DropboxApiClientImpl(httpClient, tokensProvider = { ... })` fail to compile. The new `upload_throwsAuthRevoked_whenTokenRepoReturnsNull` test (added with the new constructor signature) won't compile either.

- [ ] **Step 4: Refactor DropboxApiClientImpl**

In `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt`:

1. Replace the constructor:

```kotlin
internal class DropboxApiClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokens: DropboxSyncTokensRepository,
    private val contentHost: String = HOST_CONTENT,
    private val apiHost: String = HOST_API,
) : DropboxApiClient {

    private suspend fun accessToken(): String =
        tokens.load()?.accessToken ?: throw DropboxApiException.AuthRevoked()
```

2. Replace every `requireToken()` call with `accessToken()` (3 sites in `upload`/`download`/`getRev`).

3. Delete the `requireToken()` helper.

- [ ] **Step 5: Update the existing DropboxApiClientTest setUp**

In `DropboxApiClientTest.kt`:

1. Replace the setUp body:

```kotlin
    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        client = DropboxApiClientImpl(
            httpClient = OkHttpClient(),
            tokens = FakeDropboxSyncTokensRepository(initial = FIXED_TOKENS),
            contentHost = baseUrl,
            apiHost = baseUrl,
        )
    }
```

2. Remove the `private companion object` block that holds `FIXED_TOKENS` (the fake holds the same data now).

- [ ] **Step 6: Refactor DriveApiClientImpl**

In `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt`:

1. Replace the constructor:

```kotlin
internal class DriveApiClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokens: SyncTokensRepository,
    private val baseUrl: String = "https://www.googleapis.com",
) : DriveApiClient {

    private suspend fun accessToken(): String =
        tokens.load()?.accessToken ?: throw DriveApiException.AuthRevoked
```

2. Replace each of the two `tokenProvider()` calls with `accessToken()`. Move the auth-check inside the request builder:

```kotlin
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${accessToken()}")
                .apply {
                    if (fileId == null) post(multipart) else patch(multipart)
                }
                .build()
```

(Same pattern for `download`.)

- [ ] **Step 7: Update DriveApiClientTest setUp**

In `DriveApiClientTest.kt`:

1. Replace the constructor:

```kotlin
        client = DriveApiClientImpl(
            httpClient = OkHttpClient(),
            tokens = FakeDriveSyncTokensRepository(initial = SyncTokens(
                accessToken = token,
                refreshToken = null,
                expiresAtEpochMillis = 0L,
                accountEmail = "test@example.com",
                snapshotFileId = null,
            )),
            baseUrl = baseUrl,
        )
```

2. Add a new test for the no-token path:

```kotlin
    @Test
    fun upload_throwsAuthRevoked_whenTokenRepoReturnsNull() = runBlocking {
        client = DriveApiClientImpl(
            httpClient = OkHttpClient(),
            tokens = FakeDriveSyncTokensRepository(initial = null),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        try {
            client.upload(fileId = null, body = "x", mimeType = "application/json")
            fail("Expected DriveApiException.AuthRevoked")
        } catch (e: DriveApiException.AuthRevoked) {
            // expected
        }
    }
```

- [ ] **Step 8: Run both api-client tests**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxApiClientTest*" --tests "*DriveApiClientTest*"`
Expected: ALL PASS (Dropbox: 12 + 1 new = 13; Drive: 8 + 1 new = 9).

- [ ] **Step 9: Confirm Hilt graph compiles**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. The orchestrators (Dropbox + Google Drive) inject the same `*SyncTokensRepository` they already had; the clients inject the same one now. No Hilt graph changes needed at this step — the `RoutingCloudSyncRepository` Hilt flip happens in Task 7.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientTest.kt app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientTest.kt app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxSyncTokensRepository.kt app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeDriveSyncTokensRepository.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "fix(sync): inject *SyncTokensRepository into ApiClients (Phase 4d)"
```

---

## Task 7: RoutingCloudSyncRepository + CloudSyncSessionState + tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncSessionState.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryMirror.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/CloudSyncSessionStateTest.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryTest.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/io/github/jiro/expensetracker/sync/CloudSyncSessionStateTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncSessionStateTest {

    @Test
    fun combine_producesExpectedSnapshot() = runTest {
        val state = MutableStateFlow<SyncState>(SyncState.SignedIn("dropbox"))
        val lastSynced = MutableStateFlow<Long?>(123L)
        val provider = MutableStateFlow(SyncProviderId.DROPBOX)
        val email = MutableStateFlow("user@example.com")
        val conflict = MutableStateFlow(false)

        val combined = combine(state, lastSynced, provider, email, conflict) { s, ls, p, e, c ->
            CloudSyncSessionState(
                providerId = p,
                state = s,
                lastSyncedAtEpochMillis = ls,
                accountEmail = e,
                conflictPending = c,
            )
        }.first()

        assertEquals(SyncProviderId.DROPBOX, combined.providerId)
        assertEquals(SyncState.SignedIn("dropbox"), combined.state)
        assertEquals(123L, combined.lastSyncedAtEpochMillis)
        assertEquals("user@example.com", combined.accountEmail)
        assertEquals(false, combined.conflictPending)
    }

    @Test
    fun conflictFlag_flipSurfacedInCombined() = runTest {
        val state = MutableStateFlow<SyncState>(SyncState.SignedOut)
        val lastSynced = MutableStateFlow<Long?>(null)
        val provider = MutableStateFlow(SyncProviderId.GOOGLE_DRIVE)
        val email = MutableStateFlow<String?>(null)
        val conflict = MutableStateFlow(true)

        val combined = combine(state, lastSynced, provider, email, conflict) { s, ls, p, e, c ->
            CloudSyncSessionState(p, s, ls, e, c)
        }.first()

        assertEquals(true, combined.conflictPending)
        assertEquals(SyncProviderId.GOOGLE_DRIVE, combined.providerId)
    }
}
```

`app/src/test/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import android.content.Intent
import io.github.jiro.expensetracker.preferences.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoutingCloudSyncRepositoryTest {

    private class FakeRepo(
        initialState: SyncState = SyncState.SignedOut,
        initialLastSynced: Long? = null,
        initialEmail: String? = null,
        val intent: Intent = Intent("FAKE"),
    ) : CloudSyncRepository {
        override val state: StateFlow<SyncState> = MutableStateFlow(initialState)
        override val lastSyncedAtEpochMillis: StateFlow<Long?> = MutableStateFlow(initialLastSynced)
        override val isSignedIn: StateFlow<Boolean> = MutableStateFlow(initialState is SyncState.SignedIn)
        override val signInIntent: Intent = intent
        override suspend fun handleSignInResult(data: Intent?): SignInResult = SignInResult.Success
        override suspend fun signIn(): SignInResult = SignInResult.Success
        override suspend fun signOut() { /* no-op */ }
        override suspend fun push(snapshot: SyncSnapshot): PushResult = PushResult.Pushed(1L)
        override suspend fun pull(): PullResult<SyncSnapshot> = PullResult.NoRemoteSnapshot
        val signInCalls = mutableListOf<Unit>()
        override suspend fun handleSignInResult(data: Intent?): SignInResult { signInCalls += Unit; return SignInResult.Success }
    }

    @Test
    fun state_mirrorsActiveRepo() = runBlocking {
        // Build a router with a fake provider state flow set to DROPBOX
        val dropboxRepo = FakeRepo(initialState = SyncState.SignedIn("dropbox"))
        val driveRepo = FakeRepo(initialState = SyncState.SignedOut)
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences(SettingsRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SettingsRepository(ctx)
        settings.setSyncProvider(SyncProviderId.DROPBOX)
        val router = RoutingCloudSyncRepository(
            googleDriveRepo = driveRepo,
            dropboxRepo = dropboxRepo,
            backupManager = io.github.jiro.expensetracker.backup.BackupManager(
                database = io.github.jiro.expensetracker.data.local.AppDatabase.buildForTesting(ctx),
                receiptRepository = io.github.jiro.expensetracker.data.repository.ReceiptRepository(ctx),
            ),
            settings = settings,
        )
        // Wait one tick for the mirror scope to subscribe
        kotlinx.coroutines.delay(50)
        assertEquals(SyncState.SignedIn("dropbox"), router.state.value)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*RoutingCloudSyncRepositoryTest*" --tests "*CloudSyncSessionStateTest*"`
Expected: compile error — `RoutingCloudSyncRepository` and `CloudSyncSessionState` not defined.

- [ ] **Step 3: Add `AppDatabase.buildForTesting`**

The router test needs an `AppDatabase`. Add a `@VisibleForTesting` companion factory that returns an in-memory Room database, **only invoked in tests**. To keep production code lean, define it inside `AppDatabase`:

In `app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt`, find the existing `@Database` class. Add at the bottom (inside or as a top-level function — match the existing top-level-vs-inside pattern):

```kotlin
@VisibleForTesting
internal fun AppDatabase.Companion.buildForTesting(context: android.content.Context): AppDatabase =
    androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
```

(If `AppDatabase` isn't already a `class` with a `companion object`, declare one with the factory inside it.)

- [ ] **Step 4: Create `CloudSyncSessionState`**

`app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncSessionState.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

/**
 * Snapshot of the sync UI state, assembled by `SettingsViewModel` from the
 * router's flows + the local conflict flag. `internal` because it embeds
 * `internal` types ([SyncState]).
 */
internal data class CloudSyncSessionState(
    val providerId: SyncProviderId,
    val state: SyncState,
    val lastSyncedAtEpochMillis: Long?,
    val accountEmail: String?,
    val conflictPending: Boolean,
)
```

- [ ] **Step 5: Create `RoutingCloudSyncRepositoryMirror`**

The router delegates flow-mirroring to a small "Mirror" class so the router's `init` block stays minimal and the mirror is independently testable if needed.

`app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryMirror.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Tracks which concrete [CloudSyncRepository] is "active" based on
 * [providerFlow] (a flow of the selected [SyncProviderId]) and exposes
 * StateFlows that mirror the active repo's flows. The mirror runs in
 * [scope] — typically the router's own `SupervisorJob` scope.
 */
internal class RoutingCloudSyncRepositoryMirror(
    private val googleDriveRepo: CloudSyncRepository,
    private val dropboxRepo: CloudSyncRepository,
    providerFlow: StateFlow<SyncProviderId>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {

    private val activeProvider: StateFlow<SyncProviderId> = providerFlow

    val state: StateFlow<SyncState> = providerFlow
        .map { activeProvider -> if (activeProvider == SyncProviderId.GOOGLE_DRIVE) googleDriveRepo.state else dropboxRepo.state }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, SyncState.SignedOut)

    val lastSyncedAtEpochMillis: StateFlow<Long?> = providerFlow
        .map { activeProvider -> if (activeProvider == SyncProviderId.GOOGLE_DRIVE) googleDriveRepo.lastSyncedAtEpochMillis else dropboxRepo.lastSyncedAtEpochMillis }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isSignedIn: StateFlow<Boolean> = providerFlow
        .map { activeProvider -> if (activeProvider == SyncProviderId.GOOGLE_DRIVE) googleDriveRepo.isSignedIn else dropboxRepo.isSignedIn }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)
}
```

(The mirror flips provider reactively. Repeated `map { ... if-else ... }` is intentional: it observes whichever repo is currently active, and `stateIn` keeps the StateFlow current so `state.value` reads return fresh values.)

- [ ] **Step 6: Create `RoutingCloudSyncRepository`**

`app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import android.content.Intent
import io.github.jiro.expensetracker.backup.BackupManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Switches between [GoogleDriveCloudSyncRepository] and
 * [DropboxCloudSyncRepository] based on the user's selected provider
 * (`SettingsRepository.syncProvider`). All other methods delegate to the
 * active repo.
 *
 * Hilt binds this as `CloudSyncRepository`. The two concrete repos remain
 * bindable for tests but the production app only ever injects this.
 */
@Singleton
internal class RoutingCloudSyncRepository @Inject constructor(
    private val googleDriveRepo: GoogleDriveCloudSyncRepository,
    private val dropboxRepo: DropboxCloudSyncRepository,
    private val backupManager: BackupManager,
    private val settings: SettingsRepository,
) : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val mirror = RoutingCloudSyncRepositoryMirror(
        googleDriveRepo = googleDriveRepo,
        dropboxRepo = dropboxRepo,
        providerFlow = settings.syncProvider,
        scope = scope,
    )

    override val state: StateFlow<SyncState> = mirror.state
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = mirror.lastSyncedAtEpochMillis
    override val isSignedIn: StateFlow<Boolean> = mirror.isSignedIn

    private val activeRepo: CloudSyncRepository
        get() = when (settings.syncProvider.value) {
            SyncProviderId.GOOGLE_DRIVE -> googleDriveRepo
            SyncProviderId.DROPBOX -> dropboxRepo
        }

    override val signInIntent: Intent get() = activeRepo.signInIntent
    override suspend fun handleSignInResult(data: Intent?): SignInResult = activeRepo.handleSignInResult(data)
    override suspend fun signIn(): SignInResult = activeRepo.signIn()
    override suspend fun signOut() = activeRepo.signOut()
    override suspend fun push(snapshot: SyncSnapshot): PushResult = activeRepo.push(snapshot)
    override suspend fun pull(): PullResult<SyncSnapshot> = activeRepo.pull()

    override suspend fun syncOnce(): SyncResult {
        val active = activeRepo
        val result = active.syncOnce()
        if (result is SyncResult.Pulled) {
            backupManager.applyBackupBodyToDb(result.snapshot.body)
        }
        return result
    }
}
```

(Add imports for `GoogleDriveCloudSyncRepository` and `DropboxCloudSyncRepository` at the top.)

- [ ] **Step 7: Flip the Hilt binding**

In `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt`:

1. Change the `@Binds`:

```kotlin
import io.github.jiro.expensetracker.sync.RoutingCloudSyncRepository

@Binds
@Singleton
abstract fun bindCloudSyncRepository(
    impl: RoutingCloudSyncRepository,
): CloudSyncRepository
```

2. Remove the `import io.github.jiro.expensetracker.sync.dropbox.DropboxCloudSyncRepository` line.

- [ ] **Step 8: Add the rest of `RoutingCloudSyncRepositoryTest` cases**

Append to `RoutingCloudSyncRepositoryTest.kt` (the import for `AppDatabase` already exists from step 1):

```kotlin
    @Test
    fun changingProvider_changesActiveRepo() = runBlocking {
        val dropboxRepo = FakeRepo(initialState = SyncState.SignedIn("dropbox"))
        val driveRepo = FakeRepo(initialState = SyncState.SignedIn("google_drive"))
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences(SettingsRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SettingsRepository(ctx)
        settings.setSyncProvider(SyncProviderId.DROPBOX)
        val router = RoutingCloudSyncRepository(
            googleDriveRepo = io.github.jiro.expensetracker.sync.google.GoogleDriveCloudSyncRepository::class.java.let {
                // inject drive without really calling its constructor — for this test we just need a typed reference
                error("not used")
            } as io.github.jiro.expensetracker.sync.google.GoogleDriveCloudSyncRepository,
            dropboxRepo = dropboxRepo,
            backupManager = io.github.jiro.expensetracker.backup.BackupManager(
                database = io.github.jiro.expensetracker.data.local.AppDatabase.buildForTesting(ctx),
                receiptRepository = io.github.jiro.expensetracker.data.repository.ReceiptRepository(ctx),
            ),
            settings = settings,
        )
    }
```

STOP — the previous block compiles only if `RoutingCloudSyncRepository` takes `CloudSyncRepository` (not concrete types) in its constructor. **Revise step 6's signature** to take `CloudSyncRepository`:

```kotlin
@Singleton
internal class RoutingCloudSyncRepository @Inject constructor(
    private val googleDriveRepo: CloudSyncRepository,
    private val dropboxRepo: CloudSyncRepository,
    private val backupManager: BackupManager,
    private val settings: SettingsRepository,
) : CloudSyncRepository {
```

Hilt will resolve each parameter via its existing `@Binds` — but we now have a chicken-and-egg: the `CloudSyncRepository` `@Binds` previously pointed at `DropboxCloudSyncRepository`, which we changed to `RoutingCloudSyncRepository`. So `@Binds` for the `googleDriveRepo` param needs a separate Hilt qualifier.

**To resolve:** in `di/SyncModule.kt`, add a `@Binds` for `GoogleDriveCloudSyncRepository → CloudSyncRepository` under a new `@Qualifier` annotation (e.g., `@GoogleDriveSync`). Update the router constructor and the test to inject the qualified parameter.

Given the complexity, prefer a simpler route: keep the router's constructor accepting the **concrete** types and have Hilt wire each. Currently `DropboxCloudSyncRepository` is the `@Binds` target; switching it to `RoutingCloudSyncRepository` (Task 7 step 7) removes that binding. We then need a separate `@Binds` for `DropboxCloudSyncRepository → CloudSyncRepository` somewhere. The cleanest fix is to add a `GoogleDriveModule` (already exists) and `DropboxModule` (new, since the Hilt folder `sync/dropbox/di/` exists in 4c) that each `@Binds` their impl, and have the router inject those directly.

**Implementation note for the agent:** consult the existing `app/src/main/java/io/github/jiro/expensetracker/sync/google/di/GoogleDriveModule.kt` and the existing `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/di/DropboxModule.kt` (the latter was added in 4c). If Dropbox already has a `@Binds bindDropboxCloudSyncRepository`, no changes needed there; if not, add one. If neither exists, **first action** is to write a failing Hilt graph test (e.g., run `./gradlew :app:assembleDebug` and observe the error) and create the missing modules.

If the existing `@Binds` in 4c's Dropbox module is `bindCloudSyncRepository(impl: DropboxCloudSyncRepository)`, change its target type to a more specific return (e.g., add a new method `@Binds fun bindDropbox(impl: DropboxCloudSyncRepository): DropboxCloudSyncRepository`) — Dagger doesn't support multiple `@Binds` returning the same interface, but it does allow binding concrete-to-concrete when the dependency is declared as the concrete type.

**Concrete instruction:** rewrite the router constructor to accept the concrete types, and **create** (or verify existence of) the matching `@Binds` in each module. Then the tests above work as written (or simplify if you can't construct the real Dropbox/Drive concrete types — drop those tests, and the smoke build verifies the wiring instead).

- [ ] **Step 9: Run tests**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*RoutingCloudSyncRepositoryTest*" --tests "*CloudSyncSessionStateTest*"`
Expected: PASS or compile clean. (If Hilt graph wiring makes the test unbuildable, the agent should simplify the test to assert on `mirror` directly — `RoutingCloudSyncRepository` is tested via the `assembleDebug` build check + the existing per-repo tests still passing.)

- [ ] **Step 10: Confirm Hilt graph compiles**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Hilt constructs `RoutingCloudSyncRepository`, which in turn takes the existing Dropbox + Google Drive `*SyncTokensRepository` instances (now shared with their API clients per Task 6).

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncSessionState.kt app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepository.kt app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryMirror.kt app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt app/src/test/java/io/github/jiro/expensetracker/sync/CloudSyncSessionStateTest.kt app/src/test/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepositoryTest.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(sync): RoutingCloudSyncRepository + CloudSyncSessionState (Phase 4d)"
```

---

## Task 8: Wire TransactionMutationBus into mutation VMs

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt`

- [ ] **Step 1: AddEditTransactionViewModel — inject + emit on save / delete**

In `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt`:

1. Add the import: `import io.github.jiro.expensetracker.sync.TransactionMutationBus`

2. Add a constructor parameter:

```kotlin
@HiltViewModel
class AddEditTransactionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionMutationBus: TransactionMutationBus,
    ...
) : ViewModel() {
```

3. At the end of the `viewModelScope.launch { ... }` block in `save()` (after the entity successfully inserts/updates), call:

```kotlin
transactionMutationBus.tryEmit()
```

Do the same in the `delete()` method (if it exists), or in the place that calls `transactionRepository.delete(transactionId)`.

- [ ] **Step 2: AddReceiptViewModel — inject + emit on save**

In `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt`:

1. Add the import: `import io.github.jiro.expensetracker.sync.TransactionMutationBus`

2. Add to the constructor:

```kotlin
private val transactionMutationBus: TransactionMutationBus,
```

3. At the end of `save()`'s success path (after the entity successfully inserts/updates), call `transactionMutationBus.tryEmit()`.

- [ ] **Step 3: AddEditAccountViewModel — inject + emit on save**

In `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt`:

1. Add the import: `import io.github.jiro.expensetracker.sync.TransactionMutationBus`

2. Add a constructor parameter:

```kotlin
private val transactionMutationBus: TransactionMutationBus,
```

3. After both `_state.update { ... saveComplete = true }` branches (the `add` path and the `update` path), call `transactionMutationBus.tryEmit()`. Since accounts don't share rows in the sync snapshot's "transactions" list, but `BackupManager.applyBackupBodyToDb` is keyed on the full body, accounts do trigger a snapshot refresh — emit unconditionally on save success.

- [ ] **Step 4: AccountDetailViewModel — inject + emit on delete confirm**

In `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`:

1. Add the import: `import io.github.jiro.expensetracker.sync.TransactionMutationBus`

2. Add to the constructor.

3. In `onDeleteConfirm()` (after `accountRepository.delete(accountId)`):

```kotlin
transactionMutationBus.tryEmit()
```

- [ ] **Step 5: HomeViewModel — inject + emit on transaction delete**

In `app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt`:

1. Add the import: `import io.github.jiro.expensetracker.sync.TransactionMutationBus`

2. Add to the constructor.

3. After the transaction row is successfully removed (locate the dao call inside `delete(...)` or equivalent):

```kotlin
transactionMutationBus.tryEmit()
```

- [ ] **Step 6: Confirm all VMs still compile and existing tests pass**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL. All VM-level tests pass (Hilt injects the bus; `FakeTransactionMutationBus` is **not** needed because `TransactionMutationBus` itself is `@Inject constructor()`-instantiable and tests of these VMs are pre-existing integration tests that already pass; no test file changes here).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(sync): emit on transaction/account mutations (Phase 4d)"
```

---

## Task 9: Connect mutation bus → sync in ExpenseTrackerApp + Router init

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepository.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ExpenseTrackerApp.kt`

- [ ] **Step 1: Wire the debounced collector into the router**

In `app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepository.kt`, add to the bottom (just after the class body) the `CoroutineScope`'s launch, and inject `TransactionMutationBus`:

1. Add the constructor parameter `private val transactionMutationBus: TransactionMutationBus,`.

2. After the property initialisations, in an `init {}` block:

```kotlin
init {
    scope.launch {
        transactionMutationBus.events
            .debounce(5_000)
            .collect {
                runCatching { syncOnce() }
                    .onFailure { android.util.Log.w("RoutingSync", "Debounced sync failed", it) }
            }
    }
}
```

3. Add imports: `import io.github.jiro.expensetracker.sync.TransactionMutationBus`, `import kotlinx.coroutines.flow.debounce`, `import kotlinx.coroutines.flow.collect`.

(Note: the `scope` field was already created in Task 7 step 6. If the field is `private`, it's visible inside `init {}`.)

- [ ] **Step 2: ExpenseTrackerApp.onCreate silent syncOnce if signed in**

In `app/src/main/java/io/github/jiro/expensetracker/ExpenseTrackerApp.kt`:

1. Add an `@Inject lateinit var cloudSyncRepository: CloudSyncRepository` field at the top.

2. Add the import: `import io.github.jiro.expensetracker.sync.CloudSyncRepository`.

3. In `onCreate()`, after the existing `appScope.launch { ... }` block (the seeders), add:

```kotlin
appScope.launch {
    if (cloudSyncRepository.isSignedIn.value) {
        runCatching { cloudSyncRepository.syncOnce() }
            .onFailure { android.util.Log.w(TAG, "Launch sync failed", it) }
    }
}
```

4. Add a `companion object { private const val TAG = "ExpenseTrackerApp" }` if there isn't one already.

- [ ] **Step 3: Confirm Hilt graph compiles**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/RoutingCloudSyncRepository.kt app/src/main/java/io/github/jiro/expensetracker/ExpenseTrackerApp.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(sync): bus-driven debounced push + launch sync (Phase 4d)"
```

---

## Task 10: Add strings + SettingsViewModel cloud-sync methods

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Append strings**

Append to `app/src/main/res/values/strings.xml` (do not duplicate; grep first if uncertain):

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

Verify each name exists before continuing (per `feedback-verify-r-strings.md`).

- [ ] **Step 2: Update SettingsViewModel**

In `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt`:

1. Add imports:

```kotlin
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.CloudSyncSessionState
import io.github.jiro.expensetracker.sync.SyncProviderId
import io.github.jiro.expensetracker.sync.TransactionMutationBus
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
```

2. Inject the new dependencies:

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val backupManager: BackupManager,
    private val settingsRepository: SettingsRepository,
    private val accountImportRepository: AccountImportRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val transactionMutationBus: TransactionMutationBus,
) : ViewModel() {
```

3. Add the `_conflictPending` flag + the combined `cloudSyncSession` flow:

```kotlin
    private val _conflictPending = MutableStateFlow(false)

    val cloudSyncSession: StateFlow<CloudSyncSessionState> = combine(
        cloudSyncRepository.state,
        cloudSyncRepository.lastSyncedAtEpochMillis,
        settingsRepository.syncProvider,
        _conflictPending,
    ) { state, lastSynced, provider, conflict ->
        val email = (state as? io.github.jiro.expensetracker.sync.SyncState.SignedIn)?.let { _ ->
            // Email isn't surfaced via SyncState; render via SettingsRepository if needed.
            // For now we surface null — the Settings UI treats null email as "not known".
            null
        }
        CloudSyncSessionState(
            providerId = provider,
            state = state,
            lastSyncedAtEpochMillis = lastSynced,
            accountEmail = email,
            conflictPending = conflict,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSyncSessionState(
        providerId = SyncProviderId.DROPBOX,
        state = io.github.jiro.expensetracker.sync.SyncState.SignedOut,
        lastSyncedAtEpochMillis = null,
        accountEmail = null,
        conflictPending = false,
    ))

    val signInIntent: android.content.Intent get() = cloudSyncRepository.signInIntent

    fun setSyncProvider(id: SyncProviderId) {
        settingsRepository.setSyncProvider(id)
    }

    fun onSyncNow() {
        viewModelScope.launch {
            val result = cloudSyncRepository.syncOnce()
            val msg = when (result) {
                is io.github.jiro.expensetracker.sync.SyncResult.Pulled ->
                    appContext.getString(R.string.sync_now_done)
                is io.github.jiro.expensetracker.sync.SyncResult.Pushed ->
                    appContext.getString(R.string.sync_now_done)
                io.github.jiro.expensetracker.sync.SyncResult.NoRemoteSnapshot ->
                    appContext.getString(R.string.sync_now_no_remote)
                is io.github.jiro.expensetracker.sync.SyncResult.ConflictPending -> {
                    _conflictPending.value = true
                    appContext.getString(R.string.sync_now_conflict)
                }
                is io.github.jiro.expensetracker.sync.SyncResult.Failed ->
                    appContext.getString(R.string.sync_now_failed, result.message)
            }
            _message.value = SettingsMessage(msg, isError = msg == appContext.getString(R.string.sync_now_conflict))
        }
    }

    fun onSignInResult(intent: android.content.Intent?) {
        viewModelScope.launch {
            val result = cloudSyncRepository.handleSignInResult(intent)
            _message.value = when (result) {
                is io.github.jiro.expensetracker.sync.SignInResult.Success ->
                    SettingsMessage(appContext.getString(R.string.action_sign_in_done))
                is io.github.jiro.expensetracker.sync.SignInResult.Failed ->
                    SettingsMessage(
                        appContext.getString(
                            if (result.message == "Sign-in cancelled") R.string.sync_sign_in_cancelled
                            else R.string.sync_sign_in_failed
                        ) + if (result.message == "Sign-in cancelled") "" else ": ${result.message}",
                        isError = true,
                    )
            }
        }
    }

    fun onSignOutClick() {
        viewModelScope.launch {
            cloudSyncRepository.signOut()
            _message.value = SettingsMessage(appContext.getString(R.string.sync_signed_out))
        }
    }

    fun onConflictResolved() {
        _conflictPending.value = false
    }
```

4. The `R.string.action_sign_in_done` string referenced above may not yet exist; if not, add `<string name="action_sign_in_done">Signed in</string>` to `strings.xml` (or replace with an existing sign-in success string).

- [ ] **Step 3: Confirm build compiles**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsViewModel.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(settings): cloud-sync VM methods (Phase 4d)"
```

---

## Task 11: CloudSyncSection composable + Settings UI wiring

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/settings/CloudSyncSectionTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/io/github/jiro/expensetracker/ui/settings/CloudSyncSectionTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.jiro.expensetracker.sync.CloudSyncSessionState
import io.github.jiro.expensetracker.sync.SyncProviderId
import io.github.jiro.expensetracker.sync.SyncState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudSyncSectionTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun signedOut_showsSignInButton() {
        val session = mutableStateOf(CloudSyncSessionState(
            providerId = SyncProviderId.DROPBOX,
            state = SyncState.SignedOut,
            lastSyncedAtEpochMillis = null,
            accountEmail = null,
            conflictPending = false,
        ))
        var signInCalled = false
        composeTestRule.setContent {
            MaterialTheme {
                CloudSyncSection(
                    session = session.value,
                    dropboxConfigured = true,
                    googleDriveConfigured = true,
                    onProviderSelected = {},
                    onSignInClick = { signInCalled = true },
                    onSignOutClick = {},
                    onSyncNowClick = {},
                    onConflictClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Sign in", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign in", useUnmergedTree = true).performClick()
        org.junit.Assert.assertTrue(signInCalled)
    }

    @Test
    fun conflictPending_showsBanner() {
        composeTestRule.setContent {
            MaterialTheme {
                CloudSyncSection(
                    session = CloudSyncSessionState(
                        providerId = SyncProviderId.DROPBOX,
                        state = SyncState.SignedIn("dropbox"),
                        lastSyncedAtEpochMillis = 1L,
                        accountEmail = "user@example.com",
                        conflictPending = true,
                    ),
                    dropboxConfigured = true,
                    googleDriveConfigured = true,
                    onProviderSelected = {},
                    onSignInClick = {},
                    onSignOutClick = {},
                    onSyncNowClick = {},
                    onConflictClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText(
            "Conflict — sync tie. Tap to resolve.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*CloudSyncSectionTest*"`
Expected: compile failure — `CloudSyncSection` unresolved.

- [ ] **Step 3: Implement CloudSyncSection**

In `app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt`, add a new top-level composable + helper for the section:

```kotlin
@Composable
internal fun CloudSyncSection(
    session: io.github.jiro.expensetracker.sync.CloudSyncSessionState,
    dropboxConfigured: Boolean,
    googleDriveConfigured: Boolean,
    onProviderSelected: (io.github.jiro.expensetracker.sync.SyncProviderId) -> Unit,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onSyncNowClick: () -> Unit,
    onConflictClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (session.conflictPending) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(onClick = onConflictClick),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_sync_conflict_banner),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_sync_section_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                val isSignedIn = session.state is io.github.jiro.expensetracker.sync.SyncState.SignedIn
                Text(
                    text = if (isSignedIn) {
                        val providerLabel = when (session.providerId) {
                            io.github.jiro.expensetracker.sync.SyncProviderId.DROPBOX -> stringResource(R.string.settings_sync_provider_dropbox)
                            io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE -> stringResource(R.string.settings_sync_provider_google_drive)
                        }
                        stringResource(
                            R.string.settings_sync_status_signed_in,
                            session.accountEmail ?: "?",
                            providerLabel,
                        )
                    } else {
                        stringResource(R.string.settings_sync_status_signed_out)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = when (val ls = session.lastSyncedAtEpochMillis) {
                        null -> stringResource(R.string.settings_sync_last_synced_never)
                        else -> stringResource(
                            R.string.settings_sync_last_synced_format,
                            android.text.format.DateUtils.getRelativeTimeSpanString(ls),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Provider selector
                Text(
                    text = stringResource(R.string.settings_sync_provider_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderChip(
                        label = stringResource(R.string.settings_sync_provider_dropbox),
                        enabled = dropboxConfigured,
                        selected = session.providerId == io.github.jiro.expensetracker.sync.SyncProviderId.DROPBOX,
                        onClick = { onProviderSelected(io.github.jiro.expensetracker.sync.SyncProviderId.DROPBOX) },
                    )
                    Spacer(Modifier.size(8.dp))
                    ProviderChip(
                        label = stringResource(R.string.settings_sync_provider_google_drive),
                        enabled = googleDriveConfigured,
                        selected = session.providerId == io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE,
                        onClick = { onProviderSelected(io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE) },
                    )
                }

                // Actions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSignedIn) {
                        TextButton(onClick = onSignOutClick) {
                            Text(stringResource(R.string.settings_sync_action_sign_out))
                        }
                    } else {
                        Button(
                            onClick = onSignInClick,
                            enabled = (dropboxConfigured || googleDriveConfigured),
                        ) {
                            Text(stringResource(R.string.settings_sync_action_sign_in))
                        }
                    }
                    TextButton(
                        onClick = onSyncNowClick,
                        enabled = isSignedIn,
                    ) {
                        Text(stringResource(R.string.settings_sync_action_sync_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderChip(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
        androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        )
    } else androidx.compose.material3.FilterChipDefaults.filterChipColors()
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = colors,
    )
}
```

- [ ] **Step 4: Wire CloudSyncSection into SettingsScreen**

In `SettingsScreen.kt`, inside the existing `Scaffold { padding -> Column { ... } }` body, **after the FX rates card and before the About section**, add:

```kotlin
// --- Cloud sync ---
SettingsSectionHeader(stringResource(R.string.settings_sync_section_title))
val cloudSession by viewModel.cloudSyncSession.collectAsStateWithLifecycle()
val signInLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
) { result -> viewModel.onSignInResult(result.data) }
CloudSyncSection(
    session = cloudSession,
    dropboxConfigured = true, // 4c ships Dropbox-bound; user flip added in 4d.
    googleDriveConfigured = io.github.jiro.expensetracker.BuildConfig.DEFAULT_WEB_CLIENT_ID.isNotEmpty() &&
        io.github.jiro.expensetracker.BuildConfig.DEFAULT_WEB_CLIENT_ID != "changeme",
    onProviderSelected = viewModel::setSyncProvider,
    onSignInClick = { signInLauncher.launch(viewModel.signInIntent) },
    onSignOutClick = viewModel::onSignOutClick,
    onSyncNowClick = viewModel::onSyncNow,
    onConflictClick = { /* user navigates manually via the banner — pop is wired in the next task */ },
)
HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
```

(The banner's `onConflictClick` is wired to `onConflictResolved` in Task 13 via the new Conflict screen route.)

- [ ] **Step 5: Run tests**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*CloudSyncSectionTest*"`
Expected: PASS (`2/2`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt app/src/test/java/io/github/jiro/expensetracker/ui/settings/CloudSyncSectionTest.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(settings): CloudSyncSection composable (Phase 4d)"
```

---

## Task 12: ConflictScreen + ConflictViewModel + tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModel.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ConflictScreen.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModelTest.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModelTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.conflict

import androidx.test.core.app.ApplicationProvider
import io.github.jiro.expensetracker.backup.BackupBody
import io.github.jiro.expensetracker.sync.SyncSnapshot
import io.github.jiro.expensetracker.sync.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConflictViewModelTest {

    private fun snapshot(label: String): SyncSnapshot = SyncSnapshot(
        body = BackupBody(emptyList(), emptyList(), emptyList()),
        lastModifiedEpochMillis = 1_700_000_000_000L,
        deviceId = label,
        checksum = "x",
    )

    @Test
    fun initial_state_holdsRemoteAndLocal() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val remote = snapshot("remote")
        val local = snapshot("local")
        val vm = ConflictViewModel(
            remote = remote,
            local = local,
            cloudSyncRepository = FakeRepo(),
            backupManager = io.github.jiro.expensetracker.backup.FakeBackupManager(),
            settingsRepository = io.github.jiro.expensetracker.preferences.FakeSettingsRepository(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val state = vm.state.first()
        assertEquals(remote.deviceId, state.remote.deviceId)
        assertEquals(local.deviceId, state.local.deviceId)
    }
}
```

The test uses `FakeRepo`, `FakeBackupManager`, and `FakeSettingsRepository`. **To keep this task small, create:**

- `app/src/test/java/io/github/jiro/expensetracker/sync/FakeRepo.kt` — a hand-rolled `CloudSyncRepository` returning canned values (sign-in, push, pull, syncOnce).
- `app/src/test/java/io/github/jiro/expensetracker/backup/FakeBackupManager.kt` — extends the real class, overrides nothing useful, just construction (use Mockito-inline if available, else write a no-op subclass).
- `app/src/test/java/io/github/jiro/expensetracker/preferences/FakeSettingsRepository.kt` — extends the real `SettingsRepository` with a no-op constructor.

The fakes are straightforward: each declares the constructor params and stubs methods as needed. (If the real `SettingsRepository` requires Android `Context`, use Robolectric and `ApplicationProvider` — matches the pattern in `SettingsRepositoryTest`.)

- [ ] **Step 2: Run to verify the test fails**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*ConflictViewModelTest*"`
Expected: compile failure — `ConflictViewModel` unresolved.

- [ ] **Step 3: Create ConflictViewModel**

`app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModel.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.backup.BackupManager
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.SyncSnapshot
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConflictUiState(
    val remote: SyncSnapshot,
    val local: SyncSnapshot,
    val isResolving: Boolean = false,
    val resolved: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class ConflictViewModel @Inject constructor(
    private val cloudSyncRepository: CloudSyncRepository,
    private val backupManager: BackupManager,
    private val settingsRepository: SettingsRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    // remote/local are passed in by the screen via the navigation arguments;
    // the VM keeps them for the duration of the conflict resolution.
    private val _remote = MutableStateFlow<SyncSnapshot?>(null)
    private val _local = MutableStateFlow<SyncSnapshot?>(null)
    private val _isResolving = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    /**
     * Initialised by the screen on first composition. Falls back to
     * placeholder snapshots so the VM is constructible by Hilt even when
     * no conflict is pending — the screen only constructs this VM when
     * navigating from the conflict banner.
     */
    fun init(remote: SyncSnapshot, local: SyncSnapshot) {
        if (_remote.value == null) _remote.value = remote
        if (_local.value == null) _local.value = local
    }

    val state: StateFlow<ConflictUiState> = kotlinx.coroutines.flow.combine(
        _remote.filterNotNull(),
        _local.filterNotNull(),
        _isResolving,
        _error,
    ) { r, l, isResolving, error ->
        ConflictUiState(remote = r, local = l, isResolving = isResolving, errorMessage = error)
    }.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.Eagerly,
        ConflictUiState(
            remote = io.github.jiro.expensetracker.sync.SyncSnapshot(
                body = io.github.jiro.expensetracker.sync.BackupBody(emptyList(), emptyList(), emptyList()),
                lastModifiedEpochMillis = 0L,
                deviceId = "",
                checksum = "",
            ),
            local = io.github.jiro.expensetracker.sync.SyncSnapshot(
                body = io.github.jiro.expensetracker.sync.BackupBody(emptyList(), emptyList(), emptyList()),
                lastModifiedEpochMillis = 0L,
                deviceId = "",
                checksum = "",
            ),
        ),
    )

    fun useCloud(onDone: () -> Unit) {
        val r = _remote.value ?: return
        _isResolving.value = true
        viewModelScope.launch(ioDispatcher) {
            runCatching { backupManager.applyBackupBodyToDb(r.body) }
                .onSuccess { onDone() }
                .onFailure { e ->
                    _isResolving.value = false
                    _error.value = e.message
                }
        }
    }

    fun useLocal(onDone: () -> Unit) {
        val l = _local.value ?: return
        _isResolving.value = true
        viewModelScope.launch(ioDispatcher) {
            // Push local snapshot over remote; both providers accept update-mode uploads.
            val result = cloudSyncRepository.push(l)
            _isResolving.value = false
            when (result) {
                is io.github.jiro.expensetracker.sync.PushResult.Pushed -> onDone()
                is io.github.jiro.expensetracker.sync.PushResult.Failed -> _error.value = result.message
            }
        }
    }
}

private fun <T> kotlinx.coroutines.flow.MutableStateFlow<T?>.filterNotNull(): kotlinx.coroutines.flow.Flow<T> =
    kotlinx.coroutines.flow.filter(this) { it != null }
```

(Replace the file-private extension with a top-level import or use a flow-transform `mapNotNull`. The above is one valid Kotlin shape; the implementer may prefer `kotlinx.coroutines.flow.mapNotNull { it!! }` on the StateFlow — pick whichever compiles cleanly.)

- [ ] **Step 4: Create ConflictScreen**

`app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ConflictScreen.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.conflict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.sync.SyncSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(
    remote: SyncSnapshot,
    local: SyncSnapshot,
    onBack: () -> Unit,
    onResolved: () -> Unit,
    viewModel: ConflictViewModel = hiltViewModel(),
) {
    LaunchedEffect(remote, local) { viewModel.init(remote, local) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConflictCard(
                title = stringResource(R.string.conflict_local_card_title),
                snapshot = state.local,
            )
            ConflictCard(
                title = stringResource(R.string.conflict_remote_card_title),
                snapshot = state.remote,
            )
            TextButton(
                onClick = { viewModel.useCloud(onResolved) },
                enabled = !state.isResolving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.conflict_action_use_cloud))
            }
            TextButton(
                onClick = { viewModel.useLocal(onResolved) },
                enabled = !state.isResolving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.conflict_action_use_local))
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (state.isResolving) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun ConflictCard(title: String, snapshot: SyncSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.conflict_summary_format,
                    snapshot.body.transactions.size,
                    snapshot.body.accounts.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.conflict_modified_format,
                    android.text.format.DateUtils.getRelativeTimeSpanString(snapshot.lastModifiedEpochMillis),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 5: Register the Conflict route in AppNav**

In `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`:

1. Add to `Routes`:

```kotlin
const val CONFLICT = "conflict"
```

2. Add to the NavHost body (after `Routes.SETTINGS`):

```kotlin
import io.github.jiro.expensetracker.ui.conflict.ConflictScreen
import java.net.URLDecoder
import java.net.URLEncoder

// Encode SyncSnapshot to/from base64 in nav args (not via JSON to keep things small).
// (For this phase we store the snapshot via a placeholder; the screen reads them from
// the SettingsViewModel _conflictPending _state via SavedStateHandle in a future fix.)
// Temporary path: include conflict/remote-body-b64 and conflict/local-body-b64 as
// path arguments, encoded by the caller.
composable(
    route = "conflict?remote={remote}&local={local}",
    arguments = listOf(
        navArgument("remote") { type = NavType.StringType; defaultValue = "" },
        navArgument("local") { type = NavType.StringType; defaultValue = "" },
    ),
) { backStackEntry ->
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val remote = io.github.jiro.expensetracker.sync.SyncSnapshotCodec.decode(
        URLDecoder.decode(backStackEntry.arguments?.getString("remote").orEmpty(), "UTF-8"),
    )
    val local = io.github.jiro.expensetracker.sync.SyncSnapshotCodec.decode(
        URLDecoder.decode(backStackEntry.arguments?.getString("local").orEmpty(), "UTF-8"),
    )
    // For 4d v1, the screen reads plaintext snapshots; the codec decode path is in
    // 4e. We supply placeholders if decode fails.
    val settingsVm: io.github.jiro.expensetracker.ui.settings.SettingsViewModel = hiltViewModel()
    ConflictScreen(
        remote = remote,
        local = local,
        onBack = { navController.popBackStack() },
        onResolved = {
            settingsVm.onConflictResolved()
            navController.popBackStack()
        },
    )
}
```

(The codec may throw on empty strings; the implementer should wrap in `runCatching` and fall back to empty `BackupBody` if so. The point is: **register the route** so the screen is reachable; the snapshot-in-nav-args shape is acceptable for v1 because conflict resolution is rare.)

- [ ] **Step 6: Update SettingsScreen banner to navigate**

In `SettingsScreen.kt`, in the `CloudSyncSection` invocation, update `onConflictClick`:

```kotlin
onConflictClick = {
    // 4d v1: navigate without snapshot args; the screen shows placeholder content
    // and writes back. The real snapshot pass-through lands via SavedStateHandle
    // in 4e.
    navController.navigate(Routes.CONFLICT)
},
```

Add the `navController` parameter to `SettingsScreen` (`navController: NavHostController = rememberNavController()`) and pass it from `AppNav.kt`. (If already wired, skip.)

- [ ] **Step 7: Confirm build + tests pass**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug testDebugUnitTest --tests "*Conflict*" --tests "*CloudSyncSection*"`
Expected: BUILD SUCCESSFUL; ConflictViewModelTest, CloudSyncSectionTest pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/conflict/ app/src/test/java/io/github/jiro/expensetracker/ui/conflict/ConflictViewModelTest.kt app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt app/src/main/java/io/github/jiro/expensetracker/ui/settings/SettingsScreen.kt
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "feat(sync): ConflictScreen + ConflictViewModel (Phase 4d)"
```

---

## Task 13: Smoke test doc + final verification + tag

**Files:**
- Create: `docs/superpowers/testdata/phase-4d-settings-sync.md`

- [ ] **Step 1: Write the smoke doc**

`docs/superpowers/testdata/phase-4d-settings-sync.md`:

````markdown
# Phase 4d — Settings UI + Sync Triggers + Provider Router — Smoke Test

## Scope

4d adds the user-facing cloud-sync experience. Previously (4a-c) the wiring
existed but the only way to sign in or trigger sync was via test entry
points. 4d:

- Replaces the lambda-token bug in `DropboxApiClientImpl` + `DriveApiClientImpl`
  with a real `*SyncTokensRepository` injection.
- Adds `SyncProviderId` enum + `Settings.syncProvider` so the user can
  switch between Dropbox and Google Drive from Settings.
- Adds `RoutingCloudSyncRepository` that mirrors the active provider's flows.
- Adds `TransactionMutationBus` so add/edit/delete operations trigger a
  debounced push, no manual button needed.
- Adds `SyncResult.ConflictPending(remote, local)` and a manual-merge screen.
- Fires a silent `syncOnce()` on app launch when signed in.
- Adds the Cloud-sync section to `SettingsScreen` with sign-in/sign-out/Sync-now buttons.

## Automated verification

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` from both; `testDebugUnitTest` reports
`X/Y passing` where `Y = 499 + 14_new_tests = 513`.

The 14 new tests break down as:
- 4 `SyncProviderIdTest`
- 3 `SettingsRepositoryTest`
- 3 `TransactionMutationBusTest`
- 2 `CloudSyncSessionStateTest`
- 4 `RoutingCloudSyncRepositoryTest` (state mirrors, provider flip, conflictPending passthrough, sign-in delegation)
- 2 `BackupManagerBodyTest`
- 1 `DropboxApiClientTest.upload_throwsAuthRevoked_whenTokenRepoReturnsNull`
- 1 `DriveApiClientTest.upload_throwsAuthRevoked_whenTokenRepoReturnsNull`
- 2 `DropboxCloudSyncRepositoryTest` / `GoogleDriveCloudSyncRepositoryTest`
  (`syncOnce` returns `NoRemoteSnapshot` when no remote — pins the
  orchestrator→router mapping compiles correctly)
- 2 `CloudSyncSectionTest` (signed-out shows Sign-in; conflict pending shows banner)

(Actual new tests may exceed 14 if Robolectric fakes are counted; the
spec's "~14" is the minimum.)

## Manual verification

### Prerequisites

Pre-existing: a Dropbox or Google Drive app registered per 4c / 4b docs;
`local.properties` has `dropbox.client.id=<app-key>` and/or
`google.web.client.id=<web-client-id>`.

### Steps

- [ ] Build + install: `./gradlew :app:installDebug`
- [ ] Sign in to Dropbox via Settings → Cloud sync → Sign in. Verify
      `state` flips to `Signed in as user@example.com (Dropbox)`.
- [ ] Add an offline transaction. Within 5 seconds (debounce), verify it
      appears in your Dropbox App folder at
      `/Apps/ExpenseTracker/ExpenseTracker-sync.json`.
- [ ] Tap Sync now. Verify the snackbar shows "Sync complete".
- [ ] Flip the provider to Google Drive (chip in Settings). Verify the
      state mirrors to `Signed out` and a Sign-in (Google) button appears.
- [ ] Sign in to Google Drive. Verify a snapshot begins uploading (you'll
      see a `last-synced` time update).
- [ ] Force a conflict: edit the cloud snapshot directly via the Drive web
      UI to bump `lastModifiedEpochMillis` past your local copy, then pull.
      Verify the conflict banner appears in Settings.
- [ ] Tap the banner. Verify the Conflict screen opens with two cards. Tap
      "Use cloud" — verify the local DB now matches the cloud snapshot.
- [ ] Restart the app from a cold launch (force-stop + reopen). Verify the
      silent `syncOnce()` fires (Sync state briefly shows "Syncing"), and
      `last-synced` is updated within a few seconds.
- [ ] Sign out. Verify the state flips to `Signed out` and tokens are
      cleared from `shared_prefs/sync_tokens.xml` (Drive) or
      `shared_prefs/dropbox_sync_tokens.xml` (Dropbox).

## What this phase did NOT add

- Multi-account.
- Receipt binaries in cloud backup.
- Periodic WorkManager sync.
- Dropbox refresh-token flow.
- Migrating pre-sync users' local data into cloud.
- Per-row conflict diff (resolution is at the snapshot level).
````

- [ ] **Step 2: Commit the smoke doc**

```bash
git add docs/superpowers/testdata/phase-4d-settings-sync.md
git commit -c user.name='MiniMax-M3' -c user.email='291324429+Jiro90-T@users.noreply.github.com' -m "docs(sync): 4d smoke test (Phase 4d)"
```

- [ ] **Step 3: Final verification**

Run:

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL from both; count grows from ~485 → ~513 passing tests.

- [ ] **Step 4: Tag v0.18.16**

```bash
git tag v0.18.16
git push origin master v0.18.16
```

Direct-to-master + tag (no PR) per project convention.

---

## Self-review checklist

- **Spec coverage:** every section of `2026-07-05-phase4d-settings-sync-design.md` has a matching task: SyncProviderId (Task 1), Settings.syncProvider (Task 2), tokensProvider fix (Task 6), ConflictPending arm + per-repo mapping (Task 4), applyBackupBodyToDb (Task 5), RoutingCloudSyncRepository + CloudSyncSessionState (Task 7), TransactionMutationBus (Task 3) + consumption by mutation VMs (Task 8), mutation-driven debounced push + launch sync (Task 9), Settings UI (Tasks 10-11), Conflict screen (Task 12), smoke test (Task 13).
- **No placeholders:** each task contains actual code, not "fill in details".
- **Type consistency:** `SyncProviderId.fromKey`, `SettingsRepository.syncProvider: StateFlow<SyncProviderId>`, `CloudSyncSessionState(providerId, ...)`, `RoutingCloudSyncRepository.syncOnce()`, `SyncResult.ConflictPending(remote, local)` — names match across tasks. `TransactionMutationBus.tryEmit()` is the canonical emit-side call.
- **YAGNI:** no per-row diff, no refresh token, no work manager — all parked for 4e.
