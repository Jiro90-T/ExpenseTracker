# Phase 2.6 — Trends Polish Pack — Design

**Status:** Approved 2026-06-12
**Phase:** 2.6
**Predecessors:** Phase 2.5 (Trends) ships the 3-line chart, the `MonthlyTrend` data shape, and the `TrendsViewModel`/`TrendsScreen`/`LineChart` triad that this phase extends.

## Goal

Make the Trends tab configurable and analytically richer without adding a chart library. Specifically:
- **Period selector** (3M / 6M / 12M / YTD / All) so the user can change the window.
- **Comparison vs prior period** as both ghost lines on the chart and a percent-delta panel below it.
- **Current month marker** as a thin vertical dashed line on the chart when today is in the window.

Out of scope (deferred to later phases): the bar/line period-anchor inconsistency (the Home bar chart stays on Home's `Period`), dark-scheme `NetBlue` variant, animated chart transitions, "Save to Photos" share affordance.

## User-visible behavior

When the user opens the Trends tab:

- A `SingleChoiceSegmentedButtonRow` sits above the chart with five options: 3M, 6M, 12M, YTD, All. The default is **6M** (matches Phase 2.5 behavior — zero behavior change on first open).
- Tapping an option re-renders the chart and the panel for that window. The currently selected month (if any) is cleared on period change.
- The chart shows the same 3 colored polylines as Phase 2.5 (income, expense, net) with dots at each data point. When the period has a meaningful prior (3M/6M/12M/YTD — not All), three additional **ghost lines** are drawn underneath at 30% alpha showing the prior period's values for the same three series, with no dots and a slightly thinner stroke.
- A thin **vertical dashed line** is drawn at the X position of the month containing today, only when that month is inside the selected window.
- The legend grows from 3 swatches (Phase 2.5) to 6 swatches arranged in two rows of three: "Income", "Income (prior)", "Expense", "Expense (prior)", "Net", "Net (prior)". When the period is All, the legend collapses back to 3 swatches.
- Below the chart, when the period has a meaningful prior, a comparison card titled "vs prior {N} months" shows three rows: Income, Expense, Net, each with a percent delta colored green/red/gray. When prior is null (All), the card is hidden.
- Tap-to-inspect on the current period still works (Phase 2.5 behavior unchanged). Tapping a ghost line's region does nothing.

## Data model

**No schema changes.** Phase 2.5's `MonthlyTrend` data class is reused unchanged. New types are added next to it in `ui/charts/`.

**New types** in `app/src/main/java/io/github/jiro/expensetracker/ui/charts/TrendsPeriod.kt` (lumped with the chart data siblings — keeps `ui/charts/` as the single home for "chart things"):

```kotlin
enum class TrendsPeriod(val monthsBack: Int?, val labelRes: Int) {
    ThreeMonths(3, R.string.trends_period_3m),
    SixMonths(6, R.string.trends_period_6m),
    TwelveMonths(12, R.string.trends_period_12m),
    Ytd(null, R.string.trends_period_ytd),
    All(0, R.string.trends_period_all);
}

data class PeriodTrends(
    val current: List<MonthlyTrend>,
    val prior: List<MonthlyTrend>?,
    val delta: ComparisonDelta?,
    val currentMonthMs: Long?,
)

data class ComparisonDelta(
    val incomePct: Double?,
    val expensePct: Double?,
    val netPct: Double?,
)
```

**`monthsBack` semantics:**
- `ThreeMonths(3)`, `SixMonths(6)`, `TwelveMonths(12)` — number of months back from the start of the current month. `3M` = the current month and the two before it.
- `Ytd(null)` — the months from January 1 of the current year through the current month.
- `All(0)` — the full history. The `0` is a sentinel meaning "no windowing"; the function treats it specially.

**`prior` is null only for `All`.** For YTD, prior is the same calendar range in the prior calendar year. For 3M/6M/12M, prior is the consecutive, non-overlapping N months immediately before the current window.

**`delta` percent calculation:** for each series, `delta.seriesPct = (current.sum - prior.sum) / abs(prior.sum) * 100`. If `prior.sum == 0`, the field is `null` (UI shows "—"). The function uses `abs(prior.sum)` as the denominator to avoid sign-flips dominating the percent.

**`currentMonthMs` semantics:** `startOfMonth(nowMs)` if and only if that month is inside the `current` list. Otherwise `null` (no marker drawn). For `All` period the field is always non-null when the data isn't empty (today is always in "all of time"), but the chart will still draw the marker because today is conceptually part of the range.

**New pure function** (in the same file):

```kotlin
fun computePeriodTrends(
    rows: List<TransactionWithCategory>,
    period: TrendsPeriod,
    nowMs: Long,
): PeriodTrends
```

A pure function that:
1. Buckets all rows by `startOfMonth` (reusing the helper from Phase 2.5).
2. Computes the `[fromMs, toMsExclusive)` range for the current period.
3. Computes the equivalent range for the prior period (consecutive for N-month windows; year-prior for YTD; null for All).
4. Filters and emits two `List<MonthlyTrend>` — the current and the prior — using the same shape as `computeMonthlyTrends` from Phase 2.5.
5. Sums each side across the window's months and computes the percent deltas.
6. Returns `PeriodTrends(current, prior, delta, currentMonthMs)`.

JVM-testable, deterministic.

**Why a new function instead of layering on `computeMonthlyTrends`:** the window range for the current period depends on `nowMs` and `TrendsPeriod` (not just "give me all months"), and the prior period is its own separate window. Trying to express this as a sequence of `computeMonthlyTrends` calls would scatter the date math. One function, one set of parameters, one return value.

**Why keep the existing `computeMonthlyTrends`:** it's still used (or at least tested) and removing it would churn tests. It can stay as a thin wrapper if helpful, or be left untouched if not used elsewhere.

## Components

| File | Purpose |
| --- | --- |
| `ui/charts/TrendsPeriod.kt` (new) | `TrendsPeriod` enum, `PeriodTrends` data class, `ComparisonDelta` data class, `computePeriodTrends` pure helper. |
| `ui/charts/LineChart.kt` (modified) | Extended signature: `prior`, `currentMonthMs`. Draws ghost lines underneath and the dashed marker. Legend grows to 6 swatches. |
| `ui/trends/TrendsViewModel.kt` (modified) | Add `period: StateFlow<TrendsPeriod>` (default `SixMonths`), `periodTrends: StateFlow<PeriodTrends>`, `setPeriod(...)`. `selected` resets to `null` on period change. |
| `ui/trends/TrendsScreen.kt` (modified) | Add `SingleChoiceSegmentedButtonRow` above the chart and a `ComparisonCard` below. Wire to VM. |
| `res/values/strings.xml` (modified) | Add 8 new strings (see below). |
| `app/src/test/java/.../ui/charts/TrendsPeriodTest.kt` (new) | JUnit tests for `computePeriodTrends`. |

### `LineChart` extended signature

```kotlin
@Composable
fun LineChart(
    data: List<MonthlyTrend>,
    prior: List<MonthlyTrend>?,
    currentMonthMs: Long?,
    selected: MonthlyTrend?,
    onSelect: (MonthlyTrend?) -> Unit,
    modifier: Modifier = Modifier,
)
```

- **Current period** (3 solid polylines, dots, ring on selected month) — unchanged from Phase 2.5.
- **Prior period** (3 ghost polylines, `alpha = 0.30f`, no dots, slightly thinner stroke like 1.5.dp instead of 2.dp). Drawn first (underneath) so the current lines and dots land on top.
- **Current month marker** — a single vertical dashed line (1.dp stroke, `alpha = 0.6f`, `PathEffect.dashPathEffect(floatArrayOf(6f, 6f))`) drawn at the X position of `currentMonthMs`. Drawn after the lines and before the dots so the dots are visible over it. Hidden when `currentMonthMs == null` or when `currentMonthMs` is not in `data`.
- **Legend** — when `prior != null`: 6 swatches in two rows of three. Row 1: Income, Income (prior), Expense. Row 2: Expense (prior), Net, Net (prior). When `prior == null`: 3 swatches in one row, same as Phase 2.5. Swatches for prior lines are smaller (4.dp instead of 6.dp) and at 30% alpha to visually distinguish them.
- **Y-axis scaling** — `max(max(current.income), max(current.expense), abs(min(current.net)), max(prior.income), max(prior.expense), abs(min(prior.net)))`. Stays consistent across the comparison so both lines share the same scale.
- **X-axis labels** — only the `current` window's months are labelled, evenly spaced. The prior ghost lines use the same X positions (month 0 of the prior line aligns with month 0 of the current line), so the comparison is visually obvious.
- **Tap-to-inspect** — only the current period is interactive. The ghost lines have no hit-testing. The existing tap-to-clear behavior on re-tap is preserved.

### `TrendsViewModel`

```kotlin
@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _period = MutableStateFlow(TrendsPeriod.SixMonths)
    val period: StateFlow<TrendsPeriod> = _period.asStateFlow()

    val periodTrends: StateFlow<PeriodTrends> =
        combine(
            repository.observeAll(),
            _period,
            settingsRepository.homeCurrency,
            settingsRepository.fxRates,
        ) { rows, period, _, _ -> rows to period }
            .map { (rows, period) -> computePeriodTrends(rows, period, nowMs = System.currentTimeMillis()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeriodTrends(emptyList(), null, null, null))

    private val _selected = MutableStateFlow<MonthlyTrend?>(null)
    val selected: StateFlow<MonthlyTrend?> = _selected.asStateFlow()

    fun setPeriod(period: TrendsPeriod) {
        if (_period.value != period) {
            _period.value = period
            _selected.value = null  // clear selection on period change
        }
    }

    fun select(month: MonthlyTrend?) { _selected.value = month }
}
```

The `nowMs` is captured inside the `map` so the calculation is anchored to the moment the data arrives. For unit tests, this is replaced with a parameter — see "Testability" below.

**`nowMs` parameter is captured in the production mapping but is a parameter of the pure function.** The VM closure is the only place the production "now" is injected. Tests pass an explicit `nowMs` to the pure function.

### `TrendsScreen` layout

```
┌─────────────────────────────────────┐
│  TopAppBar: "Trends"                 │
├─────────────────────────────────────┤
│  [ 3M ][ 6M* ][ 12M ][ YTD ][ All ]  │  ← SingleChoiceSegmentedButtonRow
│                                     │
│  "Tap a month to see details"       │
│                                     │
│  ┌───────────────────────────────┐  │
│  │  ● Income  ● Income (prior)   │  │  ← legend row 1
│  │  ● Expense                    │  │
│  │                               │  │
│  │     Line chart (160.dp)       │  │
│  │  ghost lines + 3 solid lines  │  │
│  │  dashed marker at "now"       │  │
│  │                               │  │
│  └───────────────────────────────┘  │
│  Mar   Apr   May   Jun   Jul   Aug  │
│                                     │
│  [Detail panel — when selected]     │  ← Phase 2.5
│                                     │
│  ┌───────────────────────────────┐  │
│  │  vs prior 6 months            │  │  ← NEW comparison card
│  │  Income:  +12.3%   ▲          │  │
│  │  Expense:  -5.4%   ▼          │  │
│  │  Net:     +24.1%   ▲          │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

The `SingleChoiceSegmentedButtonRow` (Material 3) is a horizontally-scrolling row of toggle buttons. Wrapping isn't needed because five short labels fit. The `*` indicates the selected option (rendered as a filled button vs outlined).

The comparison card uses `Card` with `CardDefaults.elevatedCard` so it visually separates from the detail panel. When `period == All` it doesn't render at all.

### Comparison card details

- Title: `vs prior {N} months` (parameterized: `R.string.trends_compare_panel_title` with `%1$d`).
- Three rows of label + percent + arrow:
  - `Income: +12.3% ▲` (green up arrow)
  - `Expense: -5.4% ▼` (red down arrow)
  - `Net: +24.1% ▲` (green/red depending on sign)
- The arrow direction is "up if positive, down if negative", regardless of "good/bad" semantics. Green = positive, red = negative. Zero is gray with no arrow.
- Format: `String.format("%.1f", pct)` followed by a `%` sign. Trailing zeros are stripped (e.g. `12.0` → `12%`, not `12.0%`).
- When `pct == null` (prior is zero), the row shows `—` (em dash) instead of a number. The arrow is hidden.
- The card is `AnimatedVisibility`-wrapped so it fades in/out when the period changes (e.g. switching from 6M to All makes it disappear).

### Tap-to-inspect detail

Phase 2.5 behavior is unchanged: tapping a current-period month shows the detail panel (Month label, Income, Expense, Net). The detail panel sits above the comparison card, separated by 8.dp of vertical space.

## Edge cases

| Case | Behavior |
| --- | --- |
| No transactions | "No data" placeholder. No ghost lines, no marker, no comparison card, no detail panel. |
| All period | No ghost lines, no marker, no comparison card. The full history is shown. |
| Window has only 1 month | Chart still draws. Y-axis scales correctly. Tap-to-inspect works. |
| Prior income = 0, current income > 0 | `delta.incomePct = null`. Card shows "—" for that row. |
| Prior income = current income | Percent delta = 0.0%. Format: `0%` (no decimal). Gray, no arrow. |
| Today's month is not in the window (e.g. window is "last 3 months" but app data is from 2 years ago) | `currentMonthMs = null`. No marker drawn. |
| Selected month when changing period | Selection clears. |
| Window has fewer months than the user might expect (e.g. 12M but only 3 months of data) | The chart shows 3 data points with the rest of the X axis empty. Y-axis scales to the actual data. The chart's X labels show only the 3 months with data. |
| YTD edge: today is January 1 | `Ytd` window is just January. Prior is January of last year. Both have at most 1 data point. |
| YTD edge: today is December 31 | `Ytd` window is Jan–Dec (12 months). Prior is Jan–Dec of last year. |

## Error handling

| Failure | Surfaced as |
| --- | --- |
| DB read fails upstream | VM state stays empty; chart shows "No data". Consistent with Phase 2.5. |
| `nowMs` clock skew (device clock changed) | The `currentMonthMs` is computed from `System.currentTimeMillis()`. If the user changes the device clock backwards, the marker may be in a weird spot. Acceptable — the data is anchored to wall-clock time throughout. |
| Pure function called with `nowMs` in the future | The current window still resolves to "the current month and N-1 prior months". The prior window resolves to "N months before that". No special handling — works as defined. |

## Testability

The pure function `computePeriodTrends` takes `nowMs` as a parameter, so tests can pin "now" to a known instant. The VM uses `System.currentTimeMillis()` once per emission, captured in the `map`. No mocking framework is needed.

The VM test (if added later) would mock `TransactionRepository` and `SettingsRepository`. For Phase 2.6 the spec doesn't require a VM test — the pure function tests cover the math, and the UI is exercised on device in the manual smoke test.

## Tests

| Test | File | What it asserts |
| --- | --- | --- |
| `computePeriodTrends_emptyRows_returnsEmpty` | `TrendsPeriodTest.kt` | `rows = []` → `current = []`, `prior = []` (or null for All), `currentMonthMs = ???`. |
| `computePeriodTrends_sixMonths_windowOnly` | same | Rows from various months (some inside 6M window, some outside) → only inside-window months appear in `current`. |
| `computePeriodTrends_sixMonths_priorIsPrecedingSix` | same | The `prior` list contains the 6 months immediately before the `current` window, with correct totals. |
| `computePeriodTrends_allPeriod_priorIsNull` | same | `period = All` → `prior = null`, `delta = null`. |
| `computePeriodTrends_ytdPriorIsSameRangeLastYear` | same | `nowMs` in June 2026 → `current` is Jan–Jun 2026, `prior` is Jan–Jun 2025. |
| `computePeriodTrends_ytdJanEdge` | same | `nowMs` in early January 2026 → `current` is just Jan 2026, `prior` is Jan 2025. |
| `computePeriodTrends_deltaCalculations` | same | Hand-built `MonthlyTrend` lists, check percent deltas (e.g. income 100→150 = +50.0%, expense 50→40 = -20.0%, net 50→110 = +120.0%). |
| `computePeriodTrends_priorZero_pctIsNull` | same | prior income sum = 0 → `delta.incomePct = null`. |
| `computePeriodTrends_currentAndPriorEqual_pctIsZero` | same | current sum = prior sum → `delta.*Pct = 0.0`. |
| `computePeriodTrends_purityRepeatedCalls` | same | Idempotence: two calls with the same args return equal `PeriodTrends`. |
| `computePeriodTrends_currentMonthIsInWindow` | same | `nowMs` in the middle of a 6M window → `currentMonthMs = startOfMonth(nowMs)`. |
| `computePeriodTrends_currentMonthIsOutsideWindow` | same | `nowMs` older than the window (e.g. window = recent 3M, nowMs = 2 years ago) → `currentMonthMs = null`. |
| `computePeriodTrends_priorMatchesCurrentLength` | same | For fixed N (3M/6M/12M), `current.size == prior.size`. For YTD, the lengths are equal. For All, prior is null. |

No Compose UI test. The chart is exercised on device in the manual smoke test.

## Strings to add

```
trends_period_3m               "3M"
trends_period_6m               "6M"
trends_period_12m              "12M"
trends_period_ytd              "YTD"
trends_period_all              "All"
trends_compare_panel_title     "vs prior %1$d months"
trends_compare_income          "Income"
trends_compare_expense         "Expense"
trends_compare_net             "Net"
```

Nine new strings. The "vs prior" panel uses the existing `trends_detail_income` / `trends_detail_expense` / `trends_detail_net` strings as the row labels (parameterized as `"Income: %1$s"`, etc., to match Phase 2.5) — no new strings needed for the row labels.

The "no data" case reuses the existing `charts_no_data` — no new string needed.

## Files touched (summary)

**New:** `TrendsPeriod.kt`, `TrendsPeriodTest.kt`.

**Modified:** `LineChart.kt`, `TrendsViewModel.kt`, `TrendsScreen.kt`, `strings.xml`.

## Out of scope (intentional)

- **Bar/line period-anchor inconsistency** from Phase 2.5 — the Home bar chart stays on Home's `Period` (Month / All). Threading a shared period through both screens is a bigger refactor. Defer.
- **Dark-scheme `NetBlue` variant** — defer to a future theme polish pass.
- **Animated chart transitions** on period change (no morphing between data sets).
- **"Save to Photos" share affordance** for the chart — defer.
- **Pinch-to-zoom / drag** on the chart — defer.
- **Forecast / projection** — defer.
- **Per-line color customization** — defer.
- **Animations on percent deltas** (no tween between -5% and -10%) — keep it static.
- **Switching "now" anchor** (e.g. "as of 3 months ago") — keep it as "now" only.

## Open questions

None. Decisions were taken one at a time during brainstorming and recorded in the User-visible behavior, Data model, Components, and Edge cases sections above.
