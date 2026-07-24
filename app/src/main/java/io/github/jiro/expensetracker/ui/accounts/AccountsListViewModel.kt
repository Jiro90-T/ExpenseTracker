package io.github.jiro.expensetracker.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Which sub-list is currently visible on the Manage Accounts screen. */
enum class AccountsTab { BANK, INVESTMENT }

data class AccountsListUiState(
    val accounts: List<AccountWithBalance> = emptyList(),
    val bankCount: Int = 0,
    val investmentCount: Int = 0,
    val activeTab: AccountsTab = AccountsTab.BANK,
    val netBalanceInHome: String = "",
    val count: Int = 0,
    val isLoading: Boolean = true,
    val showClosed: Boolean = false,
)

@HiltViewModel
class AccountsListViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _showClosed = MutableStateFlow(false)
    private val _activeTab = MutableStateFlow(AccountsTab.BANK)

    val state: StateFlow<AccountsListUiState> = combine(
        _showClosed,
        _activeTab,
        accountRepository.observeAllWithBalances(),
        accountRepository.observeWithBalances(),
        settingsRepository.fxRates,
        settingsRepository.homeCurrency,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val showClosed = values[0] as Boolean
        @Suppress("UNCHECKED_CAST")
        val tab = values[1] as AccountsTab
        @Suppress("UNCHECKED_CAST")
        val allAccounts = values[2] as List<AccountWithBalance>
        @Suppress("UNCHECKED_CAST")
        val activeAccounts = values[3] as List<AccountWithBalance>
        @Suppress("UNCHECKED_CAST")
        val fx = values[4] as Map<String, Double>
        @Suppress("UNCHECKED_CAST")
        val home = values[5] as String

        val source = if (showClosed) allAccounts else activeAccounts
        val bank = source.filter { it.account.type != "INVESTMENT" }
            .sortedBy { it.account.name.lowercase() }
        val invest = source.filter { it.account.type == "INVESTMENT" }
            .sortedBy { it.account.name.lowercase() }
        val visible = if (tab == AccountsTab.BANK) bank else invest

        // Net balance: still totals ALL accounts so the user sees the grand
        // total, not the tab-scoped sum. Matches the pre-tab behavior.
        val net = computeNetBalanceInHome(allAccounts, home, fx)
        val netMinor = kotlin.math.round(net * 100.0).toLong()
        AccountsListUiState(
            accounts = visible,
            bankCount = bank.size,
            investmentCount = invest.size,
            activeTab = tab,
            netBalanceInHome = "${MoneyFormat.formatForDisplay(netMinor)} $home",
            count = visible.size,
            isLoading = false,
            showClosed = showClosed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AccountsListUiState(),
    )

    fun setShowClosed(value: Boolean) {
        _showClosed.value = value
    }

    fun setActiveTab(tab: AccountsTab) {
        _activeTab.value = tab
    }
}

/**
 * Pure: sums each account's balance in [homeCurrency], FX-converted via [fxRates],
 * and returns the total in MAJOR units (e.g. 35.0 for $35.00). Returns 0.0
 * (rather than skipping) when a currency pair has no rate, to match the
 * pre-fix behaviour of treating unknown rates as a 1:1 fallback for the
 * dashboard aggregate. Callers that want a strict missing-rate count
 * should check [FxConverter.convertMinor] returns.
 */
fun computeNetBalanceInHome(
    accounts: List<AccountWithBalance>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
): Double = accounts.fold(0.0) { acc, aw ->
    val convertedMinor = FxConverter.convertMinor(
        amountMinor = aw.balanceMinor,
        fromCurrency = aw.account.currencyCode,
        toCurrency = homeCurrency,
        rates = fxRates,
    )
    acc + (convertedMinor?.toDouble() ?: 0.0) / 100.0
}
