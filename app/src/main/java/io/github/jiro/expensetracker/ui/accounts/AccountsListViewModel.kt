package io.github.jiro.expensetracker.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AccountsListUiState(
    val accounts: List<AccountWithBalance> = emptyList(),
    val netBalanceInHome: String = "",
    val count: Int = 0,
    val isLoading: Boolean = true,
)

@HiltViewModel
class AccountsListViewModel @Inject constructor(
    accountRepository: AccountRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AccountsListUiState> = combine(
        accountRepository.observeWithBalances(),
        settingsRepository.fxRates,
        settingsRepository.homeCurrency,
    ) { accounts, fx, home ->
        val net = accounts.fold(0.0) { acc, aw ->
            val convertedMinor = FxConverter.convertMinor(
                amountMinor = aw.balanceMinor,
                fromCurrency = aw.account.currencyCode,
                toCurrency = home,
                rates = fx,
            )
            acc + (convertedMinor?.toDouble() ?: 0.0)
        }
        AccountsListUiState(
            accounts = accounts,
            netBalanceInHome = "%.2f %s".format(net, home),
            count = accounts.size,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AccountsListUiState(),
    )
}
