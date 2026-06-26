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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountDetailUiState(
    val accountWithBalance: AccountWithBalance? = null,
    val transactions: List<TransactionWithCategory> = emptyList(),
    val isLoading: Boolean = true,
    // Phase 2.17 delete flow:
    val showDeleteConfirm: Boolean = false,
    val deleteGuard: DeleteGuard? = null,
    val referenceCount: Int = 0,
    val deleted: Boolean = false,    // signal to UI: pop back stack
    val errorMessage: String? = null,// surfaced as a snackbar
)

enum class DeleteGuard { ALLOW, BLOCK_TRANSACTIONS_EXIST }

fun evaluateDelete(referenceCount: Int): DeleteGuard =
    if (referenceCount == 0) DeleteGuard.ALLOW else DeleteGuard.BLOCK_TRANSACTIONS_EXIST

private const val DEFAULT_ACCOUNT_ID = 1L

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>(Routes.ACCOUNT_DETAIL_ARG_ID) ?: -1L

    private val _state = MutableStateFlow(AccountDetailUiState())
    val state: StateFlow<AccountDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                accountRepository.observeWithBalances(),
                transactionRepository.observeByAccount(accountId),
            ) { accounts, txns ->
                AccountDetailUiState(
                    accountWithBalance = accounts.firstOrNull { it.account.id == accountId },
                    transactions = txns,
                    isLoading = false,
                )
            }.collect { upstream ->
                // Preserve transient delete-flow fields across upstream emissions.
                // Using stateIn here would reset the entire state object on every
                // emission, wiping the confirm dialog the user just opened.
                _state.update { current ->
                    upstream.copy(
                        showDeleteConfirm = current.showDeleteConfirm,
                        deleteGuard = current.deleteGuard,
                        referenceCount = current.referenceCount,
                        deleted = current.deleted,
                        errorMessage = current.errorMessage,
                    )
                }
            }
        }
    }

    fun onDeleteClick() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        if (accountId == DEFAULT_ACCOUNT_ID) return
        viewModelScope.launch {
            val count = transactionRepository.countReferencingAccount(accountId)
            _state.update {
                it.copy(
                    showDeleteConfirm = true,
                    deleteGuard = evaluateDelete(count),
                    referenceCount = count,
                )
            }
        }
    }

    fun onDeleteConfirm() {
        val s = _state.value
        val guard = s.deleteGuard ?: return
        if (guard != DeleteGuard.ALLOW) return
        val accountId = s.accountWithBalance?.account?.id ?: return
        viewModelScope.launch {
            accountRepository.delete(accountId)
            _state.update { it.copy(showDeleteConfirm = false, deleted = true) }
        }
    }

    fun onDeleteDismiss() {
        _state.update { it.copy(showDeleteConfirm = false) }
    }
}
