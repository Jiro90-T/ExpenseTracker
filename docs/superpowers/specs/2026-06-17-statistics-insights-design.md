# Phase 2.13 — Statistics / Insights Screen — Design

**Status:** Approved 2026-06-17
**Phase:** 2.13
**Predecessors:** Phase 2.9 introduced FX conversion to home currency for the amount-range filter; Phase 2.12 extended that pattern for sort-by-amount. Phase 2.3 introduced the monthly bar chart pattern (`MonthlyBarChart`). Tagged v0.12.0.

## Goal

Add a new bottom-nav "Statistics" tab with four sub-tabs: **Top Cats** (pie of top spending categories for the current month), **Savings** (savings rate, average monthly spend, top transaction), **Patterns** (day-of-week spending for the last 90 days), **YoY** (this month vs same month last year). Reuses the existing `PieChartWithLegend`. Two new lightweight composables (`DayOfWeekBars`, `YoyCompareCard`). No new chart primitives. ~500 lines production code, ~400 lines test code, ~22 new strings, 0 new abstractions.

Out of scope (intentional, deferred): custom date-range picker on any tab, drill-down from a category slice to a filtered Transactions list, export/screenshot, animated chart entry, multi-year YoY comparison, per-account breakdowns, anomaly detection.

## User-visible behavior

A new "Statistics" tab joins the bottom navigation: **Home · Transactions · Statistics**.

The Statistics screen has its own `TabRow` with four tabs:

| Tab | Title | Time window | Content |
|---|---|---|---|
| 1 | Top Cats | this calendar month | Pie chart of top 5 spending categories + "Other" rollup, with legend chips (category name + percent). |
| 2 | Savings | this calendar month | Three stat tiles: Savings Rate, Average Monthly (last 6 completed months), Top Transaction. |
| 3 | Patterns | last 90 days (rolling) | 7 vertical bars (Mon..Sun) showing total spend per weekday. |
| 4 | YoY | this month vs same month last year | Two side-by-side numeric tiles + delta chip (↑ green / ↓ red / no change). |

- All currency values shown in the user's home currency (FX-converted; same fallback rules as Phase 2.9 / 2.12 when rate is missing).
- Tab labels are short — "Top Cats", "Savings", "Patterns", "YoY" — and the selected tab text fits on phones without truncation.
- The currently selected tab is the only one whose body renders (lazy via `HorizontalPager`); the others stay uninitialized until first shown.
- Pull-to-refresh is **not** added (the screen observes the same Room flow as Home and Transactions — data updates within ~100ms of a write).

## Data model

**No schema changes.** No new tables, columns, or files in `data/local/`. New types added to a new `ui/statistics/` package.

### Calculator output types

```kotlin
data class CategorySpend(
    val categoryId: Long,        // -1L for the synthetic "Other" rollup
    val categoryName: String,    // "Other" when categoryId == -1L
    val amountMinor: Long,
)

data class TopCategoriesResult(
    val monthLabel: String,                 // "Jun 2026"
    val slices: List<CategorySpend>,        // exactly 6 entries: top 5 + "Other" (or fewer if ≤5 categories)
    val missingRateCount: Int,
)

data class SavingsAndAverage(
    val monthLabel: String,
    val incomeMinor: Long,                  // FX-converted
    val expenseMinor: Long,                 // FX-converted
    val netMinor: Long,                     // incomeMinor - expenseMinor (signed)
    val savingsRate: Float,                 // 0..1, or 0f when income == 0
    val averageMonthlyExpenseMinor: Long,   // last 6 completed months, or 0L when <3 months of data
    val topTransactionMinor: Long,          // single largest expense this month, or 0L
    val averageMonthlySampleMonths: Int,    // 0..6, how many completed months fed the average
)

data class DayOfWeekBucket(
    val isoDayOfWeek: Int,    // 1=Mon..7=Sun
    val amountMinor: Long,    // FX-converted; sum across all 90 days for that weekday
)

data class YearOverYear(
    val currentMonthLabel: String,        // "Jun 2026"
    val previousMonthLabel: String,       // "Jun 2025"
    val currentExpenseMinor: Long,        // FX-converted
    val previousExpenseMinor: Long,       // FX-converted
    val percentChange: Float,             // (current - previous) / previous, or 0f when previous == 0
    val isNewSpending: Boolean,           // true when previous == 0 and current > 0
)
```

