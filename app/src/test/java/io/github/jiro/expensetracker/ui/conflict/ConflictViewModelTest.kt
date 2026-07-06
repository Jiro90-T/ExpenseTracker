package io.github.jiro.expensetracker.ui.conflict

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jiro.expensetracker.backup.BackupManager
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.sync.BackupBody
import io.github.jiro.expensetracker.sync.FakeCloudSyncRepository
import io.github.jiro.expensetracker.sync.SyncSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ConflictViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var fakeRepo: FakeCloudSyncRepository
    private lateinit var settings: SettingsRepository
    private lateinit var backup: BackupManager
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        fakeRepo = FakeCloudSyncRepository()
        settings = SettingsRepository(ctx)
        database = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backup = BackupManager(database, ReceiptRepository(ctx))
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun init_storesRemoteAndLocalSnapshots() = runTest(testDispatcher) {
        val remote = snapshot("remote-device", listOf("R1", "R2"))
        val local = snapshot("local-device", listOf("L1"))
        val viewModel = ConflictViewModel(fakeRepo, backup, settings)

        viewModel.init(remote, local)
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertEquals(remote, state.remote)
        assertEquals(local, state.local)
        assertFalse(state.resolving)
        assertNull(state.error)
    }

    @Test
    fun state_initialValues_areSentinels() = runTest(testDispatcher) {
        val viewModel = ConflictViewModel(fakeRepo, backup, settings)
        val state = viewModel.state.first()
        assertNull(state.remote)
        assertNull(state.local)
        assertFalse(state.resolving)
        assertNull(state.error)
    }

    @Test
    fun useCloud_isNoOpWhenRemoteNotInitialized() = runTest(testDispatcher) {
        val viewModel = ConflictViewModel(fakeRepo, backup, settings)
        advanceUntilIdle()

        var resolved = false
        viewModel.useCloud { resolved = true }
        advanceUntilIdle()

        // Without init() having been called, the VM has no remote snapshot
        // and useCloud is a no-op — the callback is not invoked and the
        // resolving flag stays at its initial false value.
        assertFalse(resolved)
        assertFalse(viewModel.state.value.resolving)
        assertNull(viewModel.state.value.error)
    }

    private fun snapshot(deviceId: String, txTitles: List<String>): SyncSnapshot {
        val txs = txTitles.mapIndexed { i, title ->
            io.github.jiro.expensetracker.backup.TransactionRow(
                id = (i + 1).toLong(),
                title = title,
                amountMinor = 0L,
                currencyCode = "USD",
                type = "EXPENSE",
                categoryId = 0L,
                occurredAtEpochMillis = 0L,
                note = null,
                createdAtEpochMillis = 0L,
                recurringGroupId = null,
                recurrenceKind = null,
                recurrenceInterval = 1,
                recurrenceEndAt = null,
                recurrenceMaxOccurrences = null,
                recurrenceNextAt = null,
                receiptPath = null,
                accountId = 1L,
                transferAccountId = null,
            )
        }
        return SyncSnapshot(
            body = BackupBody(accounts = emptyList(), categories = emptyList(), transactions = txs),
            lastModifiedEpochMillis = 0L,
            deviceId = deviceId,
            checksum = "",
        )
    }
}
