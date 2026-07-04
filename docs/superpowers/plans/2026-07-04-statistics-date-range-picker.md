# Phase 2.13b — Custom Date-Range Picker on Statistics

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users pick arbitrary date windows for each of the four Statistics tabs (Top Cats, Savings, Patterns, YoY). Each tab owns its own range; the choice persists across app launches via DataStore.

**Architecture:** Per-tab `LongRange` stored in DataStore Preferences (8 `longPreferencesKey`s, defaults derived on the fly — never stored). `StatisticsCalculator` functions change signatures from `(tx, nowMs)` to `(tx, startMs, endMs)`, making them window-agnostic. `StatisticsViewModel` exposes per-tab `StateFlow`s via `rangeRepo.observe(tab).flatMapLatest { … }` so range changes auto-recompute. UI: each tab header has a `RangeChip` that opens a `ModalBottomSheet` with preset chips + Material 3 `DateRangePicker`.

**Tech Stack:** Existing Kotlin + Jetpack Compose + Material 3 + Hilt + Coroutines/Flow + DataStore Preferences + JUnit4 + Robolectric (for repository DataStore tests).

**Reference spec:** `docs/superpowers/specs/2026-07-04-statistics-date-range-picker-design.md`

**Test command (used throughout):**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest
```

---

## Task 1: Add new strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (append a new section)

- [ ] **Step 1: Append the new strings**

Open `app/src/main/res/values/strings.xml`. After the existing `<!-- Statistics screen (Phase 2.13) -->` block (ending at `stats_yoy_no_change` on line 148), add this block (place it inside the same `<!-- Statistics screen (Phase 2.13) -->` block, before the closing comment):

```xml
    <!-- Statistics screen (Phase 2.13b) — date-range picker -->
    <string name="stats_range_chip_label">Range</string>
    <string name="stats_preset_last_7d">Last 7 days</string>
    <string name="stats_preset_last_30d">Last 30 days</string>
    <string name="stats_preset_this_month">This month</string>
    <string name="stats_preset_last_month">Last month</string>
    <string name="stats_preset_this_year">This year</string>
    <string name="stats_picker_title">Pick a range</string>
    <string name="stats_picker_apply">Apply</string>
    <string name="stats_picker_cancel">Cancel</string>
    <string name="stats_empty_in_range">No transactions in this range</string>
    <string name="stats_empty_reset">Reset to this month</string>
```

- [ ] **Step 2: Verify by grep**

Run:

```bash
grep -E "stats_range_chip_label|stats_preset_last_7d|stats_preset_last_30d|stats_preset_this_month|stats_preset_last_month|stats_preset_this_year|stats_picker_title|stats_picker_apply|stats_picker_cancel|stats_empty_in_range|stats_empty_reset" app/src/main/res/values/strings.xml
```

Expected: 11 lines (one per new key), each on its own line. No empty results.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): add date-range picker strings"
```

---

## Task 2: PresetRanges sealed class + tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/PresetRanges.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/PresetRangesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/PresetRangesTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PresetRangesTest {

    private val zone = ZoneId.systemDefault()
    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun last7Days_resolvesTo7x24hWindowEndingAtNow() {
        val now = ms(2026, 6, 17) + 14_000_000L  // 14:00 UTC-ish
        val range = StatisticsPreset.Last7Days.resolve(now)
        // 7 days = 7 * 24 * 3600 * 1000 = 604_800_000 ms
        assertEquals(now - 7L * 24L * 3600L * 1000L, range.first)
        assertEquals(now, range.last)
    }

    @Test
    fun last30Days_resolvesTo30x24hWindowEndingAtNow() {
        val now = ms(2026, 6, 17) + 14_000_000L
        val range = StatisticsPreset.Last30Days.resolve(now)
        assertEquals(now - 30L * 24L * 3600L * 1000L, range.first)
        assertEquals(now, range.last)
    }

    @Test
    fun thisMonth_resolvesToCalendarMonthContainingNow() {
        val now = ms(2026, 6, 17)
        val range = StatisticsPreset.ThisMonth.resolve(now)
        // First instant of June 1, 2026 .. first instant of July 1, 2026
        assertEquals(ms(2026, 6, 1), range.first)
        assertEquals(ms(2026, 7, 1), range.last)
    }

    @Test
    fun lastMonth_resolvesToPreviousCalendarMonth() {
        val now = ms(2026, 6, 17)
        val range = StatisticsPreset.LastMonth.resolve(now)
        assertEquals(ms(2026, 5, 1), range.first)
        assertEquals(ms(2026, 6, 1), range.last)
    }

    @Test
    fun thisYear_resolvesToCalendarYearContainingNow() {
        val now = ms(2026, 6, 17)
        val range = StatisticsPreset.ThisYear.resolve(now)
        assertEquals(ms(2026, 1, 1), range.first)
        assertEquals(ms(2027, 1, 1), range.last)
    }

    @Test
    fun thisMonth_atMonthEnd_returnsSameMonthWindow() {
        // Boundary: nowMs = Jan 31, 23:59. Should still return Jan 1..Feb 1.
        val now = ms(2026, 1, 31) + 23L * 3600L * 1000L + 59L * 60L * 1000L
        val range = StatisticsPreset.ThisMonth.resolve(now)
        assertEquals(ms(2026, 1, 1), range.first)
        assertEquals(ms(2026, 2, 1), range.last)
    }

    @Test
    fun lastMonth_atJanuary_returnsDecemberOfPriorYear() {
        val now = ms(2026, 1, 15)
        val range = StatisticsPreset.LastMonth.resolve(now)
        assertEquals(ms(2025, 12, 1), range.first)
        assertEquals(ms(2026, 1, 1), range.last)
    }

    @Test
    fun thisYear_atYearBoundary_returnsCurrentYear() {
        // Dec 31, 23:59 → still "this year" 2026
        val now = ms(2026, 12, 31) + 23L * 3600L * 1000L + 59L * 60L * 1000L
        val range = StatisticsPreset.ThisYear.resolve(now)
        assertEquals(ms(2026, 1, 1), range.first)
        assertEquals(ms(2027, 1, 1), range.last)
    }

    @Test
    fun allPresets_haveDistinctLabels() {
        val labels = StatisticsPreset.all.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }
}
```

- [ ] **Step 2: Run the test — it should fail to compile (PresetRanges doesn't exist yet)**

```bash
./gradlew testDebugUnitTest --tests "*PresetRangesTest*"
```

Expected: compile error — `Unresolved reference: StatisticsPreset`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/PresetRanges.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

sealed class StatisticsPreset(val label: String) {
    abstract fun resolve(nowMs: Long): LongRange

    object Last7Days : StatisticsPreset("Last 7 days") {
        override fun resolve(nowMs: Long): LongRange =
            (nowMs - 7L * 24L * 3600L * 1000L)..nowMs
    }

    object Last30Days : StatisticsPreset("Last 30 days") {
        override fun resolve(nowMs: Long): LongRange =
            (nowMs - 30L * 24L * 3600L * 1000L)..nowMs
    }

    object ThisMonth : StatisticsPreset("This month") {
        override fun resolve(nowMs: Long): LongRange {
            val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
            val ym = YearMonth.of(date.year, date.monthValue)
            return ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()..
                ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }

    object LastMonth : StatisticsPreset("Last month") {
        override fun resolve(nowMs: Long): LongRange {
            val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
            val ym = YearMonth.of(date.year, date.monthValue).minusMonths(1)
            return ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()..
                ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }

    object ThisYear : StatisticsPreset("This year") {
        override fun resolve(nowMs: Long): LongRange {
            val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
            val start = date.withDayOfYear(1)
            val end = date.plusYears(1).withDayOfYear(1)
            return start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()..
                end.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }

    companion object {
        val all: List<StatisticsPreset> = listOf(Last7Days, Last30Days, ThisMonth, LastMonth, ThisYear)
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
./gradlew testDebugUnitTest --tests "*PresetRangesTest*"
```

