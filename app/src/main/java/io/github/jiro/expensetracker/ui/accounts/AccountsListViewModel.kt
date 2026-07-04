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

data class AccountsListUiState(
    val accounts: List<AccountWithBalance> = emptyList(),
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

    val state: StateFlow<AccountsListUiState> = combine(
        _showClosed,
        accountRepository.observeAllWithBalances(),
        accountRepository.observeWithBalances(),
        settingsRepository.fxRates,
        settingsRepository.homeCurrency,
    ) { showClosed, allAccounts, activeAccounts, fx, home ->
        val listed = if (showClosed) allAccounts else activeAccounts
        val net = computeNetBalanceInHome(allAccounts, home, fx)
        // `net` is in major units (Double) — convert to minor (Long) so
        // MoneyFormat can apply thousands grouping ("39,318.12" not "39318.12").
        val netMinor = kotlin.math.round(net * 100.0).toLong()
        AccountsListUiState(
            accounts = listed,
            netBalanceInHome = "${MoneyFormat.formatForDisplay(netMinor)} $home",
            count = listed.size,
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