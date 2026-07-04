package io.github.jiro.expensetracker.ui.statistics

/**
 * One observation surfaced on the Insights tab. Each subclass carries the
 * raw numbers needed to render its headline + supporting text in the UI.
 * The UI ([InsightCard]) is responsible for resolving string resources and
 * formatting monetary values; the calculator does not touch Android
 * resources.
 */
sealed class Insight {
    abstract val priority: Int

    enum class Direction { UP, DOWN, NEW, UNCHANGED }

    /**
     * The single category with the largest absolute spend change vs the
     * prior calendar month. All amounts are FX-converted to [currencyCode]
     * (the user's home currency).
     */
    data class CategoryDelta(
        val categoryName: String,
        val direction: Direction,        // UP, DOWN, or NEW (previous == 0)
        val percentChange: Float,        // 0f when direction == NEW
        val currentMinor: Long,
        val previousMinor: Long,
        val currencyCode: String,
    ) : Insight() { override val priority = 10 }

    /**
     * 90-day rolling window. [weekendPercent] = weekendMinor / (weekend +
     * weekday), already rounded to two decimals at compute time.
     */
    data class WeekendVsWeekday(
        val weekendMinor: Long,
        val weekdayMinor: Long,
        val weekendPercent: Float,        // 0..1
        val currencyCode: String,
    ) : Insight() { override val priority = 20 }

    /**
     * Savings rate delta between current and prior calendar month, in
     * percentage points (1.0 == 100pp — UI multiplies by 100 for display).
     */
    data class SavingsTrend(
        val currentRate: Float,          // 0..1
        val previousRate: Float,         // 0..1
        val direction: Direction,        // UP, DOWN, or UNCHANGED
    ) : Insight() { override val priority = 30 }

    /**
     * Largest single expense this calendar month. [amountMinor] is in
     * [currencyCode] — the transaction's native currency, NOT the home
     * currency — so the headline shows what the user actually paid.
     */
    data class TopExpenseSpotlight(
        val amountMinor: Long,
        val currencyCode: String,
        val title: String,               // "—" when blank
        val dateLabel: String,           // "MMM d"
    ) : Insight() { override val priority = 40 }
}
