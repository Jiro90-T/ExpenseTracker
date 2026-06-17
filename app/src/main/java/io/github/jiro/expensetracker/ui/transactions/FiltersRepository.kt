package io.github.jiro.expensetracker.ui.transactions

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's [TransactionFilters] and [TransactionSort] across app
 * restarts. SharedPreferences round-trips the keys. Mirrors
 * [io.github.jiro.expensetracker.preferences.SettingsRepository].
 */
@Singleton
class FiltersRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _filters = MutableStateFlow(loadFilters())
    val filters: StateFlow<TransactionFilters> = _filters.asStateFlow()

    private val _sort = MutableStateFlow(loadSort())
    val sort: StateFlow<TransactionSort> = _sort.asStateFlow()

    fun setFilters(filters: TransactionFilters) {
        if (_filters.value == filters) return
        prefs.edit()
            .putString(KEY_SEARCH_QUERY, filters.searchQuery)
            .putLong(KEY_CATEGORY_ID, filters.categoryId ?: CATEGORY_ID_ALL)
            .putString(KEY_TYPE_FILTER, filters.typeFilter.name)
            .putString(KEY_DATE_RANGE, encodeDateRange(filters.dateRange))
            .putLong(KEY_FILTER_MIN_AMOUNT, filters.minAmount ?: LONG_MIN_VALUE)
            .putLong(KEY_FILTER_MAX_AMOUNT, filters.maxAmount ?: LONG_MIN_VALUE)
        _filters.value = filters
    }

    fun setSort(sort: TransactionSort) {
        if (_sort.value == sort) return
        prefs.edit()
            .putString(KEY_SORT_FIELD, sort.field.name)
            .putString(KEY_SORT_DIRECTION, sort.direction.name)
        _sort.value = sort
    }

    private fun loadFilters(): TransactionFilters = TransactionFilters(
        searchQuery = prefs.getString(KEY_SEARCH_QUERY, "").orEmpty(),
        categoryId = prefs.getLong(KEY_CATEGORY_ID, CATEGORY_ID_ALL)
            .takeIf { it != CATEGORY_ID_ALL },
        typeFilter = runCatching {
            TypeFilter.valueOf(prefs.getString(KEY_TYPE_FILTER, null) ?: TypeFilter.ALL.name)
        }.getOrDefault(TypeFilter.ALL),
        dateRange = decodeDateRange(prefs.getString(KEY_DATE_RANGE, null)),
        minAmount = prefs.getLong(KEY_FILTER_MIN_AMOUNT, LONG_MIN_VALUE)
            .takeIf { it != LONG_MIN_VALUE },
        maxAmount = prefs.getLong(KEY_FILTER_MAX_AMOUNT, LONG_MIN_VALUE)
            .takeIf { it != LONG_MIN_VALUE },
    )

    private fun loadSort(): TransactionSort = TransactionSort(
        field = runCatching {
            SortField.valueOf(prefs.getString(KEY_SORT_FIELD, null) ?: SortField.DATE.name)
        }.getOrDefault(SortField.DATE),
        direction = runCatching {
            SortDirection.valueOf(prefs.getString(KEY_SORT_DIRECTION, null) ?: SortDirection.DESC.name)
        }.getOrDefault(SortDirection.DESC),
    )

    private fun encodeDateRange(preset: DateRangePreset): String = when (preset) {
        DateRangePreset.Any -> "Any"
        DateRangePreset.Last7Days -> "Last7Days"
        DateRangePreset.Last30Days -> "Last30Days"
        DateRangePreset.ThisMonth -> "ThisMonth"
        DateRangePreset.ThisYear -> "ThisYear"
        is DateRangePreset.Custom -> "Custom|${preset.fromMs}|${preset.toMsExclusive}"
    }

    private fun decodeDateRange(stored: String?): DateRangePreset {
        if (stored == null) return DateRangePreset.Any
        val parts = stored.split("|")
        return when (parts[0]) {
            "Any" -> DateRangePreset.Any
            "Last7Days" -> DateRangePreset.Last7Days
            "Last30Days" -> DateRangePreset.Last30Days
            "ThisMonth" -> DateRangePreset.ThisMonth
            "ThisYear" -> DateRangePreset.ThisYear
            "Custom" -> if (parts.size == 3) {
                val from = parts[1].toLongOrNull() ?: return DateRangePreset.Any
                val to = parts[2].toLongOrNull() ?: return DateRangePreset.Any
                DateRangePreset.Custom(from, to)
            } else {
                DateRangePreset.Any
            }
            else -> DateRangePreset.Any
        }
    }

    companion object {
        const val PREFS_NAME = "expense_tracker_filters"
        const val KEY_SEARCH_QUERY = "filters.searchQuery"
        const val KEY_CATEGORY_ID = "filters.categoryId"
        const val KEY_TYPE_FILTER = "filters.typeFilter"
        const val KEY_DATE_RANGE = "filters.dateRange"
        const val KEY_FILTER_MIN_AMOUNT = "filters.minAmount"
        const val KEY_FILTER_MAX_AMOUNT = "filters.maxAmount"
        const val KEY_SORT_FIELD = "sort.field"
        const val KEY_SORT_DIRECTION = "sort.direction"
        const val CATEGORY_ID_ALL = -1L
        const val LONG_MIN_VALUE = Long.MIN_VALUE
    }
}
