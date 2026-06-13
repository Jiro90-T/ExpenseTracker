package io.github.jiro.expensetracker.ui.transactions

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.local.MoneyFormat
import java.util.Calendar
import java.util.TimeZone

/**
 * A date range for the Transactions filter. The five presets resolve to
 * `[fromMs, toMsExclusive)` ranges against the production "now" passed
 * to [filterTransactions]. [Custom] carries its own bounds and is
 * auto-swapped if the user picks from > to.
 */
sealed interface DateRangePreset {
    data object Any : DateRangePreset
    data object Last7Days : DateRangePreset
    data object Last30Days : DateRangePreset
    data object ThisMonth : DateRangePreset
    data object ThisYear : DateRangePreset
    data class Custom(val fromMs: Long, val toMsExclusive: Long) : DateRangePreset
}

/** The type-filter enum. `ALL` means no type filter. */
enum class TypeFilter { ALL, INCOME, EXPENSE }

/**
 * The four filter dimensions for the Transactions list. Default is
 * "all-empty" — [isEmpty] returns true. Any non-default value flips
 * [isEmpty] to false.
 */
data class TransactionFilters(
    val searchQuery: String = "",
    val categoryId: Long? = null,
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val dateRange: DateRangePreset = DateRangePreset.Any,
) {
    val isEmpty: Boolean
        get() = searchQuery.isEmpty() && categoryId == null
            && typeFilter == TypeFilter.ALL && dateRange is DateRangePreset.Any
}

/**
 * Pure filter. Applies the four dimensions to [rows] and returns the
 * filtered list in the same order. [allCategories] is needed for the
 * "search by category name" match. [nowMs] anchors the date presets.
 *
 *   - Search: case-insensitive substring match on title, note (if non-null),
 *     category name, and the formatted amount. Empty/blank query is a no-op.
 *   - Category: equality match. `null` is a no-op.
 *   - Type: equality match. `ALL` is a no-op.
 *   - Date range: resolves the preset to `[from, toExclusive)` and filters
 *     by `transaction.occurredAtEpochMillis`. `Custom(from, to)` auto-swaps
 *     so the range is non-empty.
 */
fun filterTransactions(
    rows: List<TransactionWithCategory>,
    filters: TransactionFilters,
    allCategories: List<CategoryEntity>,
    nowMs: Long,
): List<TransactionWithCategory> {
    val trimmedQuery = filters.searchQuery.trim()
    val hasQuery = trimmedQuery.isNotEmpty()
    val categoryNameById = allCategories.associate { it.id to it.name }
    val (rangeFrom, rangeToExclusive) = resolveDateRange(filters.dateRange, nowMs)

    return rows.filter { row ->
        val t = row.transaction

        // Search query: must match at least one of the searched fields.
        if (hasQuery) {
            val titleMatch = t.title.contains(trimmedQuery, ignoreCase = true)
            val noteMatch = t.note?.contains(trimmedQuery, ignoreCase = true) == true
            val categoryMatch = categoryNameById[t.categoryId]
                ?.contains(trimmedQuery, ignoreCase = true) == true
            val amountMatch = MoneyFormat.formatAmountForEdit(t.amountMinor)
                .contains(trimmedQuery, ignoreCase = true)
            if (!(titleMatch || noteMatch || categoryMatch || amountMatch)) return@filter false
        }

        // Category.
        if (filters.categoryId != null && t.categoryId != filters.categoryId) return@filter false

        // Type.
        when (filters.typeFilter) {
            TypeFilter.ALL -> Unit
            TypeFilter.INCOME -> if (t.type != "INCOME") return@filter false
            TypeFilter.EXPENSE -> if (t.type != "EXPENSE") return@filter false
        }

        // Date range. Custom(from, to) with from == to is a single-point range
        // that includes the point (inclusive on both ends). All other ranges
        // are half-open [from, toExclusive).
        val inRange = if (filters.dateRange is DateRangePreset.Custom &&
            filters.dateRange.fromMs == filters.dateRange.toMsExclusive
        ) {
            t.occurredAtEpochMillis in rangeFrom..rangeToExclusive
        } else {
            t.occurredAtEpochMillis in rangeFrom until rangeToExclusive
        }
        if (!inRange) return@filter false

        true
    }
}

private fun resolveDateRange(
    preset: DateRangePreset,
    nowMs: Long,
): Pair<Long, Long> = when (preset) {
    DateRangePreset.Any -> Long.MIN_VALUE to Long.MAX_VALUE
    DateRangePreset.Last7Days -> (nowMs - 7L * 86_400_000L) to Long.MAX_VALUE
    DateRangePreset.Last30Days -> (nowMs - 30L * 86_400_000L) to Long.MAX_VALUE
    DateRangePreset.ThisMonth -> startOfMonth(nowMs) to startOfNextMonth(nowMs)
    DateRangePreset.ThisYear -> startOfYear(nowMs) to startOfNextYear(nowMs)
    is DateRangePreset.Custom -> {
        if (preset.fromMs < preset.toMsExclusive) {
            preset.fromMs to preset.toMsExclusive
        } else {
            preset.toMsExclusive to preset.fromMs
        }
    }
}

private fun startOfMonth(epochMs: Long): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochMs
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

private fun startOfNextMonth(epochMs: Long): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochMs
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.MONTH, 1)
    }
    return cal.timeInMillis
}

private fun startOfYear(epochMs: Long): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochMs
        set(Calendar.MONTH, Calendar.JANUARY)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

private fun startOfNextYear(epochMs: Long): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochMs
        set(Calendar.MONTH, Calendar.JANUARY)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.YEAR, 1)
    }
    return cal.timeInMillis
}
