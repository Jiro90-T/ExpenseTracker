package io.github.jiro.expensetracker.ui.statistics

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
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
    val currentMonthLabel: String,
    val previousMonthLabel: String,
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

    internal fun monthLabel(nowMs: Long): String {
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$month ${date.year}"
    }

    fun topCategories(
        txns: List<TransactionWithCategory>,
        cats: List<CategoryEntity>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        nowMs: Long,
    ): TopCategoriesResult {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val (monthStart, monthEnd) = monthBounds(today.year, today.monthValue)
        val catsById = cats.associateBy { it.id }

        val byCategory = mutableMapOf<Long, Long>()
        var missingRateCount = 0

        for (row in txns) {
            val t = row.transaction
            if (t.type != TransactionType.EXPENSE.name) continue
            if (t.occurredAtEpochMillis < monthStart || t.occurredAtEpochMillis >= monthEnd) continue
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
            if (converted == null && t.currencyCode != homeCurrency) {
                missingRateCount++
            }
            val contribution = converted ?: t.amountMinor
            byCategory[t.categoryId] = (byCategory[t.categoryId] ?: 0L) + contribution
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

        return TopCategoriesResult(monthLabel(nowMs), withOther, missingRateCount)
    }

    fun savingsAndAverage(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        nowMs: Long,
    ): SavingsAndAverage {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val (monthStart, monthEnd) = monthBounds(today.year, today.monthValue)

        var incomeMinor = 0L
        var expenseMinor = 0L
        var topTransactionMinor = 0L

        for (row in txns) {
            val t = row.transaction
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates) ?: t.amountMinor
            if (t.occurredAtEpochMillis in monthStart until monthEnd) {
                if (t.type == TransactionType.INCOME.name) {
                    incomeMinor += converted
                } else if (t.type == TransactionType.EXPENSE.name) {
                    expenseMinor += converted
                    if (converted > topTransactionMinor) topTransactionMinor = converted
                }
            }
        }

        val netMinor = incomeMinor - expenseMinor
        val savingsRate = if (incomeMinor > 0L) {
            ((netMinor.toDouble()) / incomeMinor.toDouble()).toFloat().coerceIn(0f, 1f)
        } else 0f

        // Average over the 6 calendar months immediately preceding [today].
        var sumPrior = 0L
        var monthsWithData = 0
        for (offset in 1..6) {
            val ym = YearMonth.of(today.year, today.monthValue).minusMonths(offset.toLong())
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
            if (monthTotal > 0L) {
                sumPrior += monthTotal
                monthsWithData++
            }
        }
        val averageMonthlyExpenseMinor = if (monthsWithData >= 3) sumPrior / 6L else 0L

        return SavingsAndAverage(
            monthLabel = monthLabel(nowMs),
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
        nowMs: Long,
    ): List<DayOfWeekBucket> {
        val zone = ZoneId.systemDefault()
        val windowStart = nowMs - 90L * 24L * 3600L * 1000L
        val sums = LongArray(8) // index 1..7
        for (row in txns) {
            val t = row.transaction
            if (t.type != TransactionType.EXPENSE.name) continue
            if (t.occurredAtEpochMillis < windowStart || t.occurredAtEpochMillis > nowMs) continue
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
        nowMs: Long,
    ): YearOverYear {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val (curStart, curEnd) = monthBounds(today.year, today.monthValue)
        val previous = YearMonth.of(today.year - 1, today.monthValue)
        val (prevStart, prevEnd) = monthBounds(previous.year, previous.monthValue)

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

        val currentExpenseMinor = sum(curStart, curEnd)
        val previousExpenseMinor = sum(prevStart, prevEnd)
        val percentChange = if (previousExpenseMinor > 0L) {
            ((currentExpenseMinor - previousExpenseMinor).toDouble() / previousExpenseMinor.toDouble()).toFloat()
        } else 0f
        val isNewSpending = previousExpenseMinor == 0L && currentExpenseMinor > 0L

        return YearOverYear(
            currentMonthLabel = monthLabel(nowMs),
            previousMonthLabel = monthLabel(
                previous.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            ),
            currentExpenseMinor = currentExpenseMinor,
            previousExpenseMinor = previousExpenseMinor,
            percentChange = percentChange,
            isNewSpending = isNewSpending,
        )
    }
}
