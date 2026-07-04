package io.github.jiro.expensetracker.ui.statistics

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class CategorySpend(
    val categoryId: Long,
    val categoryName: String,
    val amountMinor: Long,
)

data class TopCategoriesResult(
    val monthLabel: String,
    val slices: List<CategorySpend>,
    val missingRateCount: Int,
)

data class SavingsAndAverage(
    val monthLabel: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long,
    val savingsRate: Float,
    val averageMonthlyExpenseMinor: Long,
    val topTransactionMinor: Long,
    val averageMonthlySampleMonths: Int,
)

data class DayOfWeekBucket(
    val isoDayOfWeek: Int,
    val amountMinor: Long,
)

data class YearOverYear(
    val currentWindowLabel: String,
    val previousWindowLabel: String,
    val currentExpenseMinor: Long,
    val previousExpenseMinor: Long,
    val percentChange: Float,
    val isNewSpending: Boolean,
)

object StatisticsCalculator {

    internal fun monthBounds(year: Int, month: Int): Pair<Long, Long> {
        require(month in 1..12) { "month must be 1..12, got $month" }
        val ym = YearMonth.of(year, month)
        val zone = ZoneId.systemDefault()
        val startMs = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }

    internal fun rangeLabel(startMs: Long, endMs: Long): String {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        // endMs is the exclusive upper bound (half-open interval). Step back one day in
        // calendar terms to get the inclusive last day of the window.
        val endDate = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate().minusDays(1)

        val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
        val monthFmt = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US)

        return when {
            startDate.year == endDate.year && startDate.month == endDate.month ->
                startDate.format(monthFmt)
            startDate.year == endDate.year ->
                "${startDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))} – ${endDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))}, ${endDate.year}"
            else ->
                "${startDate.format(fmt)} – ${endDate.format(fmt)}"
        }
    }

    internal fun subtractOneYear(ms: Long): Long {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        // LocalDate.minusYears auto-clamps Feb 29 to Feb 28 when stepping back to a non-leap year.
        return date.minusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun topCategories(
        txns: List<TransactionWithCategory>,
        cats: List<CategoryEntity>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        startMs: Long, endMs: Long,
    ): TopCategoriesResult {
        val catsById = cats.associateBy { it.id }
        val byCategory = mutableMapOf<Long, Long>()
        var missingRateCount = 0
        for (row in txns) {
            val t = row.transaction
            if (t.type != TransactionType.EXPENSE.name) continue
            if (t.occurredAtEpochMillis < startMs || t.occurredAtEpochMillis >= endMs) continue
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
            if (converted == null && t.currencyCode != homeCurrency) missingRateCount++
            val contribution = converted ?: t.amountMinor
            val cid = t.categoryId ?: continue
            byCategory[cid] = (byCategory[cid] ?: 0L) + contribution
        }
        val sorted = byCategory.entries.sortedByDescending { it.value }
        val top5 = sorted.take(5)
        val rest = sorted.drop(5)
        val slices = top5.map { (id, amt) ->
            val name = catsById[id]?.name ?: "Other"
            CategorySpend(categoryId = id, categoryName = name, amountMinor = amt)
        }
        val withOther = if (rest.isEmpty()) slices
        else slices + CategorySpend(categoryId = -1L, categoryName = "Other", amountMinor = rest.sumOf { it.value })
        return TopCategoriesResult(rangeLabel(startMs, endMs), withOther, missingRateCount)
    }

    fun savingsAndAverage(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        startMs: Long, endMs: Long,
    ): SavingsAndAverage {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val priorAnchor = YearMonth.of(startDate.year, startDate.monthValue)

        var incomeMinor = 0L
        var expenseMinor = 0L
        var topTransactionMinor = 0L

        for (row in txns) {
            val t = row.transaction
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates) ?: t.amountMinor
            if (t.occurredAtEpochMillis in startMs until endMs) {
                if (t.type == TransactionType.INCOME.name) incomeMinor += converted
                else if (t.type == TransactionType.EXPENSE.name) {
                    expenseMinor += converted
                    if (converted > topTransactionMinor) topTransactionMinor = converted
                }
            }
        }

        val netMinor = incomeMinor - expenseMinor
        val savingsRate = if (incomeMinor > 0L) {
            ((netMinor.toDouble()) / incomeMinor.toDouble()).toFloat().coerceIn(0f, 1f)
        } else 0f

        var sumPrior = 0L
        var monthsWithData = 0
        for (offset in 1..6) {
            val ym = priorAnchor.minusMonths(offset.toLong())
            val (s, e) = monthBounds(ym.year, ym.monthValue)
            var monthTotal = 0L
            for (row in txns) {
                val t = row.transaction
                if (t.type != TransactionType.EXPENSE.name) continue
                if (t.occurredAtEpochMillis in s until e) {
                    val c = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates) ?: t.amountMinor
                    monthTotal += c
                }
            }
            if (monthTotal > 0L) { sumPrior += monthTotal; monthsWithData++ }
        }
        val averageMonthlyExpenseMinor = if (monthsWithData >= 3) sumPrior / 6L else 0L

        return SavingsAndAverage(
            monthLabel = rangeLabel(startMs, endMs),
            incomeMinor = incomeMinor,
            expenseMinor = expenseMinor,
            netMinor = netMinor,
            savingsRate = savingsRate,
            averageMonthlyExpenseMinor = averageMonthlyExpenseMinor,
            topTransactionMinor = topTransactionMinor,
            averageMonthlySampleMonths = monthsWithData,
        )
    }

    fun dayOfWeekPattern(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        startMs: Long, endMs: Long,
    ): List<DayOfWeekBucket> {
        val zone = ZoneId.systemDefault()
        val sums = LongArray(8) // index 1..7
        for (row in txns) {
            val t = row.transaction
            if (t.type != TransactionType.EXPENSE.name) continue
            if (t.occurredAtEpochMillis < startMs || t.occurredAtEpochMillis >= endMs) continue
            val dow = Instant.ofEpochMilli(t.occurredAtEpochMillis).atZone(zone).toLocalDate().dayOfWeek.value
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates) ?: t.amountMinor
            sums[dow] += converted
        }
        return (1..7).map { DayOfWeekBucket(it, sums[it]) }
    }

    fun yearOverYear(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        currentStartMs: Long, currentEndMs: Long,
        priorStartMs: Long,   priorEndMs: Long,
    ): YearOverYear {
        fun sum(start: Long, end: Long): Long {
            var s = 0L
            for (row in txns) {
                val t = row.transaction
                if (t.type != TransactionType.EXPENSE.name) continue
                if (t.occurredAtEpochMillis !in start until end) continue
                val c = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates) ?: t.amountMinor
                s += c
            }
            return s
        }
        val currentExpenseMinor = sum(currentStartMs, currentEndMs)
        val previousExpenseMinor = sum(priorStartMs, priorEndMs)
        val percentChange = if (previousExpenseMinor > 0L) {
            ((currentExpenseMinor - previousExpenseMinor).toDouble() / previousExpenseMinor.toDouble()).toFloat()
        } else 0f
        val isNewSpending = previousExpenseMinor == 0L && currentExpenseMinor > 0L
        return YearOverYear(
            currentWindowLabel = rangeLabel(currentStartMs, currentEndMs),
            previousWindowLabel = rangeLabel(priorStartMs, priorEndMs),
            currentExpenseMinor = currentExpenseMinor,
            previousExpenseMinor = previousExpenseMinor,
            percentChange = percentChange,
            isNewSpending = isNewSpending,
        )
    }
}