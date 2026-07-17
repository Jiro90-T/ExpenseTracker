package io.github.jiro.expensetracker.data.accountimport

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end repository tests against an in-memory Room DB.
 *
 * [TestRepo] bypasses the [android.content.ContentResolver] by overriding
 * the protected seams [AccountImportRepositoryImpl.readInput] and
 * [AccountImportRepositoryImpl.readDisplayName] — mirrors the strategy
 * used by [ReceiptRepository.saveFromUri] in JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class AccountImportRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var txnRepo: TransactionRepository
    private lateinit var repo: TestRepo

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepo = AccountRepository(db.accountDao(), db.investmentHoldingDao())
        txnRepo = TransactionRepository(db.transactionDao(), ReceiptRepository(context))
        repo = TestRepo(context, accountRepo, txnRepo)
    }

    @After
    fun tearDown() { db.close() }

    // ---------------- preview() ----------------

    @Test
    fun preview_willCreateForNewAccounts() = runTest {
        repo.feed(
            "name,type,currency,balance\n" +
                "Cash,CASH,USD,250.00\n" +
                "BPI,BANK,PHP,15000.00\n" +
                "AmEx,CREDIT_CARD,USD,-120.50\n"
        )

        val preview = repo.preview(Uri.parse("content://stub/test.csv"))

        assertEquals(3, preview.rows.size)
        assertEquals("test.csv", preview.fileName)
        assertTrue(preview.rows[0].status is ImportStatus.WillCreate)
        assertTrue(preview.rows[1].status is ImportStatus.WillCreate)
        assertTrue(preview.rows[2].status is ImportStatus.WillCreate)
    }

    @Test
    fun preview_willUpdateForExistingAccounts() = runTest {
        db.accountDao().insert(
            AccountEntity(
                name = "Cash",
                type = "CASH",
                icon = "💵",
                color = 0xFF43A047.toInt(),
                currencyCode = "USD",
                openingBalanceMinor = 100_00L,
                createdAtEpochMillis = 0L,
            )
        )
        db.accountDao().insert(
            AccountEntity(
                name = "BPI",
                type = "BANK",
                icon = "🏦",
                color = 0xFF1976D2.toInt(),
                currencyCode = "PHP",
                openingBalanceMinor = 0L,
                createdAtEpochMillis = 0L,
            )
        )

        repo.feed(
            "name,type,currency,balance\n" +
                "Cash,CASH,USD,500.00\n" +
                "BPI,BANK,PHP,10000.00\n"
        )
        val preview = repo.preview(Uri.parse("content://stub/test.csv"))

        assertEquals(2, preview.rows.size)
        assertTrue(preview.rows[0].status is ImportStatus.WillUpdate)
        assertTrue(preview.rows[1].status is ImportStatus.WillUpdate)
    }

    @Test
    fun preview_rejectedForCurrencyMismatch() = runTest {
        db.accountDao().insert(
            AccountEntity(
                name = "Cash",
                type = "CASH",
                icon = "💵",
                color = 0xFF43A047.toInt(),
                currencyCode = "USD",
                openingBalanceMinor = 0L,
                createdAtEpochMillis = 0L,
            )
        )

        repo.feed("name,type,currency,balance\nCash,CASH,PHP,250.00\n")
        val preview = repo.preview(Uri.parse("content://stub/test.csv"))

        assertEquals(1, preview.rows.size)
        val status = preview.rows[0].status
        assertTrue("expected Rejected, got $status", status is ImportStatus.Rejected)
        val reason = (status as ImportStatus.Rejected).reason
        assertTrue("reason should mention currency mismatch, got: $reason", reason.contains("currency mismatch"))
    }

    @Test
    fun preview_rejectedForAccountWithTxns() = runTest {
        val id = db.accountDao().insert(
            AccountEntity(
                name = "Cash",
                type = "CASH",
                icon = "💵",
                color = 0xFF43A047.toInt(),
                currencyCode = "USD",
                openingBalanceMinor = 0L,
                createdAtEpochMillis = 0L,
            )
        )
        db.transactionDao().insert(stubTxn(accountId = id))

        repo.feed("name,type,currency,balance\nCash,CASH,USD,250.00\n")
        val preview = repo.preview(Uri.parse("content://stub/test.csv"))

        assertEquals(1, preview.rows.size)
        val status = preview.rows[0].status
        assertTrue("expected Rejected, got $status", status is ImportStatus.Rejected)
        val reason = (status as ImportStatus.Rejected).reason
        assertTrue("reason should mention '1 transactions', got: $reason", reason.contains("1 transactions"))
    }

    // ---------------- apply() ----------------

    @Test
    fun apply_persistsCreatedAccountsInSingleTransaction() = runTest {
        val preview = ImportPreview(
            fileName = "test.csv",
            rows = listOf(
                resolved(line = 2, name = "Cash", type = "CASH", currency = "USD", balance = 250_00L,
                    status = ImportStatus.WillCreate),
                resolved(line = 3, name = "BPI", type = "BANK", currency = "PHP", balance = 15_000_00L,
                    status = ImportStatus.WillCreate),
            ),
        )

        val result = repo.apply(preview)
        assertEquals(ImportApplyResult(created = 2, updated = 0), result)

        val cash = findByName("Cash")
        val bpi = findByName("BPI")
        assertNotNull(cash)
        assertNotNull(bpi)
        assertEquals(250_00L, cash!!.openingBalanceMinor)
        assertEquals("USD", cash.currencyCode)
        assertEquals(15_000_00L, bpi!!.openingBalanceMinor)
        assertEquals("PHP", bpi.currencyCode)
        // Sort orders are unique and monotonically increasing.
        assertTrue("sort orders must be unique", cash.sortOrder != bpi.sortOrder)
    }

    @Test
    fun apply_updatesOpeningBalanceOnly() = runTest {
        val originalIcon = "💰"
        val originalColor = 0xFF112233.toInt()
        val id = db.accountDao().insert(
            AccountEntity(
                name = "Cash",
                type = "CASH",
                icon = originalIcon,
                color = originalColor,
                currencyCode = "USD",
                openingBalanceMinor = 100_00L,
                createdAtEpochMillis = 0L,
            )
        )

        val preview = ImportPreview(
            fileName = "test.csv",
            rows = listOf(
                ResolvedImportRow(
                    raw = RawImportRow(
                        lineNumber = 2,
                        name = "Cash",
                        type = "CASH",
                        currency = "USD",
                        balanceMinor = 999_00L,
                    ),
                    status = ImportStatus.WillUpdate,
                ),
            ),
        )

        val result = repo.apply(preview)
        assertEquals(ImportApplyResult(created = 0, updated = 1), result)

        val updated = db.accountDao().findById(id)
        assertNotNull(updated)
        assertEquals(999_00L, updated!!.openingBalanceMinor)
        assertEquals(originalIcon, updated.icon)
        assertEquals(originalColor, updated.color)
        assertEquals("CASH", updated.type)
        assertEquals("USD", updated.currencyCode)
    }

    @Test
    fun apply_respectsTypeDefaultsForIconAndColor() = runTest {
        val preview = ImportPreview(
            fileName = "test.csv",
            rows = listOf(
                resolved(line = 2, name = "A1", type = "CASH",         status = ImportStatus.WillCreate),
                resolved(line = 3, name = "A2", type = "BANK",         status = ImportStatus.WillCreate),
                resolved(line = 4, name = "A3", type = "CREDIT_CARD",  status = ImportStatus.WillCreate),
                resolved(line = 5, name = "A4", type = "EWALLET",      status = ImportStatus.WillCreate),
                resolved(line = 6, name = "A5", type = "OTHER",        status = ImportStatus.WillCreate),
                resolved(line = 7, name = "A6", type = "INVENTORY",    status = ImportStatus.WillCreate),
            ),
        )

        repo.apply(preview)

        assertEquals("💵", findByName("A1")!!.icon)
        assertEquals("🏦", findByName("A2")!!.icon)
        assertEquals("💳", findByName("A3")!!.icon)
        assertEquals("📱", findByName("A4")!!.icon)
        assertEquals("💰", findByName("A5")!!.icon)
        // INVENTORY is not a known type → falls back to defaults.
        assertEquals("💵", findByName("A6")!!.icon)
        assertEquals(0xFF1976D2.toInt(), findByName("A6")!!.color)

        assertEquals(0xFF43A047.toInt(), findByName("A1")!!.color)
        assertEquals(0xFF1976D2.toInt(), findByName("A2")!!.color)
        assertEquals(0xFFC62828.toInt(), findByName("A3")!!.color)
        assertEquals(0xFFF57C00.toInt(), findByName("A4")!!.color)
        assertEquals(0xFF455A64.toInt(), findByName("A5")!!.color)
    }

    @Test
    fun apply_rejectedRowsAreNoOp() = runTest {
        db.accountDao().insert(
            AccountEntity(
                name = "Existing",
                type = "CASH",
                icon = "💵",
                color = 0xFF43A047.toInt(),
                currencyCode = "USD",
                openingBalanceMinor = 0L,
                createdAtEpochMillis = 0L,
            )
        )

        val preview = ImportPreview(
            fileName = "test.csv",
            rows = listOf(
                resolved(line = 2, name = "NewOne", type = "CASH", currency = "USD", balance = 100L,
                    status = ImportStatus.WillCreate),
                ResolvedImportRow(
                    raw = RawImportRow(
                        lineNumber = 3,
                        name = "Existing",
                        type = "CASH",
                        currency = "USD",
                        balanceMinor = 200L,
                    ),
                    status = ImportStatus.WillUpdate,
                ),
                ResolvedImportRow(
                    raw = RawImportRow(
                        lineNumber = 4,
                        name = "RejectedOne",
                        type = "CASH",
                        currency = "USD",
                        balanceMinor = 300L,
                    ),
                    status = ImportStatus.Rejected("synthetic rejection"),
                ),
            ),
        )

        val result = repo.apply(preview)
        assertEquals(ImportApplyResult(created = 1, updated = 1), result)

        assertNotNull("NewOne should be persisted", findByName("NewOne"))
        val existing = findByName("Existing")
        assertNotNull(existing)
        assertEquals(200L, existing!!.openingBalanceMinor)
        assertNull("RejectedOne must NOT be persisted", findByName("RejectedOne"))
    }

    // ---------------- helpers ----------------

    private suspend fun findByName(name: String): AccountEntity? {
        val active = db.accountDao().listActiveOnce()
        return active.firstOrNull { it.name == name }
    }

    private fun resolved(
        line: Int,
        name: String,
        type: String,
        currency: String = "USD",
        balance: Long = 0L,
        status: ImportStatus,
    ) = ResolvedImportRow(
        raw = RawImportRow(line, name, type, currency, balance),
        status = status,
    )

    private fun stubTxn(accountId: Long): TransactionEntity = TransactionEntity(
        title = "stub",
        amountMinor = 100L,
        currencyCode = "USD",
        type = "ADJUSTMENT",
        categoryId = null,
        accountId = accountId,
        occurredAtEpochMillis = 0L,
        createdAtEpochMillis = 0L,
    )

    /**
     * Test-only subclass that bypasses [android.content.ContentResolver]
     * by serving configured bytes/filename regardless of URI.
     */
    private class TestRepo(
        context: Context,
        accountRepo: AccountRepository,
        txnRepo: TransactionRepository,
    ) : AccountImportRepositoryImpl(context, accountRepo, txnRepo) {
        private var nextBytes: ByteArray = ByteArray(0)
        private var nextName: String = "test.csv"

        fun feed(csv: String) {
            nextBytes = csv.toByteArray(Charsets.UTF_8)
            nextName = "test.csv"
        }

        override fun readInput(uri: Uri): ByteArray = nextBytes
        override fun readDisplayName(uri: Uri): String = nextName
    }
}