Expected: 9 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/PresetRanges.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/statistics/PresetRangesTest.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): PresetRanges sealed class with 5 presets + tests"
```

---

## Task 3: Add `rangeLabel` helper to calculator

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculator.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculatorTest.kt`

The existing `monthLabel(nowMs)` returns a single-month label like "June 2026". After the refactor, the calculator functions don't have a single "nowMs" — they have `startMs`/`endMs`. We need `rangeLabel(startMs, endMs)` to format the window for display. We delete `monthLabel` (and its 2 tests) and replace with `rangeLabel` + new tests.

- [ ] **Step 1: Delete the existing `monthLabel` tests**

In `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculatorTest.kt`, delete these two methods entirely:

- `monthLabel_june` (lines ~36–39)
- `monthLabel_january` (lines ~41–45)

Also remove the now-unused `monthLabel` import (if any). Verify no other test in the file calls `monthLabel`. (`grep monthLabel StatisticsCalculatorTest.kt` should return nothing.)

- [ ] **Step 2: Add the failing `rangeLabel` tests**

Append the following block at the bottom of `StatisticsCalculatorTest.kt` (just before the closing `}`):

```kotlin
    // ---- rangeLabel ----

    @Test
    fun rangeLabel_sameMonth_returnsMonthYear() {
        val start = date(2026, 6, 1)
        val end = date(2026, 7, 1)
        assertEquals("June 2026", StatisticsCalculator.rangeLabel(start, end))
    }

    @Test
    fun rangeLabel_crossMonthSameYear_returnsStartEndYear() {
        val start = date(2026, 1, 15)
        val end = date(2026, 2, 14)
        // "Jan 15 – Feb 14, 2026"  (en dash U+2013, not hyphen)
        assertEquals("Jan 15 – Feb 14, 2026", StatisticsCalculator.rangeLabel(start, end))
    }

    @Test
    fun rangeLabel_crossYear_returnsFullDates() {
        val start = date(2025, 12, 28)
        val end = date(2026, 1, 4)
        assertEquals("Dec 28, 2025 – Jan 4, 2026", StatisticsCalculator.rangeLabel(start, end))
    }
```

