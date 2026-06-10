package io.github.jiro.expensetracker.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.BudgetEntity
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.budget.computeSpentByCategory
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.preferences.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row per expense category, for the current month. */
data class BudgetRowUiState(
    val categoryId: Long,
    val categoryName: String,
    val limitMinor: Long?,
    val spentMinor: Long,
    val isOverspent: Boolean,
)

data class BudgetScreenUiState(
    val monthLabel: String,
    val homeCurrency: String,
    val rows: List<BudgetRowUiState>,
    val missingRateCount: Int,
    val isLoaded: Boolean = false,
)

/** Transient state for the edit dialog. `null` when the dialog is closed. */
data class BudgetEditDialogState(
    val categoryId: Long,
    val categoryName: String,
    val currentLimitMinor: Long?,
    val homeCurrency: String,
    val amountInput: String = "",
    val isInvalid: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val monthStart: Long = BudgetRepository.currentMonthStart()

    private val periodTransactions = transactionRepository.observeAll()
        .map { all -> all.filter { it.transaction.occurredAtEpochMillis >= monthStart } }

    private val uiState: StateFlow<BudgetScreenUiState> = combine(
        categoryRepository.observeByType(TransactionType.EXPENSE),
        budgetRepository.observeByMonth(monthStart),
        periodTransactions,
        settingsRepository.homeCurrency,
        settingsRepository.fxRates,
    ) { categories, budgets, transactions, home, rates ->
        val spent = computeSpentByCategory(
            rows = transactions,
            bounds = monthStart until BudgetRepository.nextMonthStart(monthStart),
            homeCurrency = home,
            fxRates = rates,
        )
        val budgetByCategory = budgets.associate { it.categoryId to it.amountMinor }
        val rows = categories
            .sortedBy { it.name }
            .map { cat ->
                val limit = budgetByCategory[cat.id]
                val spentForCat = spent.byCategoryMinor[cat.id] ?: 0L
                BudgetRowUiState(
                    categoryId = cat.id,
                    categoryName = cat.name,
                    limitMinor = limit,
                    spentMinor = spentForCat,
                    isOverspent = limit != null && spentForCat > limit,
                )
            }
        BudgetScreenUiState(
            monthLabel = monthLabel(monthStart),
            homeCurrency = home,
            rows = rows,
            missingRateCount = spent.missingRateCount,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BudgetScreenUiState(
            monthLabel = monthLabel(monthStart),
            homeCurrency = "USD",
            rows = emptyList(),
            missingRateCount = 0,
        ),
    )

    val state: StateFlow<BudgetScreenUiState> = uiState

    private val _editDialog = MutableStateFlow<BudgetEditDialogState?>(null)
    val editDialog: StateFlow<BudgetEditDialogState?> = _editDialog.asStateFlow()

    fun openEdit(categoryId: Long) {
        val row = uiState.value.rows.firstOrNull { it.categoryId == categoryId } ?: return
        _editDialog.value = BudgetEditDialogState(
            categoryId = categoryId,
            categoryName = row.categoryName,
            currentLimitMinor = row.limitMinor,
            homeCurrency = uiState.value.homeCurrency,
            amountInput = row.limitMinor?.let { io.github.jiro.expensetracker.data.local.MoneyFormat.formatAmountForEdit(it) }.orEmpty(),
        )
    }

    fun onAmountInputChange(value: String) {
        _editDialog.update { it?.copy(amountInput = value, isInvalid = false) }
    }

    fun closeEdit() {
        _editDialog.value = null
    }

    /** Parses the current dialog input. If valid, persists and closes; if invalid, marks the dialog invalid. */
    fun submitEdit() {
        val dialog = _editDialog.value ?: return
        val minor = io.github.jiro.expensetracker.data.local.MoneyFormat.parseAmountToMinor(dialog.amountInput)
        if (minor == null || minor <= 0) {
            _editDialog.update { it?.copy(isInvalid = true) }
            return
        }
        setLimit(dialog.categoryId, minor)
        _editDialog.value = null
    }

    fun setLimit(categoryId: Long, amountMinor: Long) {
        if (amountMinor <= 0) return
        viewModelScope.launch {
            budgetRepository.upsert(
                BudgetEntity(
                    categoryId = categoryId,
                    monthStartEpochMs = monthStart,
                    amountMinor = amountMinor,
                ),
            )
        }
    }

    fun clearLimit(categoryId: Long) {
        viewModelScope.launch {
            budgetRepository.deleteByKey(categoryId, monthStart)
        }
        // If the dialog for this category is open, close it.
        if (_editDialog.value?.categoryId == categoryId) _editDialog.value = null
    }

    companion object {
        fun monthLabel(monthStart: Long): String =
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(monthStart))
    }
}

/** Re-export the category entity so callers don't have to import it just to render rows. */
typealias BudgetCategory = CategoryEntity
