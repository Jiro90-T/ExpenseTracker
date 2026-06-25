package io.github.jiro.expensetracker.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Stable identifiers for each user-facing error. */
enum class AccountFormError { NAME_REQUIRED, NAME_DUPLICATE, CURRENCY_REQUIRED }

/** The 5 presets; user can also pick "custom" and type their own. */
val ACCOUNT_TYPE_PRESETS = listOf("CASH", "BANK", "CREDIT_CARD", "EWALLET", "OTHER")

/** 8 emoji icons to pick from (matches the visual mockup). */
val ACCOUNT_ICON_CHOICES = listOf("💵", "🏦", "💳", "📱", "💰", "💼", "🎯", "🏠")

/** 8 preset colors (ARGB). Index 0 is the default blue. */
val ACCOUNT_COLOR_CHOICES = listOf(
    0xFF1976D2.toInt(), // blue
    0xFF43A047.toInt(), // green
    0xFFF57C00.toInt(), // orange
    0xFFC62828.toInt(), // red
    0xFF7B1FA2.toInt(), // purple
    0xFF00838F.toInt(), // teal
    0xFF5D4037.toInt(), // brown
    0xFF455A64.toInt(), // slate
)

data class AddEditAccountUiState(
    val isEdit: Boolean = false,
    val name: String = "",
    val type: String = "CASH",
    val customType: String = "",
    val icon: String = "💵",
    val color: Int = ACCOUNT_COLOR_CHOICES.first(),
    val currency: String = "USD",
    val openingBalanceInput: String = "0",
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val error: AccountFormError? = null,
    /** True after the form has been hydrated from an existing row. */
    val isCurrencyLocked: Boolean = false,
    /** Adjust balance dialog state. Non-null when the dialog should be visible. */
    val adjustDialog: AdjustBalanceDialogState? = null,
    /** True when at least one transaction exists against this account (Edit only). */
    val hasTransactions: Boolean = false,
    /** Current balance for the adjust dialog. */
    val currentBalanceMinor: Long = 0L,
)

data class AdjustBalanceDialogState(
    val newBalanceInput: String = "",
    val isSaving: Boolean = false,
)

@HiltViewModel
class AddEditAccountViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) : AndroidViewModel(application) {

    private val accountId: Long? = savedStateHandle
        .get<Long>("id")
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(AddEditAccountUiState(isEdit = accountId != null))
    val state: StateFlow<AddEditAccountUiState> = _state.asStateFlow()

    init {
        if (accountId != null) {
            viewModelScope.launch {
                val existing = accountRepository.findById(accountId) ?: return@launch
                val txnCount = transactionRepository.countForAccount(accountId)
                _state.update {
                    it.copy(
                        name = existing.name,
                        type = if (existing.type in ACCOUNT_TYPE_PRESETS) existing.type else "OTHER",
                        customType = if (existing.type !in ACCOUNT_TYPE_PRESETS) existing.type else "",
                        icon = existing.icon,
                        color = existing.color,
                        currency = existing.currencyCode,
                        openingBalanceInput = MoneyFormat.formatAmountForEdit(existing.openingBalanceMinor),
                        isCurrencyLocked = true,
                        hasTransactions = txnCount > 0,
                        isLoaded = true,
                    )
                }
            }
        } else {
            _state.update { it.copy(isLoaded = true) }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onTypeChange(value: String) = _state.update { it.copy(type = value, customType = "") }
    fun onCustomTypeChange(value: String) = _state.update { it.copy(customType = value) }
    fun onIconChange(value: String) = _state.update { it.copy(icon = value) }
    fun onColorChange(value: Int) = _state.update { it.copy(color = value) }
    fun onCurrencyChange(value: String) = _state.update {
        if (it.isCurrencyLocked) it else it.copy(currency = value, error = null)
    }
    fun onOpeningBalanceChange(value: String) = _state.update { it.copy(openingBalanceInput = value) }

    fun openAdjustDialog() {
        viewModelScope.launch {
            val id = accountId ?: return@launch
            // Take the first non-empty emission. observeWithBalances emits when
            // accounts OR transactions change; on a fresh Edit screen it's
            // synchronous on the first emit.
            val first = accountRepository.observeWithBalances().firstSnapshotForAccount(id)
            _state.update {
                it.copy(
                    currentBalanceMinor = first,
                    adjustDialog = AdjustBalanceDialogState(
                        newBalanceInput = MoneyFormat.formatAmountForEdit(first),
                    ),
                )
            }
        }
    }

    fun closeAdjustDialog() = _state.update { it.copy(adjustDialog = null) }

    fun onAdjustNewBalanceChange(value: String) = _state.update {
        it.copy(adjustDialog = it.adjustDialog?.copy(newBalanceInput = value))
    }

    fun confirmAdjustBalance() {
        val id = accountId ?: return
        val dialog = _state.value.adjustDialog ?: return
        val newBalance = MoneyFormat.parseAmountToMinor(dialog.newBalanceInput)
        if (newBalance == null) return
        val delta = newBalance - _state.value.currentBalanceMinor
        if (delta == 0L) {
            _state.update { it.copy(adjustDialog = null) }
            return
        }
        _state.update { it.copy(adjustDialog = dialog.copy(isSaving = true)) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val oldStr = MoneyFormat.formatAmountForEdit(_state.value.currentBalanceMinor)
            val newStr = MoneyFormat.formatAmountForEdit(newBalance)
            transactionRepository.add(
                io.github.jiro.expensetracker.data.local.TransactionEntity(
                    title = "Balance adjustment: $oldStr → $newStr",
                    amountMinor = delta,
                    currencyCode = _state.value.currency,
                    type = TransactionType.ADJUSTMENT.name,
                    accountId = id,
                    occurredAtEpochMillis = now,
                    createdAtEpochMillis = now,
                ),
            )
            _state.update { it.copy(adjustDialog = null) }
        }
    }

    fun save() {
        val s = _state.value
        val name = s.name.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(error = AccountFormError.NAME_REQUIRED) }
            return
        }
        val type = if (s.type == "OTHER" && s.customType.isNotBlank()) s.customType.trim() else s.type
        val currency = s.currency.trim().uppercase()
        if (currency.length != 3) {
            _state.update { it.copy(error = AccountFormError.CURRENCY_REQUIRED) }
            return
        }
        val opening = MoneyFormat.parseAmountToMinor(s.openingBalanceInput) ?: 0L

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = AccountEntity(
                id = accountId ?: 0L,
                name = name,
                type = type,
                icon = s.icon,
                color = s.color,
                currencyCode = currency,
                openingBalanceMinor = opening,
                createdAtEpochMillis = now,
                sortOrder = 0,
            )
            if (s.isEdit) {
                accountRepository.update(entity)
            } else {
                val newId = accountRepository.add(entity)
                if (newId == -1L) {
                    _state.update {
                        it.copy(isSaving = false, error = AccountFormError.NAME_DUPLICATE)
                    }
                    return@launch
                }
            }
            _state.update { it.copy(isSaving = false, saveComplete = true) }
        }
    }
}

/**
 * Helper: take the first emission of `observeWithBalances()` and return the
 * balance for [targetId]. Falls back to 0L if no row matches (shouldn't
 * happen for an existing account but guards against race conditions during
 * Edit screen hydration).
 */
private suspend fun Flow<List<AccountWithBalance>>.firstSnapshotForAccount(targetId: Long): Long {
    return this.first().firstOrNull { it.account.id == targetId }?.balanceMinor ?: 0L
}