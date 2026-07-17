package io.github.jiro.expensetracker.ui.investments

import androidx.lifecycle.SavedStateHandle
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.CachedQuoteDao
import io.github.jiro.expensetracker.data.local.CachedQuoteEntity
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.market.QuoteDataSource
import io.github.jiro.expensetracker.data.market.RefreshOutcome
import io.github.jiro.expensetracker.data.market.SymbolOutcome
import io.github.jiro.expensetracker.data.repository.AccountDataSource
import io.github.jiro.expensetracker.preferences.SettingsDataSource
import io.github.jiro.expensetracker.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentAccountDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun vm(
        accountId: Long = 1L,
        account: AccountEntity = AccountEntity(
            id = accountId, name = "Brokerage", type = "INVESTMENT", icon = "📈",
            color = 0, currencyCode = "USD", createdAtEpochMillis = 0L,
        ),
        holdingsFlow: List<InvestmentHoldingEntity> = emptyList(),
        cachedQuotes: List<CachedQuoteEntity> = emptyList(),
        fxRates: Map<String, Double> = emptyMap(),
        refreshResult: RefreshOutcome = RefreshOutcome(emptyMap()),
        refreshThrows: Boolean = false,
        closeThrows: Boolean = false,
        holdingsCount: Int = 0,
    ): Pair<InvestmentAccountDetailViewModel, FakeQuoteRepository> {
        val accountRepo = FakeAccountRepository(account, closeThrows, holdingsCount)
        val holdingDao = DetailFakeHoldingDao(holdingsFlow)
        val cachedDao = FakeCachedQuoteDao(cachedQuotes)
        val settings = FakeSettingsRepository(fxRates)
        val quoteRepo = FakeQuoteRepository(refreshResult, refreshThrows)
        val v = InvestmentAccountDetailViewModel(
            savedStateHandle = SavedStateHandle().apply {
                set(Routes.INVESTMENT_ACCOUNT_DETAIL_ARG_ID, accountId)
            },
            accountRepository = accountRepo,
            holdingDao = holdingDao,
            cachedQuoteDao = cachedDao,
            quoteRepository = quoteRepo,
            settingsRepository = settings,
        )
        return v to quoteRepo
    }

    @Test fun emptyAccount_totalIsZero() = runTest(dispatcher) {
        val (v, _) = vm()
        advanceUntilIdle()
        assertEquals(0L, v.state.value.totalValueMinor)
        assertEquals(0L, v.state.value.totalCostMinor)
        assertEquals(0, v.state.value.holdings.size)
        assertTrue(v.state.value.missingFxPairs.isEmpty())
    }

    @Test fun usdHolding_rollsUpDirectly() = runTest(dispatcher) {
        val (v, _) = vm(
            holdingsFlow = listOf(holding(1L, "AAPL", 10.0, 150_000L, "USD")),
            cachedQuotes = listOf(CachedQuoteEntity("AAPL", 18_000L, "USD", 1L)),
        )
        advanceUntilIdle()
        // Current value = 10 × $180 = $1800. Cost = $1500. Gain = $300.
        assertEquals(180_000L, v.state.value.totalValueMinor)
        assertEquals(150_000L, v.state.value.totalCostMinor)
        assertEquals(30_000L, v.state.value.unrealizedMinor)
        assertTrue(v.state.value.missingFxPairs.isEmpty())
    }

    @Test fun mixedCurrencies_fxConverted() = runTest(dispatcher) {
        val (v, _) = vm(
            account = AccountEntity(
                id = 1L, name = "Brokerage", type = "INVESTMENT", icon = "📈",
                color = 0, currencyCode = "USD", createdAtEpochMillis = 0L,
            ),
            holdingsFlow = listOf(
                holding(1L, "AAPL", 10.0, 150_000L, "USD"),
                holding(2L, "7203.T", 100.0, 200_000L, "JPY"),
            ),
            cachedQuotes = listOf(
                CachedQuoteEntity("AAPL", 18_000L, "USD", 1L),
                CachedQuoteEntity("7203.T", 3_000L, "JPY", 1L),
            ),
            fxRates = mapOf("JPY_to_USD" to 0.67),
        )
        advanceUntilIdle()
        // AAPL: 10 × $180 = $1800.
        // 7203.T: 100 × ¥3000 = ¥300,000 → USD = 300_000 × 0.67 = $201,000.
        // Total = $381,000; cost = $1500 + $134,000 = $284,000.
        assertEquals(381_000L, v.state.value.totalValueMinor)
        assertEquals(284_000L, v.state.value.totalCostMinor)
        assertTrue(v.state.value.missingFxPairs.isEmpty())
    }

    @Test fun missingFxRate_excludesFromTotal() = runTest(dispatcher) {
        val (v, _) = vm(
            holdingsFlow = listOf(
                holding(1L, "AAPL", 10.0, 150_000L, "USD"),
                holding(2L, "7203.T", 100.0, 200_000L, "JPY"),
            ),
            cachedQuotes = listOf(
                CachedQuoteEntity("AAPL", 18_000L, "USD", 1L),
                CachedQuoteEntity("7203.T", 3_000L, "JPY", 1L),
            ),
            // No JPY_to_USD rate.
        )
        advanceUntilIdle()
        // Only AAPL contributes: $1800.
        assertEquals(180_000L, v.state.value.totalValueMinor)
        assertEquals(listOf("JPY_to_USD"), v.state.value.missingFxPairs)
    }

    @Test fun staleQuote_isMarked() = runTest(dispatcher) {
        val now = 1_000_000_000_000L
        val oldFetched = now - (7L * 60 * 60 * 1000)  // 7h ago
        val (v, _) = vm(
            holdingsFlow = listOf(holding(1L, "AAPL", 1.0, 100L, "USD")),
            cachedQuotes = listOf(CachedQuoteEntity("AAPL", 100L, "USD", oldFetched)),
        )
        // Inject a "current time" via VM clock override. For this test we use
        // the real clock and accept the result based on the system time being
        // after oldFetched.
        advanceUntilIdle()
        val row = v.state.value.holdings.single()
        // The row is stale iff now - fetchedAt > 6h. The real "now" is well past oldFetched.
        assertTrue(row.stale)
    }

    @Test fun refresh_triggersNetworkOnce() = runTest(dispatcher) {
        val (v, repo) = vm(
            holdingsFlow = listOf(holding(1L, "AAPL", 1.0, 100L, "USD")),
            refreshResult = RefreshOutcome(mapOf("AAPL" to SymbolOutcome.Fresh)),
        )
        advanceUntilIdle()
        assertEquals(0, repo.refreshCount)
        v.refresh()
        advanceUntilIdle()
        assertEquals(1, repo.refreshCount)
        assertEquals(listOf("AAPL"), repo.lastRequestedSymbols)
    }

    @Test fun onClose_emitsCloseEvent() = runTest(dispatcher) {
        val (v, _) = vm(accountId = 5L)
        advanceUntilIdle()
        v.onCloseClick()
        advanceUntilIdle()
        assertEquals(true, v.state.value.showCloseConfirm)
        v.onCloseConfirm()
        advanceUntilIdle()
        // FakeAccountRepository.close is a no-op; verify the dialog closes and
        // the closeEvent fires (consumed once via first emission).
        assertEquals(false, v.state.value.showCloseConfirm)
        val emitted = v.closeEvent.first()
        assertEquals(5L, emitted)
    }

    @Test fun refresh_failure_setsErrorMessage() = runTest(dispatcher) {
        val (v, _) = vm(
            holdingsFlow = listOf(holding(1L, "AAPL", 1.0, 100L, "USD")),
            refreshThrows = true,
        )
        advanceUntilIdle()
        v.refresh()
        advanceUntilIdle()
        assertTrue(v.state.value.errorMessage != null)
    }

    @Test fun onCloseConfirm_failure_keepsDialogClosed() = runTest(dispatcher) {
        val (v, _) = vm(accountId = 5L, closeThrows = true)
        advanceUntilIdle()
        v.onCloseClick()
        advanceUntilIdle()
        assertEquals(true, v.state.value.showCloseConfirm)
        v.onCloseConfirm()
        advanceUntilIdle()
        assertEquals(false, v.state.value.showCloseConfirm)
        assertTrue(v.state.value.errorMessage != null)
    }

    @Test fun delete_withHoldings_showsBlockedDialog() = runTest(dispatcher) {
        val (v, _) = vm(accountId = 5L, holdingsCount = 3)
        advanceUntilIdle()
        v.onDeleteClick()
        advanceUntilIdle()
        assertEquals(true, v.state.value.showDeleteBlocked)
        assertEquals(false, v.state.value.showDeleteConfirm)
        assertEquals(3, v.state.value.holdingsCount)
    }

    @Test fun delete_noHoldings_showsConfirmDialog() = runTest(dispatcher) {
        val (v, _) = vm(accountId = 5L, holdingsCount = 0)
        advanceUntilIdle()
        v.onDeleteClick()
        advanceUntilIdle()
        assertEquals(true, v.state.value.showDeleteConfirm)
        assertEquals(false, v.state.value.showDeleteBlocked)
    }

    @Test fun delete_confirm_emitsDeleteEvent() = runTest(dispatcher) {
        val (v, _) = vm(accountId = 5L, holdingsCount = 0)
        advanceUntilIdle()
        v.onDeleteClick()
        advanceUntilIdle()
        assertEquals(true, v.state.value.showDeleteConfirm)
        v.onDeleteConfirm()
        advanceUntilIdle()
        assertEquals(false, v.state.value.showDeleteConfirm)
        val emitted = v.deleteEvent.first()
        assertEquals(5L, emitted)
    }
}