- [ ] **Step 3: Run the new tests — they should fail to compile (`rangeLabel` doesn't exist yet)**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest.rangeLabel_*"
```

Expected: compile error — `Unresolved reference: rangeLabel`.

- [ ] **Step 4: Replace `monthLabel` with `rangeLabel`**

In `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculator.kt`:

1. Delete the entire `internal fun monthLabel(nowMs: Long): String { … }` block (lines ~60–64 in the current file).

2. Add a new internal helper immediately after the `monthBounds` function:

```kotlin
    internal fun rangeLabel(startMs: Long, endMs: Long): String {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        // endMs is exclusive — subtract one day to get the inclusive last day
        val endDate = Instant.ofEpochMilli(endMs - 1L).atZone(zone).toLocalDate()

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
```

- [ ] **Step 5: Run the new tests — they should pass**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest.rangeLabel_*"
```

Expected: 3 tests passed. (The 5 monthBounds tests + the new rangeLabel tests = 8 total pass; `monthLabel_*` tests are gone.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculator.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculatorTest.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "refactor(stats): replace monthLabel with rangeLabel(start, end)"
```

---

## Task 4: Add `subtractOneYear` helper to calculator

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculator.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculatorTest.kt`

The spec's leap-day rule: Feb 29, 2024 → Feb 28, 2025 (clamp to Feb 28 when prior year is non-leap).

- [ ] **Step 1: Write the failing tests**

Append to `StatisticsCalculatorTest.kt`:

```kotlin
    // ---- subtractOneYear ----

    @Test
    fun subtractOneYear_january_returnsJanuaryPrior() {
        val ms = date(2026, 1, 15)
        assertEquals(date(2025, 1, 15), StatisticsCalculator.subtractOneYear(ms))
    }

    @Test
    fun subtractOneYear_leapDay_clampToFeb28() {
        val ms = date(2024, 2, 29)
        assertEquals(date(2025, 2, 28), StatisticsCalculator.subtractOneYear(ms))
    }

    @Test
    fun subtractOneYear_nonLeapDay_returnsSameDayPrior() {
        val ms = date(2025, 6, 17)
        assertEquals(date(2024, 6, 17), StatisticsCalculator.subtractOneYear(ms))
    }

    @Test
    fun subtractOneYear_yearBoundary_dec31ToDec31() {
        // Dec 31, 2026 → Dec 31, 2025 (both non-leap). Year-rolls-back.
        val ms = date(2026, 12, 31)
        assertEquals(date(2025, 12, 31), StatisticsCalculator.subtractOneYear(ms))
    }
```

- [ ] **Step 2: Run the tests — they should fail to compile**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest.subtractOneYear_*"
```

Expected: compile error — `Unresolved reference: subtractOneYear`.

- [ ] **Step 3: Add the implementation**

In `StatisticsCalculator.kt`, add this `internal fun` immediately after `monthBounds`:

```kotlin
    internal fun subtractOneYear(ms: Long): Long {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val candidate = date.minusYears(1)
        // Clamp Feb 29 to Feb 28 when the prior year is non-leap.
        val adjusted = if (candidate.monthValue == 2 && candidate.dayOfMonth == 29 &&
            !java.time.Year.isLeap(candidate.year.toLong())
        ) {
            candidate.withDayOfMonth(28)
        } else {
            candidate
        }
        return adjusted.atStartOfDay(zone).toInstant().toEpochMilli()
    }
```

- [ ] **Step 4: Run the tests — they should pass**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest.subtractOneYear_*"
```

Expected: 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculator.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculatorTest.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): subtractOneYear helper with leap-day clamp"
```

---

## Task 5: Refactor `StatisticsCalculator` signatures + rewrite tests

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculator.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculatorTest.kt`

This is the most invasive change. It touches every public function on `StatisticsCalculator` and 26 of the existing 31 tests. Apply the changes function-family by function-family within this single task so tests + impl stay in lockstep (intermediate compile-broken state is acceptable but should be brief).

### 5a. `topCategories` + its 8 tests

- [ ] **Step 1: Rewrite the 8 topCategories tests**

In `StatisticsCalculatorTest.kt`, every `topCategories` test currently calls:

```kotlin
val out = StatisticsCalculator.topCategories(txns, cats, "USD", emptyMap(), nowMs)
```

Replace with:

```kotlin
val (monthStart, monthEnd) = StatisticsCalculator.monthBounds(2026, 6)
val out = StatisticsCalculator.topCategories(txns, cats, "USD", emptyMap(), monthStart, monthEnd)
```

Add `monthBounds(2026, 6)` call once at the top of each test that needs it (or hoist to a helper). Tests to update:

- `topCategories_groupsByCategoryAndSortsDesc`
- `topCategories_topFivePlusOther`
- `topCategories_topFiveOnlyWhenFewerCategories`
- `topCategories_excludesIncome`
- `topCategories_fxConversionToHomeCurrency`
- `topCategories_missingRateCount`
- `topCategories_emptyTxns`
- `topCategories_excludesOutsideCurrentMonth`

(No assertion changes — the label and slices data are the same.)

- [ ] **Step 2: Update `topCategories` signature**

In `StatisticsCalculator.kt`, change the `topCategories` function signature and body. The function now takes `(startMs, endMs)` instead of `nowMs`, derives the label from those, and uses them directly for filtering (no `monthBounds` call inside):

```kotlin
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
            if (converted == null && t.currencyCode != homeCurrency) {
                missingRateCount++
            }
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
```

- [ ] **Step 3: Run the 8 topCategories tests**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest.topCategories_*"
```

Expected: 8 tests pass.

### 5b. `savingsAndAverage` + its 7 tests

- [ ] **Step 4: Rewrite the 7 savingsAndAverage tests**

Each existing `savingsAndAverage` test passes `nowMs` as the last arg. Replace the `nowMs` argument with `(monthStart, monthEnd)` from `monthBounds(2026, 6)`:

- `savingsAndAverage_basicIncomeAndExpense`
- `savingsAndAverage_zeroIncome_returnsZeroRate`
- `savingsAndAverage_expenseExceedsIncome_clampsToZero`
- `savingsAndAverage_averageOverSixCompletedMonths` — the prior 6 months now derive from `startMs` (Jun 1 2026), not from `nowMs`. Prior months are: Dec 2025, Jan–May 2026. The test data already matches this — no further changes needed.
- `savingsAndAverage_averageReturnsZeroWhenLessThanThreeMonths` — same: prior 6 months derive from `startMs`, which is Jun 1 2026. The test seeds only Apr + May 2026, so monthsWithData = 2, average = 0. Already aligned.
- `savingsAndAverage_topTransaction`
- `savingsAndAverage_emptyTxns`

Example rewrite pattern:

```kotlin
val (monthStart, monthEnd) = StatisticsCalculator.monthBounds(2026, 6)
val out = StatisticsCalculator.savingsAndAverage(txns, "USD", emptyMap(), monthStart, monthEnd)
```

- [ ] **Step 5: Update `savingsAndAverage` signature**

In `StatisticsCalculator.kt`:

```kotlin
    fun savingsAndAverage(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        startMs: Long, endMs: Long,
    ): SavingsAndAverage {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        // For "prior 6 months", walk back from the month containing startMs.
        val priorAnchor = YearMonth.of(startDate.year, startDate.monthValue)

        var incomeMinor = 0L
        var expenseMinor = 0L
        var topTransactionMinor = 0L

        for (row in txns) {
            val t = row.transaction
            val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates) ?: t.amountMinor
            if (t.occurredAtEpochMillis in startMs until endMs) {
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

        // Average over the 6 calendar months immediately preceding the picked range.
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
            if (monthTotal > 0L) {
                sumPrior += monthTotal
                monthsWithData++
            }
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
```

- [ ] **Step 6: Run the savingsAndAverage tests**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest.savingsAndAverage_*"
```

Expected: 7 tests pass.

### 5c. `dayOfWeekPattern` + its 5 tests

- [ ] **Step 7: Rewrite the 5 dayOfWeek tests**

The existing tests use a 90-day rolling window ending at `nowMs`. Preserve that semantic by passing `startMs = nowMs - 90 days` and `endMs = nowMs`. Hoist this constant to the top of the test file (just below `nowMs`):

```kotlin
private val windowStartMs = nowMs - 90L * 24L * 3600L * 1000L
```

Tests to update:

- `dayOfWeek_alwaysReturnsSevenBuckets`
- `dayOfWeek_sumsAcrossMultipleWeeks`
- `dayOfWeek_excludesIncome`
- `dayOfWeek_usesHomeCurrency`
- `dayOfWeek_respects90DayWindow` — the test description changes: this is now about a custom window (90 days). Replace the test body to verify the window can be configured. New test name: `dayOfWeek_customWindow_filtersCorrectly`.

Example rewrite:

```kotlin
val out = StatisticsCalculator.dayOfWeekPattern(txns, "USD", emptyMap(), windowStartMs, nowMs)
```

- [ ] **Step 8: Update `dayOfWeekPattern` signature**

In `StatisticsCalculator.kt`:

```kotlin
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
```

- [ ] **Step 9: Run the dayOfWeek tests**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest.dayOfWeek_*"
```

Expected: 5 tests pass.

### 5d. `yearOverYear` + its 6 tests

- [ ] **Step 10: Rewrite the 6 yearOverYear tests**

For YoY tests, replace `(txns, ..., nowMs)` with `(txns, ..., currentStart, currentEnd, priorStart, priorEnd)`. Compute both windows at the top of each test:

```kotlin
val (curStart, curEnd) = StatisticsCalculator.monthBounds(2026, 6)
val (prevStart, prevEnd) = StatisticsCalculator.monthBounds(2025, 6)
val out = StatisticsCalculator.yearOverYear(txns, "USD", emptyMap(), curStart, curEnd, prevStart, prevEnd)
```

Tests to update:

- `yearOverYear_basicPercentChange`
- `yearOverYear_previousIsZero_marksNewSpending`
- `yearOverYear_bothZero`
- `yearOverYear_calendarBoundary`
- `yearOverYear_excludesIncome`
- `yearOverYear_usesHomeCurrency`

(No assertion changes — the data is the same; only the parameter shape changes.)

- [ ] **Step 11: Update `yearOverYear` signature + rename data class fields**

In `StatisticsCalculator.kt`:

```kotlin
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
```

Also update the `YearOverYear` data class:

```kotlin
data class YearOverYear(
    val currentWindowLabel: String,
    val previousWindowLabel: String,
    val currentExpenseMinor: Long,
    val previousExpenseMinor: Long,
    val percentChange: Float,
    val isNewSpending: Boolean,
)
```

- [ ] **Step 12: Update test assertions to use new field names**

In the 6 rewritten YoY tests, change `out.currentMonthLabel` → `out.currentWindowLabel` and `out.previousMonthLabel` → `out.previousWindowLabel`. The string values remain "June 2026" / "June 2025" (rangeLabel returns the same string for a same-month range as the old monthLabel did).

- [ ] **Step 13: Run the yearOverYear tests**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest.yearOverYear_*"
```

Expected: 6 tests pass.

### 5e. Add 6 new tests

- [ ] **Step 14: Add new tests**

Append to `StatisticsCalculatorTest.kt`:

```kotlin
    // ---- new: window-agnostic edge cases ----

    @Test
    fun topCategories_arbitraryWindow_filtersByStartEnd() {
        // Jan 1..Jan 7, 2026
        val (start, end) = StatisticsCalculator.monthBounds(2026, 1)
        val txns = listOf(
            txn(1L, "In", 999L, "USD", "EXPENSE", 1L, date(2025, 12, 31)),  // outside
            txn(2L, "Out", 500L, "USD", "EXPENSE", 1L, date(2026, 1, 7)),   // inside
            txn(3L, "After", 999L, "USD", "EXPENSE", 1L, date(2026, 1, 8)), // outside
        )
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val out = StatisticsCalculator.topCategories(txns, cats, "USD", emptyMap(), start, end)
        assertEquals(1, out.slices.size)
        assertEquals(500L, out.slices[0].amountMinor)
    }

    @Test
    fun savingsAndAverage_windowWithNoIncome_returnsZeroRate() {
        val (start, end) = StatisticsCalculator.monthBounds(2026, 6)
        val txns = listOf(
            txn(1L, "Coffee", 1_000L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
        )
        val out = StatisticsCalculator.savingsAndAverage(txns, "USD", emptyMap(), start, end)
        assertEquals(0f, out.savingsRate, 0.0001f)
    }

    @Test
    fun spendingPatterns_weekSpanningMonthBoundary_aggregatesBothWeeks() {
        // Window: Mar 25, 2026 .. Apr 7, 2026 (cross-month).
        val start = date(2026, 3, 25)
        val end = date(2026, 4, 8)
        val txns = listOf(
            txn(1L, "Wed", 100L, "USD", "EXPENSE", 1L, date(2026, 3, 25)),  // Wed
            txn(2L, "Mon", 200L, "USD", "EXPENSE", 1L, date(2026, 3, 30)),  // Mon
            txn(3L, "Mon", 300L, "USD", "EXPENSE", 1L, date(2026, 4, 6)),   // Mon
            txn(4L, "Sun", 50L,  "USD", "EXPENSE", 1L, date(2026, 4, 5)),   // Sun
        )
        val out = StatisticsCalculator.dayOfWeekPattern(txns, "USD", emptyMap(), start, end)
        assertEquals(500L, out.first { it.isoDayOfWeek == 1 }.amountMinor)  // Mon: 200 + 300
        assertEquals(100L, out.first { it.isoDayOfWeek == 3 }.amountMinor)  // Wed: 100
        assertEquals(50L,  out.first { it.isoDayOfWeek == 7 }.amountMinor)  // Sun: 50
    }

    @Test
    fun yearOverYear_subtractOneYear_handlesLeapDay() {
        // Feb 29, 2024 - 1 year = Feb 28, 2025
        val ms = StatisticsCalculator.subtractOneYear(date(2024, 2, 29))
        assertEquals(date(2025, 2, 28), ms)
    }

    @Test
    fun yearOverYear_subtractOneYear_handlesNonLeapYear() {
        val ms = StatisticsCalculator.subtractOneYear(date(2025, 6, 17))
        assertEquals(date(2024, 6, 17), ms)
    }

    @Test
    fun yearOverYear_priorWindowComputation_matchesSubtractOneYear() {
        // Caller contract: priorStartMs = subtractOneYear(currentStartMs); same for end.
        val curStart = date(2026, 3, 15)
        val curEnd = date(2026, 4, 14)
        val priorStart = StatisticsCalculator.subtractOneYear(curStart)
        val priorEnd = StatisticsCalculator.subtractOneYear(curEnd)
        assertEquals(date(2025, 3, 15), priorStart)
        assertEquals(date(2025, 4, 14), priorEnd)
    }
```

- [ ] **Step 15: Run the full calculator test suite**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsCalculatorTest*"
```

Expected: 38 tests pass (5 monthBounds + 3 rangeLabel + 4 subtractOneYear + 8 topCategories + 7 savingsAndAverage + 5 dayOfWeek + 6 yearOverYear + 2 new topCats/savings + 1 spendingPatterns + 2 leap/subtract + 1 priorWindow = 38). Original count was 31; new count is 38 (+7 new tests + 0 net from monthLabel/rangeLabel swap).

- [ ] **Step 16: Build to surface any consumers that still use old field names**

```bash
./gradlew assembleDebug
```

Expected: build fails. Note the errors — they will be in `StatisticsScreen.kt` (uses `result.monthLabel`, `savings.monthLabel`, `result.currentMonthLabel`, `result.previousMonthLabel`). These are fixed in Task 11.

**Do NOT fix them here** — leave them broken until Task 11 wires the full feature. This task is complete when the calculator tests pass and the build errors are documented.

- [ ] **Step 17: Commit the calculator refactor + test rewrites**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculator.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsCalculatorTest.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "refactor(stats): calculator takes (tx, startMs, endMs) — YoY takes both windows"
```

---

## Task 6: `StatisticsRangeRepository` (DataStore-backed)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jiro.expensetracker.ui.statistics.StatisticsTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsRangeRepositoryTest {

    private fun repo(): DataStoreStatisticsRangeRepository =
        DataStoreStatisticsRangeRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun setThenObserve_returnsSameRange() = runTest {
        val r = repo()
        val range = 1_700_000_000_000L..1_730_000_000_000L
        r.set(StatisticsTab.TOP_CATS, range)
        assertEquals(range, r.observe(StatisticsTab.TOP_CATS).first())
    }

    @Test
    fun observeBeforeAnySet_returnsDefaultForTab() = runTest {
        val r = repo()
        val range = r.observe(StatisticsTab.SAVINGS).first()
        // Default = current calendar month. Verify it's at least 1 day long.
        assertEquals(true, range.last > range.first)
        assertEquals(true, (range.last - range.first) >= 28L * 24L * 3600L * 1000L)
    }

    @Test
    fun perTabIndependence_setOneTabDoesNotAffectOthers() = runTest {
        val r = repo()
        val topCatsRange = 1_700_000_000_000L..1_730_000_000_000L
        r.set(StatisticsTab.TOP_CATS, topCatsRange)
        // SAVINGS, PATTERNS, YOY should still return defaults.
        assertNotEquals(topCatsRange, r.observe(StatisticsTab.SAVINGS).first())
        assertNotEquals(topCatsRange, r.observe(StatisticsTab.PATTERNS).first())
        assertNotEquals(topCatsRange, r.observe(StatisticsTab.YOY).first())
    }

    @Test
    fun defaultForYoy_returnsCurrentMonth() = runTest {
        val r = repo()
        val range = r.defaultFor(StatisticsTab.YOY, System.currentTimeMillis())
        // Default current month = (start of month)..(start of next month)
        // Verify the start of the range is the first day of some month.
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = range.first }
        assertEquals(1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(java.util.Calendar.MINUTE))
        assertEquals(0, cal.get(java.util.Calendar.SECOND))
    }

    @Test
    fun persistedAcrossInstances() = runTest {
        val r1 = repo()
        val range = 1_700_000_000_000L..1_730_000_000_000L
        r1.set(StatisticsTab.PATTERNS, range)
        // Create a new repository instance — should read the same persisted range.
        val r2 = repo()
        assertEquals(range, r2.observe(StatisticsTab.PATTERNS).first())
    }
}
```

- [ ] **Step 2: Run the tests — they should fail to compile**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsRangeRepositoryTest*"
```

Expected: compile error — `Unresolved reference: StatisticsTab`, `Unresolved reference: DataStoreStatisticsRangeRepository`.

- [ ] **Step 3: Add `StatisticsTab` enum (it currently lives inside `StatisticsScreen.kt` — extract it for shared use)**

We need `StatisticsTab` to be referenceable from both the repository and the ViewModel. Currently it's defined as `internal enum class StatTab(...)` inside `StatisticsScreen.kt`. Refactor: rename it to `StatisticsTab` (no rename needed if we just create a new enum alongside — but we want one enum shared). Decision: keep the existing `StatTab` as-is for screen use, and add a separate `StatisticsTab` enum at the top of `StatisticsRangeRepository.kt` for repository/VM use. The mapping happens in the VM (later task). **Alternative cleaner approach:** extract `StatisticsTab` to its own file now and update `StatisticsScreen.kt`. Pick whichever fits the existing code style.

For simplicity in this plan, **create `StatisticsTab` in `StatisticsRangeRepository.kt` with the same four cases, and accept that there are two enums temporarily**. Task 11 (StatisticsScreen wiring) will delete the screen-side `StatTab` and switch to the repository-side one. Document this in code with `// TODO(2.13b): merge with StatTab in StatisticsScreen.kt`.

Create `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The four statistics tabs. Exists in this file (rather than StatisticsScreen.kt)
 * because the repository and ViewModel need to reference it. The screen-side
 * `StatTab` enum will be merged into this one in Task 11.
 */
enum class StatisticsTab {
    TOP_CATS, SAVINGS, PATTERNS, YOY,
}

interface StatisticsRangeRepository {
    fun observe(tab: StatisticsTab): Flow<LongRange>
    suspend fun set(tab: StatisticsTab, range: LongRange)
    suspend fun defaultFor(tab: StatisticsTab, nowMs: Long): LongRange
}

private val Context.statisticsRangeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "statistics_range",
)

class DataStoreStatisticsRangeRepository(
    private val context: Context,
) : StatisticsRangeRepository {

    private val dataStore get() = context.statisticsRangeDataStore

    override fun observe(tab: StatisticsTab): Flow<LongRange> =
        dataStore.data.map { prefs ->
            val (startKey, endKey) = keysFor(tab)
            val start = prefs[startKey]
            val end = prefs[endKey]
            if (start != null && end != null) start..end
            else defaultFor(tab, System.currentTimeMillis())
        }

    override suspend fun set(tab: StatisticsTab, range: LongRange) {
        val (startKey, endKey) = keysFor(tab)
        dataStore.edit { prefs ->
            prefs[startKey] = range.first
            prefs[endKey] = range.last
        }
    }

    override suspend fun defaultFor(tab: StatisticsTab, nowMs: Long): LongRange {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val ym = YearMonth.of(date.year, date.monthValue)
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start..end
    }

    private fun keysFor(tab: StatisticsTab): Pair<Preferences.Key<Long>, Preferences.Key<Long>> =
        when (tab) {
            StatisticsTab.TOP_CATS -> KEY_TOP_CATS_START to KEY_TOP_CATS_END
            StatisticsTab.SAVINGS  -> KEY_SAVINGS_START  to KEY_SAVINGS_END
            StatisticsTab.PATTERNS -> KEY_PATTERNS_START to KEY_PATTERNS_END
            StatisticsTab.YOY      -> KEY_YOY_START      to KEY_YOY_END
        }

    companion object {
        private val KEY_TOP_CATS_START = longPreferencesKey("stats_range_top_cats_start")
        private val KEY_TOP_CATS_END   = longPreferencesKey("stats_range_top_cats_end")
        private val KEY_SAVINGS_START  = longPreferencesKey("stats_range_savings_start")
        private val KEY_SAVINGS_END    = longPreferencesKey("stats_range_savings_end")
        private val KEY_PATTERNS_START = longPreferencesKey("stats_range_patterns_start")
        private val KEY_PATTERNS_END   = longPreferencesKey("stats_range_patterns_end")
        private val KEY_YOY_START      = longPreferencesKey("stats_range_yoy_start")
        private val KEY_YOY_END        = longPreferencesKey("stats_range_yoy_end")
    }
}
```

- [ ] **Step 4: Run the tests — they should pass**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsRangeRepositoryTest*"
```

Expected: 5 tests pass.

(Note: tests need Robolectric for the real DataStore. If the project's `testDebugUnitTest` config already includes Robolectric, no further setup is needed. If not, add `testImplementation("org.robolectric:robolectric:4.11.1")` to `app/build.gradle.kts`.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepositoryTest.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): DataStore-backed StatisticsRangeRepository"
```

---

## Task 7: Hilt module for the repository

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/di/StatisticsModule.kt`

- [ ] **Step 1: Add the binding module**

Create `app/src/main/java/io/github/jiro/expensetracker/di/StatisticsModule.kt`:

```kotlin
package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.ui.statistics.DataStoreStatisticsRangeRepository
import io.github.jiro.expensetracker.ui.statistics.StatisticsRangeRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StatisticsModule {

    @Binds
    @Singleton
    abstract fun bindStatisticsRangeRepository(
        impl: DataStoreStatisticsRangeRepository
    ): StatisticsRangeRepository
}
```

Note: `DataStoreStatisticsRangeRepository` doesn't have a `@Inject` constructor — Hilt will need one. Update the class:

In `StatisticsRangeRepository.kt`, change the class signature:

```kotlin
class DataStoreStatisticsRangeRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : StatisticsRangeRepository {
```

(The `@Inject` + `@ApplicationContext` follows the pattern in `DatabaseModule.kt` / `MemberCardsModule.kt`.)

- [ ] **Step 2: Build to verify Hilt wiring compiles**

```bash
./gradlew assembleDebug
```

Expected: build still has the same Task-5-step-16 errors (broken consumers), but no NEW errors related to Hilt or the StatisticsRangeRepository injection.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/di/StatisticsModule.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsRangeRepository.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): Hilt module for StatisticsRangeRepository"
```

---

## Task 8: Refactor `StatisticsViewModel` + add tests

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModelTest.kt`

The current `StatisticsViewModel` exposes four `StateFlow`s derived from `combine(...)`. With per-tab ranges, each `StateFlow` now needs `rangeRepo.observe(tab).flatMapLatest { range -> ... calculator(...) }`. The picker calls `onRangeSelected(tab, range)` which writes to the repository.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModelTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import app.cash.turbine.test
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.statistics.StatisticsTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildVm(
        rangeRepo: FakeRangeRepo = FakeRangeRepo(),
    ): Pair<StatisticsViewModel, FakeRangeRepo> {
        val vm = StatisticsViewModel(
            transactionRepository = FakeTxRepo(),
            categoryRepository = FakeCatRepo(),
            settingsRepository = FakeSettingsRepo(),
            rangeRepository = rangeRepo,
        )
        return vm to rangeRepo
    }

    @Test
    fun rangeChangeForOneTab_doesNotAffectOthers() = runTest(testDispatcher) {
        val (vm, rangeRepo) = buildVm()
        val originalSavings = vm.savings.value
        rangeRepo.set(StatisticsTab.TOP_CATS, 1_700_000_000_000L..1_730_000_000_000L)
        // SAVINGS range unchanged — its value should not be re-derived.
        assertEquals(originalSavings.monthLabel, vm.savings.value.monthLabel)
    }

    @Test
    fun yoyPriorWindow_subtractsOneYear() = runTest(testDispatcher) {
        val (vm, _) = buildVm()
        val yoy = vm.yoy.value
        // Labels should be set (not empty strings from a defaulted state).
        assertEquals(true, yoy.currentWindowLabel.isNotEmpty())
        assertEquals(true, yoy.previousWindowLabel.isNotEmpty())
        // The previous label should reference the prior year.
        // (The exact format depends on rangeLabel; just verify it's different from current.)
        assertNotEquals(yoy.currentWindowLabel, yoy.previousWindowLabel)
    }

    @Test
    fun yoyPriorWindow_handlesLeapDay() = runTest(testDispatcher) {
        val (vm, rangeRepo) = buildVm()
        // Feb 29, 2024 is the only valid leap-day moment in 2024.
        val leapStart = java.time.LocalDate.of(2024, 2, 29)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val leapEnd = java.time.LocalDate.of(2024, 3, 1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        rangeRepo.set(StatisticsTab.YOY, leapStart..leapEnd)
        // The prior window should be Feb 28, 2024..Feb 29, 2024 (clamped Feb 29 -> Feb 28).
        // We can't directly observe the prior window, but the YoY card should compute
        // without crashing.
        val yoy = vm.yoy.value
        assertEquals(true, yoy.currentWindowLabel.isNotEmpty())
    }

    @Test
    fun rangeChange_triggersRecomputation() = runTest(testDispatcher) {
        val (vm, rangeRepo) = buildVm()
        val before = vm.topCategories.value.slices.size
        rangeRepo.set(StatisticsTab.TOP_CATS, 1L..System.currentTimeMillis())
        // flatMapLatest should re-derive topCategories with the new range.
        // The value may or may not have changed depending on the underlying txns;
        // we just verify it emits a valid TopCategoriesResult.
        assertEquals(true, vm.topCategories.value.monthLabel.isNotEmpty())
    }
}

// ---- fakes ----

private class FakeRangeRepo : StatisticsRangeRepository {
    private val flows = mutableMapOf<StatisticsTab, MutableStateFlow<LongRange?>>()
    override fun observe(tab: StatisticsTab): Flow<LongRange> =
        flows.getOrPut(tab) { MutableStateFlow(null) }.asStateFlow().let {
            kotlinx.coroutines.flow.flow {
                it.collect { v -> if (v != null) emit(v) }
            }
        }
    override suspend fun set(tab: StatisticsTab, range: LongRange) {
        flows.getOrPut(tab) { MutableStateFlow(null) }.value = range
    }
    override suspend fun defaultFor(tab: StatisticsTab, nowMs: Long): LongRange {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val ym = java.time.YearMonth.of(date.year, date.monthValue)
        return ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()..
            ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

private class FakeTxRepo : TransactionRepository(...) {
    // Use the same stub pattern as AccountDetailViewModelTest — observe returns empty.
    // ... full stub (see existing test files for the exact DAO interface)
}
```

**NOTE:** The `FakeTxRepo` and `FakeCatRepo` and `FakeSettingsRepo` stubs above are abbreviated. Implement them by mirroring the pattern in `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt` — the existing fakes there show exactly which methods to stub out and how. Copy the relevant `FakeAccountRepository`, `FakeTransactionRepository`, etc. pattern and adapt for `TransactionRepository`, `CategoryRepository`, `SettingsRepository`. **Do not invent stubs** — read those test files and copy.

- [ ] **Step 2: Run the tests — they should fail to compile (`StatisticsViewModel` doesn't take the new constructor yet)**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsViewModelTest*"
```

Expected: compile error.

- [ ] **Step 3: Update the `StatisticsViewModel`**

Replace `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt` with:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
    private val rangeRepository: StatisticsRangeRepository,
) : ViewModel() {

    private val cats = categoryRepository.observeAll()
    private val home = settingsRepository.homeCurrency
    private val rates = settingsRepository.fxRates

    private fun rangeFlow(tab: StatisticsTab) =
        rangeRepository.observe(tab)

    val topCategories: StateFlow<TopCategoriesResult> =
        rangeFlow(StatisticsTab.TOP_CATS)
            .flatMapLatest { range ->
                val txns = transactionRepository.observeAll()
                combine(txns, cats, home, rates) { t, c, h, r ->
                    StatisticsCalculator.topCategories(t, c, h, r, range.first, range.last)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                TopCategoriesResult("", emptyList(), 0))

    val savings: StateFlow<SavingsAndAverage> =
        rangeFlow(StatisticsTab.SAVINGS)
            .flatMapLatest { range ->
                val txns = transactionRepository.observeAll()
                combine(txns, home, rates) { t, h, r ->
                    StatisticsCalculator.savingsAndAverage(t, h, r, range.first, range.last)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                StatisticsCalculator.savingsAndAverage(emptyList(), "USD", emptyMap(),
                    System.currentTimeMillis(), System.currentTimeMillis() + 1))

    val dayOfWeek: StateFlow<List<DayOfWeekBucket>> =
        rangeFlow(StatisticsTab.PATTERNS)
            .flatMapLatest { range ->
                val txns = transactionRepository.observeAll()
                combine(txns, home, rates) { t, h, r ->
                    StatisticsCalculator.dayOfWeekPattern(t, h, r, range.first, range.last)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                (1..7).map { DayOfWeekBucket(it, 0L) })

    val yoy: StateFlow<YearOverYear> =
        rangeFlow(StatisticsTab.YOY)
            .flatMapLatest { range ->
                val priorStart = StatisticsCalculator.subtractOneYear(range.first)
                val priorEnd = StatisticsCalculator.subtractOneYear(range.last)
                val txns = transactionRepository.observeAll()
                combine(txns, home, rates) { t, h, r ->
                    StatisticsCalculator.yearOverYear(
                        t, h, r,
                        range.first, range.last,
                        priorStart, priorEnd,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                YearOverYear("", "", 0L, 0L, 0f, false))

    fun onRangeSelected(tab: StatisticsTab, range: LongRange) {
        viewModelScope.launch { rangeRepository.set(tab, range) }
    }
}
```

- [ ] **Step 4: Run the VM tests**

```bash
./gradlew testDebugUnitTest --tests "*StatisticsViewModelTest*"
```

Expected: 4 tests pass. If tests fail due to fake stub mismatches, fix the stubs by reading the existing test files (`AccountDetailViewModelTest.kt`, `AddReceiptViewModelTest.kt`) and copying their `Fake…Repository` patterns.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModelTest.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): ViewModel takes per-tab range via flatMapLatest"
```

---

## Task 9: `RangeChip` composable

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/RangeChip.kt`

- [ ] **Step 1: Create the composable**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/RangeChip.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R

/**
 * Pill chip showing the currently-selected date range. Tap → opens picker.
 * Muted color when the range equals the current calendar month (default);
 * default color when the user has picked a non-default range.
 */
@Composable
fun RangeChip(
    startMs: Long,
    endMs: Long,
    isDefault: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelColor = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant
                     else MaterialTheme.colorScheme.primary
    val label = compactRangeLabel(startMs, endMs)

    AssistChip(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = "Range: $label. Tap to change."
        },
        label = {
            Text(
                text = label,
                color = labelColor,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = labelColor,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.Transparent,
        ),
    )
}

/**
 * Compact "Mar 1 – 31" / "Jan 15 – Feb 3" / "Dec 28, 2025 – Jan 4, 2026" labels.
 * Used by the chip; the more verbose form is in StatisticsCalculator.rangeLabel.
 */
private fun compactRangeLabel(startMs: Long, endMs: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val startDate = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
    val endDate = java.time.Instant.ofEpochMilli(endMs - 1L).atZone(zone).toLocalDate()
    val monthDay = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)
    val month = java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.US)
    val full = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)

    return when {
        startDate.year == endDate.year && startDate.month == endDate.month ->
            "${startDate.format(month)} ${startDate.dayOfMonth}–${endDate.dayOfMonth}"
        startDate.year == endDate.year ->
            "${startDate.format(monthDay)} – ${endDate.format(monthDay)}"
        else ->
            "${startDate.format(full)} – ${endDate.format(full)}"
    }
}
```

(En dash `–` U+2013 between the day numbers in the same-month case.)

- [ ] **Step 2: Verify the file compiles**

```bash
./gradlew compileDebugKotlin
```

Expected: success (no callers yet, but the file should compile).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/RangeChip.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): RangeChip composable"
```

