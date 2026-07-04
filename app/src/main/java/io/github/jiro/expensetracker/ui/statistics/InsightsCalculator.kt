package io.github.jiro.expensetracker.ui.statistics

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure-function calculator that turns a list of transactions + categories
 * + currency context into a list of [Insight]s for the Insights tab.
 *
 * Each public function returns null when the input is too sparse to make a
 * meaningful claim. [compute] composes the four helpers, drops nulls, and
 * returns the survivors sorted by [Insight.priority] ascending.
 *
 * FX-conversion fallback mirrors Phase 2.13: when [FxConverter.convertMinor]
 * returns null (rate missing), use the raw `amountMinor` instead. Insights
 * do NOT surface a `missingRateCount` chip — the same policy as Phase 2.13
 * keeps the implementation uniform.
 */
object InsightsCalculator {

    private const val NINETY_DAYS_MS: Long = 90L * 24L * 60L * 60L * 1000L
    private val SAVINGS_EPSILON = 0.0001f
    private val DATE_LABEL_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun compute(
        txns: List<TransactionWithCategory>,
        cats: List<CategoryEntity>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        nowMs: Long,
    ): List<Insight> {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val currentYm = YearMonth.of(now.year, now.monthValue)
        val priorYm = currentYm.minusMonths(1)
        val (cStart, cEnd) = StatisticsCalculator.monthBounds(currentYm.year, currentYm.monthValue)
        val (pStart, pEnd) = StatisticsCalculator.monthBounds(priorYm.year, priorYm.monthValue)
        val windowStart = nowMs - NINETY_DAYS_MS
        val catsById = cats.associateBy { it.id }

        return listOfNotNull(
            categoryDelta(txns, catsById, homeCurrency, fxRates, cStart, cEnd, pStart, pEnd),
            weekendVsWeekday(txns, homeCurrency, fxRates, windowStart, nowMs),
            savingsTrend(txns, homeCurrency, fxRates, cStart, cEnd, pStart, pEnd),
            topExpenseSpotlight(txns, cStart, cEnd),
        ).sortedBy { it.priority }
    }

    private fun categoryDelta(
        txns: List<TransactionWithCategory>,
        catsById: Map<Long, CategoryEntity>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        currentStart: Long, currentEnd: Long,
        priorStart: Long, priorEnd: Long,
    ): Insight.CategoryDelta? {
        // Group expenses by category for both months. Skip rows without a
        // category (orphan rows aren't attributable to a top mover).
        val currentByCat = mutableMapOf<Long, Long>()
        val priorByCat = mutableMapOf<Long, Long>()
        for (row in txns) {
            val t = row.transaction
            if (t.type != TransactionType.EXPENSE.name) continue
            val cid = t.categoryId ?: continue
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
                ?: t.amountMinor
            when (t.occurredAtEpochMillis) {
                in currentStart until currentEnd -> currentByCat[cid] = (currentByCat[cid] ?: 0L) + converted
                in priorStart until priorEnd -> priorByCat[cid] = (priorByCat[cid] ?: 0L) + converted
            }
        }
        // Score each category that appears in either month. A category with no
        // prior-month spend still produces a NEW insight (previousMinor = 0).
        val allIds = currentByCat.keys + priorByCat.keys
        data class Scored(val id: Long, val current: Long, val previous: Long)
        val scored = allIds.mapNotNull { id ->
            val cur = currentByCat[id] ?: 0L
            val prev = priorByCat[id] ?: 0L
            if (cur == 0L && prev == 0L) return@mapNotNull null
            Scored(id, cur, prev)
        }
        if (scored.isEmpty()) return null

        // Largest |delta|; tie-break by largest current; tie-break again by id
        // (stable for tests). All comparators are ascending; maxWith returns
        // the last element in the resulting order, which is the desired max.
        val best = scored.maxWith(
            compareBy<Scored> { kotlin.math.abs(it.current - it.previous) }
                .thenBy { it.current }
                .thenBy { it.id }
        )
        val name = catsById[best.id]?.name ?: "Other"
        val (direction, percent) = when {
            best.previous == 0L -> Insight.Direction.NEW to 0f
            best.current == best.previous -> Insight.Direction.DOWN to 0f  // 0% is rendered as DOWN with 0
            best.current > best.previous -> {
                val pct = (best.current - best.previous).toFloat() / best.previous.toFloat()
                Insight.Direction.UP to pct
            }
            else -> {
                // best.current < best.previous
                val pct = (best.previous - best.current).toFloat() / best.previous.toFloat()
                Insight.Direction.DOWN to pct
            }
        }
        return Insight.CategoryDelta(
            categoryName = name,
            direction = direction,
            percentChange = percent,
            currentMinor = best.current,
            previousMinor = best.previous,
            currencyCode = homeCurrency,
        )
    }