// --- Fakes ---

private fun holding(id: Long, symbol: String, qty: Double, cost: Long, currency: String) =
    InvestmentHoldingEntity(
        id = id, accountId = 1L, symbol = symbol, quantity = qty,
        costBasisMinor = cost, currencyCode = currency, createdAtEpochMillis = 0L,
    )

private class FakeAccountRepository(
    val account: AccountEntity,
    val closeThrows: Boolean = false,
    val holdingsCount: Int = 0,
) : AccountDataSource {
    override suspend fun findById(id: Long) = if (id == account.id) account else null
    override suspend fun close(id: Long) { if (closeThrows) error("close failed") }
    override suspend fun countHoldings(id: Long) = holdingsCount
    override fun observeActive(): Flow<List<AccountEntity>> = flowOf(listOf(account))
}

private class DetailFakeHoldingDao(rows: List<InvestmentHoldingEntity>) : InvestmentHoldingDao {
    private val rows = rows.associateBy { it.id }.toMutableMap()
    override suspend fun insert(row: InvestmentHoldingEntity): Long { rows[row.id] = row; return row.id }
    override suspend fun update(row: InvestmentHoldingEntity) { rows[row.id] = row }
    override suspend fun delete(id: Long) { rows.remove(id) }
    override fun observeByAccount(accountId: Long) = flowOf(rows.values.filter { it.accountId == accountId })
    override suspend fun findById(id: Long) = rows[id]
    override suspend fun countByAccount(accountId: Long) = rows.values.count { it.accountId == accountId }
}