`CategorySpend` mirrors `ui.home.CategoryBreakdown` shape but lives in the statistics package — at the UI boundary, the screen maps to `CategoryBreakdown` for the existing `PieChartWithLegend`.

## Components

| File | Action | Purpose |
| --- | --- | --- |
| `ui/statistics/StatisticsCalculator.kt` | new | Pure functions: `topCategories`, `savingsAndAverage`, `dayOfWeekPattern`, `yearOverYear`, plus helpers `monthBounds`, `monthLabel`. JVM-testable. |
| `ui/statistics/StatisticsViewModel.kt` | new | `@HiltViewModel`. Combines 4 sources (txns, categories, homeCurrency, fxRates) into 4 derived `StateFlow`s, one per tab. |
| `ui/statistics/StatisticsScreen.kt` | new | `TabRow` + `HorizontalPager`. Each tab body is stateless and takes its typed result as a parameter. |
| `ui/charts/DayOfWeekBars.kt` | new | 7-bar chart (Mon..Sun). Compose `Row` + weighted `Box` heights (same pattern as `MonthlyBarChart`). |
| `ui/charts/YoyCompareCard.kt` | new | Side-by-side numeric tiles + delta chip. Pure Compose, no Canvas. |
| `ui/MainActivity.kt` (or root nav) | modify | Add "Statistics" tab to bottom nav. |
| `res/values/strings.xml` | modify | +14 strings (4 tab labels, 4 section headers, 6 stat labels / empty states). |
| `app/src/test/.../ui/statistics/StatisticsCalculatorTest.kt` | new | 27 tests across the 4 calculators + helpers. |

### `StatisticsCalculator` (pure helpers)

```kotlin
fun topCategories(
    txns: List<TransactionWithCategory>,
    cats: List<CategoryEntity>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
    nowMs: Long,
): TopCategoriesResult

fun savingsAndAverage(
    txns: List<TransactionWithCategory>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
    nowMs: Long,
): SavingsAndAverage

fun dayOfWeekPattern(
    txns: List<TransactionWithCategory>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
    nowMs: Long,
): List<DayOfWeekBucket>           // always 7 entries, ordered Mon..Sun

fun yearOverYear(
    txns: List<TransactionWithCategory>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
    nowMs: Long,
): YearOverYear
```

**Common rules:**
- **Only expenses are counted** for `topCategories`, `dayOfWeekPattern`, `yearOverYear`. Income is excluded.
- **Income is included** for `savingsAndAverage` (income and expense both feed the savings calculation).
- **FX conversion** via `FxConverter.convertMinor(amountMinor, fromCurrency, toCurrency, rates)`. When that returns `null` (rate missing), the row's `amountMinor` is incremented by the raw `amountMinor` and `missingRateCount` is incremented. Same fallback policy as Phase 2.9 (the amount range filter) and Phase 2.12 (`sortTransactions`).
- **Calendar boundaries** use the device's local timezone (`ZoneId.systemDefault()`, `Instant.ofEpochMilli`, `LocalDate`). Helper:
  ```kotlin
  internal fun monthBounds(year: Int, month: Int): Pair<Long, Long>   // (startMs, endMsExclusive)
  ```
- **Empty / single-row / all-same-currency inputs** all handled; no special cases in callers.

**`topCategories` specifics:**
- Filter `txns` to expenses only (`txn.type == EXPENSE`) whose `occurredAtEpochMillis` is within `[monthStart, monthEnd)`.
- Group by `categoryId`. Resolve each to a `CategoryEntity` from `cats` by id (missing category → `categoryName = "Other (deleted)"` is **not** used; instead, that row is rolled into the synthetic "Other" slice alongside everything below the top 5).
- Sort groups by `amountMinor` desc. Take top 5; sum the rest into a single `CategorySpend(categoryId = -1L, categoryName = "Other", amountMinor = sum)`.
- `missingRateCount` = number of input rows whose `currencyCode != homeCurrency` and whose FX rate is missing.
- `percentOfTotal` is computed in the UI by `PieChartWithLegend`'s existing legend — not stored on the model.
- Returns exactly 6 slices when ≥6 categories exist; fewer when fewer exist (no padding).

