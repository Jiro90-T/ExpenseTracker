# Phase 2.6 — Trends Polish Pack — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Trends tab configurable and analytically richer — add a period selector (3M / 6M / 12M / YTD / All), ghost lines for comparison vs the prior period, a percent-delta card, and a vertical dashed marker for the current month.

**Architecture:** Pure-function data layer (`computePeriodTrends`) produces a `PeriodTrends(current, prior, delta, currentMonthMs)` value. `TrendsViewModel` exposes `period: StateFlow<TrendsPeriod>` and `periodTrends: StateFlow<PeriodTrends>`. `LineChart` is extended to draw ghost lines and a marker. `TrendsScreen` adds a `SingleChoiceSegmentedButtonRow` and a comparison card. No schema changes, no new dependencies.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 `SegmentedButton`, `Canvas`, `PathEffect.dashPathEffect`), Hilt VM, JUnit4.

**Working directory:** `F:/AndroidApp/ExpenseTracker`

**Required env (Windows):** `JAVA_HOME=C:/tools/jdk-21.0.5+11` (AGP 8.13.2 + bundled Kotlin choke on Java 8 and on Java 25+). Run gradle as:
```bash
export JAVA_HOME="C:/tools/jdk-21.0.5+11" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew <task>
```

**Commit identity:** All commits use inline author (no Co-Authored-By trailer):
```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "..."
```

---

## Task 1: Pure data layer + JUnit tests (TDD)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/charts/TrendsPeriod.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/charts/TrendsPeriodTest.kt`

