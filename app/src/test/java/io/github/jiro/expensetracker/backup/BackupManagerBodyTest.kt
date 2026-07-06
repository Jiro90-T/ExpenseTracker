package io.github.jiro.expensetracker.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.sync.BackupBody
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
        manager.applyBackupBodyToDb(BackupBody(
            accounts = listOf(
                AccountRow(
                    id = 1, name = "Checking", type = "BANK", icon = "🏦",
                    color = 0xFF1976D2.toInt(), currencyCode = "USD",
                    openingBalanceMinor = 0L, createdAtEpochMillis = 1L,
                    archived = false, archivedAtEpochMillis = null, sortOrder = 0,
                ),
            ),
            categories = listOf(
                CategoryRow(id = 100, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = false),
            ),
            transactions = listOf(
                TransactionRow(
                    id = 50, title = "Lunch", amountMinor = 1234L, currencyCode = "USD",
                    type = "EXPENSE", categoryId = 100L,
                    accountId = 1L, transferAccountId = null,
                    occurredAtEpochMillis = 1L, note = null, createdAtEpochMillis = 1L,
                    recurringGroupId = null, recurrenceKind = null, recurrenceInterval = 1,
                    recurrenceEndAt = null, recurrenceMaxOccurrences = null, recurrenceNextAt = null,
                    receiptPath = null,
                ),
            ),
        ))
        assertEquals(1, database.accountDao().countActive())
        assertEquals(50L, database.transactionDao().findById(50L)?.id)
    }
}
