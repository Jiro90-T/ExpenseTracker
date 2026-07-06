package io.github.jiro.expensetracker.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.sync.TransactionMutationBus
import io.github.jiro.expensetracker.ui.navigation.Routes
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
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
    val errorMessage: String? = null,
    // Phase 2.19 close/reopen flow:
    val showCloseConfirm: Boolean = false,
    val showReopenConfirm: Boolean = false,
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
    private val transactionMutationBus: TransactionMutationBus,
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>(Routes.ACCOUNT_DETAIL_ARG_ID) ?: -1L

    private val _state = MutableStateFlow(AccountDetailUiState())
    val state: StateFlow<AccountDetailUiState> = _state.asStateFlow()

    // One-shot events consumed by the screen to show snackbars.
    private val _closeEvent = Channel<Long>(Channel.BUFFERED)
    val closeEvent: Flow<Long> = _closeEvent.receiveAsFlow()

    private val _reopenEvent = Channel<Long>(Channel.BUFFERED)
    val reopenEvent: Flow<Long> = _reopenEvent.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                accountRepository.observeAllWithBalances(),
                transactionRepository.observeByAccount(accountId),
            ) { accounts, txns ->
                AccountDetailUiState(
                    accountWithBalance = accounts.firstOrNull { it.account.id == accountId },
                    transactions = txns,
                    isLoading = false,
                )
            }.collect { upstream ->
                _state.update { current ->
                    upstream.copy(
                        showDeleteConfirm = current.showDeleteConfirm,
                        deleteGuard = current.deleteGuard,
                        referenceCount = current.referenceCount,
                        deleted = current.deleted,
                        errorMessage = current.errorMessage,
                        showCloseConfirm = current.showCloseConfirm,
                        showReopenConfirm = current.showReopenConfirm,
                    )
                }
            }
        }
    }

    // ---- Delete flow (unchanged) ----

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
            transactionMutationBus.tryEmit()
        }
    }

    fun onDeleteDismiss() {
        _state.update { it.copy(showDeleteConfirm = false) }
    }

    // ---- Close / Reopen flow (Phase 2.19) ----

    fun onCloseClick() {
        _state.update { it.copy(showCloseConfirm = true) }
    }

    fun onCloseConfirm() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        viewModelScope.launch {
            accountRepository.close(accountId)
            _state.update { it.copy(showCloseConfirm = false) }
            _closeEvent.send(accountId)
        }
    }

    fun onCloseDismiss() {
        _state.update { it.copy(showCloseConfirm = false) }
    }

    fun onReopenClick() {
        _state.update { it.copy(showReopenConfirm = true) }
    }

    fun onReopenConfirm() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        viewModelScope.launch {
            accountRepository.reopen(accountId)
            _state.update { it.copy(showReopenConfirm = false) }
            _reopenEvent.send(accountId)
        }
    }

    fun onReopenDismiss() {
        _state.update { it.copy(showReopenConfirm = false) }
    }

    /**
     * Undo path for the post-close snackbar. Called by the screen when the
     * user taps the Undo action — reopens the just-closed account.
     */
    fun undoClose() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        viewModelScope.launch {
            accountRepository.reopen(accountId)
            _reopenEvent.send(accountId)
        }
    }
}
