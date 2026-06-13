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
 * Persists the user's [TransactionFilters] across app restarts. SharedPreferences
 * round-trips four keys — one per field. Mirrors [io.github.jiro.expensetracker.preferences.SettingsRepository].
 */
@Singleton
class FiltersRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _filters = MutableStateFlow(loadFilters())
    val filters: StateFlow<TransactionFilters> = _filters.asStateFlow()

    fun setFilters(filters: TransactionFilters) {
        if (_filters.value == filters) return
        prefs.edit()
            .putString(KEY_SEARCH_QUERY, filters.searchQuery)
            .putLong(KEY_CATEGORY_ID, filters.categoryId ?: CATEGORY_ID_ALL)
            .putString(KEY_TYPE_FILTER, filters.typeFilter.name)
            .putString(KEY_DATE_RANGE, encodeDateRange(filters.dateRange))
        _filters.value = filters
    }

    private fun loadFilters(): TransactionFilters = TransactionFilters(
        searchQuery = prefs.getString(KEY_SEARCH_QUERY, "").orEmpty(),
        categoryId = prefs.getLong(KEY_CATEGORY_ID, CATEGORY_ID_ALL)
            .takeIf { it != CATEGORY_ID_ALL },
        typeFilter = runCatching {
            TypeFilter.valueOf(prefs.getString(KEY_TYPE_FILTER, null) ?: TypeFilter.ALL.name)
        }.getOrDefault(TypeFilter.ALL),
        dateRange = decodeDateRange(prefs.getString(KEY_DATE_RANGE, null)),
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
        const val CATEGORY_ID_ALL = -1L
    }
}
