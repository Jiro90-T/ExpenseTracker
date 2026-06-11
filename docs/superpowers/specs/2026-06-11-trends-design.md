# Phase 2.5 — Trend Line Chart — Design

**Status:** Approved 2026-06-11
**Phase:** 2.5
**Predecessors:** Phase 2.4 (Receipts) ships the schema and repository plumbing that this phase's data layer reuses. The `ui/charts/ChartData.kt::computeMonthlyTotals` pure function (Phase 1.x) is the load-bearing data source for the new chart.

## Goal

Add a "Trends" tab to the bottom nav, replacing the existing "Reports" placeholder. The tab is centered on a 3-line chart (income, expense, net) over the last 6 months, with tap-to-inspect showing the selected month's totals below the chart. Pure Compose Canvas — no new chart library.

Out of scope (deferred): period selector, smoothing, drag/zoom, comparison to a previous period, forecast/projection, per-line color customization.

## User-visible behavior

When the user taps the new "Trends" tab in the bottom nav:

- The screen shows a top app bar titled "Trends", a 160.dp-tall line chart spanning the full width, a small inline legend, and month labels below the chart.
- The chart renders three polylines: one for income (green), one for expense (red), one for net (blue). Each polyline has a dot at every monthly data point.
- Above the chart, a hint text "Tap a month to see details" (only visible when nothing is selected).
- The user taps any data point. That month's dot gets a ring indicator. A detail panel fades in below the chart showing the month's label, income, expense, and net (formatted in the home currency).
- Tapping the same selected point again, or tapping outside the chart, clears the selection (detail panel disappears).
- The chart re-uses the same period selector semantics as the bar chart: last 6 months, oldest left → newest right.

