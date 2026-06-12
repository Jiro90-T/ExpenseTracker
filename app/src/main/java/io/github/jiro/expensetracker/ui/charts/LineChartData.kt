package io.github.jiro.expensetracker.ui.charts

import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One month in the line chart, with income/expense totals and a precomputed
 * net (= income - expense). `monthStartMs` is the local-time midnight at the
 * start of the month; `shortLabel` is the abbreviated month name (e.g. "Mar").
 */
data class MonthlyTrend(
    val monthStartMs: Long,
    val shortLabel: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long,
)

/**
 * Computes the trend-line data from raw transactions. Groups transactions by
 * their local-time month and emits one [MonthlyTrend] per month that has at
 * least one transaction, in chronological (oldest-first) order. Months with
 * no transactions are NOT zero-filled — the caller decides how to anchor the
 * window. Pure, JVM-testable.
 */
fun computeMonthlyTrends(
    rows: List<TransactionWithCategory>,
): List<MonthlyTrend> {
    if (rows.isEmpty()) return emptyList()
    val labelFmt = SimpleDateFormat("MMM", Locale.getDefault())
    val byMonth = rows.groupBy { startOfMonth(it.transaction.occurredAtEpochMillis) }
    return byMonth.keys.sorted().map { monthStart ->
        var income = 0L
        var expense = 0L
        for (r in byMonth[monthStart].orEmpty()) {
            when (r.transaction.type) {
                "INCOME" -> income += r.transaction.amountMinor
                "EXPENSE" -> expense += r.transaction.amountMinor
            }
        }
        MonthlyTrend(
            monthStartMs = monthStart,
            shortLabel = labelFmt.format(Date(monthStart)),
            incomeMinor = income,
            expenseMinor = expense,
            netMinor = income - expense,
        )
    }
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
