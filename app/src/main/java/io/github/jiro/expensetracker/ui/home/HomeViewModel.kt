package io.github.jiro.expensetracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.charts.MonthlyTotals
import io.github.jiro.expensetracker.ui.charts.computeMonthlyTotals
import io.github.jiro.expensetracker.ui.transactions.DateRangePreset
import io.github.jiro.expensetracker.ui.transactions.FiltersRepository
import io.github.jiro.expensetracker.ui.transactions.TransactionFilters
import io.github.jiro.expensetracker.ui.transactions.TypeFilter
import io.github.jiro.expensetracker.ui.transactions.filterTransactions
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
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
    /** Currency all amounts are denominated in (i.e. the home currency). */
    val homeCurrency: String = "USD",
    /** Number of transactions whose currency had no rate to [homeCurrency]. */
    val missingRateCount: Int = 0,
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
    private val settingsRepository: SettingsRepository,
    private val categoryRepository: CategoryRepository,
    private val filtersRepository: FiltersRepository,
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

    val summary: StateFlow<DashboardSummary> = combine(
        periodTransactions,
        settingsRepository.homeCurrency,
        settingsRepository.fxRates,
    ) { rows, home, rates ->
        computeDashboardSummary(rows, home, rates)
    }.stateIn(
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

    /** All categories, used by the Transactions tab's category dropdown and filter. */
    val allCategories: StateFlow<List<CategoryEntity>> = categoryRepository
        .observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** The current filter state, sourced from FiltersRepository. */
    val filters: StateFlow<TransactionFilters> = filtersRepository.filters

    /** All transactions filtered by the current [filters]. */
    val filteredTransactions: StateFlow<List<TransactionWithCategory>> =
        combine(
            repository.observeAll(),
            filters,
            allCategories,
        ) { rows, f, cats -> Triple(rows, f, cats) }
            .map { (rows, f, cats) ->
                filterTransactions(rows, f, cats, nowMs = System.currentTimeMillis())
            }
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

    fun setFilters(filters: TransactionFilters) {
        filtersRepository.setFilters(filters)
    }

    fun setSearchQuery(q: String) = setFilters(filters.value.copy(searchQuery = q))
    fun setCategoryFilter(id: Long?) = setFilters(filters.value.copy(categoryId = id))
    fun setTypeFilter(t: TypeFilter) = setFilters(filters.value.copy(typeFilter = t))
    fun setDateRange(d: DateRangePreset) = setFilters(filters.value.copy(dateRange = d))
    fun clearFilters() = filtersRepository.setFilters(TransactionFilters())
}

/**
 * Pure function: aggregates a (period-filtered) list of joined rows into a summary.
 * Top expense categories returns at most [topN] entries; if more exist, a synthetic
 * "Others" bucket is appended with the rolled-up remainder.
 *
 * All amounts are normalised to [homeCurrency] using [fxRates] before
 * aggregation. Transactions whose currency has no rate to [homeCurrency]
 * are converted 1:1 and counted in [missingRateCount] so the UI can
 * show a warning.
 */
fun computeDashboardSummary(
    rows: List<TransactionWithCategory>,
    homeCurrency: String = "USD",
    fxRates: Map<String, Double> = emptyMap(),
    topN: Int = 5,
): DashboardSummary {
    var income = 0L
    var expense = 0L
    var missingRateCount = 0
    val byCategory = mutableMapOf<Long, CategoryBreakdown>()

    for (row in rows) {
        val t = row.transaction
        val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
            ?: run {
                missingRateCount += 1
                t.amountMinor
            }
        when (TransactionType.fromStorage(t.type)) {
            TransactionType.INCOME -> income += converted
            TransactionType.EXPENSE -> {
                expense += converted
                val existing = byCategory[t.categoryId]
                if (existing == null) {
                    byCategory[t.categoryId] = CategoryBreakdown(
                        categoryId = t.categoryId,
                        categoryName = row.category.name,
                        amountMinor = converted,
                    )
                } else {
                    byCategory[t.categoryId] = existing.copy(
                        amountMinor = existing.amountMinor + converted,
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
        homeCurrency = homeCurrency,
        missingRateCount = missingRateCount,
    )
}
