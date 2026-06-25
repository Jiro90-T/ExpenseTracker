package io.github.jiro.expensetracker.ui.charts

import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A single month in the bar chart, with income and expense totals in minor units. */
data class MonthlyTotals(
    val monthStartMs: Long,
    val shortLabel: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
)

/**
 * Computes income/expense totals grouped by month, returning the most recent
 * [monthsBack] months in chronological order (oldest first, so the chart reads
 * left-to-right as time progresses). Months with no transactions are zero-filled.
 */
fun computeMonthlyTotals(
    rows: List<TransactionWithCategory>,
    monthsBack: Int = 6,
    todayMs: Long = System.currentTimeMillis(),
): List<MonthlyTotals> {
    val byMonth = rows.groupBy { startOfMonth(it.transaction.occurredAtEpochMillis) }
    val labelFmt = SimpleDateFormat("MMM", Locale.getDefault())
    val months = mutableListOf<MonthlyTotals>()
    val cursor = Calendar.getInstance().apply {
        timeInMillis = startOfMonth(todayMs)
    }
    repeat(monthsBack) {
        val monthStart = cursor.timeInMillis
        val txns = byMonth[monthStart].orEmpty()
        var income = 0L
        var expense = 0L
        for (r in txns) {
            when (TransactionType.fromStorage(r.transaction.type)) {
                TransactionType.INCOME -> income += r.transaction.amountMinor
                TransactionType.EXPENSE -> expense += r.transaction.amountMinor
                // TRANSFER and ADJUSTMENT don't contribute to income/expense totals.
                else -> Unit
            }
        }
        months.add(
            0, // prepend; we iterate from latest backward, so prepend to get oldest-first
            MonthlyTotals(
                monthStartMs = monthStart,
                shortLabel = labelFmt.format(Date(monthStart)),
                incomeMinor = income,
                expenseMinor = expense,
            ),
        )
        cursor.add(Calendar.MONTH, -1)
    }
    return months
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
