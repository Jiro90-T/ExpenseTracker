package io.github.jiro.expensetracker.ui.charts

import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import java.util.Calendar
import kotlin.math.abs

/**
 * User-selectable time window for the Trends tab. [monthsBack] is null for
 * YTD (year-to-date from Jan 1 of the current year) and 0 for All (full
 * history; no windowing, no prior period).
 */
enum class TrendsPeriod(val monthsBack: Int?, val labelRes: Int) {
    ThreeMonths(3, R.string.trends_period_3m),
    SixMonths(6, R.string.trends_period_6m),
    TwelveMonths(12, R.string.trends_period_12m),
    Ytd(null, R.string.trends_period_ytd),
    All(0, R.string.trends_period_all),
}

/**
 * Result of [computePeriodTrends] for a given period and "now". The current
 * and prior lists are in the same shape the line chart already consumes.
 * [prior] is null only for [TrendsPeriod.All] (no meaningful comparison).
 * [currentMonthMs] is null when there is nothing to mark (e.g. the current
 * window contains no data points).
 */
data class PeriodTrends(
    val current: List<MonthlyTrend>,
    val prior: List<MonthlyTrend>?,
    val delta: ComparisonDelta?,
    val currentMonthMs: Long?,
)

/**
 * Percent change of each series from prior period to current period. Each
 * field is null when the prior sum is exactly zero (the percent would be
 * infinite or undefined); the UI shows "—" in that case. Percent uses
 * `abs(prior.sum)` as the denominator to avoid sign-flips from dominating
 * the magnitude.
 */
data class ComparisonDelta(
    val incomePct: Double?,
    val expensePct: Double?,
    val netPct: Double?,
)

/**
 * Pure, JVM-testable. Computes the trend data for the [period] ending at
 * [nowMs], plus the immediately-prior period for comparison.
 *
 *   - 3M/6M/12M: current = the last N months ending at startOfMonth(nowMs).
 *     prior = the N months immediately before that (consecutive, non-
 *     overlapping).
 *   - YTD: current = Jan 1 of nowMs's year through startOfMonth(nowMs).
 *     prior = Jan 1 of nowMs's year - 1 through the same day-of-year.
 *   - All: current = all months with at least one transaction. prior = null.
 *
 * Months with no transactions inside the window are NOT zero-filled.
 */
fun computePeriodTrends(
    rows: List<TransactionWithCategory>,
    period: TrendsPeriod,
    nowMs: Long,
): PeriodTrends {
    if (rows.isEmpty()) {
        return PeriodTrends(emptyList(), emptyList(), null, null)
    }

    val byMonth = rows.groupBy { startOfMonth(it.transaction.occurredAtEpochMillis) }
    val labelFmt = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
    val allMonthStarts = byMonth.keys.sorted()

    // Resolve the current window's bounds.
    val (currentFrom, currentToExclusive) = when (period) {
        TrendsPeriod.All -> Long.MIN_VALUE to Long.MAX_VALUE
        TrendsPeriod.Ytd -> {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val year = cal.get(Calendar.YEAR)
            val from = startOfMonth(makeMs(year, Calendar.JANUARY, 1))
            val to = startOfMonth(nowMs) + 1L
            from to to
        }
        else -> {
            val n = period.monthsBack ?: 0
            val currentStart = startOfMonth(addMonths(nowMs, -(n - 1)))
            val currentEnd = startOfMonth(nowMs) + 1L
            currentStart to currentEnd
        }
    }

    val currentMonths = allMonthStarts.filter { it in currentFrom until currentToExclusive }
    val current = currentMonths.map { it.toMonthlyTrend(byMonth, labelFmt) }

    // Resolve the prior window (only for fixed-N and YTD).
    val (priorFrom, priorToExclusive) = when (period) {
        TrendsPeriod.All -> null to null
        TrendsPeriod.Ytd -> {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val prevYear = cal.get(Calendar.YEAR) - 1
            val from = startOfMonth(makeMs(prevYear, Calendar.JANUARY, 1))
            val to = startOfMonth(addYears(nowMs, -1)) + 1L
            from to to
        }
        else -> {
            val n = period.monthsBack ?: 0
            val priorStart = startOfMonth(addMonths(nowMs, -(2 * n - 1)))
            val priorEnd = startOfMonth(addMonths(nowMs, -(n - 1)))
            priorStart to priorEnd
        }
    }

    val prior: List<MonthlyTrend>? = if (priorFrom == null || priorToExclusive == null) {
        null
    } else {
        allMonthStarts.filter { it in priorFrom until priorToExclusive }
            .map { it.toMonthlyTrend(byMonth, labelFmt) }
    }

    // Deltas.
    val delta: ComparisonDelta? = if (prior == null) {
        null
    } else {
        val currentIncome = current.sumOf { it.incomeMinor }
        val priorIncome = prior.sumOf { it.incomeMinor }
        val currentExpense = current.sumOf { it.expenseMinor }
        val priorExpense = prior.sumOf { it.expenseMinor }
        val currentNet = current.sumOf { it.netMinor }
        val priorNet = prior.sumOf { it.netMinor }
        ComparisonDelta(
            incomePct = pct(currentIncome, priorIncome),
            expensePct = pct(currentExpense, priorExpense),
            netPct = pct(currentNet, priorNet),
        )
    }

    // Current month marker: the start-of-month of nowMs, if that month is
    // present in `current`. Otherwise null.
    val currentMonthMs = startOfMonth(nowMs).takeIf { it in currentFrom until currentToExclusive && it in currentMonths }

    return PeriodTrends(current, prior, delta, currentMonthMs)
}

private fun Long.toMonthlyTrend(
    byMonth: Map<Long, List<TransactionWithCategory>>,
    labelFmt: java.text.SimpleDateFormat,
): MonthlyTrend {
    var income = 0L
    var expense = 0L
    for (r in byMonth[this].orEmpty()) {
        when (r.transaction.type) {
            "INCOME" -> income += r.transaction.amountMinor
            "EXPENSE" -> expense += r.transaction.amountMinor
        }
    }
    return MonthlyTrend(
        monthStartMs = this,
        shortLabel = labelFmt.format(java.util.Date(this)),
        incomeMinor = income,
        expenseMinor = expense,
        netMinor = income - expense,
    )
}

private fun pct(current: Long, prior: Long): Double? {
    if (prior == 0L) return null
    return (current - prior).toDouble() / abs(prior.toDouble()) * 100.0
}

private fun startOfMonth(epochMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

private fun addMonths(epochMs: Long, delta: Int): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
    cal.add(Calendar.MONTH, delta)
    return cal.timeInMillis
}

private fun addYears(epochMs: Long, delta: Int): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
    cal.add(Calendar.YEAR, delta)
    return cal.timeInMillis
}

private fun makeMs(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance().apply {
        clear()
        set(year, month, day, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}