**`savingsAndAverage` specifics:**
- Income this month = FX-converted sum of `type == INCOME` rows in current calendar month.
- Expense this month = FX-converted sum of `type == EXPENSE` rows in current calendar month.
- `netMinor = incomeMinor - expenseMinor`.
- `savingsRate = if (incomeMinor > 0) ((incomeMinor - expenseMinor).toFloat() / incomeMinor.toFloat()).coerceIn(0f, 1f) else 0f`.
- `averageMonthlyExpenseMinor`: sum of expense per completed month for the 6 calendar months immediately preceding the current month, divided by 6. If fewer than 3 completed months have any data, return 0L and `averageMonthlySampleMonths = countOfCompletedMonthsWithData`.
- `topTransactionMinor`: largest single `amountMinor` (FX-converted) of an expense this month. 0L when none.

**`dayOfWeekPattern` specifics:**
- Filter `txns` to expenses with `occurredAtEpochMillis >= nowMs - 90L * 24 * 3600 * 1000` AND `<= nowMs`.
- For each row, compute its `LocalDate` from `occurredAtEpochMillis` (zone = system default), get `dayOfWeek.value` (ISO: Mon=1..Sun=7).
- Sum `amountMinor` (FX-converted) per bucket.
- Always returns exactly 7 buckets, ordered Mon..Sun, including zero-amount days. The UI maps `isoDayOfWeek` to a `stringResource` (new keys `stats_dow_mon`..`stats_dow_sun`) so labels stay translatable.

**`yearOverYear` specifics:**
- `currentBounds = monthBounds(currentYear, currentMonth)`.
- `previousBounds = monthBounds(currentYear - 1, currentMonth)`.
- `currentExpenseMinor` = FX-converted sum of expenses in current bounds.
- `previousExpenseMinor` = FX-converted sum of expenses in previous bounds.
- `percentChange = if (previousExpenseMinor > 0) (currentExpenseMinor - previousExpenseMinor).toFloat() / previousExpenseMinor.toFloat() else 0f`.
- `isNewSpending = previousExpenseMinor == 0L && currentExpenseMinor > 0L`.

### `StatisticsViewModel` (composition)

```kotlin
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val txns = repository.observeAll()
    private val cats = categoryRepository.observeAll()
    private val home = settingsRepository.homeCurrency
    private val rates = settingsRepository.fxRates

    val topCategories: StateFlow<TopCategoriesResult> = combine(txns, cats, home, rates) { t, c, h, r ->
        StatisticsCalculator.topCategories(t, c, h, r, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TopCategoriesResult("", emptyList(), 0))

    // ... similar for savingsAndAverage, dayOfWeekPattern, yearOverYear
}
```

Mirrors the `HomeViewModel` shape — same `combine` + `stateIn` pattern. No new abstractions.

### `StatisticsScreen` (UI)

```kotlin
@Composable
fun StatisticsScreen(
    topCategories: TopCategoriesResult,
    savings: SavingsAndAverage,
    dayOfWeek: List<DayOfWeekBucket>,
    yoy: YearOverYear,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(StatTab.TopCats, StatTab.Savings, StatTab.Patterns, StatTab.YoY)
    val pagerState = rememberPagerState { tabs.size }
    Column(modifier) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { i, tab ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }
        HorizontalPager(state = pagerState) { page ->
            when (tabs[page]) {
                StatTab.TopCats -> TopCatsTab(topCategories)
                StatTab.Savings -> SavingsTab(savings)
                StatTab.Patterns -> PatternsTab(dayOfWeek)
                StatTab.YoY -> YoyTab(yoy)
            }
        }
    }
}
```

Each tab body is a separate private `@Composable` function. They are stateless — they receive the typed result and render.

**`TopCatsTab`**: header `"Top spending — ${result.monthLabel}"` → `PieChartWithLegend(slices = result.slices.map { CategoryBreakdown(it.categoryId, it.categoryName, it.amountMinor) })`. If `result.missingRateCount > 0`, a small chip below: `"Some amounts shown without FX conversion"`.

**`SavingsTab`**: header `"${result.monthLabel}"` → three `Card`s in a `Row` (vertical stack on phones via `Column` when width < 360dp):
- **Savings Rate** — large numeric `"${(savingsRate * 100).toInt()}%"`, color = green when ≥ 20%, neutral otherwise. Subtitle `"Savings rate"`.
- **Avg Monthly** — `"${formatCurrency(averageMonthlyExpenseMinor)}"`, subtitle `"Avg / mo · last ${averageMonthlySampleMonths} mo"`.
- **Top Transaction** — `"${formatCurrency(topTransactionMinor)}"`, subtitle `"Largest expense"`.
- Below: `"Net: +$X"` (green) or `"Net: −$X"` (red) or `"Net: $0"`.