    private fun weekendVsWeekday(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        windowStart: Long,
        windowEnd: Long,
    ): Insight.WeekendVsWeekday? {
        val zone = ZoneId.systemDefault()
        var weekend = 0L
        var weekday = 0L
        for (row in txns) {
            val t = row.transaction
            if (t.type != TransactionType.EXPENSE.name) continue
            if (t.occurredAtEpochMillis < windowStart || t.occurredAtEpochMillis > windowEnd) continue
            val dow = Instant.ofEpochMilli(t.occurredAtEpochMillis).atZone(zone).toLocalDate().dayOfWeek.value
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
                ?: t.amountMinor
            if (dow >= 6) weekend += converted else weekday += converted
        }
        val total = weekend + weekday
        if (total <= 0L) return null
        return Insight.WeekendVsWeekday(
            weekendMinor = weekend,
            weekdayMinor = weekday,
            weekendPercent = weekend.toFloat() / total.toFloat(),
            currencyCode = homeCurrency,
        )
    }

    private fun savingsTrend(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        currentStart: Long, currentEnd: Long,
        priorStart: Long, priorEnd: Long,
    ): Insight.SavingsTrend? {
        data class Rollup(var income: Long = 0L, var expense: Long = 0L)
        val cur = Rollup()
        val prev = Rollup()
        for (row in txns) {
            val t = row.transaction
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
                ?: t.amountMinor
            val target = when (t.occurredAtEpochMillis) {
                in currentStart until currentEnd -> cur
                in priorStart until priorEnd -> prev
                else -> continue
            }
            when (t.type) {
                TransactionType.INCOME.name -> target.income += converted
                TransactionType.EXPENSE.name -> target.expense += converted
            }
        }
        // Skip when both months have no income (rate undefined).
        if (cur.income == 0L && prev.income == 0L) return null

        fun rate(r: Rollup): Float =
            if (r.income > 0L) ((r.income - r.expense).toFloat() / r.income.toFloat()).coerceIn(0f, 1f)
            else 0f

        val currentRate = rate(cur)
        val previousRate = rate(prev)
        val direction = when {
            kotlin.math.abs(currentRate - previousRate) < SAVINGS_EPSILON -> Insight.Direction.UNCHANGED
            currentRate > previousRate -> Insight.Direction.UP
            else -> Insight.Direction.DOWN
        }
        return Insight.SavingsTrend(currentRate, previousRate, direction)
    }

    private fun topExpenseSpotlight(
        txns: List<TransactionWithCategory>,
        currentStart: Long,
        currentEnd: Long,
    ): Insight.TopExpenseSpotlight? {
        val zone = ZoneId.systemDefault()
        var best: TransactionWithCategory? = null
        for (row in txns) {
            val t = row.transaction
            if (t.type != TransactionType.EXPENSE.name) continue
            if (t.occurredAtEpochMillis !in currentStart until currentEnd) continue
            val candidate = best?.transaction
            if (candidate == null || t.amountMinor > candidate.amountMinor) {
                best = row
            }
        }
        val winner = best?.transaction ?: return null
        val dateLabel = LocalDate
            .ofInstant(Instant.ofEpochMilli(winner.occurredAtEpochMillis), zone)
            .format(DATE_LABEL_FMT)
        return Insight.TopExpenseSpotlight(
            amountMinor = winner.amountMinor,
            currencyCode = winner.currencyCode,
            title = winner.title.ifBlank { "—" },
            dateLabel = dateLabel,
        )
    }
}
