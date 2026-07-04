package io.github.jiro.expensetracker.ui.statistics

import android.app.Application
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- helpers ----

    private fun buildVm(): Pair<StatisticsViewModel, FakeRangeRepo> {
        val rangeRepo = FakeRangeRepo()
        val vm = StatisticsViewModel(
            transactionRepository = FakeTxRepo(),
            categoryRepository = FakeCatRepo(),
            settingsRepository = FakeSettingsRepo(),
            rangeRepository = rangeRepo,
        )
        return vm to rangeRepo
    }

    // ---- tests ----

    @Test
    fun rangeChangeForOneTab_doesNotAffectOthers() = runTest(testDispatcher) {
        val (vm, rangeRepo) = buildVm()
        advanceUntilIdle()
        val originalSavings = vm.savings.value
        rangeRepo.set(StatisticsTab.TOP_CATS, 1_700_000_000_000L..1_730_000_000_000L)
        advanceUntilIdle()
        assertEquals(originalSavings.monthLabel, vm.savings.value.monthLabel)
    }

    @Test
    fun yoyPriorWindow_subtractsOneYear() = runTest(testDispatcher) {
        val (vm, _) = buildVm()
        advanceUntilIdle()
        val yoy = vm.yoy.value
        assertTrue(yoy.currentWindowLabel.isNotEmpty())
        assertTrue(yoy.previousWindowLabel.isNotEmpty())
        assertNotEquals(yoy.currentWindowLabel, yoy.previousWindowLabel)
    }

    @Test
    fun yoyPriorWindow_handlesLeapDay() = runTest(testDispatcher) {
        val (vm, rangeRepo) = buildVm()
        val zone = java.time.ZoneId.systemDefault()
        val leapStart = java.time.LocalDate.of(2024, 2, 29)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val leapEnd = java.time.LocalDate.of(2024, 3, 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        rangeRepo.set(StatisticsTab.YOY, leapStart..leapEnd)
        advanceUntilIdle()
        val yoy = vm.yoy.value
        assertTrue(yoy.currentWindowLabel.isNotEmpty())
    }

    @Test
    fun rangeChange_triggersRecomputation() = runTest(testDispatcher) {
        val (vm, rangeRepo) = buildVm()
        rangeRepo.set(StatisticsTab.TOP_CATS, 1L..System.currentTimeMillis())
        advanceUntilIdle()
        assertTrue(vm.topCategories.value.monthLabel.isNotEmpty())
    }
}

// ---- fakes ----

private class NoopApplication : Application()

private class FakeTxRepo : TransactionRepository(
    dao = StubTransactionDao(),
    receiptRepository = FakeReceiptRepo(),
)

private class FakeCatRepo : CategoryRepository(
    dao = StubCategoryDao(),
) {
    override fun observeAll(): Flow<List<CategoryEntity>> =
        MutableStateFlow<List<CategoryEntity>>(emptyList()).asStateFlow()
}

private class FakeReceiptRepo : ReceiptRepository(
    context = NoopApplication(),
)

private class FakeSettingsRepo : SettingsRepository(
    context = NoopApplication(),
) {
    private val homeFlow = MutableStateFlow("USD")
    override val homeCurrency: kotlinx.coroutines.flow.StateFlow<String> = homeFlow.asStateFlow()

    private val fxFlow = MutableStateFlow<Map<String, Double>>(emptyMap())
    override val fxRates: kotlinx.coroutines.flow.StateFlow<Map<String, Double>> = fxFlow.asStateFlow()
}

private class FakeRangeRepo : StatisticsRangeRepository {
    private val flows = mutableMapOf<StatisticsTab, MutableStateFlow<LongRange>>()

    override fun observe(tab: StatisticsTab): Flow<LongRange> =
        flows.getOrPut(tab) { MutableStateFlow(placeholderRange()) }
            .asStateFlow()

    override suspend fun set(tab: StatisticsTab, range: LongRange) {
        flows.getOrPut(tab) { MutableStateFlow(placeholderRange()) }.value = range
    }

    private fun placeholderRange(): LongRange = 0L..1L

    override suspend fun defaultFor(tab: StatisticsTab, nowMs: Long): LongRange {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val ym = java.time.YearMonth.of(date.year, date.monthValue)
        return ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()..
            ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

// ---- minimal DAO stubs ----

@Suppress("UNUSED_PARAMETER")
private class StubTransactionDao : TransactionDao {
    override fun observeAllWithCategory(): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override fun observeInRangeWithCategory(startMs: Long, endMs: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long) = error("not used in tests")
    override suspend fun insert(transaction: TransactionEntity) = error("not used in tests")
    override suspend fun restore(transaction: TransactionEntity) = error("not used in tests")
    override suspend fun update(transaction: TransactionEntity) = error("not used in tests")
    override suspend fun delete(transaction: TransactionEntity) = error("not used in tests")
    override suspend fun observeAllForExport() = error("not used in tests")
    override suspend fun deleteAll() = error("not used in tests")
    override suspend fun insertAll(transactions: List<TransactionEntity>) = error("not used in tests")
    override suspend fun clearReceiptPathsFor(paths: List<String>) = error("not used in tests")
    override suspend fun dueRecurringParents(nowMs: Long) = error("not used in tests")
    override fun observeByRecurringGroup(groupId: String) =
        MutableStateFlow(emptyList<TransactionWithCategory>()).asStateFlow()
    override suspend fun countByRecurringGroup(groupId: String) = error("not used in tests")
    override suspend fun countForAccount(accountId: Long) = error("not used in tests")
    override suspend fun countReferencingAccount(id: Long) = error("not used in tests")
    override fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
}

@Suppress("UNUSED_PARAMETER")
private class StubCategoryDao : CategoryDao {
    override fun observeByType(type: String) =
        MutableStateFlow(emptyList<CategoryEntity>()).asStateFlow()
    override fun observeAll() =
        MutableStateFlow(emptyList<CategoryEntity>()).asStateFlow()
    override suspend fun count() = error("not used in tests")
    override suspend fun findById(id: Long) = error("not used in tests")
    override suspend fun insertAll(categories: List<CategoryEntity>) = error("not used in tests")
    override suspend fun insert(category: CategoryEntity) = error("not used in tests")
    override suspend fun insertAllReplacing(categories: List<CategoryEntity>) = error("not used in tests")
    override suspend fun update(category: CategoryEntity) = error("not used in tests")
    override suspend fun deleteById(id: Long) = error("not used in tests")
    override suspend fun deleteAllNonBuiltIn() = error("not used in tests")
    override suspend fun observeAllOnce() = error("not used in tests")
}

@Suppress("UNUSED_PARAMETER")
private class StubAccountDao : AccountDao {
    override fun observeActive(): Flow<List<AccountEntity>> =
        MutableStateFlow(emptyList<AccountEntity>()).asStateFlow()
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
    override fun observeBalances(): Flow<List<io.github.jiro.expensetracker.data.local.AccountBalanceRow>> =
        MutableStateFlow(emptyList<io.github.jiro.expensetracker.data.local.AccountBalanceRow>()).asStateFlow()
    override fun observeAllBalances(): Flow<List<io.github.jiro.expensetracker.data.local.AccountBalanceRow>> =
        MutableStateFlow(emptyList<io.github.jiro.expensetracker.data.local.AccountBalanceRow>()).asStateFlow()
    override fun observeAllEntities(): Flow<List<AccountEntity>> =
        MutableStateFlow(emptyList<AccountEntity>()).asStateFlow()
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