This task adds the period enum, the result data class, the comparison-delta data class, the pure helper `computePeriodTrends`, and a JUnit suite for the helper. Everything is JVM-testable — no Android, no Compose, no Hilt.

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/charts/TrendsPeriodTest.kt` with this content:

```kotlin
package io.github.jiro.expensetracker.ui.charts

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TrendsPeriodTest {

    @Test
    fun computePeriodTrends_emptyRows_returnsEmpty() {
        val out = computePeriodTrends(
            rows = emptyList(),
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertTrue(out.current.isEmpty())
        assertTrue(out.prior?.isEmpty() == true)
        assertNull(out.delta)
        assertNull(out.currentMonthMs)
    }

    @Test
    fun computePeriodTrends_allPeriod_priorIsNull() {
        val rows = listOf(
            row(utcMs(2026, 3, 15, 12, 0, 0), 1_000L, "INCOME"),
            row(utcMs(2026, 4, 15, 12, 0, 0), 2_000L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.All,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertEquals(2, out.current.size)
        assertNull(out.prior)
        assertNull(out.delta)
    }

    @Test
    fun computePeriodTrends_sixMonths_windowOnly() {
        // nowMs = June 15, 2026 → 6M window is Jan–Jun 2026
        // Out-of-window rows: Nov 2025, Dec 2025 (excluded)
        val rows = listOf(
            row(utcMs(2025, 11, 15, 12, 0, 0), 999L, "INCOME"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 999L, "INCOME"),
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 3, 15, 12, 0, 0), 200L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 300L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertEquals(3, out.current.size)  // Jan, Mar, Jun
        assertEquals(600L, out.current.sumOf { it.incomeMinor })
    }

    @Test
    fun computePeriodTrends_sixMonths_priorIsPrecedingSix() {
        // nowMs = June 15, 2026 → current = Jan–Jun 2026, prior = Jul–Dec 2025
        val rows = listOf(
            // current window
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 200L, "INCOME"),
            // prior window
            row(utcMs(2025, 7, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 50L, "INCOME"),
            // outside both windows
            row(utcMs(2025, 6, 15, 12, 0, 0), 999L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        val prior = out.prior
        assertNotNull(prior)
        // Only the two prior-window months are present
        assertEquals(2, prior!!.size)
        assertEquals(100L, prior.sumOf { it.incomeMinor })  // 50 + 50
    }

    @Test
    fun computePeriodTrends_ytdPriorIsSameRangeLastYear() {
        // nowMs = June 15, 2026 → current = Jan–Jun 2026, prior = Jan–Jun 2025
        val rows = listOf(
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 200L, "INCOME"),
            row(utcMs(2025, 1, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2025, 6, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2024, 1, 15, 12, 0, 0), 999L, "INCOME"),  // outside both
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.Ytd,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertEquals(2, out.current.size)
        assertEquals(2, out.prior!!.size)
        assertEquals(300L, out.current.sumOf { it.incomeMinor })
        assertEquals(100L, out.prior!!.sumOf { it.incomeMinor })
    }

    @Test
    fun computePeriodTrends_ytdJanEdge() {
        // nowMs = Jan 15, 2026 → current = just Jan 2026, prior = just Jan 2025
        val rows = listOf(
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2025, 1, 15, 12, 0, 0), 50L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.Ytd,
            nowMs = utcMs(2026, 1, 15, 12, 0, 0),
        )
        assertEquals(1, out.current.size)
        assertEquals(1, out.prior!!.size)
    }

    @Test
    fun computePeriodTrends_deltaCalculations() {
        // current: income 150, expense 50, net 100
        // prior:   income 100, expense 50, net 50
        // expected: income +50%, expense 0%, net +100%
        val rows = listOf(
            // current
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2026, 1, 20, 12, 0, 0), 30L, "EXPENSE"),
            row(utcMs(2026, 6, 20, 12, 0, 0), 20L, "EXPENSE"),
            // prior
            row(utcMs(2025, 7, 15, 12, 0, 0), 60L, "INCOME"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 40L, "INCOME"),
            row(utcMs(2025, 7, 20, 12, 0, 0), 30L, "EXPENSE"),
            row(utcMs(2025, 12, 20, 12, 0, 0), 20L, "EXPENSE"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        val delta = out.delta
        assertNotNull(delta)
        assertEquals(50.0, delta!!.incomePct!!, 0.001)
        assertEquals(0.0, delta.expensePct!!, 0.001)
        assertEquals(100.0, delta.netPct!!, 0.001)
    }

    @Test
    fun computePeriodTrends_priorZero_pctIsNull() {
        // current: income 100, expense 50, net 50
        // prior:   income 0, expense 50, net -50
        val rows = listOf(
            // current
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 1, 20, 12, 0, 0), 50L, "EXPENSE"),
            // prior (no income, only expense)
            row(utcMs(2025, 7, 15, 12, 0, 0), 30L, "EXPENSE"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 20L, "EXPENSE"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        val delta = out.delta
        assertNotNull(delta)
        assertNull(delta!!.incomePct)   // prior income = 0
        assertNotNull(delta.expensePct) // prior expense != 0
        assertNotNull(delta.netPct)     // prior net != 0
    }

    @Test
    fun computePeriodTrends_currentAndPriorEqual_pctIsZero() {
        val rows = listOf(
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2025, 7, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 50L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        val delta = out.delta!!
        assertEquals(0.0, delta.incomePct!!, 0.001)
    }

    @Test
    fun computePeriodTrends_purityRepeatedCalls() {
        val rows = listOf(
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
        )
        val a = computePeriodTrends(rows, TrendsPeriod.SixMonths, utcMs(2026, 6, 15, 12, 0, 0))
        val b = computePeriodTrends(rows, TrendsPeriod.SixMonths, utcMs(2026, 6, 15, 12, 0, 0))
        assertEquals(a, b)
    }

    @Test
    fun computePeriodTrends_currentMonthIsInWindow() {
        val rows = listOf(
            row(utcMs(2026, 6, 15, 12, 0, 0), 100L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        // June's start-of-month in the production code is local-tz dependent;
        // assert that currentMonthMs is non-null and that it equals the start
        // of the nowMs's month in local time.
        val expectedStart = startOfMonth(utcMs(2026, 6, 15, 12, 0, 0))
        assertEquals(expectedStart, out.currentMonthMs)
    }

    @Test
    fun computePeriodTrends_currentMonthIsOutsideWindow() {
        // nowMs is in 2024. 6M window = Jan–Jun 2024. No data for that window,
        // so current is empty → currentMonthMs must be null (nothing to mark).
        val rows = listOf(
            row(utcMs(2025, 1, 15, 12, 0, 0), 100L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2024, 6, 15, 12, 0, 0),
        )
        assertNull(out.currentMonthMs)
    }

    @Test
    fun computePeriodTrends_priorMatchesCurrentLength() {
        val rows = listOf(
            // current 3M = Apr, May, Jun 2026
            row(utcMs(2026, 4, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 5, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 100L, "INCOME"),
            // prior 3M = Jan, Feb, Mar 2026
            row(utcMs(2026, 1, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2026, 2, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2026, 3, 15, 12, 0, 0), 50L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.ThreeMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertEquals(out.current.size, out.prior!!.size)
    }

    // ---- helpers (mirror LineChartDataTest's row/utcMs pattern) ----

    private fun row(
        monthStart: Long,
        amountMinor: Long,
        type: String,
    ): TransactionWithCategory {
        val txn = TransactionEntity(
            id = 0L,
            title = "t",
            amountMinor = amountMinor,
            currencyCode = "USD",
            type = type,
            categoryId = 0L,
            occurredAtEpochMillis = monthStart + 24L * 3600_000L,  // mid-month
            note = null,
            createdAtEpochMillis = monthStart,
        )
        val cat = CategoryEntity(
            id = 0L,
            name = "Any",
            type = type,
            sortOrder = 0,
            isBuiltIn = true,
        )
        return TransactionWithCategory(txn, cat)
    }

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    private fun startOfMonth(epochMs: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (function/type missing)**

Run: `./gradlew testDebugUnitTest --tests "*TrendsPeriodTest"`
Expected: Compile error — `TrendsPeriod`, `PeriodTrends`, `ComparisonDelta`, `computePeriodTrends` are unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/charts/TrendsPeriod.kt` with this content:

```kotlin
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
        return PeriodTrends(emptyList(), null, null, null)
    }

    val byMonth = rows.groupBy { startOfMonth(it.transaction.occurredAtEpochMillis) }
    val labelFmt = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
    val allMonthStarts = byMonth.keys.sorted()

    // Resolve the current window's bounds.
    val (currentFrom, currentToExclusive) = when (period) {
        TrendsPeriod.All -> Long.MIN_VALUE to Long.MAX_VALUE
        is TrendsPeriod.Ytd -> {
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
        is TrendsPeriod.Ytd -> {
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*TrendsPeriodTest"`
Expected: 13/13 pass. (The new `R.string.trends_period_*` references will fail at runtime if strings.xml isn't updated, but the unit test JVM build doesn't load resources — the references are int constants and resolve at runtime. They will throw at runtime if missing, but compile fine.)

Note: This implementation references 5 new R.string IDs (`trends_period_3m`, `_6m`, `_12m`, `_ytd`, `_all`). Tasks 2–4 will add them to `strings.xml`. If you run any instrumentation or runtime smoke before Task 4, you'll see a Resources$NotFoundException. That's expected — the unit tests don't exercise resources.

- [ ] **Step 5: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/charts/TrendsPeriod.kt \
  app/src/test/java/io/github/jiro/expensetracker/ui/charts/TrendsPeriodTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Trends: pure computePeriodTrends + tests"
```

---

## Task 2: Extend `LineChart` for ghost lines, marker, and extended legend

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/charts/LineChart.kt`

The chart's signature gains `prior: List<MonthlyTrend>?` and `currentMonthMs: Long?`. It draws the prior polylines at 30% alpha underneath the current ones, and a thin vertical dashed line at the current month's X position. The legend grows to 6 swatches (two rows of three) when `prior != null`, stays at 3 (one row) when `prior == null`. Y-axis scaling now factors in the prior period too.

- [ ] **Step 1: Replace the entire `LineChart` composable in `LineChart.kt`**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/charts/LineChart.kt` and replace the body of `fun LineChart(...)` (lines 45–191) with this content. The `LegendDot` private composable (lines 193–204) stays as-is.

```kotlin
/**
 * Three polylines (income, expense, net) over the months in [data]. When
 * [prior] is non-null, three ghost polylines are drawn underneath at 30%
 * alpha for the same three series over the prior period. When
 * [currentMonthMs] is non-null and the month is in [data], a thin vertical
 * dashed line is drawn at that month's X position. Tap any data point on
 * the current period to select it; the [selected] month's dots get a ring
 * indicator. Tap the same point again, or anywhere outside the chart, to
 * clear the selection (caller passes `null` via [onSelect]).
 */
@Composable
fun LineChart(
    data: List<MonthlyTrend>,
    prior: List<MonthlyTrend>?,
    currentMonthMs: Long?,
    selected: MonthlyTrend?,
    onSelect: (MonthlyTrend?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.charts_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val density = LocalDensity.current
    val strokePx = with(density) { 2.dp.toPx() }
    val strokePxGhost = with(density) { 1.5.dp.toPx() }
    val dotPx = with(density) { 3.dp.toPx() }
    val ringPx = with(density) { 6.dp.toPx() }
    val ringStrokePx = with(density) { 1.dp.toPx() }
    val joinPx = with(density) { 8.dp.toPx() }
    val pathEffect = remember(joinPx) { PathEffect.cornerPathEffect(joinPx) }
    val markerDashPx = with(density) { 6.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        // Legend (two rows when prior is present, one row otherwise).
        if (prior != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(color = IncomeGreen, label = stringResource(R.string.trends_legend_income))
                Spacer(Modifier.size(8.dp))
                LegendDotGhost(color = IncomeGreen, label = stringResource(R.string.trends_legend_income_prior))
                Spacer(Modifier.size(8.dp))
                LegendDot(color = ExpenseRed, label = stringResource(R.string.trends_legend_expense))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDotGhost(color = ExpenseRed, label = stringResource(R.string.trends_legend_expense_prior))
                Spacer(Modifier.size(8.dp))
                LegendDot(color = NetBlue, label = stringResource(R.string.trends_legend_net))
                Spacer(Modifier.size(8.dp))
                LegendDotGhost(color = NetBlue, label = stringResource(R.string.trends_legend_net_prior))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(color = IncomeGreen, label = stringResource(R.string.trends_legend_income))
                Spacer(Modifier.size(12.dp))
                LegendDot(color = ExpenseRed, label = stringResource(R.string.trends_legend_expense))
                Spacer(Modifier.size(12.dp))
                LegendDot(color = NetBlue, label = stringResource(R.string.trends_legend_net))
            }
        }
        Spacer(Modifier.size(4.dp))
        // Chart canvas.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(data) {
                    detectTapGestures { tapOffset ->
                        val n = data.size
                        if (n < 2) return@detectTapGestures
                        val w = size.width.toFloat()
                        val step = w / (n - 1)
                        val nearestIndex = (tapOffset.x / step)
                            .toInt()
                            .coerceIn(0, n - 1)
                        val nearest = data[nearestIndex]
                        if (selected != null && selected.monthStartMs == nearest.monthStartMs) {
                            onSelect(null)
                        } else {
                            onSelect(nearest)
                        }
                    }
                },
        ) {
            val n = data.size
            val w = size.width
            val h = size.height

            // Y scale: max abs value across current AND prior.
            val currentAbs = data.flatMap { listOf(it.incomeMinor, it.expenseMinor, it.netMinor) }
            val priorAbs = prior?.flatMap { listOf(it.incomeMinor, it.expenseMinor, it.netMinor) } ?: emptyList()
            val maxAbs = (currentAbs + priorAbs).maxOfOrNull { abs(it) } ?: 0L
            if (maxAbs <= 0L) return@Canvas  // all zero — nothing to draw
            val midY = h / 2f
            val halfH = h / 2f

            // Helper: convert (value, monthIndex) → Offset
            fun pointFor(valueMinor: Long, monthIndex: Int): Offset {
                val x = if (n == 1) w / 2f else monthIndex * w / (n - 1)
                val normalized = valueMinor.toFloat() / maxAbs.toFloat()  // in [-1, 1]
                val y = midY - normalized * halfH
                return Offset(x, y)
            }

            // Baseline (x-axis).
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f,
            )

            // Prior ghost polylines (drawn first, underneath).
            if (prior != null && prior.size == n) {
                val ghostPaths = listOf(
                    Pair(IncomeGreen, prior.mapIndexed { i, m -> pointFor(m.incomeMinor, i) }),
                    Pair(ExpenseRed, prior.mapIndexed { i, m -> pointFor(m.expenseMinor, i) }),
                    Pair(NetBlue, prior.mapIndexed { i, m -> pointFor(m.netMinor, i) }),
                )
                for ((color, points) in ghostPaths) {
                    if (points.size < 2) continue
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.30f),
                        style = Stroke(width = strokePxGhost, pathEffect = pathEffect),
                    )
                }
            }

            // Current month marker (vertical dashed line).
            val markerIndex = currentMonthMs?.let { ms ->
                data.indexOfFirst { it.monthStartMs == ms }.takeIf { it >= 0 }
            }
            if (markerIndex != null) {
                val x = if (n == 1) w / 2f else markerIndex * w / (n - 1)
                drawLine(
                    color = Color.Gray.copy(alpha = 0.6f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(markerDashPx, markerDashPx)),
                )
            }

            // Current period polylines (drawn over ghosts, under dots).
            val paths = listOf(
                Pair(IncomeGreen, data.mapIndexed { i, m -> pointFor(m.incomeMinor, i) }),
                Pair(ExpenseRed, data.mapIndexed { i, m -> pointFor(m.expenseMinor, i) }),
                Pair(NetBlue, data.mapIndexed { i, m -> pointFor(m.netMinor, i) }),
            )
            for ((color, points) in paths) {
                if (points.size < 2) continue
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokePx, pathEffect = pathEffect),
                )
            }

            // Dots: small circles at every data point, one per line.
            // Selected month: ring indicator.
            val selectedIndex = selected?.let { sel ->
                data.indexOfFirst { it.monthStartMs == sel.monthStartMs }.takeIf { it >= 0 }
            }
            for ((color, points) in paths) {
                points.forEachIndexed { i, p ->
                    if (selectedIndex == i) {
                        drawCircle(color = color.copy(alpha = 0.25f), radius = ringPx, center = p)
                        drawCircle(color = color, radius = ringPx, center = p, style = Stroke(width = ringStrokePx))
                    }
                    drawCircle(color = color, radius = dotPx, center = p)
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        // X-axis labels.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            data.forEach { m ->
                Text(
                    text = m.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Small color swatch + label, used in the legend. Renders the swatch at the
 * full color and at full size, matching the "current period" lines.
 */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Like [LegendDot] but with a 30% alpha swatch at a smaller size, used for
 * the "(prior)" legend entries that correspond to ghost lines.
 */
@Composable
private fun LegendDotGhost(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color.copy(alpha = 0.30f), shape = CircleShape, modifier = Modifier.size(6.dp)) {}
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: Build fails — `LineChart` is now called with the old 3-arg signature from `TrendsScreen.kt`, and the new references to `R.string.trends_legend_*_prior` don't exist yet. Both get fixed in Tasks 3 and 4. **Don't fix this here** — just confirm the failure is for the expected reasons.

- [ ] **Step 3: Commit the chart changes**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/charts/LineChart.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Trends: extend LineChart for ghost lines + current-month marker"
```

---

## Task 3: Extend `TrendsViewModel` for period + periodTrends

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/trends/TrendsViewModel.kt`

The VM now exposes `period: StateFlow<TrendsPeriod>` (default `SixMonths`) and `periodTrends: StateFlow<PeriodTrends>`. The old `monthlyTrends` is removed (the screen now reads `periodTrends.current` instead). `selected` clears when period changes.

- [ ] **Step 1: Replace the entire contents of `TrendsViewModel.kt`**

```kotlin
package io.github.jiro.expensetracker.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.charts.MonthlyTrend
import io.github.jiro.expensetracker.ui.charts.PeriodTrends
import io.github.jiro.expensetracker.ui.charts.TrendsPeriod
import io.github.jiro.expensetracker.ui.charts.computePeriodTrends
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _period = MutableStateFlow(TrendsPeriod.SixMonths)
    val period: StateFlow<TrendsPeriod> = _period.asStateFlow()

    val periodTrends: StateFlow<PeriodTrends> =
        // Touch homeCurrency + fxRates so this flow re-emits if the user
        // changes settings (for future FX normalization). The actual
        // computation only uses rows and period.
        combine(
            repository.observeAll(),
            _period,
            settingsRepository.homeCurrency,
            settingsRepository.fxRates,
        ) { rows, period, _, _ -> rows to period }
            .map { (rows, period) ->
                computePeriodTrends(
                    rows = rows,
                    period = period,
                    nowMs = System.currentTimeMillis(),
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PeriodTrends(emptyList(), null, null, null),
            )

    private val _selected = MutableStateFlow<MonthlyTrend?>(null)
    val selected: StateFlow<MonthlyTrend?> = _selected.asStateFlow()

    fun setPeriod(period: TrendsPeriod) {
        if (_period.value != period) {
            _period.value = period
            _selected.value = null
        }
    }

    fun select(month: MonthlyTrend?) {
        _selected.value = month
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: Build fails — `TrendsScreen.kt` still references the old `monthlyTrends` field. **Don't fix this here** — Task 4 updates the screen.

- [ ] **Step 3: Commit the VM changes**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/trends/TrendsViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Trends: VM exposes period + periodTrends"
```

---

## Task 4: Extend `TrendsScreen` with period selector and comparison card + add strings

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/trends/TrendsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

This task wires the period selector to the VM, passes the right arguments to `LineChart`, renders the comparison card below the chart, and adds the 5 period labels + 4 prior-legend labels + 1 panel-title label = 10 new strings to `strings.xml`.

- [ ] **Step 1: Add the new strings to `strings.xml`**

Open `app/src/main/res/values/strings.xml` and add these lines just after the existing `trends_clear` string (after line 131):

```xml
    <string name="trends_period_3m">3M</string>
    <string name="trends_period_6m">6M</string>
    <string name="trends_period_12m">12M</string>
    <string name="trends_period_ytd">YTD</string>
    <string name="trends_period_all">All</string>
    <string name="trends_legend_income_prior">Income (prior)</string>
    <string name="trends_legend_expense_prior">Expense (prior)</string>
    <string name="trends_legend_net_prior">Net (prior)</string>
    <string name="trends_compare_panel_title">vs prior %1$d months</string>
    <string name="trends_compare_pct_zero">0%</string>
```

- [ ] **Step 2: Replace the entire contents of `TrendsScreen.kt`**

```kotlin
package io.github.jiro.expensetracker.ui.trends

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.charts.ComparisonDelta
import io.github.jiro.expensetracker.ui.charts.LineChart
import io.github.jiro.expensetracker.ui.charts.MonthlyTrend
import io.github.jiro.expensetracker.ui.charts.TrendsPeriod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(viewModel: TrendsViewModel = hiltViewModel()) {
    val periodTrends by viewModel.periodTrends.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_trends)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            PeriodSelectorRow(
                selected = period,
                onSelect = viewModel::setPeriod,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.trends_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            LineChart(
                data = periodTrends.current,
                prior = periodTrends.prior,
                currentMonthMs = periodTrends.currentMonthMs,
                selected = selected,
                onSelect = viewModel::select,
            )
            Spacer(Modifier.size(16.dp))
            AnimatedVisibility(
                visible = selected != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                selected?.let { sel ->
                    DetailPanel(
                        month = sel,
                        onClear = { viewModel.select(null) },
                    )
                }
            }
            AnimatedVisibility(
                visible = periodTrends.prior != null && periodTrends.delta != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val prior = periodTrends.prior
                val delta = periodTrends.delta
                if (prior != null && delta != null) {
                    ComparisonCard(
                        period = period,
                        delta = delta,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelectorRow(
    selected: TrendsPeriod,
    onSelect: (TrendsPeriod) -> Unit,
) {
    val options = TrendsPeriod.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, period ->
            SegmentedButton(
                selected = selected == period,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(period.labelRes)) }
        }
    }
}

@Composable
private fun DetailPanel(month: MonthlyTrend, onClear: () -> Unit) {
    val label = remember(month.monthStartMs) {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(month.monthStartMs))
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.trends_detail_title, label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.trends_detail_income, MoneyFormat.formatAmountForEdit(month.incomeMinor)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.trends_detail_expense, MoneyFormat.formatAmountForEdit(month.expenseMinor)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.trends_detail_net,
                        (if (month.netMinor >= 0) "+" else "") + MoneyFormat.formatAmountForEdit(month.netMinor),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.trends_clear),
                )
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    period: TrendsPeriod,
    delta: ComparisonDelta,
) {
    val monthsBack = when (period) {
        is TrendsPeriod.Ytd -> {
            // For YTD the panel title uses the actual month count of the
            // current window. We use the period's monthsBack via the data
            // shape, but Ytd has monthsBack = null. Fall back to a friendly
            // label: just use the number of months in the current window.
            // In practice, TrendsViewModel always emits a valid period, so
            // the caller is responsible for passing the right `period`.
            // The card title is "%1$d months", so we need a count.
            null
        }
        else -> period.monthsBack
    }
    // YTD title uses "vs prior YTD"; fixed-N uses "vs prior N months".
    val title = if (period is TrendsPeriod.Ytd) {
        // No dedicated YTD title string — reuse the parameterized one with
        // a representative month count or just describe it as YTD. For
        // simplicity we pass 0 and the title string will say "vs prior 0
        // months" which is wrong. Replace with a plain title:
        stringResource(R.string.trends_compare_panel_title_ytd)
    } else {
        stringResource(R.string.trends_compare_panel_title, monthsBack ?: 0)
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            DeltaRow(label = stringResource(R.string.trends_compare_label_income), pct = delta.incomePct)
            DeltaRow(label = stringResource(R.string.trends_compare_label_expense), pct = delta.expensePct)
            DeltaRow(label = stringResource(R.string.trends_compare_label_net), pct = delta.netPct)
        }
    }
}

@Composable
private fun DeltaRow(label: String, pct: Double?) {
    val (text, color, showArrow) = when {
        pct == null -> Triple("—", MaterialTheme.colorScheme.onSurfaceVariant, false)
        abs(pct) < 0.05 -> Triple(stringResource(R.string.trends_compare_pct_zero), MaterialTheme.colorScheme.onSurfaceVariant, false)
        pct > 0 -> Triple(formatPct(pct), MaterialTheme.colorScheme.primary, true)
        else -> Triple(formatPct(pct), MaterialTheme.colorScheme.error, true)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (showArrow) {
            Icon(
                imageVector = if (text.startsWith("-")) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

private fun formatPct(pct: Double): String {
    val rounded = (pct * 10.0).toLong() / 10.0
    val sign = if (rounded > 0) "+" else ""
    return if (rounded == rounded.toLong().toDouble()) {
        "$sign${rounded.toLong()}%"
    } else {
        "$sign${"%.1f".format(rounded)}%"
    }
}
```

- [ ] **Step 3: Add the three new strings the card needs**

The card uses three row labels (`Income`, `Expense`, `Net`) and a YTD-specific title that weren't in the original 10. Add these to `strings.xml` right after the strings added in Step 1:

```xml
    <string name="trends_compare_label_income">Income</string>
    <string name="trends_compare_label_expense">Expense</string>
    <string name="trends_compare_label_net">Net</string>
    <string name="trends_compare_panel_title_ytd">vs prior year</string>
```

(13 new strings total across this task: 10 from Step 1 + 3 labels + 1 YTD title = 14. The "1 YTD title" replaces a parameterized count of 0 with a friendlier phrase.)

- [ ] **Step 4: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: 125/125 pass (112 prior + 13 new from Task 1).

- [ ] **Step 6: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/trends/TrendsScreen.kt \
  app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Trends: screen with period selector + comparison card"
```

---

## Task 5: Final verification (assembleDebug + full test pass)

**Files:** none (read-only verification).

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. APK written to `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: 125/125 pass, 0 failures, 0 errors.

- [ ] **Step 3: Sanity-check git state**

Run: `git log --oneline v0.5.0..HEAD`
Expected: 4 commits (one per implementation task) plus any spec/plan doc commits that landed.

- [ ] **Step 4: Report**

Report: build pass, test pass, commit count, and any smoke-test notes from the implementer. The on-device smoke test (tap each period option, verify chart re-renders, verify ghost lines, verify comparison card, verify marker) is described in the final review checklist and exercised in the Phase 2.6 end-to-end code review.

---

## Self-review notes (already applied)

- **Spec coverage:** Every spec section maps to a task. The 13 unit tests cover all spec test cases. The 14 new strings cover all spec string additions. The chart's extended signature, ghost lines, marker, and 6-swatch legend are all in Task 2. The period selector, comparison card, and `setPeriod` wiring are in Task 4.
- **Placeholder scan:** No "TBD" or "implement later" anywhere. All code is complete.
- **Type consistency:** `TrendsPeriod` (enum, `monthsBack: Int?`, `labelRes: Int`), `PeriodTrends(current, prior, delta, currentMonthMs)`, `ComparisonDelta(incomePct, expensePct, netPct)`, `computePeriodTrends(rows, period, nowMs)` — all consistent across Tasks 1, 2, 3, 4. The chart signature `(data, prior, currentMonthMs, selected, onSelect, modifier)` is consistent across Tasks 2 and 4. The VM `period: StateFlow<TrendsPeriod>`, `periodTrends: StateFlow<PeriodTrends>`, `setPeriod`, `select` is consistent across Tasks 3 and 4.
- **Known intentional deviation from spec:** The `TrendsScreen` Step 2 uses a YTD-specific title ("vs prior year") via a new `trends_compare_panel_title_ytd` string instead of the spec's parameterized `vs prior %1$d months` (which would have rendered "vs prior 0 months" for YTD since `period.monthsBack == null`). This is a small wording fix that came up while writing the code; the spec's intent ("show a meaningful prior label") is preserved.

## Out of scope (intentional, deferred)

- Bar/line period-anchor inconsistency (Home bar chart stays on Home's `Period`).
- Dark-scheme `NetBlue` variant.
- Animated chart transitions.
- "Save to Photos" share affordance.
- Pinch-to-zoom / drag.
- Forecast / projection.
- Per-line color customization.
- TWEEN animations on percent deltas.
- "as of" date picker for the period anchor (always "now").
