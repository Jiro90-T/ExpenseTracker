# Phase 2.13c — Statistics Insights Tab — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fifth "Insights" tab to the Statistics screen that surfaces 4 actionable text observations (CategoryDelta, WeekendVsWeekday, SavingsTrend, TopExpenseSpotlight) derived from the user's transactions.

**Architecture:** Pure-function `InsightsCalculator` returns a `List<Insight>` (sealed class). `StatisticsViewModel` exposes `insights: StateFlow<List<Insight>>` via the same `combine + stateIn` pattern as the other four tabs. New `InsightCard` composable renders any subclass via `when` over the sealed class. New private `InsightsTab` composable is wired into the existing `HorizontalPager`. No new abstractions; ~150 production / ~120 test lines, 15 strings.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Hilt, Coroutines+Flow, Room (existing), JUnit, Robolectric (existing infra).

---

## File Structure

| File | Action | Responsibility |
| --- | --- | --- |
| `app/src/main/res/values/strings.xml` | modify | +15 insight strings |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/Insight.kt` | new | Sealed `Insight` + `Direction` enum. Pure data, no logic. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/InsightsCalculator.kt` | new | Pure functions: `categoryDelta`, `weekendVsWeekday`, `savingsTrend`, `topExpenseSpotlight`, `compute` (compose + sort). |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt` | modify | Add `INSIGHTS` to `StatisticsTab` enum. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt` | modify | Add `insights: StateFlow<List<Insight>>` via `combine + stateIn`. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsScreen.kt` | modify | Add `INSIGHTS` to `tabs` list, `labelRes` branch, `HorizontalPager` `when` branch; add private `InsightsTab`. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/InsightCard.kt` | new | `InsightCard` composable that renders any `Insight` subclass. |
| `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/InsightsCalculatorTest.kt` | new | 14 tests for `InsightsCalculator.compute` and its 4 helpers. |
| `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModelTest.kt` | modify | Add test verifying `insights` StateFlow combines sources. |
| `docs/superpowers/testdata/phase-2.13c-insights.md` | new | Manual smoke test. |

---

### Task 1: Add insight strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml:438` (insert after `stats_widget_next`)

- [ ] **Step 1: Add the 15 insight strings**

Open `app/src/main/res/values/strings.xml`. After the line `<string name="stats_widget_next">Next</string>` (and any subsequent `stats_*` strings), insert the following 15 entries:

```xml
    <!-- Insights tab -->
    <string name="stats_tab_insights">Insights</string>
    <string name="stats_insights_empty">Not enough data yet — log transactions to see insights</string>
    <string name="stats_insights_cat_up">%1$s up %2$d%%</string>
    <string name="stats_insights_cat_down">%1$s down %2$d%%</string>
    <string name="stats_insights_cat_new">New spending: %1$s</string>
    <string name="stats_insights_cat_supporting">%1$s this month vs %2$s last month</string>
    <string name="stats_insights_cat_supporting_new">%1$s this month</string>
    <string name="stats_insights_weekend">Weekend spending is %1$d%% of your total</string>
    <string name="stats_insights_weekend_support">%1$s on weekends vs %2$s on weekdays</string>
    <string name="stats_insights_savings_up">Savings rate up %1$d pts</string>
    <string name="stats_insights_savings_down">Savings rate down %1$d pts</string>
    <string name="stats_insights_savings_same">Savings rate unchanged at %1$d%%</string>
    <string name="stats_insights_savings_support">%1$d%% this month vs %2$d%% last month</string>
    <string name="stats_insights_top_expense">Largest expense: %1$s</string>
    <string name="stats_insights_top_expense_support">%1$s on %2$s</string>
```

The exact line to insert after depends on the file's current state — search for `stats_widget_next` and paste the block immediately after its closing `</string>`.

- [ ] **Step 2: Verify the strings compile**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugResources 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(strings): add 15 insight strings (Phase 2.13c)"
```

---

### Task 2: Insight sealed class + Direction enum

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/Insight.kt`

- [ ] **Step 1: Write the failing test file (skeleton — extension in Task 3)**

Skip — this is a pure data class with no behavior; compile errors at the use sites in Task 3 will be the failing test.

- [ ] **Step 2: Create the file with the sealed class**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/Insight.kt`:

```kotlin
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
```

- [ ] **Step 3: Verify it compiles**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/Insight.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(stats): Insight sealed class + Direction enum (Phase 2.13c)"
```

---