When the user is on the tab and has no transactions: a "No data" placeholder fills the chart area (consistent with the bar chart's behavior).

## Data model

**No schema changes.** Phase 2.4 added `receiptPath` (v4→v5). Phase 2.5 reuses the existing schema unchanged.

**No new pure-function aggregation needed.** `computeMonthlyTotals(rows, monthsBack = 6, todayMs)` (in `ui/charts/ChartData.kt`) already produces the income/expense time series. The new line chart consumes that data unchanged and computes the per-month net inline.

**New data class** (wraps `MonthlyTotals` plus a precomputed net — kept separate so the existing `MonthlyBarChart` continues to consume the original shape):

```kotlin
data class MonthlyTrend(
    val monthStartMs: Long,
    val shortLabel: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long,        // = incomeMinor - expenseMinor
)
```

**New pure helper** (lives in `ui/charts/LineChartData.kt`):

```kotlin
fun computeMonthlyTrends(rows: List<TransactionWithCategory>): List<MonthlyTrend>
```

A thin one-liner that delegates to `computeMonthlyTotals(rows)` and maps the result. Pure, JVM-testable, ~5 lines.

**Why a new data class instead of reusing `MonthlyTotals`:** the chart's data flow is "compute trends once, cache the result in the VM, recompose when DB changes". A precomputed `netMinor` on the data class lets the chart draw without re-doing arithmetic on every recomposition. Cheap but worth a tiny abstraction. The bar chart's `MonthlyTotals` stays unchanged to avoid touching unrelated code.

## Components

| File | Purpose |
| --- | --- |
| `ui/charts/LineChartData.kt` (new) | `MonthlyTrend` data class + `computeMonthlyTrends` pure helper. |
| `ui/charts/LineChart.kt` (new) | The 3-line chart composable. Pure UI, no VM. Includes tap-to-inspect. |
| `ui/trends/TrendsScreen.kt` (new) | The full Trends tab composable. Hosts `LineChart` + the detail panel. |
| `ui/trends/TrendsViewModel.kt` (new) | Hilt VM that exposes `monthlyTrends: StateFlow<List<MonthlyTrend>>` and `selected: StateFlow<MonthlyTrend?>`. |
| `ui/navigation/AppNav.kt` (modified) | Rename `Routes.REPORTS = "reports"` to `Routes.TRENDS = "trends"`. Wire `TrendsScreen` into the existing `composable(...)` block. |
| `ui/navigation/BottomNav.kt` (modified) | Update the tab label string (no resource-id change in code, just the string value) and the icon. |
| `ui/theme/Color.kt` (modified) | Add `NetBlue` color (used for the net line). |
| `res/values/strings.xml` (modified) | Rename `nav_reports` → `nav_trends`. Drop `reports_title` and `reports_coming_soon_detail`. Add new strings (see below). |
| `app/src/test/java/.../ui/charts/LineChartDataTest.kt` (new) | JUnit tests for `computeMonthlyTrends`. |

### `LineChart` composable

```kotlin
@Composable
fun LineChart(
    data: List<MonthlyTrend>,
    selected: MonthlyTrend?,
    onSelect: (MonthlyTrend?) -> Unit,
    modifier: Modifier = Modifier,
)
```

- 160.dp tall, full width.
- Three polylines drawn with `Canvas { drawPath(path, color, ...) }` using `PathEffect.cornerPathEffect(8f)` for soft joins.
- Three data-point dots per month, one per line, drawn with `drawCircle` (3.dp radius). Tap target: a slightly larger 24.dp invisible circle per month for finger-friendly hit-testing.
- The selected month's dots (all three lines) get a larger ring (6.dp circle + 1.dp stroke) to indicate the selection.
- Y-axis: scaled to `max(maxIncome, maxExpense, abs(minNet))` (in absolute value, so negative net renders below the x-axis). No axis labels on Y.
- X-axis: 6 evenly-spaced month positions. Labels (the `shortLabel` from `MonthlyTrend`) rendered below the chart.
- Legend: 3 small colored dots + labels at top-right corner of the chart area.
- All three lines drawn in the project's existing theme colors: `IncomeGreen`, `ExpenseRed`, and a new `NetBlue` (added to `Color.kt`).
- `Modifier.pointerInput(data) { detectTapGestures { offset -> findAndSelectNearest(offset, ...) } }` for tap-to-inspect.
- No-data case: a "No data" `Text` in place of the chart, matching the bar chart's behavior.

### `TrendsViewModel`

```kotlin
@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val monthlyTrends: StateFlow<List<MonthlyTrend>> =
        repository.observeAll()
            .combine(settingsRepository.homeCurrency, settingsRepository.fxRates) { rows, _, _ -> rows }
            .map { computeMonthlyTrends(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<MonthlyTrend?>(null)
    val selected: StateFlow<MonthlyTrend?> = _selected.asStateFlow()

    fun select(month: MonthlyTrend?) { _selected.value = month }
}
```

The `.combine` with `homeCurrency` + `fxRates` matches the `HomeViewModel` pattern (consistency for future FX normalization). For MVP the line chart shows minor units as-is, not converted. Same as the existing `MonthlyBarChart` shape.

### `TrendsScreen` layout

```
┌─────────────────────────────────────┐
│  TopAppBar: "Trends"                 │
├─────────────────────────────────────┤
│  "Tap a month to see details" (hint)│
│                                     │
│  ┌───────────────────────────────┐  │
│  │  ● Income  ● Expense  ● Net  │  │
│  │                               │  │
│  │       Line chart (160.dp)     │  │
│  │   three colored polylines     │  │
│  │   with dots at data points    │  │
│  │   tap a dot → select it       │  │
│  │                               │  │
│  └───────────────────────────────┘  │
│  Jan   Feb   Mar   Apr   May   Jun   │
│                                     │
│  [Detail panel — shown when selected]│
│  ┌───────────────────────────────┐  │
│  │  March 2026                   │  │
│  │  Income:    $1,200.00         │  │
│  │  Expense:   $   800.00         │  │
│  │  Net:       $   400.00         │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

The detail panel is `AnimatedVisibility` so it fades in when a month is selected and fades out when cleared.

### Tap-to-inspect detail

The detail panel shows the selected month's:
- Long label (e.g. "March 2026", not the short "Mar" used in the chart axis)
- Income (formatted in home currency, with thousands separators, 2 decimals)
- Expense (same format)
- Net (same format, with a "+" prefix if positive)

A small "clear" button (X) in the top-right of the panel clears the selection.

### Edge cases

| Case | Behavior |
| --- | --- |
| No transactions at all | "No data" text in place of the chart. |
| All months zero income | Income line is flat at 0. No rendering glitch. |
| Negative net | Drawn through the x-axis (the path dips). |
| One month zero | Drawn as a dot at the x-axis baseline. |
| All values identical | Three flat lines on top of each other — slightly confusing but technically correct. The legend still tells them apart. |
| Very large values (e.g. 1M+) | Y-axis scales to fit. No overflow. |
| Single month (period 1) | All 6 data points are the same — the chart shows a flat line with 6 dots. (Edge case, not actively tested.) |

## Error handling

| Failure | Surfaced as |
| --- | --- |
| DB read fails upstream | VM state stays empty; chart shows "No data" placeholder. No error toast — consistent with the existing pattern. |
| Compose draw fails (rare) | Falls through to an empty Canvas; no crash. |

## Tests

| Test | File | What it asserts |
| --- | --- | --- |
| `computeMonthlyTrends_emptyList_returnsEmptyList` | `LineChartDataTest.kt` | `emptyList()` → `emptyList()`. |
| `computeMonthlyTrends_singleMonth_returnsOne` | same | One transaction → one `MonthlyTrend` with the right monthStartMs, labels, and net. |
| `computeMonthlyTrends_mixedIncomeExpense_computesNet` | same | Multiple transactions across months → `netMinor = incomeMinor - expenseMinor` for each. |
| `computeMonthlyTrends_pureFunction_repeatedCallsAreIdempotent` | same | Calling `computeMonthlyTrends(rows)` twice yields equal results. |
| `computeMonthlyTrends_preservesSortOrder` | same | The result preserves the chronological order from `computeMonthlyTotals`. |
| `MonthlyTrend_netMinorCalculation` | same | Verifying the formula directly with hand-built instances. |

No Compose UI test. The `LineChart` composable is exercised on device in the manual smoke test (Phase 2.5 Task 8).

## Strings to add / change

```
nav_trends               "Trends"   (renamed from nav_reports "Reports")
trends_tap_hint          "Tap a month to see details"
trends_detail_title      "%1$s"   (parameterized by long month label)
trends_detail_income     "Income: %1$s"
trends_detail_expense    "Expense: %1$s"
trends_detail_net        "Net: %1$s"
trends_legend_income     "Income"
trends_legend_expense    "Expense"
trends_legend_net        "Net"
trends_clear             "Clear"
```

The "no data" case reuses the existing `charts_no_data` ("No data for this view") string — no new string needed.

The `reports_title` and `reports_coming_soon_detail` strings are deleted (no longer referenced after the tab is repurposed).

## Files touched (summary)

**New:** `LineChart.kt`, `LineChartData.kt`, `TrendsScreen.kt`, `TrendsViewModel.kt`, `LineChartDataTest.kt`.

**Modified:** `AppNav.kt`, `BottomNav.kt`, `Color.kt`, `strings.xml`.

## Out of scope (intentional)

- Period selector (3M / 6M / 12M / YTD / All) — defer to a polish pass.
- Smoothing (cubic spline) — straight lines with rounded corner joins are clean enough for MVP.
- Drag/zoom — not needed at 6 data points.
- Comparison to a previous period — separate feature.
- Forecast / projection — separate feature.
- "Save to Photos" share affordance for the chart — defer to the existing receipt share work.
- Per-line color customization — defer.
- Animated chart updates (the data refreshes when the DB changes; no morph between old and new values).
- Annotations (e.g., "current" month marker) — defer.

## Open questions

None. Decisions were taken one at a time during brainstorming and recorded in the User-visible behavior, Data model, Components, and Edge cases sections above.
