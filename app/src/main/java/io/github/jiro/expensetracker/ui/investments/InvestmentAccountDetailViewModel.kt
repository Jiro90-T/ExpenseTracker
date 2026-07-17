package io.github.jiro.expensetracker.ui.investments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.CachedQuoteDao
import io.github.jiro.expensetracker.data.local.CachedQuoteEntity
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.market.QuoteDataSource
import io.github.jiro.expensetracker.data.repository.AccountDataSource
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.preferences.SettingsDataSource
import io.github.jiro.expensetracker.ui.navigation.Routes
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A single holding as the screen renders it. */
data class HoldingRow(
    val holding: InvestmentHoldingEntity,
    val cachedPriceMinor: Long?,
    val cachedPriceCurrency: String?,
    val cachedAtEpochMillis: Long?,
    val marketValueInAccountCurrencyMinor: Long?,  // null when FX or price missing
    val costInAccountCurrencyMinor: Long?,          // null when FX missing
    val unrealizedInAccountCurrencyMinor: Long?,    // null when FX missing
    val stale: Boolean,
)

data class InvestmentDetailUiState(
    val account: AccountEntity? = null,
    val holdings: List<HoldingRow> = emptyList(),
    val totalValueMinor: Long = 0L,
    val totalCostMinor: Long = 0L,
    val unrealizedMinor: Long = 0L,
    val missingFxPairs: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    val showCloseConfirm: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val errorMessage: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class InvestmentAccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountDataSource,
    private val holdingDao: InvestmentHoldingDao,
    private val cachedQuoteDao: CachedQuoteDao,
    private val quoteRepository: QuoteDataSource,
    private val settingsRepository: SettingsDataSource,
) : ViewModel() {

    private val accountId: Long = savedStateHandle
        .get<Long>(Routes.INVESTMENT_ACCOUNT_DETAIL_ARG_ID) ?: -1L

    private val _showCloseConfirm = MutableStateFlow(false)
    private val _showDeleteConfirm = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _closeEvent = Channel<Long>(Channel.BUFFERED)
    val closeEvent: Flow<Long> = _closeEvent.receiveAsFlow()

    private data class Rollup(
        val accounts: List<AccountEntity>,
        val holdings: List<InvestmentHoldingEntity>,
        val cached: Map<String, CachedQuoteEntity>,
        val fxRates: Map<String, Double>,
        val homeCurrency: String,
    )

    private val rollupFlow: Flow<Rollup> = combine(
        accountRepository.observeActive(),
        holdingDao.observeByAccount(accountId),
        settingsRepository.fxRates,
        settingsRepository.homeCurrency,
    ) { accounts, holdings, fxRates, homeCurrency ->
        Quad(
            accounts = accounts,
            holdings = holdings,
            fxRates = fxRates,
            homeCurrency = homeCurrency,
        )
    }.flatMapLatest { q ->
        val symbols = q.holdings.map { it.symbol }.distinct()
        cachedQuoteDao.observeBySymbols(symbols).map { cachedList ->
            Rollup(
                accounts = q.accounts,
                holdings = q.holdings,
                cached = cachedList.associateBy { it.symbol },
                fxRates = q.fxRates,
                homeCurrency = q.homeCurrency,
            )
        }
    }

    private data class Quad(
        val accounts: List<AccountEntity>,
        val holdings: List<InvestmentHoldingEntity>,
        val fxRates: Map<String, Double>,
        val homeCurrency: String,
    )

    val state: StateFlow<InvestmentDetailUiState> = combine(
        rollupFlow,
        _showCloseConfirm,
        _showDeleteConfirm,
        _isRefreshing,
        _errorMessage,
    ) { rollup, showClose, showDelete, refreshing, error ->
        buildState(
            rollup.accounts, rollup.holdings, rollup.cached,
            rollup.fxRates, rollup.homeCurrency,
            showClose, showDelete, refreshing, error,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eager: a VM-owned state should always be current so consumers
        // reading `state.value` see live data without needing to subscribe.
        started = SharingStarted.Eagerly,
        initialValue = InvestmentDetailUiState(),
    )

    // No init refresh: callers (UI pull-to-refresh) drive refresh().
    // An initial refresh would race with the UI and confuse tests that
    // assert on refreshCount == 0 before invoking refresh() explicitly.

    fun refresh() {
        viewModelScope.launch {
            val symbols = holdingDao.observeByAccount(accountId).first()
                .map { it.symbol }
                .distinct()
            if (symbols.isEmpty()) return@launch
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                quoteRepository.refresh(symbols)
            } catch (e: Throwable) {
                _errorMessage.value = e.message ?: "Refresh failed"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun onCloseClick() { _showCloseConfirm.value = true }
    fun onCloseConfirm() {
        viewModelScope.launch {
            try {
                accountRepository.close(accountId)
                _closeEvent.send(accountId)
            } catch (e: Throwable) {
                _errorMessage.value = e.message ?: "Close failed"
            } finally {
                _showCloseConfirm.value = false
            }
        }
    }
    fun onCloseDismiss() { _showCloseConfirm.value = false }
    fun onDeleteClick() { _showDeleteConfirm.value = true }
    fun onDeleteDismiss() { _showDeleteConfirm.value = false }

    private fun buildState(
        accounts: List<AccountEntity>,
        holdings: List<InvestmentHoldingEntity>,
        cached: Map<String, CachedQuoteEntity>,
        fxRates: Map<String, Double>,
        homeCurrency: String,
        showClose: Boolean,
        showDelete: Boolean,
        refreshing: Boolean,
        error: String?,
    ): InvestmentDetailUiState {
        val account = accounts.firstOrNull { it.id == accountId }
        val targetCurrency = account?.currencyCode ?: homeCurrency
        val now = System.currentTimeMillis()
        var totalValue = 0L
        var totalCost = 0L
        val missingPairs = mutableSetOf<String>()
        val rows = holdings.map { h ->
            val c = cached[h.symbol]
            val marketValueNativeMinor: Long? = if (c != null) {
                // Multiply with rounding. quantity is fractional; price is Long minor.
                Math.round(h.quantity * c.priceMinor)
            } else null
            val valueConverted = if (c != null && marketValueNativeMinor != null) {
                FxConverter.convertMinor(
                    marketValueNativeMinor, c.currencyCode, targetCurrency, fxRates,
                )
            } else null
            if (c != null && valueConverted == null &&
                c.currencyCode != targetCurrency
            ) {
                missingPairs.add(FxConverter.rateKey(c.currencyCode, targetCurrency))
            }
            val costConverted = FxConverter.convertMinor(
                h.costBasisMinor, h.currencyCode, targetCurrency, fxRates,
            )
            if (costConverted == null && h.currencyCode != targetCurrency) {
                missingPairs.add(FxConverter.rateKey(h.currencyCode, targetCurrency))
            }
            val unrealized = if (valueConverted != null && costConverted != null) {
                valueConverted - costConverted
            } else null
            if (valueConverted != null) totalValue += valueConverted
            if (costConverted != null) totalCost += costConverted
            HoldingRow(
                holding = h,
                cachedPriceMinor = c?.priceMinor,
                cachedPriceCurrency = c?.currencyCode,
                cachedAtEpochMillis = c?.fetchedAtEpochMillis,
                marketValueInAccountCurrencyMinor = valueConverted,
                costInAccountCurrencyMinor = costConverted,
                unrealizedInAccountCurrencyMinor = unrealized,
                stale = c != null && (now - c.fetchedAtEpochMillis) > STALE_THRESHOLD_MS,
            )
        }
        return InvestmentDetailUiState(
            account = account,
            holdings = rows,
            totalValueMinor = totalValue,
            totalCostMinor = totalCost,
            unrealizedMinor = totalValue - totalCost,
            missingFxPairs = missingPairs.toList(),
            isRefreshing = refreshing,
            showCloseConfirm = showClose,
            showDeleteConfirm = showDelete,
            errorMessage = error,
        )
    }

    companion object {
        const val STALE_THRESHOLD_MS = 6L * 60L * 60L * 1000L
    }
}
