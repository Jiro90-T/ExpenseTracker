package io.github.jiro.expensetracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.export.CsvExporter
import io.github.jiro.expensetracker.ui.charts.MonthlyTotals
import io.github.jiro.expensetracker.ui.charts.computeMonthlyTotals
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val _period = MutableStateFlow<Period>(Period.currentMonth())
    val period: StateFlow<Period> = _period.asStateFlow()

    val transactions: StateFlow<List<TransactionWithCategory>> = _period
        .flatMapLatest { p ->
            p.monthBounds()?.let { (start, end) -> repository.observeInRange(start, end) }
                ?: repository.observeAll()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val summary: StateFlow<DashboardSummary> = transactions
        .map { computeDashboardSummary(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DashboardSummary(),
        )

    /**
     * Last 6 months of income/expense totals, derived from ALL transactions
     * (not period-filtered) — the bar chart is a trend view, independent of
     * the user's current period filter.
     */
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

    /**
     * Builds a CSV for the current period using the latest transactions snapshot.
     * Returns the CSV string. Empty list -> CSV with header only.
     */
    suspend fun buildCsvForCurrentPeriod(): String {
        val rows = transactions.first()
        return CsvExporter.toCsv(rows)
    }
}