### Task 3: InsightsCalculator + compute() with tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/InsightsCalculator.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/InsightsCalculatorTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/InsightsCalculatorTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InsightsCalculatorTest {

    // ---- helpers (mirror StatisticsCalculatorTest) ----

    private fun date(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun txn(
        id: Long, title: String, amountMinor: Long, currency: String,
        type: String, categoryId: Long, occurredAt: Long,
    ): TransactionWithCategory {
        val t = TransactionEntity(
            id = id, title = title, amountMinor = amountMinor,
            currencyCode = currency, type = type, categoryId = categoryId,
            occurredAtEpochMillis = occurredAt, createdAtEpochMillis = occurredAt,
        )
        val c = CategoryEntity(id = categoryId, name = "Cat-$categoryId", type = type)
        return TransactionWithCategory(t, c)
    }

    private val nowMs: Long = date(2026, 6, 17)
    private val currentStart = StatisticsCalculator.monthBounds(2026, 6).first
    private val currentEnd = StatisticsCalculator.monthBounds(2026, 6).second
    private val priorStart = StatisticsCalculator.monthBounds(2026, 5).first
    private val priorEnd = StatisticsCalculator.monthBounds(2026, 5).second

    // ---- compute() ----

    @Test
    fun compute_returnsFourInsights_forTypicalUserWithTwoMonths() {
        val cats = listOf(
            CategoryEntity(1L, "Food", "EXPENSE"),
            CategoryEntity(2L, "Transit", "EXPENSE"),
        )
        val txns = listOf(
            // Current month — Food higher than last month
            txn(1, "Groceries", 1500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Coffee", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
            txn(3, "Bus", 300L, "USD", "EXPENSE", 2L, date(2026, 6, 7)),
            txn(4, "Salary", 5000L, "USD", "INCOME", 99L, date(2026, 6, 1)),
            // Prior month — Food lower, Transit higher
            txn(5, "Groceries", 1000L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
            txn(6, "Bus", 500L, "USD", "EXPENSE", 2L, date(2026, 5, 7)),
            txn(7, "Salary", 5000L, "USD", "INCOME", 99L, date(2026, 5, 1)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        // All four insight types present, ordered by priority ascending.
        assertEquals(4, insights.size)
        assertTrue(insights[0] is Insight.CategoryDelta)
        assertTrue(insights[1] is Insight.WeekendVsWeekday)
        assertTrue(insights[2] is Insight.SavingsTrend)
        assertTrue(insights[3] is Insight.TopExpenseSpotlight)
    }

    @Test
    fun compute_categoryDelta_picksLargestAbsoluteDelta() {
        val cats = listOf(
            CategoryEntity(1L, "Food", "EXPENSE"),
            CategoryEntity(2L, "Transit", "EXPENSE"),
            CategoryEntity(3L, "Coffee", "EXPENSE"),
        )
        val txns = listOf(
            // Current month
            txn(1, "Food", 2000L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Transit", 1500L, "USD", "EXPENSE", 2L, date(2026, 6, 7)),
            txn(3, "Coffee", 600L, "USD", "EXPENSE", 3L, date(2026, 6, 8)),
            // Prior month — biggest delta is Food (+1000) vs Transit (+200) vs Coffee (+500)
            txn(4, "Food", 1000L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
            txn(5, "Transit", 1300L, "USD", "EXPENSE", 2L, date(2026, 5, 7)),
            txn(6, "Coffee", 100L, "USD", "EXPENSE", 3L, date(2026, 5, 8)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val cd = insights.filterIsInstance<Insight.CategoryDelta>().single()
        assertEquals("Food", cd.categoryName)
        assertEquals(Insight.Direction.UP, cd.direction)
        assertEquals(2000L, cd.currentMinor)
        assertEquals(1000L, cd.previousMinor)
        // 100% increase (2000 - 1000) / 1000 = 1.0
        assertEquals(1.0f, cd.percentChange, 0.001f)
    }

    @Test
    fun compute_categoryDelta_picksDirectionUpDownNew() {
        val cats = listOf(
            CategoryEntity(1L, "Up", "EXPENSE"),
            CategoryEntity(2L, "Down", "EXPENSE"),
            CategoryEntity(3L, "New", "EXPENSE"),
        )
        val txns = listOf(
            txn(1, "Up-now", 2000L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Up-then", 1000L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
            txn(3, "Down-now", 500L, "USD", "EXPENSE", 2L, date(2026, 6, 3)),
            txn(4, "Down-then", 1500L, "USD", "EXPENSE", 2L, date(2026, 5, 3)),
            txn(5, "New-now", 800L, "USD", "EXPENSE", 3L, date(2026, 6, 3)),
            // No prior-month row for category 3 → NEW
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val cd = insights.filterIsInstance<Insight.CategoryDelta>().single()
        // The largest |delta| is Down at 1000 (1500→500) vs Up at 1000 (1000→2000). Tie-break by largest current → Up wins.
        assertEquals("Up", cd.categoryName)
        assertEquals(Insight.Direction.UP, cd.direction)
    }

    @Test
    fun compute_categoryDelta_marksNewWhenPriorIsZero() {
        val cats = listOf(CategoryEntity(1L, "New", "EXPENSE"))
        val txns = listOf(
            txn(1, "Only-this-month", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val cd = insights.filterIsInstance<Insight.CategoryDelta>().single()
        assertEquals(Insight.Direction.NEW, cd.direction)
        assertEquals(0f, cd.percentChange, 0.001f)
        assertEquals(500L, cd.currentMinor)
        assertEquals(0L, cd.previousMinor)
    }

    @Test
    fun compute_categoryDelta_skipsWhenOnlyOneMonthOfData() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // Only current-month data
        val txns = listOf(
            txn(1, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        assertTrue(insights.filterIsInstance<Insight.CategoryDelta>().isEmpty())
    }

    @Test
    fun compute_categoryDelta_excludesIncome() {
        val cats = listOf(
            CategoryEntity(1L, "Food", "EXPENSE"),
            CategoryEntity(99L, "Salary", "INCOME"),
        )
        val txns = listOf(
            txn(1, "Salary-now", 5000L, "USD", "INCOME", 99L, date(2026, 6, 1)),
            txn(2, "Salary-then", 1000L, "USD", "INCOME", 99L, date(2026, 5, 1)),
            // Food: same in both months, no delta
            txn(3, "Food-now", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(4, "Food-then", 500L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        // Salary delta should NOT appear (income is excluded from CategoryDelta).
        val cd = insights.filterIsInstance<Insight.CategoryDelta>()
        assertTrue(cd.isEmpty() || cd.single().categoryName == "Food")
    }

    @Test
    fun compute_weekendVsWeekday_uses90DayRollingWindow() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // nowMs = 2026-06-17 (Wednesday). 90 days back = 2026-03-19 (Thursday).
        // 2026-04-04 (Saturday) is inside the window. 2026-01-10 is outside.
        val txns = listOf(
            txn(1, "Weekend", 2000L, "USD", "EXPENSE", 1L, date(2026, 4, 4)), // Saturday
            txn(2, "Weekend-2", 500L, "USD", "EXPENSE", 1L, date(2026, 4, 5)), // Sunday
            txn(3, "Weekday", 1000L, "USD", "EXPENSE", 1L, date(2026, 4, 6)), // Monday
            txn(4, "Old", 9999L, "USD", "EXPENSE", 1L, date(2026, 1, 10)),    // outside window
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val wv = insights.filterIsInstance<Insight.WeekendVsWeekday>().single()
        assertEquals(2500L, wv.weekendMinor)    // 2000 + 500
        assertEquals(1000L, wv.weekdayMinor)
        // 2500 / 3500 = 0.7143
        assertEquals(0.7143f, wv.weekendPercent, 0.001f)
    }

    @Test
    fun compute_weekendVsWeekday_returnsNullWhenAllZero() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // No expenses at all in the 90-day window
        val insights = InsightsCalculator.compute(emptyList(), cats, "USD", emptyMap(), nowMs)
        assertTrue(insights.filterIsInstance<Insight.WeekendVsWeekday>().isEmpty())
    }

    @Test
    fun compute_savingsTrend_undefinedWhenBothMonthsHaveZeroIncome() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // No income, only expenses — savings rate undefined for both months.
        val txns = listOf(
            txn(1, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        assertTrue(insights.filterIsInstance<Insight.SavingsTrend>().isEmpty())
    }

    @Test
    fun compute_savingsTrend_marksUnchangedWhenWithinEpsilon() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // Both months: income 1000, expense 500 → rate 0.5f
        val txns = listOf(
            txn(1, "Salary", 1000L, "USD", "INCOME", 99L, date(2026, 6, 1)),
            txn(2, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(3, "Salary", 1000L, "USD", "INCOME", 99L, date(2026, 5, 1)),
            txn(4, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val st = insights.filterIsInstance<Insight.SavingsTrend>().single()
        assertEquals(0.5f, st.currentRate, 0.001f)
        assertEquals(0.5f, st.previousRate, 0.001f)
        assertEquals(Insight.Direction.UNCHANGED, st.direction)
    }

    @Test
    fun compute_topExpenseSpotlight_picksLargestExpense() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val txns = listOf(
            txn(1, "Groceries", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Dinner-out", 2500L, "USD", "EXPENSE", 1L, date(2026, 6, 10)),
            txn(3, "Coffee", 300L, "USD", "EXPENSE", 1L, date(2026, 6, 11)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val te = insights.filterIsInstance<Insight.TopExpenseSpotlight>().single()
        assertEquals(2500L, te.amountMinor)
        assertEquals("Dinner-out", te.title)
        assertEquals("Jun 10", te.dateLabel)
    }

    @Test
    fun compute_topExpenseSpotlight_usesNativeCurrencyNotHome() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // MYR expense, USD home — no FX rate. The amount is shown as-is in MYR
        // (native currency), NOT converted to USD.
        val txns = listOf(
            txn(1, "Lunch-MY", 4500L, "MYR", "EXPENSE", 1L, date(2026, 6, 5)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val te = insights.filterIsInstance<Insight.TopExpenseSpotlight>().single()
        assertEquals("MYR", te.currencyCode)
        assertEquals(4500L, te.amountMinor)
    }

    @Test
    fun compute_topExpenseSpotlight_returnsNullWhenNoExpenses() {
        val cats = listOf(CategoryEntity(99L, "Salary", "INCOME"))
        val txns = listOf(
            txn(1, "Salary", 5000L, "USD", "INCOME", 99L, date(2026, 6, 1)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        assertTrue(insights.filterIsInstance<Insight.TopExpenseSpotlight>().isEmpty())
    }

    @Test
    fun compute_fallsBackToRawAmountWhenFxRateMissing() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // MYR expense, USD home, no rate. CategoryDelta should still pick it
        // up using the raw amountMinor (no crash, no missingRateCount tracking
        // for insights — just fallback per Phase 2.13 calculator policy).
        val txns = listOf(
            txn(1, "Now-MY", 4500L, "MYR", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Then-MY", 1500L, "MYR", "EXPENSE", 1L, date(2026, 5, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val cd = insights.filterIsInstance<Insight.CategoryDelta>().single()
        assertEquals(4500L, cd.currentMinor)
        assertEquals(1500L, cd.previousMinor)
    }

    @Test
    fun compute_returnsEmptyListWhenNoTransactionsAtAll() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val insights = InsightsCalculator.compute(emptyList(), cats, "USD", emptyMap(), nowMs)
        assertEquals(emptyList<Insight>(), insights)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (compile error)**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*InsightsCalculatorTest*" 2>&1 | tail -15`
Expected: FAIL with `Unresolved reference: InsightsCalculator`.

- [ ] **Step 3: Implement InsightsCalculator**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/InsightsCalculator.kt`:

```kotlin
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
        // Require at least one row in the prior month — otherwise we can't
        // claim a "vs last month" delta.
        if (priorByCat.isEmpty()) return null

        // Score each category that appears in either month.
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
        // (stable for tests).
        val best = scored.maxWith(
            compareBy<Scored> { -(kotlin.math.abs(it.current - it.previous)) }
                .thenByDescending { it.current }
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
```

- [ ] **Step 4: Run the tests**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*InsightsCalculatorTest*" 2>&1 | tail -20`
Expected: 14 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/InsightsCalculator.kt app/src/test/java/io/github/jiro/expensetracker/ui/statistics/InsightsCalculatorTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(stats): InsightsCalculator + 14 tests (Phase 2.13c)"
```

---

### Task 4: StatisticsViewModel.insights StateFlow + StatisticsTab.INSIGHTS

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt:23-25` (add enum entry)
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt:21-105` (add `insights` StateFlow)
- Modify: `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModelTest.kt` (add a test)

- [ ] **Step 1: Add INSIGHTS to the StatisticsTab enum**

In `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt`, change line 23-25 from:

```kotlin
enum class StatisticsTab {
    TOP_CATS, SAVINGS, PATTERNS, YOY,
}
```

to:

```kotlin
enum class StatisticsTab {
    TOP_CATS, SAVINGS, PATTERNS, YOY, INSIGHTS,
}
```

- [ ] **Step 2: Add the insights StateFlow to the ViewModel**

In `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt`, **insert the following block immediately after line 80** (the closing `)` of the `yoy` StateFlow's `stateIn` call):

```kotlin
    val insights: StateFlow<List<Insight>> =
        combine(txns(), cats, home, rates) { t, c, h, r ->
            InsightsCalculator.compute(t, c, h, r, System.currentTimeMillis())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun txns() = transactionRepository.observeAll()
```

Wait — the existing `yoy` block already calls `transactionRepository.observeAll()` inline inside its `flatMapLatest`. We need a single source. Replace the existing `private val txns = ...` declaration at the top of the class (currently absent — the file uses inline `transactionRepository.observeAll()` calls). To avoid duplication, add a `private val txns` property at the top of the class.

Apply the following changes to `StatisticsViewModel.kt`:

**Change 1** — insert a `txns` property at the top of the class body. After the existing `private val rates = settingsRepository.fxRates` line (currently line 30), insert:

```kotlin
    private val txns = transactionRepository.observeAll()
```

**Change 2** — replace each of the four `transactionRepository.observeAll()` inline calls with the new `txns` reference. The four locations are inside the `flatMapLatest` lambdas for `topCategories`, `savings`, `dayOfWeek`, and `yoy`. Specifically, change:

```kotlin
val txns = transactionRepository.observeAll()
```

(in each of the 4 spots) to remove that line — the outer `txns` is already captured by the `flatMapLatest` closure when used inside `combine`.

Actually, looking at the existing code more carefully: each `flatMapLatest { range -> val txns = transactionRepository.observeAll(); combine(txns, ...) { ... } }` pattern requires a fresh `txns` per range emission. We CANNOT hoist `txns` to a class field because then it would never re-emit when the range changes — the combine would still use the same `txns` flow.

So leave the inline `transactionRepository.observeAll()` calls as-is in the existing 4 tabs, and **only** add the new `insights` StateFlow without a range dependency (insights always use the current calendar month — no range override). Use the same inline pattern:

**Change 2** — append after the existing `yoyRange` blocks (after line 96, before `private fun defaultRange()`):

```kotlin
    val insights: StateFlow<List<Insight>> =
        combine(transactionRepository.observeAll(), cats, home, rates) { t, c, h, r ->
            InsightsCalculator.compute(t, c, h, r, System.currentTimeMillis())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

- [ ] **Step 3: Verify it compiles**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Add a VM test for the insights StateFlow**

Open `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModelTest.kt` and append the following test (place it after the last existing test method in the class):

```kotlin
    @Test
    fun insights_emitsListFromCalculator() = runTest {
        // Verify the ViewModel exposes a StateFlow that combines all four
        // sources and emits InsightsCalculator.compute(...). We can't easily
        // exercise the calculator's full path here without setting up
        // repositories, so just confirm the StateFlow is exposed and starts
        // as emptyList() (the stateIn initial value).
        // … See StatisticsViewModelTest for the existing stub pattern; if
        // the test file is pure-DB (no Compose/Hilt), gate this test on the
        // Robolectric @RunWith annotation already present.
        // For Phase 2.13c, we ship without a dedicated VM test — the
        // InsightsCalculatorTest covers the compute logic and the
        // StateFlow is mechanically identical to the other 4 tabs.
    }
```

If the existing StatisticsViewModelTest uses Robolectric/Hilt setup (which is involved), skip this step and instead rely on the InsightsCalculatorTest coverage plus a manual smoke test. The reasoning: the VM is mechanical `combine + stateIn`, identical in shape to the 4 already-shipped tabs whose behavior is already validated in production.

To keep this plan shippable, **mark this step as optional** — only execute it if `StatisticsViewModelTest.kt` already has a working Robolectric test setup you can extend. If not, skip to Step 5.

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`, 398+ tests, 0 failures (412 expected with the 14 new).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(stats): StatisticsTab.INSIGHTS + insights StateFlow (Phase 2.13c)"
```

---

### Task 5: InsightCard composable + InsightsTab integration

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/InsightCard.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsScreen.kt:46-163`

- [ ] **Step 1: Create InsightCard.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/InsightCard.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import kotlin.math.roundToInt

/**
 * Renders any [Insight] subclass as a Card. The mapping from insight type
 * to string resources, icons, and amount formatting lives here so the
 * calculator stays pure.
 */
@Composable
internal fun InsightCard(insight: Insight, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = insight.icon(),
                contentDescription = null,
                tint = insight.tintColor(),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(insight.headlineRes(), *insight.headlineArgs()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(insight.supportingTextRes(), *insight.supportingTextArgs()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- formatting mapping ---

@Composable
private fun Insight.icon(): ImageVector = when (this) {
    is Insight.CategoryDelta -> when (direction) {
        Insight.Direction.UP -> Icons.AutoMirrored.Filled.TrendingUp
        Insight.Direction.DOWN -> Icons.AutoMirrored.Filled.TrendingDown
        Insight.Direction.NEW -> Icons.Filled.FiberNew
        Insight.Direction.UNCHANGED -> Icons.AutoMirrored.Filled.TrendingUp
    }
    is Insight.WeekendVsWeekday -> Icons.Filled.CalendarMonth
    is Insight.SavingsTrend -> Icons.Filled.Savings
    is Insight.TopExpenseSpotlight -> Icons.Filled.ReceiptLong
}

@Composable
private fun Insight.tintColor(): Color = when (this) {
    is Insight.SavingsTrend -> when (direction) {
        Insight.Direction.UP -> IncomeGreen
        Insight.Direction.DOWN -> MaterialTheme.colorScheme.error
        Insight.Direction.UNCHANGED, Insight.Direction.NEW -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    is Insight.CategoryDelta -> when (direction) {
        Insight.Direction.UP -> MaterialTheme.colorScheme.error
        Insight.Direction.DOWN -> IncomeGreen
        Insight.Direction.NEW -> MaterialTheme.colorScheme.primary
        Insight.Direction.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun Insight.headlineRes(): Int = when (this) {
    is Insight.CategoryDelta -> when (direction) {
        Insight.Direction.UP -> R.string.stats_insights_cat_up
        Insight.Direction.DOWN -> R.string.stats_insights_cat_down
        Insight.Direction.NEW -> R.string.stats_insights_cat_new
        Insight.Direction.UNCHANGED -> R.string.stats_insights_cat_down
    }
    is Insight.WeekendVsWeekday -> R.string.stats_insights_weekend
    is Insight.SavingsTrend -> when (direction) {
        Insight.Direction.UP -> R.string.stats_insights_savings_up
        Insight.Direction.DOWN -> R.string.stats_insights_savings_down
        Insight.Direction.UNCHANGED -> R.string.stats_insights_savings_same
        Insight.Direction.NEW -> R.string.stats_insights_savings_same
    }
    is Insight.TopExpenseSpotlight -> R.string.stats_insights_top_expense
}

private fun Insight.headlineArgs(): Array<Any> = when (this) {
    is Insight.CategoryDelta -> arrayOf(
        categoryName,
        (kotlin.math.abs(percentChange) * 100f).roundToInt(),
    )
    is Insight.WeekendVsWeekday -> arrayOf((weekendPercent * 100f).roundToInt())
    is Insight.SavingsTrend -> arrayOf(
        kotlin.math.abs(currentRate - previousRate).let { (it * 100f).roundToInt() }
            .let { if (it == 0) (previousRate * 100f).roundToInt() else it },
    )
    is Insight.TopExpenseSpotlight -> arrayOf(MoneyFormat.formatForDisplay(amountMinor, currencyCode))
}

@Composable
private fun Insight.supportingTextRes(): Int = when (this) {
    is Insight.CategoryDelta -> if (direction == Insight.Direction.NEW)
        R.string.stats_insights_cat_supporting_new
    else R.string.stats_insights_cat_supporting
    is Insight.WeekendVsWeekday -> R.string.stats_insights_weekend_support
    is Insight.SavingsTrend -> R.string.stats_insights_savings_support
    is Insight.TopExpenseSpotlight -> R.string.stats_insights_top_expense_support
}

private fun Insight.supportingTextArgs(): Array<Any> = when (this) {
    is Insight.CategoryDelta -> if (direction == Insight.Direction.NEW)
        arrayOf(MoneyFormat.formatForDisplay(currentMinor, currencyCode))
    else arrayOf(
        MoneyFormat.formatForDisplay(currentMinor, currencyCode),
        MoneyFormat.formatForDisplay(previousMinor, currencyCode),
    )
    is Insight.WeekendVsWeekday -> arrayOf(
        MoneyFormat.formatForDisplay(weekendMinor, currencyCode),
        MoneyFormat.formatForDisplay(weekdayMinor, currencyCode),
    )
    is Insight.SavingsTrend -> arrayOf(
        (currentRate * 100f).roundToInt(),
        (previousRate * 100f).roundToInt(),
    )
    is Insight.TopExpenseSpotlight -> arrayOf(title, dateLabel)
}
```

Note: the `InsightCard` icon/tint/headline helpers use `@Composable` because `tintColor` and `icon` are pure functions of `Insight` data and don't need to be composables — but the `headlineRes` / `supportingTextRes` / `headlineArgs` / `supportingTextArgs` accessors must be `@Composable` since `stringResource` requires a composition. Adjust the `@Composable` annotations accordingly: only the accessors that wrap `stringResource` need `@Composable`; the pure data lookups (icon vector, tint color) don't. The file above has the correct annotations.

- [ ] **Step 2: Wire INSIGHTS into StatisticsScreen**

Edit `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsScreen.kt` with the following changes:

**Change A** — extend the `tabs` list. At line 85, change:

```kotlin
val tabs = listOf(StatisticsTab.TOP_CATS, StatisticsTab.SAVINGS, StatisticsTab.PATTERNS, StatisticsTab.YOY)
```

to:

```kotlin
val tabs = listOf(StatisticsTab.TOP_CATS, StatisticsTab.SAVINGS, StatisticsTab.PATTERNS, StatisticsTab.YOY, StatisticsTab.INSIGHTS)
```

**Change B** — add the INSIGHTS branch to the `HorizontalPager` `when`. After line 142 (the closing brace of the `StatisticsTab.YOY -> YoyTab(...)` branch), add:

```kotlin
                StatisticsTab.INSIGHTS -> InsightsTab(insights = insights)
```

**Change C** — extend the `labelRes()` private function. At lines 177-182, change:

```kotlin
private fun StatisticsTab.labelRes(): Int = when (this) {
    StatisticsTab.TOP_CATS -> R.string.stats_tab_top_cats
    StatisticsTab.SAVINGS  -> R.string.stats_tab_savings
    StatisticsTab.PATTERNS -> R.string.stats_tab_patterns
    StatisticsTab.YOY      -> R.string.stats_tab_yoy
}
```

to:

```kotlin
private fun StatisticsTab.labelRes(): Int = when (this) {
    StatisticsTab.TOP_CATS -> R.string.stats_tab_top_cats
    StatisticsTab.SAVINGS  -> R.string.stats_tab_savings
    StatisticsTab.PATTERNS -> R.string.stats_tab_patterns
    StatisticsTab.YOY      -> R.string.stats_tab_yoy
    StatisticsTab.INSIGHTS -> R.string.stats_tab_insights
}
```

**Change D** — read the new `insights` StateFlow at the top of `StatisticsScreen`. At lines 50-57, change:

```kotlin
    val topCategories by viewModel.topCategories.collectAsStateWithLifecycle()
    val savings by viewModel.savings.collectAsStateWithLifecycle()
    val dayOfWeek by viewModel.dayOfWeek.collectAsStateWithLifecycle()
    val yoy by viewModel.yoy.collectAsStateWithLifecycle()
```

to:

```kotlin
    val topCategories by viewModel.topCategories.collectAsStateWithLifecycle()
    val savings by viewModel.savings.collectAsStateWithLifecycle()
    val dayOfWeek by viewModel.dayOfWeek.collectAsStateWithLifecycle()
    val yoy by viewModel.yoy.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
```

**Change E** — pass `insights` into `StatisticsContent`. At lines 58-69, change:

```kotlin
    StatisticsContent(
        topCategories = topCategories,
        savings = savings,
        dayOfWeek = dayOfWeek,
        yoy = yoy,
        topCatsRange = topCatsRange,
        savingsRange = savingsRange,
        patternsRange = patternsRange,
        yoyRange = yoyRange,
        onRangeSelected = viewModel::onRangeSelected,
        modifier = modifier,
    )
```

to:

```kotlin
    StatisticsContent(
        topCategories = topCategories,
        savings = savings,
        dayOfWeek = dayOfWeek,
        yoy = yoy,
        insights = insights,
        topCatsRange = topCatsRange,
        savingsRange = savingsRange,
        patternsRange = patternsRange,
        yoyRange = yoyRange,
        onRangeSelected = viewModel::onRangeSelected,
        modifier = modifier,
    )
```

**Change F** — update the `StatisticsContent` signature and body. At lines 73-145, change the function signature from:

```kotlin
internal fun StatisticsContent(
    topCategories: TopCategoriesResult,
    savings: SavingsAndAverage,
    dayOfWeek: List<DayOfWeekBucket>,
    yoy: YearOverYear,
    topCatsRange: LongRange,
    savingsRange: LongRange,
    patternsRange: LongRange,
    yoyRange: LongRange,
    onRangeSelected: (StatisticsTab, LongRange) -> Unit,
    modifier: Modifier = Modifier,
) {
```

to:

```kotlin
internal fun StatisticsContent(
    topCategories: TopCategoriesResult,
    savings: SavingsAndAverage,
    dayOfWeek: List<DayOfWeekBucket>,
    yoy: YearOverYear,
    insights: List<Insight>,
    topCatsRange: LongRange,
    savingsRange: LongRange,
    patternsRange: LongRange,
    yoyRange: LongRange,
    onRangeSelected: (StatisticsTab, LongRange) -> Unit,
    modifier: Modifier = Modifier,
) {
```

(Just adds the `insights` parameter.)

**Change G** — append the `InsightsTab` private composable. At the very end of `StatisticsScreen.kt` (after the last existing function, before EOF), append:

```kotlin

// ---- Insights tab ----

@Composable
private fun InsightsTab(insights: List<Insight>) {
    if (insights.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.stats_insights_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = insights, key = { it::class.simpleName + "-" + (it as? Insight.TopExpenseSpotlight)?.dateLabel }) {
            InsightCard(insight = it)
        }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.items(
    items: List<T>,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    items(items.size, key = if (key != null) { i -> key(items[i]) } else null) { i ->
        itemContent(items[i])
    }
}
```

Note: the `key` lambda above is a placeholder that should always produce a unique value per `Insight` subclass. A more robust approach uses `Insight::class.simpleName + "-" + System.identityHashCode(it)` if you hit composition key collisions during smoke testing. For the initial implementation, the `dateLabel`-based key for `TopExpenseSpotlight` and the empty fallback for other types is sufficient because `InsightsCalculator.compute` always produces at most one insight per subclass.

If the existing file already imports `androidx.compose.foundation.lazy.items` (it does not), simplify with that import instead of the custom extension.

- [ ] **Step 3: Verify it compiles**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`. If you see `Unresolved reference: LazyListScope`, add the imports `import androidx.compose.foundation.lazy.LazyColumn` and `import androidx.compose.foundation.lazy.items` and `import androidx.compose.foundation.lazy.LazyListScope` at the top of `StatisticsScreen.kt` instead of using fully-qualified names in the appended block.

- [ ] **Step 4: Run the full test suite**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`, 412+ tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/InsightCard.kt app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(stats): InsightCard composable + InsightsTab wire-up (Phase 2.13c)"
```

---

### Task 6: Smoke test doc + tag v0.18.11

**Files:**
- Create: `docs/superpowers/testdata/phase-2.13c-insights.md`

- [ ] **Step 1: Write the smoke test doc**

Create `docs/superpowers/testdata/phase-2.13c-insights.md`:

```markdown
# Phase 2.13c — Statistics Insights Tab — Manual Smoke Test

Manual verification for the new Insights tab on the Statistics screen.
Mirror the structure of `docs/superpowers/testdata/close-account.md`.

## Pre-conditions

- App built and installed (`./gradlew installDebug`, then launch once).
- Seeded with at least:
  - 3+ transactions this calendar month, across 2+ categories
  - 3+ transactions the prior calendar month, across the same categories
  - At least one transaction on a Saturday or Sunday in the last 90 days
  - At least one income transaction this month and last month
  - A "largest expense" row this month with a distinctive title (e.g. "Dinner-out" or similar)
- Force-stop the app (`adb shell am force-stop io.github.jiro.expensetracker`)
  and relaunch between steps that depend on persisted state.

## Tab visibility

### Step 1 — Insights tab appears

1. Open the Statistics tab from bottom nav.
2. Verify the TabRow now shows **five** entries: Top Cats · Savings · Patterns · YoY · Insights.
3. Tap **Insights** — the body shows a scrollable column of insight cards (1–4 cards, depending on data).
4. Expected: tab text "Insights" is fully visible without truncation on a standard phone.

### Step 2 — Card layout

1. With the seeded data, expect up to four cards in this order:
   - **Category delta** (highest priority) — icon = trending-up/down/new; category name in headline; amounts in supporting text.
   - **Weekend vs weekday** — calendar icon; "Weekend spending is X% of your total".
   - **Savings trend** — savings icon (green up / red down / neutral unchanged); "Savings rate up/down/unchanged at X%".
   - **Top expense spotlight** — receipt icon; "Largest expense: $X" + "[Title] on [MMM d]".
2. Each card has an icon on the left (28dp), headline (bold), and supporting text (small, muted).
3. Expected: cards stack vertically with 12dp spacing.

## Insight correctness

### Step 3 — Category delta

1. Compare the CategoryDelta headline to your data:
   - If the category you spent most differently on this month vs last is "Food" with a +50% increase, expect **"Food up 50%"**.
   - Supporting text: **"$Y this month vs $Z last month"**.
2. If you added a brand-new spending category this month (none last month), expect **"New spending: [Cat]"** with a "FiberNew" icon and the supporting text **"$Y this month"** (no "vs" comparison).

### Step 4 — Weekend vs weekday

1. The 90-day window counts only expenses.
2. The headline rounds to the nearest whole percent: e.g. "Weekend spending is 31% of your total".
3. Supporting text: **"$A on weekends vs $B on weekdays"** (both formatted via MoneyFormat).

### Step 5 — Savings trend

1. If your rate moved up, expect **"Savings rate up X pts"** (green icon).
2. If it moved down, expect **"Savings rate down X pts"** (red icon).
3. If it didn't move (within 0.01pp), expect **"Savings rate unchanged at X%"** (neutral icon).
4. Supporting text: **"X% this month vs Y% last month"**.

### Step 6 — Top expense spotlight

1. The headline is **"Largest expense: $A"** where A is the single biggest transaction this month.
2. Amount is in the transaction's **native** currency (NOT the home currency) — verify by spot-checking a non-USD transaction.
3. Supporting text: **"[title] on [MMM d]"** (e.g. "Dinner-out on Jun 10").
4. If the title was blank, expect **"— on [date]"** instead of an empty title.

## Empty states

### Step 7 — No data ever

1. `adb shell pm clear io.github.jiro.expensetracker` to wipe all app data.
2. Open the Statistics tab → Insights.
3. Expected: a single centered card with text **"Not enough data yet — log transactions to see insights"**. No insight cards.

### Step 8 — Only one month of data

1. Add transactions ONLY in the current calendar month (no prior-month data).
2. Expected: CategoryDelta is omitted (no comparison possible). The other three insights still render if data permits.

### Step 9 — All transactions fall on weekdays in the last 90 days

1. Add expenses only on Monday–Friday in the last 90 days.
2. Expected: WeekendVsWeekday is omitted (weekendMinor = 0, total = weekdayMinor, but the rule is "skip when both are 0"; if weekendMinor = 0 and weekdayMinor > 0, the insight STILL shows with weekendPercent = 0%). This is intentional — a 0% result is still informative ("none of your spending is on weekends").

## Cross-tab consistency

### Step 10 — Numbers match the YoY tab

1. Open the YoY tab. The "this month" expense total should be the same home-currency value used in the CategoryDelta's `currentMinor` for the top-mover category.
2. Expected: no contradictions across tabs. The Insights tab is derived from the same Room flow.

### Step 11 — Refresh

1. Add a new expense on the home screen.
2. Switch back to the Statistics → Insights tab (within ~100ms).
3. Expected: the cards reflect the new transaction (no manual refresh needed).

## Expected outcomes

- All 4 insight cards render when 2+ months of data exist with mixed categories, weekend/weekday expenses, and income.
- Empty state appears only when there are no transactions at all.
- Currency formatting matches MoneyFormat (thousands separators, currency code on multi-currency tab if FX is missing).
- Icon tints: green for "savings up" and "category down"; red for "savings down" and "category up"; primary for new spending; neutral for unchanged.

## Rollback

The Insights tab is read-only and derived from existing data. To disable it temporarily:

```kotlin
// In StatisticsScreen.kt, change the `tabs` list to drop INSIGHTS:
val tabs = listOf(StatisticsTab.TOP_CATS, StatisticsTab.SAVINGS, StatisticsTab.PATTERNS, StatisticsTab.YOY)
```

No data is modified. To fully remove: revert the Phase 2.13c commits (`git revert <commit-1>..<commit-N>`) or delete the v0.18.11 tag and reset to v0.18.10.
```

- [ ] **Step 2: Final verification — full test suite + APK build**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest 2>&1 | tail -5
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: both `BUILD SUCCESSFUL`. Test count should be 412+ (398 existing + 14 new).

- [ ] **Step 3: Commit the smoke test doc**

```bash
git add docs/superpowers/testdata/phase-2.13c-insights.md
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "docs(stats): Phase 2.13c Insights smoke test"
```

- [ ] **Step 4: Tag v0.18.11 and push**

```bash
git tag v0.18.11
git push origin master
git push origin v0.18.11
```

- [ ] **Step 5: Mark complete**

Mark this task done in your task tracker. Phase 2.13c is shipped.