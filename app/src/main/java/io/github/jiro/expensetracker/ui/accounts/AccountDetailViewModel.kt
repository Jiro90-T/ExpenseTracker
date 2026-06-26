package io.github.jiro.expensetracker.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.ui.navigation.Routes
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AccountDetailUiState(
    val accountWithBalance: AccountWithBalance? = null,
    val transactions: List<TransactionWithCategory> = emptyList(),
    val isLoading: Boolean = true,
)

enum class DeleteGuard { ALLOW, BLOCK_TRANSACTIONS_EXIST }

fun evaluateDelete(referenceCount: Int): DeleteGuard =
    if (referenceCount == 0) DeleteGuard.ALLOW else DeleteGuard.BLOCK_TRANSACTIONS_EXIST

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>(Routes.ACCOUNT_DETAIL_ARG_ID) ?: -1L

    val state: StateFlow<AccountDetailUiState> = combine(
        accountRepository.observeWithBalances(),
        transactionRepository.observeByAccount(accountId),
    ) { accounts, txns ->
        AccountDetailUiState(
            accountWithBalance = accounts.firstOrNull { it.account.id == accountId },
            transactions = txns,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AccountDetailUiState(),
    )
}