**`PatternsTab`**: header `"Day of week · last 90 days"` → `DayOfWeekBars(buckets = dayOfWeek)`. If all buckets are 0, show the empty-state text instead of bars.

**`YoyTab`**: header `"${result.currentMonthLabel} vs ${result.previousMonthLabel}"` → `YoyCompareCard(result)`.

### `DayOfWeekBars` composable

```kotlin
@Composable
fun DayOfWeekBars(buckets: List<DayOfWeekBucket>, modifier: Modifier = Modifier)
```

- 7 vertical bars laid out in a `Row` with `Arrangement.spacedBy(8.dp)`.
- Each bar's height = `(bucket.amountMinor.toFloat() / maxOfBuckets) * availableHeight`. Empty buckets show as a 2dp stub at the bottom.
- X-axis labels (Mon..Sun) below each bar using `stringResource(when (bucket.isoDayOfWeek) { 1 -> R.string.stats_dow_mon; ... 7 -> R.string.stats_dow_sun })`.
- Max height: 160.dp (matches `MonthlyBarChart`).
- The component takes the list and renders; no business logic.

### `YoyCompareCard` composable

```kotlin
@Composable
fun YoyCompareCard(result: YearOverYear, modifier: Modifier = Modifier)
```

- Two `Card`s side-by-side via `Row`:
  - Left: `result.currentMonthLabel` + `"${formatCurrency(result.currentExpenseMinor)}"`.
  - Right: `result.previousMonthLabel` + `"${formatCurrency(result.previousExpenseMinor)}"`.
- Below: a chip:
  - Green ↑ + `"${(percentChange * 100).toInt()}% vs last year"` when `percentChange > 0`.
  - Red ↓ + `"${(-percentChange * 100).toInt()}% vs last year"` when `percentChange < 0`.
  - Neutral `"No spending last year"` when `isNewSpending` is true (currentExpense > 0, previous = 0).
  - Neutral `"No change"` when both are 0.

### Bottom-nav update

The root nav composable (wherever the bottom nav lives — most likely `MainActivity.kt` or a `RootScaffold.kt`) gains a third entry:
```kotlin
NavigationBarItem(
    selected = currentRoute == "statistics",
    onClick = { navController.navigate("statistics") },
    icon = { Icon(Icons.Filled.QueryStats, contentDescription = ...) },
    label = { Text("Statistics") },
)
```
The same file gets a new `composable("statistics") { StatisticsScreen(viewModel = hiltViewModel()) }` entry in the NavHost.

## Strings to add

```
stats_tab_top_cats          "Top Cats"
stats_tab_savings           "Savings"
stats_tab_patterns          "Patterns"
stats_tab_yoy               "YoY"
stats_top_cats_header       "Top spending — %1$s"            (formatted with month label)
stats_savings_header        "%1$s"                            (formatted with month label)
stats_patterns_header       "Day of week · last 90 days"
stats_yoy_header            "%1$s vs %2$s"                   (current, previous)
stats_savings_rate_label    "Savings rate"
stats_avg_monthly_label     "Avg / mo"
stats_avg_monthly_subtitle  "last %1$d mo"
stats_top_tx_label          "Largest expense"
stats_net_label             "Net"
stats_no_data               "Not enough data"
stats_fx_missing            "Some amounts shown without FX conversion"
```

(15 new strings — 4 tab labels + 4 section headers + 7 stat / state labels.)

Plus 7 day-of-week label strings (`stats_dow_mon`..`stats_dow_sun`). (Total: 22 new strings.)

## Tests

In `app/src/test/.../ui/statistics/StatisticsCalculatorTest.kt`:

1. `topCategories_groupsByCategoryAndSortsDesc`
2. `topCategories_topFivePlusOther`
3. `topCategories_topFiveOnlyWhenFewerCategories`
4. `topCategories_excludesIncome`
5. `topCategories_fxConversionToHomeCurrency`
6. `topCategories_missingRateCount`
7. `topCategories_emptyTxns`
8. `savingsAndAverage_basicIncomeAndExpense`
9. `savingsAndAverage_zeroIncome_returnsZeroRate`
10. `savingsAndAverage_expenseExceedsIncome_clampsToZero`
11. `savingsAndAverage_averageOverSixCompletedMonths`
12. `savingsAndAverage_averageReturnsZeroWhenLessThanThreeMonths`
13. `savingsAndAverage_topTransaction`
14. `savingsAndAverage_emptyTxns`
15. `dayOfWeek_alwaysReturnsSevenBuckets`
16. `dayOfWeek_sumsAcrossMultipleWeeks`
17. `dayOfWeek_excludesIncome`
18. `dayOfWeek_usesHomeCurrency`
19. `dayOfWeek_respects90DayWindow`
20. `yearOverYear_basicPercentChange`
21. `yearOverYear_previousIsZero_marksNewSpending`
22. `yearOverYear_bothZero`
23. `yearOverYear_calendarBoundary`
24. `yearOverYear_excludesIncome`
25. `yearOverYear_usesHomeCurrency`
26. `monthBounds_january`
27. `monthBounds_decemberYearRollover`