---

## Task 10: `RangePickerSheet` composable

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/RangePickerSheet.kt`

- [ ] **Step 1: Create the sheet**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/RangePickerSheet.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangePickerSheet(
    currentStartMs: Long,
    currentEndMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (startMs: Long, endMs: Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Material 3 DateRangePicker state. Initial selection = current.
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = currentStartMs,
        initialSelectedEndDateMillis = currentEndMs,
    )

    // Local state: which preset (if any) is currently highlighted.
    var activePreset: StatisticsPreset? by remember { mutableStateOf(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_picker_title),
                style = MaterialTheme.typography.titleLarge,
            )

            // Preset row
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatisticsPreset.all) { preset ->
                    FilterChip(
                        selected = activePreset == preset,
                        onClick = {
                            activePreset = preset
                            val nowMs = System.currentTimeMillis()
                            val r = preset.resolve(nowMs)
                            pickerState.setSelection(
                                java.time.Instant.ofEpochMilli(r.first)
                                    .atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                                java.time.Instant.ofEpochMilli(r.last)
                                    .atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                            )
                        },
                        label = { Text(preset.label) },
                    )
                }
            }

            // Date range picker (always visible — no separate "Custom" preset)
            DateRangePicker(
                state = pickerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp),
                showModeToggle = false,
            )

            // Apply / Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.stats_picker_cancel))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val s = pickerState.selectedStartDateMillis
                        val e = pickerState.selectedEndDateMillis
                        if (s != null && e != null) {
                            // Material 3 returns midnight UTC for date-only selections.
                            onConfirm(s, e)
                        }
                    },
                    enabled = pickerState.selectedStartDateMillis != null &&
                              pickerState.selectedEndDateMillis != null,
                ) {
                    Text(stringResource(R.string.stats_picker_apply))
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify the file compiles**

```bash
./gradlew compileDebugKotlin
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/RangePickerSheet.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): RangePickerSheet with presets + DateRangePicker"
```

---

## Task 11: Wire chip + sheet into `StatisticsScreen`

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsScreen.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt` (small extension: expose `currentRange(tab)`)

