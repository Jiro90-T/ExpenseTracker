package io.github.jiro.expensetracker.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.BudgetDao
import io.github.jiro.expensetracker.data.local.BudgetEntity
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.auth.SessionTokenGenerator
import io.github.jiro.expensetracker.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocalServerControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newController(
        serviceStarter: (port: Int) -> Unit = {},
        serviceStopper: () -> Unit = {},
        tokenRetriever: () -> String? = { "fixed-token-${System.nanoTime()}" },
        ipProvider: () -> String? = { "192.0.2.1" },
    ): LocalServerController = LocalServerController(
        context = context,
        transactionRepository = stubTxRepo,
        accountRepository = stubAccountRepo,
        categoryRepository = stubCategoryRepo,
        budgetRepository = stubBudgetRepo,
        settingsRepository = stubSettingsRepo,
        sessionTokenGenerator = SessionTokenGenerator(),
        serviceStarter = serviceStarter,
        serviceStopper = serviceStopper,
        tokenRetriever = tokenRetriever,
        ipProvider = ipProvider,
    )

    @Test
    fun initialState_isOff() = runTest(dispatcher) {
        val c = newController()
        assertEquals(false, c.state.value.isRunning)
        assertNull(c.state.value.token)
        assertNull(c.state.value.lastError)
    }

    @Test
    fun start_setsRunningAndTokenAndUrl() = runTest(dispatcher) {
        val c = newController(tokenRetriever = { "abc" })
        val result = c.start()
        assertTrue("start() should succeed", result.isSuccess)
        advanceUntilIdle()
        val s = c.state.value
        assertTrue(s.isRunning)
        assertEquals("abc", s.token)
        assertEquals("192.0.2.1", s.ipAddress)
        assertEquals(8080, s.port)
    }

    @Test
    fun start_whenPortInUse_revertsAndSetsError() = runTest(dispatcher) {
        val c = newController(
            serviceStarter = { _ -> throw java.net.BindException("Address already in use") },
            tokenRetriever = { null },
        )
        val result = c.start()
        assertTrue(result.isFailure)
        advanceUntilIdle()
        val s = c.state.value
        assertFalse(s.isRunning)
        assertNotNull(s.lastError)
        assertTrue(s.lastError!!.contains("8080"))
    }

    @Test
    fun stop_clearsRunningAndToken() = runTest(dispatcher) {
        var stopped = false
        val c = newController(serviceStopper = { stopped = true })
        c.start()
        advanceUntilIdle()
        c.stop()
        advanceUntilIdle()
        val s = c.state.value
        assertFalse(s.isRunning)
        assertNull(s.token)
        assertTrue(stopped)
    }

    companion object {
        private val context: Context by lazy {
            ApplicationProvider.getApplicationContext()
        }

        private val stubTxRepo: TransactionRepository = TransactionRepository(
            StubTransactionDao(),
            ReceiptRepository(context),
        )
        private val stubAccountRepo: AccountRepository = AccountRepository(
            StubAccountDao(),
            StubInvestmentHoldingDao,
        )
        private val stubCategoryRepo: CategoryRepository = CategoryRepository(StubCategoryDao())
        private val stubBudgetRepo: BudgetRepository = BudgetRepository(StubBudgetDao())
        private val stubSettingsRepo: SettingsRepository = SettingsRepository(context)
    }
}

private object StubInvestmentHoldingDao : InvestmentHoldingDao {
    override suspend fun insert(row: InvestmentHoldingEntity): Long = 0L
    override suspend fun update(row: InvestmentHoldingEntity) = Unit
    override suspend fun delete(id: Long) = Unit
    override fun observeByAccount(accountId: Long): Flow<List<InvestmentHoldingEntity>> =
        MutableStateFlow<List<InvestmentHoldingEntity>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long): InvestmentHoldingEntity? = null
    override suspend fun countByAccount(accountId: Long): Int = 0
}