private class FakeCachedQuoteDao(initial: List<CachedQuoteEntity>) : CachedQuoteDao {
    private val rows = initial.associateBy { it.symbol }.toMutableMap()
    override suspend fun upsert(row: CachedQuoteEntity) { rows[row.symbol] = row }
    override suspend fun findBySymbol(symbol: String) = rows[symbol]
    override fun observeBySymbols(symbols: List<String>) =
        flowOf(rows.values.filter { it.symbol in symbols })
    override suspend fun findBySymbols(symbols: List<String>) =
        rows.values.filter { it.symbol in symbols }
}

private class FakeSettingsRepository(
    rates: Map<String, Double> = emptyMap(),
    home: String = "USD",
) : SettingsDataSource {
    override val fxRates: kotlinx.coroutines.flow.Flow<Map<String, Double>> =
        MutableStateFlow(rates)
    override val homeCurrency: kotlinx.coroutines.flow.Flow<String> =
        MutableStateFlow(home)
}

private class FakeQuoteRepository(
    val result: RefreshOutcome,
    val refreshThrows: Boolean = false,
) : QuoteDataSource {
    var refreshCount = 0
    var lastRequestedSymbols: List<String> = emptyList()
    override fun observeAllCached(symbols: List<String>) = flowOf(emptyMap<String, CachedQuoteEntity>())
    override suspend fun refresh(symbols: List<String>): RefreshOutcome {
        refreshCount++
        lastRequestedSymbols = symbols
        if (refreshThrows) error("refresh failed")
        return result
    }
}