(27 new tests.)

No VM tests (thin composition over 4 pure functions + 4 `stateIn`s). No Compose UI tests (visual screen; the existing manual smoke-test protocol covers the visual). The `DayOfWeekBars` and `YoyCompareCard` composables are simple enough that visual inspection during smoke is sufficient.

## Edge cases

| Case | Behavior |
|---|---|
| No transactions ever | All tabs show "Not enough data" empty state. Savings shows 0% / 0 / 0. |
| Only income, no expenses | Top Cats / Patterns / YoY show empty state. Savings shows 100% rate, $0 expense. |
| Mixed currencies, no FX rates | Each row with missing rate uses raw `amountMinor`; `missingRateCount` chip shown on Top Cats. |
| Single transaction | All tabs render without division-by-zero (savings rate returns 0 when income=0). |
| Timezone change mid-session | Recompute on next emission; numbers update when user navigates back. |
| Category deleted after a transaction | The transaction's row is grouped under the synthetic "Other" (or under the deleted category id if it's still referenced; matches existing filter behavior). |
| Day-of-week Sunday at week boundary | ISO `DayOfWeek.values()` (Mon=1..Sun=7) used consistently. |
| Leap-year February | `YearMonth.of(y, 2).lengthOfMonth()` handles 28/29 days. |
| 90-day window spans <7 distinct weekdays | Day-of-week still returns 7 buckets with some at 0; bars show 2dp stubs. |
| Bottom-nav switch away and back | StateFlows stay warm via `WhileSubscribed(5_000)`. No recompute on quick switch. |
| App opened on Jan 1 | "Last 6 completed months" = Jul–Dec of prior year. No division-by-zero. |
| App opened on Feb 29 (leap year) | `monthBounds(2028, 2)` returns `[Feb 1, Mar 1)` (29 days). |
| Time window changes (timezone, DST) | `LocalDate` and `ZoneId.systemDefault()` re-resolve on next emission. |
| User changes home currency mid-session | All 4 `StateFlow`s recompute (combine re-fires); tabs reflect new totals on next render. |

## Error handling

| Failure | Surfaced as |
|---|---|
| FX rate missing for a row's currency | `missingRateCount++`; chip shown on Top Cats; row uses raw `amountMinor` (matches Phase 2.9). No crash. |
| `monthBounds` called with month out of range (defensive) | `require(month in 1..12)` — programmer error; throws in tests, never in production. |
| Empty `txns` | Each calculator returns its empty/zero shape. No crash. |
| `cats` empty | Top Categories returns 0+ "Other" slice with the entire sum as "Other" (no real categories to roll up under). |
| `homeCurrency` blank | Treated as a no-op FX (Phase 2.9 behavior — `FxConverter.convertMinor` returns null; rate missing path). |

## Out of scope (intentional, deferred)

- **Custom date range picker** — fixed windows only in v1.
- **Drill-down from a category slice** to a filtered Transactions list (would require navigation glue + filter persistence).
- **Export as image / share sheet** — no clear UX for this yet.
- **Animated chart entry** — instant render, consistent with the rest of the app.
- **Comparison across multiple previous years** — only "vs last year" in v1.
- **Per-account / per-payee breakdowns** — no auth in v1 (per CLAUDE.md).
- **Anomaly detection / recurring-expense detection** — needs more data and a different math model; YAGNI.
- **Insights / recommendations** ("you spent 20% more on food") — too speculative for v1.
- **Statistics-specific empty state illustrations** — text empty states only, consistent with the rest of the app.
- **Sorting within tabs** — orderings are fixed by the spec (top-5-by-amount, ISO Mon..Sun, calendar-current-vs-prior).

## Open questions

None. Decisions taken one at a time through the AskUserQuestion flow and recorded in the User-visible behavior, Architecture, UI, and Edge cases sections above.