private class StubTransactionDao : TransactionDao {
    override fun observeAllWithCategory(): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override fun observeInRangeWithCategory(startMs: Long, endMs: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long): TransactionEntity? = null
    override suspend fun insert(transaction: TransactionEntity): Long = 0L
    override suspend fun restore(transaction: TransactionEntity): Long = 0L
    override suspend fun update(transaction: TransactionEntity) = Unit
    override suspend fun delete(transaction: TransactionEntity) = Unit
    override suspend fun observeAllForExport(): List<TransactionEntity> = emptyList()
    override suspend fun deleteAll(): Int = 0
    override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> = emptyList()
    override suspend fun clearReceiptPathsFor(paths: List<String>) = Unit
    override suspend fun dueRecurringParents(nowMs: Long): List<TransactionEntity> = emptyList()
    override fun observeByRecurringGroup(groupId: String): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override suspend fun countByRecurringGroup(groupId: String): Int = 0
    override suspend fun countForAccount(accountId: Long): Int = 0
    override suspend fun countReferencingAccount(id: Long): Int = 0
    override fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override fun observeTransfersToAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
}

private class StubAccountDao : AccountDao {
    override fun observeActive(): Flow<List<AccountEntity>> =
        MutableStateFlow<List<AccountEntity>>(emptyList()).asStateFlow()
    override suspend fun listActiveOnce(): List<AccountEntity> = emptyList()
    override suspend fun findById(id: Long): AccountEntity? = null
    override suspend fun findActiveDefault(): AccountEntity? = null
    override suspend fun insert(account: AccountEntity): Long = 0L
    override suspend fun insertAllReplacing(accounts: List<AccountEntity>): List<Long> = emptyList()
    override suspend fun update(account: AccountEntity): Int = 0
    override suspend fun delete(id: Long): Int = 0
    override suspend fun deleteAll(): Int = 0
    override suspend fun updateDefaultCurrency(code: String): Int = 0
    override suspend fun countActive(): Int = 0
    override fun observeBalances(): Flow<List<AccountBalanceRow>> =
        MutableStateFlow<List<AccountBalanceRow>>(emptyList()).asStateFlow()
    override fun observeAllBalances(): Flow<List<AccountBalanceRow>> =
        MutableStateFlow<List<AccountBalanceRow>>(emptyList()).asStateFlow()
    override fun observeAllEntities(): Flow<List<AccountEntity>> =
        MutableStateFlow<List<AccountEntity>>(emptyList()).asStateFlow()
    override suspend fun listAllOnce(): List<AccountEntity> = emptyList()
    override suspend fun close(id: Long, now: Long) = Unit
    override suspend fun reopen(id: Long) = Unit
    override suspend fun maxSortOrder(): Int = 0
    override suspend fun updateOpeningBalanceByName(name: String, balance: Long): Int = 0
    override suspend fun applyAccountImport(
        rows: List<io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow>,
        nowEpochMs: Long,
    ) = Unit
}

private class StubCategoryDao : CategoryDao {
    override fun observeByType(type: String): Flow<List<CategoryEntity>> =
        MutableStateFlow<List<CategoryEntity>>(emptyList()).asStateFlow()
    override fun observeAll(): Flow<List<CategoryEntity>> =
        MutableStateFlow<List<CategoryEntity>>(emptyList()).asStateFlow()
    override suspend fun count(): Int = 0
    override suspend fun findById(id: Long): CategoryEntity? = null
    override suspend fun insertAll(categories: List<CategoryEntity>): List<Long> = emptyList()
    override suspend fun insert(category: CategoryEntity): Long = 0L
    override suspend fun insertAllReplacing(categories: List<CategoryEntity>): List<Long> = emptyList()
    override suspend fun update(category: CategoryEntity): Int = 0
    override suspend fun deleteById(id: Long): Int = 0
    override suspend fun deleteAllNonBuiltIn(): Int = 0
    override suspend fun observeAllOnce(): List<CategoryEntity> = emptyList()
}

private class StubBudgetDao : BudgetDao {
    override fun observeByMonth(monthStart: Long): Flow<List<BudgetEntity>> =
        MutableStateFlow<List<BudgetEntity>>(emptyList()).asStateFlow()
    override suspend fun upsert(budget: BudgetEntity) = Unit
    override suspend fun deleteByKey(categoryId: Long, monthStart: Long): Int = 0
}