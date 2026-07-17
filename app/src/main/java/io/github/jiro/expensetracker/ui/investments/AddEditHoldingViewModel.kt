package io.github.jiro.expensetracker.ui.investments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.navigation.Routes
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HoldingFormError {
    SYMBOL_REQUIRED, SYMBOL_TOO_LONG,
    QUANTITY_INVALID, COST_INVALID, CURRENCY_REQUIRED,
}

data class AddEditHoldingUiState(
    val isEdit: Boolean = false,
    val symbol: String = "",
    val quantityInput: String = "",
    val costBasisInput: String = "",
    val currency: String = "",
    val error: HoldingFormError? = null,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
)

@HiltViewModel
class AddEditHoldingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val holdingDao: InvestmentHoldingDao,
) : ViewModel() {

    private val accountId: Long = savedStateHandle
        .get<Long>(Routes.INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID) ?: -1L
    private val holdingId: Long? = savedStateHandle
        .get<Long>(Routes.INVESTMENT_HOLDING_EDIT_ARG_HOLDING_ID)
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(
        AddEditHoldingUiState(isEdit = holdingId != null),
    )
    val state: StateFlow<AddEditHoldingUiState> = _state.asStateFlow()

    init {
        if (holdingId != null) {
            viewModelScope.launch {
                val existing = holdingDao.findById(holdingId) ?: return@launch
                _state.update {
                    it.copy(
                        symbol = existing.symbol,
                        quantityInput = existing.quantity.toString(),
                        costBasisInput = MoneyFormat.formatAmountForEdit(existing.costBasisMinor),
                        currency = existing.currencyCode,
                        isLoaded = true,
                    )
                }
            }
        } else {
            _state.update { it.copy(isLoaded = true) }
        }
    }

    fun onSymbolChange(value: String) = _state.update {
        it.copy(symbol = value.uppercase().trim(), error = null)
    }
    fun onQuantityChange(value: String) = _state.update {
        it.copy(quantityInput = value, error = null)
    }
    fun onCostBasisChange(value: String) = _state.update {
        it.copy(costBasisInput = value, error = null)
    }
    fun onCurrencyChange(value: String) = _state.update {
        it.copy(currency = value.uppercase().trim(), error = null)
    }

    /** Currency inference from the symbol suffix. Called from save() if the
     *  user hasn't manually set the currency field. */
    private fun inferCurrency(symbol: String): String = when {
        symbol.endsWith(".T") -> "JPY"
        else -> "USD"
    }

    fun save() {
        val s = _state.value
        val symbol = s.symbol.trim().uppercase()
        if (symbol.isEmpty()) {
            _state.update { it.copy(error = HoldingFormError.SYMBOL_REQUIRED) }; return
        }
        if (symbol.length > MAX_SYMBOL_LEN) {
            _state.update { it.copy(error = HoldingFormError.SYMBOL_TOO_LONG) }; return
        }
        val qty = s.quantityInput.trim().toDoubleOrNull()
        if (qty == null || qty <= 0.0) {
            _state.update { it.copy(error = HoldingFormError.QUANTITY_INVALID) }; return
        }
        val cost = MoneyFormat.parseAmountToMinor(s.costBasisInput)
        if (cost == null) {
            _state.update { it.copy(error = HoldingFormError.COST_INVALID) }; return
        }
        val currency = if (s.currency.isNotBlank()) s.currency else inferCurrency(symbol)

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (holdingId != null) {
                val existing = holdingDao.findById(holdingId) ?: return@launch
                holdingDao.update(existing.copy(
                    symbol = symbol,
                    quantity = qty,
                    costBasisMinor = cost,
                    currencyCode = currency,
                ))
            } else {
                holdingDao.insert(InvestmentHoldingEntity(
                    accountId = accountId,
                    symbol = symbol,
                    quantity = qty,
                    costBasisMinor = cost,
                    currencyCode = currency,
                    createdAtEpochMillis = now,
                ))
            }
            _state.update { it.copy(isSaving = false, saveComplete = true) }
        }
    }

    companion object {
        const val MAX_SYMBOL_LEN = 12
    }
}
