package io.github.jiro.expensetracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.ui.charts.MonthlyTotals
import io.github.jiro.expensetracker.ui.charts.computeMonthlyTotals
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One-shot state describing the most recently deleted row so the UI can show
 * a snackbar with an Undo affordance. Cleared on undo, dismiss, or replace.
 */
data class UndoState(val row: TransactionWithCategory)

/** Summary of income/expense for the dashboard. */
data class DashboardSummary(
    val incomeMinor: Long = 0L,
    val expenseMinor: Long = 0L,
    val balanceMinor: Long = 0L,
    /** Top expense categories by amount, descending. "Others" is the rolled-up remainder. */
    val topExpenseCategories: List<CategoryBreakdown> = emptyList(),
    val totalExpenseForBreakdownMinor: Long = 0L,
    val transactionCount: Int = 0,
)

data class CategoryBreakdown(
    val categoryId: Long,
    val categoryName: String,
    val amountMinor: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val _period = MutableStateFlow<Period>(Period.currentMonth())
    val period: StateFlow<Period> = _period.asStateFlow()

    /**
     * Period-filtered transactions, used to compute [summary]. The bar chart
     * is intentionally period-independent (last 6 months, all-time) so the
     * trend view stays stable as the user steps the period.
     */
    private val periodTransactions: StateFlow<List<TransactionWithCategory>> = _period
        .flatMapLatest { p ->
            p.monthBounds()?.let { (start, end) -> repository.observeInRange(start, end) }
                ?: repository.observeAll()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** All transactions, unfiltered. Used by the Transactions tab. */
    val allTransactions: StateFlow<List<TransactionWithCategory>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val summary: StateFlow<DashboardSummary> = periodTransactions
        .map { computeDashboardSummary(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DashboardSummary(),
        )

    val monthlyTotals: StateFlow<List<MonthlyTotals>> = repository.observeAll()
        .map { computeMonthlyTotals(it, monthsBack = 6) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _undo = MutableStateFlow<UndoState?>(null)
    val undo: StateFlow<UndoState?> = _undo.asStateFlow()

    fun setPeriod(period: Period) {
        _period.value = period
    }

    fun stepMonth(direction: Int) {
        val current = _period.value
        if (current !is Period.Month) return
        _period.value = if (direction < 0) current.previous() else current.next()
    }

    fun delete(row: TransactionWithCategory) {
        viewModelScope.launch {
            repository.delete(row.transaction)
            _undo.value = UndoState(row)
        }
    }

    fun undoDelete() {
        val pending = _undo.value ?: return
        viewModelScope.launch {
            repository.restore(pending.row.transaction)
            _undo.value = null
        }
    }

    fun dismissUndo() {
        _undo.value = null
    }
}

/**
 * Pure function: aggregates a (period-filtered) list of joined rows into a summary.
 * Top expense categories returns at most [topN] entries; if more exist, a synthetic
 * "Others" bucket is appended with the rolled-up remainder.
 */
fun computeDashboardSummary(
    rows: List<TransactionWithCategory>,
    topN: Int = 5,
): DashboardSummary {
    var income = 0L
    var expense = 0L
    val byCategory = mutableMapOf<Long, CategoryBreakdown>()

    for (row in rows) {
        val t = row.transaction
        when (TransactionType.fromStorage(t.type)) {
            TransactionType.INCOME -> income += t.amountMinor
            TransactionType.EXPENSE -> {
                expense += t.amountMinor
                val existing = byCategory[t.categoryId]
                if (existing == null) {
                    byCategory[t.categoryId] = CategoryBreakdown(
                        categoryId = t.categoryId,
                        categoryName = row.category.name,
                        amountMinor = t.amountMinor,
                    )
                } else {
                    byCategory[t.categoryId] = existing.copy(
                        amountMinor = existing.amountMinor + t.amountMinor,
                    )
                }
            }
        }
    }

    val sorted = byCategory.values.sortedByDescending { it.amountMinor }
    val (top, rest) = if (sorted.size > topN) {
        sorted.take(topN) to sorted.drop(topN)
    } else {
        sorted to emptyList()
    }
    val topWithOthers = if (rest.isNotEmpty()) {
        top + CategoryBreakdown(
            categoryId = -1L,
            categoryName = "Others",
            amountMinor = rest.sumOf { it.amountMinor },
        )
    } else top

    return DashboardSummary(
        incomeMinor = income,
        expenseMinor = expense,
        balanceMinor = income - expense,
        topExpenseCategories = topWithOthers,
        totalExpenseForBreakdownMinor = expense,
        transactionCount = rows.size,
    )
}