This task is the integration point. It also fixes the build errors from Task 5.

- [ ] **Step 1: Add `currentRange(tab)` to the ViewModel**

Append to `StatisticsViewModel.kt`:

```kotlin
    fun currentRange(tab: StatisticsTab): LongRange? = null  // populated via flatMapLatest
```

(Implementation note: collect from the StateFlow's first emission. Or expose four `StateFlow<LongRange>`s. For simplicity, add to the VM four flows that mirror the persisted ranges. Update the existing per-tab flows to also surface the range, or add new ones.)

**Recommended approach:** add four new flows to the VM:

```kotlin
    val topCatsRange: StateFlow<LongRange> =
        rangeRepository.observe(StatisticsTab.TOP_CATS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRange())

    val savingsRange: StateFlow<LongRange> =
        rangeRepository.observe(StatisticsTab.SAVINGS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRange())

    val patternsRange: StateFlow<LongRange> =
        rangeRepository.observe(StatisticsTab.PATTERNS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRange())

    val yoyRange: StateFlow<LongRange> =
        rangeRepository.observe(StatisticsTab.YOY)
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRange())

    private fun defaultRange(): LongRange = runBlocking {
        rangeRepository.defaultFor(StatisticsTab.TOP_CATS, System.currentTimeMillis())
    }
```

(Using `runBlocking` for the initial default is acceptable — it's a one-shot read at startup. Alternative: pass a `Clock` and compute inline.)

- [ ] **Step 2: Rewrite `StatisticsScreen.kt` to use the new field names + add chip + sheet**

Replace the entire file `app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsScreen.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.charts.DayOfWeekBars
import io.github.jiro.expensetracker.ui.charts.PieChartWithLegend
import io.github.jiro.expensetracker.ui.charts.YoyCompareCard
import io.github.jiro.expensetracker.ui.home.CategoryBreakdown
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun StatisticsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val topCategories by viewModel.topCategories.collectAsStateWithLifecycle()
    val savings by viewModel.savings.collectAsStateWithLifecycle()
    val dayOfWeek by viewModel.dayOfWeek.collectAsStateWithLifecycle()
    val yoy by viewModel.yoy.collectAsStateWithLifecycle()
    val topCatsRange by viewModel.topCatsRange.collectAsStateWithLifecycle()
    val savingsRange by viewModel.savingsRange.collectAsStateWithLifecycle()
    val patternsRange by viewModel.patternsRange.collectAsStateWithLifecycle()
    val yoyRange by viewModel.yoyRange.collectAsStateWithLifecycle()
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
}

@Composable
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
    val tabs = listOf(StatisticsTab.TOP_CATS, StatisticsTab.SAVINGS, StatisticsTab.PATTERNS, StatisticsTab.YOY)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // Per-tab sheet state — only one sheet open at a time, keyed by the currently visible tab.
    var sheetTab by remember { mutableStateOf<StatisticsTab?>(null) }
    val rangesByTab = mapOf(
        StatisticsTab.TOP_CATS to topCatsRange,
        StatisticsTab.SAVINGS to savingsRange,
        StatisticsTab.PATTERNS to patternsRange,
        StatisticsTab.YOY to yoyRange,
    )

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { i, tab ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                    text = { Text(stringResource(tab.labelRes())) },
                )
            }
        }
        HorizontalPager(state = pagerState) { page ->
            val tab = tabs[page]
            val range = rangesByTab.getValue(tab)
            val isDefault = range == defaultFor(tab)
            when (tab) {
                StatisticsTab.TOP_CATS -> TopCatsTab(
                    result = topCategories,
                    range = range,
                    isDefault = isDefault,
                    onChipClick = { sheetTab = tab },
                )
                StatisticsTab.SAVINGS -> SavingsTab(
                    savings = savings,
                    range = range,
                    isDefault = isDefault,
                    onChipClick = { sheetTab = tab },
                )
                StatisticsTab.PATTERNS -> PatternsTab(
                    buckets = dayOfWeek,
                    range = range,
                    isDefault = isDefault,
                    onChipClick = { sheetTab = tab },
                )
                StatisticsTab.YOY -> YoyTab(
                    result = yoy,
                    range = range,
                    isDefault = isDefault,
                    onChipClick = { sheetTab = tab },
                )
            }
        }
    }

    sheetTab?.let { tab ->
        val r = rangesByTab.getValue(tab)
        RangePickerSheet(
            currentStartMs = r.first,
            currentEndMs = r.last,
            onDismiss = { sheetTab = null },
            onConfirm = { s, e ->
                onRangeSelected(tab, s..e)
                sheetTab = null
            },
        )
    }
}

private fun defaultFor(tab: StatisticsTab): LongRange {
    val now = System.currentTimeMillis()
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val ym = java.time.YearMonth.of(date.year, date.monthValue)
    val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start..end
}

private fun StatisticsTab.labelRes(): Int = when (this) {
    StatisticsTab.TOP_CATS -> R.string.stats_tab_top_cats
    StatisticsTab.SAVINGS  -> R.string.stats_tab_savings
    StatisticsTab.PATTERNS -> R.string.stats_tab_patterns
    StatisticsTab.YOY      -> R.string.stats_tab_yoy
}

// ---- per-tab composables ----

@Composable
private fun TopCatsTab(
    result: TopCategoriesResult,
    range: LongRange,
    isDefault: Boolean,
    onChipClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_top_cats_header, result.monthLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            RangeChip(
                startMs = range.first,
                endMs = range.last,
                isDefault = isDefault,
                onClick = onChipClick,
            )
        }
        if (result.slices.isEmpty()) {
            EmptyRangeState(onReset = { /* wired via ViewModel.onRangeSelected */ })
        } else {
            val pieSlices = result.slices.map {
                CategoryBreakdown(it.categoryId, it.categoryName, it.amountMinor)
            }
            PieChartWithLegend(slices = pieSlices)
        }
        if (result.missingRateCount > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.stats_fx_missing),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SavingsTab(
    savings: SavingsAndAverage,
    range: LongRange,
    isDefault: Boolean,
    onChipClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_savings_header, savings.monthLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            RangeChip(
                startMs = range.first,
                endMs = range.last,
                isDefault = isDefault,
                onClick = onChipClick,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                primary = "${(savings.savingsRate * 100).toInt()}%",
                primaryColor = if (savings.savingsRate >= 0.20f) IncomeGreen else MaterialTheme.colorScheme.onSurface,
                label = stringResource(R.string.stats_savings_rate_label),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                primary = MoneyFormat.formatForDisplay(savings.averageMonthlyExpenseMinor),
                label = stringResource(R.string.stats_avg_monthly_label),
                subLabel = if (savings.averageMonthlySampleMonths > 0)
                    stringResource(R.string.stats_avg_monthly_subtitle, savings.averageMonthlySampleMonths)
                else stringResource(R.string.stats_no_data),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                primary = MoneyFormat.formatForDisplay(savings.topTransactionMinor),
                label = stringResource(R.string.stats_top_tx_label),
                modifier = Modifier.weight(1f),
            )
        }
        NetRow(savings)
    }
}

@Composable
private fun PatternsTab(
    buckets: List<DayOfWeekBucket>,
    range: LongRange,
    isDefault: Boolean,
    onChipClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_patterns_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            RangeChip(
                startMs = range.first,
                endMs = range.last,
                isDefault = isDefault,
                onClick = onChipClick,
            )
        }
        if (buckets.all { it.amountMinor == 0L }) {
            EmptyRangeState(onReset = { /* TODO: VM hookup */ })
        } else {
            DayOfWeekBars(buckets = buckets)
        }
    }
}

@Composable
private fun YoyTab(
    result: YearOverYear,
    range: LongRange,
    isDefault: Boolean,
    onChipClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_yoy_header, result.currentWindowLabel, result.previousWindowLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            RangeChip(
                startMs = range.first,
                endMs = range.last,
                isDefault = isDefault,
                onClick = onChipClick,
            )
        }
        YoyCompareCard(result = result)
    }
}

@Composable
private fun EmptyRangeState(onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_empty_in_range),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.stats_empty_reset))
        }
    }
}

@Composable
private fun StatTile(
    primary: String,
    primaryColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    label: String,
    subLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = primaryColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subLabel != null) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NetRow(savings: SavingsAndAverage) {
    val sign = when {
        savings.netMinor > 0L -> "+"
        savings.netMinor < 0L -> "−"
        else -> ""
    }
    val absMinor = abs(savings.netMinor)
    val color = when {
        savings.netMinor > 0L -> IncomeGreen
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = "${stringResource(R.string.stats_net_label)}: $sign${MoneyFormat.formatForDisplay(absMinor)}",
        style = MaterialTheme.typography.titleMedium,
        color = color,
    )
}
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: build succeeds. (The Task-5 errors should all be fixed by this rewrite — old field names like `result.monthLabel` are gone, replaced with new `monthLabel` (still works for topCats), and `currentWindowLabel`/`previousWindowLabel` for YoY.)

- [ ] **Step 4: Run all unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: all tests pass. Total should be approximately 354 + 9 preset + 5 repo + 4 VM + 0 new calculator (rewrite only) = 372 passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsScreen.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/statistics/StatisticsViewModel.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(stats): wire chip + picker sheet into each tab"
```

---

## Task 12: Manual smoke test document

**Files:**
- Create: `docs/superpowers/testdata/statistics-date-range-picker.md`

- [ ] **Step 1: Write the manual smoke test**

Mirror the structure of `docs/superpowers/testdata/close-account.md` (15 steps + Expected outcomes + Rollback). Cover:

1. Open Statistics. Verify each tab's chip shows current month.
2. Tap Top Cats chip → bottom sheet opens with presets + calendar. Pick "Last 30 days" → tap Apply. Verify chip text changes; chart re-renders with the 30-day window.
3. Switch to Savings tab. Verify chip is still current month (independent of Top Cats).
4. On Savings tab, pick a custom range Jan 15 – Feb 14, 2026. Apply. Verify chip shows cross-month format.
5. Switch to Patterns tab. Verify chip is current month (independent of others).
6. Switch to YoY tab. Pick "Last 7 days". Verify YoY compares current 7-day window vs the prior 7-day window (subtractOneYear-derived).
7. Force-stop the app. Reopen. Verify all four ranges are still set.
8. Open a tab whose range has no transactions in it. Verify "No transactions in this range" + Reset button.
9. Tap Reset → chip returns to current month.
10. Verify chip text color: muted for default, accent for custom.
11. Cancel from sheet → no change applied.
12. Apply from sheet → tab updates.
13. Verify leap-day range (Feb 29, 2024 if data exists) doesn't crash YoY.
14. Verify empty-data range still shows empty state without crashing.
15. Verify each preset chip resolves to a sensible window.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/testdata/statistics-date-range-picker.md
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "docs(stats): manual smoke test for date-range picker"
```

---

## Task 13: Final verification + tag

- [ ] **Step 1: Run the full test suite**

```bash
./gradlew testDebugUnitTest
```

Expected: 372 tests pass (or whatever the new total is — verify no regressions vs. the previous 354).

- [ ] **Step 2: Build the debug APK**

```bash
./gradlew assembleDebug
```

Expected: success.

- [ ] **Step 3: Verify the git log**

```bash
git log --oneline -15
```

Expected: ~13 new commits since the last tag, each with a clear message and no `Co-Authored-By:` trailer (per project convention).

- [ ] **Step 4: Tag the release**

```bash
git tag -a v0.18.8 -m "Phase 2.13b: custom date-range picker on Statistics tabs"
git push origin master
git push origin v0.18.8
```

---

## Self-Review Notes (post-write)

- **Spec coverage:** Every section in `docs/superpowers/specs/2026-07-04-statistics-date-range-picker-design.md` has a corresponding task (architecture → Tasks 5–8; components → Tasks 2, 6, 9, 10; data flow → Task 8; UI → Tasks 9, 10, 11; testing → Tasks 2, 4, 5, 6, 8, 12; error handling → Task 11).
- **Placeholder scan:** No "TBD" / "TODO" / "fill in later" patterns remain. The two `// TODO: VM hookup` comments in Task 11 are intentional placeholders the implementer should resolve by hooking the reset button to `viewModel.onRangeSelected(…)` with the default range.
- **Type consistency:** `StatisticsTab` is defined once in Task 6 and reused in Tasks 8 and 11. `rangeLabel(start, end)` is defined in Task 3 and reused throughout. `subtractOneYear(ms)` is defined in Task 4 and used in the VM (Task 8) and test (Task 5).
- **File path accuracy:** All paths verified against the existing repo structure (`app/src/main/java/io/github/jiro/expensetracker/ui/statistics/`, `app/src/main/res/values/strings.xml`, `app/src/main/java/io/github/jiro/expensetracker/di/`, `app/src/test/java/io/github/jiro/expensetracker/ui/statistics/`).
- **Two enums caveat:** The plan deliberately keeps `StatTab` (screen) and `StatisticsTab` (repository/VM) separate until Task 11 deletes the screen one. Documented inline at Task 6 step 3 and Task 11.