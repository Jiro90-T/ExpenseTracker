# Phase 2.13c — Statistics / Insights Tab — Design

**Status:** Approved 2026-07-04
**Phase:** 2.13c
**Predecessors:** Phase 2.13 (Statistics screen + 4 tabs) and Phase 2.13b (per-tab
date range picker). Insights were explicitly deferred from 2.13 as "too
speculative" — this phase introduces the v1 set.

## Goal

Add a fifth tab to the Statistics screen — **Insights** — that surfaces 4
actionable text observations derived from the user's transactions. Each
insight is a Card with an icon, headline, and supporting number. No new
charts, no ML, no animation. ~150 lines production code, ~120 lines test
code, ~10 new strings, 0 new abstractions.

Out of scope (intentional, deferred): natural-language generation beyond
the templated strings, anomaly detection, predictive "you'll spend $X by
month-end", cross-year comparisons beyond the existing YoY tab, multi-
account rollups, custom thresholds, recommended actions ("set a budget
on Food"), insight history or weekly digests.

## User-visible behavior

The Statistics screen's TabRow gains a fifth entry. The new tab has the
same layout pattern as the other four: scrollable column of insight cards.

### Insight cards (4 types, in priority order)

Each card has the same shape:
- Icon (left, 32dp tinted with primary)
- Headline (1 line, bold)
- Supporting text (1 line, onSurfaceVariant)

#### 1. Category delta (priority 1)

Surfaces the top 1 spending category whose amount changed the most
this calendar month vs last calendar month.

- **Headline:** `"[Category] up X%"` or `"[Category] down X%"` or
  `"New spending: [Category]"` when last month had 0 in this category.
- **Supporting text:** `"$Y this month vs $Z last month"` (or just
  `"$Y this month"` for the new-spending case).
- **Skip when:** no transactions in both current and prior month for the
  same category, OR fewer than 2 months of data exist overall.
- **Selection rule:** among categories with a non-zero delta, pick the
  one with the largest absolute `|current - previous|`. Tie-break by
  largest `current`.

#### 2. Weekend vs weekday (priority 2)

Compares spend on Sat–Sun vs Mon–Fri over the same 90-day window the
Patterns tab already uses.

- **Headline:** `"Weekend spending is X% of your total"` (rounded).
- **Supporting text:** `"$A on weekends vs $B on weekdays"`.
- **Skip when:** zero total spend in the 90-day window.

#### 3. Savings trend (priority 3)

Savings rate this calendar month vs the immediately prior calendar
month, expressed in percentage points.

- **Headline:** `"Savings rate up X pts"` or `"Savings rate down X pts"`
  or `"Savings rate unchanged at X%"`.
- **Supporting text:** `"X% this month vs Y% last month"`.
- **Skip when:** fewer than 2 months of data overall OR both months have
  income = 0 (rate undefined).

#### 4. Top expense spotlight (priority 4)

The single largest expense this calendar month.

- **Headline:** `"Largest expense: $A"` (formatted via MoneyFormat).
- **Supporting text:** `"[Title] on [Date]"` where Date is `MMM d` format.
- **Skip when:** no expenses in the current calendar month.

### Empty states

- **No transactions ever:** show one centered `Not enough data yet — log
  transactions to see insights` card instead of any insights.
- **Some data, no insights can compute** (rare — happens only when there
  are transactions but they're all in a single month and exclude
  weekends): show the same "Not enough data" message rather than an empty
  scroll area.

### Time windows

- **Category delta, Savings trend, Top expense spotlight:** current
  calendar month vs prior calendar month (matches the rest of Statistics).
- **Weekend vs weekday:** rolling 90-day window ending at `nowMs` (same
  as the Patterns tab).

### Refresh behavior

Identical to other tabs — observes the same Room `Flow` via
`combine + stateIn(WhileSubscribed(5_000))`. Numbers update within ~100ms
of a transaction write; no manual refresh.

## Data model

No schema changes. New types added to `ui/statistics/`.

### Insight sealed class

```kotlin
sealed class Insight {
    abstract val priority: Int          // sort order, lower = higher

    data class CategoryDelta(
        val categoryName: String,
        val direction: Direction,       // UP / DOWN / NEW
        val percentChange: Float,       // 0..N (e.g. 0.30 = +30%); 0f for NEW
        val currentMinor: Long,         // FX-converted to home currency
        val previousMinor: Long,        // FX-converted; 0L when NEW
        val currencyCode: String,
    ) : Insight() { override val priority = 10 }

    data class WeekendVsWeekday(
        val weekendMinor: Long,         // FX-converted
        val weekdayMinor: Long,         // FX-converted
        val weekendPercent: Float,      // 0..1
        val currencyCode: String,
    ) : Insight() { override val priority = 20 }

    data class SavingsTrend(
        val currentRate: Float,         // 0..1 (0f when undefined)
        val previousRate: Float,        // 0..1
        val direction: Direction,       // UP / DOWN / UNCHANGED
    ) : Insight() { override val priority = 30 }

    data class TopExpenseSpotlight(
        val amountMinor: Long,          // FX-converted to native currency
        val currencyCode: String,
        val title: String,              // transaction.title (or "—" if blank)
        val dateLabel: String,          // "MMM d" localized
    ) : Insight() { override val priority = 40 }

    enum class Direction { UP, DOWN, NEW, UNCHANGED }
}
```

`currencyCode` on each insight is the home currency (or the native
currency for `TopExpenseSpotlight`). The UI formats amounts via
`MoneyFormat.formatForDisplay(minor, code)` for visual consistency.

## Components

| File | Action | Purpose |
| --- | --- | --- |
| `ui/statistics/InsightsCalculator.kt` | new | Pure functions: `categoryDelta`, `weekendVsWeekday`, `savingsTrend`, `topExpenseSpotlight`. Returns `List<Insight>` from `(txns, cats, homeCurrency, fxRates, nowMs)`. JVM-testable. |
| `ui/statistics/StatisticsViewModel.kt` | modify | Add `insights: StateFlow<List<Insight>>` via the same `combine` pattern as the other 4 tabs. |
| `ui/statistics/StatisticsScreen.kt` | modify | Add `Insights` to the `StatTab` enum and a 5th branch in the `HorizontalPager` `when`. New `InsightsTab` composable (private). |
| `ui/statistics/InsightCard.kt` | new | Single `@Composable` that renders any `Insight` subclass. Pure presentation; no business logic. |
| `res/values/strings.xml` | modify | +10 strings (4 headlines, 4 supporting-text templates, 1 empty state, 1 tab label). |
| `app/src/test/.../ui/statistics/InsightsCalculatorTest.kt` | new | ~12 tests across the 4 calculators + ordering. |

### `InsightsCalculator` (pure helpers)

```kotlin
fun compute(
    txns: List<TransactionWithCategory>,
    cats: List<CategoryEntity>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
    nowMs: Long,
): List<Insight>
```

`compute` calls each of the 4 helpers, filters out nulls, and sorts by
priority ascending. The public surface is one function so callers don't
have to remember the right composition order.

**`categoryDelta`** — internal, returns `Insight.CategoryDelta?`:

- Resolve `currentBounds = monthBounds(currentYear, currentMonth)` and
  `previousBounds = monthBounds(currentYear, currentMonth - 1)` (handles
  January → prior December via `month - 1` with year wrap).
- Filter expenses in each window; FX-convert via the same fallback used
  by Phase 2.13 (missing rate → use raw `amountMinor`).
- Group by `categoryId`, resolve name from `cats` (missing → "Other").
- Drop categories where both months are 0.
- Compute `(currentMinor - previousMinor).toFloat() / previousMinor.toFloat()`
  per category. `previous == 0 && current > 0` → `direction = NEW`. Both
  non-zero → `direction = (current > previous) ? UP : DOWN`.
- Pick the category with the largest `|current - previous|`. Tie-break
  by largest `current`. Return null if nothing qualifies (e.g. <2 months
  of data, or every category has 0 in both months).

**`weekendVsWeekday`** — internal, returns `Insight.WeekendVsWeekday?`:

- Same window and FX fallback as `dayOfWeekPattern` in
  `StatisticsCalculator` (90 days ending at `nowMs`, expenses only).
- `weekendMinor` = sum of buckets where `isoDayOfWeek in 6..7`.
- `weekdayMinor` = sum of buckets where `isoDayOfWeek in 1..5`.
- Return null when both are 0.

**`savingsTrend`** — internal, returns `Insight.SavingsTrend?`:

- Same income/expense roll-up as `savingsAndAverage` in
  `StatisticsCalculator`, but for both current month and prior month.
- `currentRate = currentIncome > 0 ? (currentIncome - currentExpense) /
  currentIncome (clamped 0..1) : 0f`. Same for prior.
- Skip when both months have income == 0.
- `direction = (currentRate > previousRate) ? UP : (currentRate <
  previousRate) ? DOWN : UNCHANGED`. The "UNCHANGED" branch includes
  the floating-point near-equal case (`abs(diff) < 0.0001f`).
- Always returns a non-null `SavingsTrend` whenever at least one month
  has income > 0.

**`topExpenseSpotlight`** — internal, returns
`Insight.TopExpenseSpotlight?`:

- Filter expenses in current month, FX-convert to their **native**
  currency (not the home currency — the headline should show the amount
  the user actually paid in the currency they remember).
- Pick the row with the largest `amountMinor` (raw, after native FX is
  applied — which is a no-op for native-currency matching).
- `dateLabel` = `"MMM d"` formatted in `ZoneId.systemDefault()`.
- Return null when no expenses in the current month.

**Composition order** (the order returned, which the UI respects):

```
listOfNotNull(categoryDelta, weekendVsWeekday, savingsTrend, topExpenseSpotlight)
    .sortedBy { it.priority }
```

### `StatisticsViewModel` changes

```kotlin
val insights: StateFlow<List<Insight>> = combine(txns, cats, home, rates) { t, c, h, r ->
    InsightsCalculator.compute(t, c, h, r, System.currentTimeMillis())
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Mirrors the existing 4 tabs exactly. Initial value is `emptyList()`.

### `StatisticsScreen` changes

- `StatTab` enum gains `INSIGHTS("stats_tab_insights", Icons.Filled.Lightbulb)`.
- TabRow loop auto-picks it up.
- `HorizontalPager` body adds an `Insights -> InsightsTab(insights)` branch.
- `InsightsTab` is a private `@Composable` that scrolls a `LazyColumn`
  of `InsightCard`s, or renders the empty-state card when the list is
  empty.

### `InsightCard` composable

```kotlin
@Composable
private fun InsightCard(insight: Insight, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), ...) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = insight.icon(), contentDescription = null, ...)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(insight.headlineRes(), insight.headlineArgs()),
                     style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(insight.supportingTextRes(), insight.supportingTextArgs()),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

The sealed-class `when` lives in `InsightCard.kt` as private extension
functions (`icon()`, `headlineRes()`, `headlineArgs()`,
`supportingTextRes()`, `supportingTextArgs()`). Centralizing the
formatting mapping in one file means a new insight type only touches
two places: the calculator (data) and the card (mapping).

Icons (Material 3):
- `CategoryDelta.UP` → `Icons.AutoMirrored.Filled.TrendingUp`
- `CategoryDelta.DOWN` → `Icons.AutoMirrored.Filled.TrendingDown`
- `CategoryDelta.NEW` → `Icons.Filled.FiberNew`
- `WeekendVsWeekday` → `Icons.Filled.CalendarMonth`
- `SavingsTrend.UP` → `Icons.Filled.Savings` (tint: green)
- `SavingsTrend.DOWN` → `Icons.Filled.Savings` (tint: red)
- `SavingsTrend.UNCHANGED` → `Icons.Filled.Savings` (tint: onSurfaceVariant)
- `TopExpenseSpotlight` → `Icons.Filled.ReceiptLong`

## Strings to add

```
stats_tab_insights              "Insights"
stats_insights_empty            "Not enough data yet — log transactions to see insights"
stats_insights_cat_up           "%1$s up %2$d%%"
stats_insights_cat_down         "%1$s down %2$d%%"
stats_insights_cat_new          "New spending: %1$s"
stats_insights_cat_supporting   "%1$s this month vs %2$s last month"
stats_insights_cat_supporting_new "%1$s this month"
stats_insights_weekend          "Weekend spending is %1$d%% of your total"
stats_insights_weekend_support  "%1$s on weekends vs %2$s on weekdays"
stats_insights_savings_up       "Savings rate up %1$d pts"
stats_insights_savings_down     "Savings rate down %1$d pts"
stats_insights_savings_same     "Savings rate unchanged at %1$d%%"
stats_insights_savings_support  "%1$d%% this month vs %2$d%% last month"
stats_insights_top_expense      "Largest expense: %1$s"
stats_insights_top_expense_support "%1$s on %2$s"
```

(15 new strings, all formatted via `stringResource` with `String.format`
positional args. Money values are pre-formatted via `MoneyFormat` before
being passed as args.)

## Tests

In `app/src/test/.../ui/statistics/InsightsCalculatorTest.kt`:

1. `compute_returnsFourInsights_forTypicalUserWithTwoMonths`
2. `compute_categoryDelta_picksLargestAbsoluteDelta`
3. `compute_categoryDelta_picksDirectionUpDownNew`
4. `compute_categoryDelta_skipsWhenOnlyOneMonthOfData`
5. `compute_categoryDelta_excludesIncome`
6. `compute_weekendVsWeekday_uses90DayRollingWindow`
7. `compute_weekendVsWeekday_returnsNullWhenAllZero`
8. `compute_savingsTrend_undefinedWhenBothMonthsHaveZeroIncome`
9. `compute_savingsTrend_marksUnchangedWhenWithinEpsilon`
10. `compute_topExpenseSpotlight_picksLargestExpense`
11. `compute_topExpenseSpotlight_usesNativeCurrencyNotHome`
12. `compute_topExpenseSpotlight_returnsNullWhenNoExpenses`
13. `compute_fallsBackToRawAmountWhenFxRateMissing`
14. `compute_returnsEmptyListWhenNoTransactionsAtAll`

(14 new tests. No VM tests; the `insights` StateFlow is the same shape
as the existing 4 tabs. No Compose UI tests; the screen is a thin
`when` over a sealed class and visual smoke covers the layout.)

## Edge cases

| Case | Behavior |
|---|---|
| No transactions ever | Empty-state card only; no insight cards. |
| Only 1 month of data | CategoryDelta and SavingsTrend skip; WeekendVsWeekday + TopExpenseSpotlight still appear. |
| New spending category (this month, none last month) | CategoryDelta with `direction = NEW`; headline says "New spending: …". |
| Mixed currencies, some FX rates missing | Same fallback as Phase 2.13 — use raw `amountMinor`. No new chip; the per-tab FX-missing chip would clutter a small card. |
| Timezone change mid-session | Recompute on next emission; numbers update when user navigates back. |
| User changes home currency mid-session | All 4 insights recompute via `combine`; FX-converted values update on next render. |
| User changes a category name (edit) | CategoryDelta uses the new name from `cats` flow on next emission. |
| Top expense has a blank title | Show `"—"` instead of empty string in supporting text. |
| 90-day window entirely inside one calendar month | WeekendVsWeekday still computes (it's a rolling window, not a calendar window). |
| Equal-tie category delta | Tie-break by largest `current`. Stable for tests. |
| Floating-point savings-rate comparison | Use `abs(current - previous) < 0.0001f` for UNCHANGED to avoid 0.33f vs 0.33f != 0.33f issues. |

## Error handling

| Failure | Surfaced as |
|---|---|
| FX rate missing for a row's currency | Fall back to raw `amountMinor` (same as Phase 2.13). No crash, no user-visible chip. |
| `monthBounds` called with month out of range | `require(month in 1..12)` — programmer error; throws in tests, never in production. |
| Empty `txns` | Each helper returns null; `compute` returns `emptyList()`; UI shows empty state. |
| Empty `cats` | CategoryDelta resolves missing ids as `"Other"` (matches existing Phase 2.13 behavior). |
| `homeCurrency` blank | Treated as no-op FX (matches Phase 2.13). |

## Out of scope (intentional, deferred)

- **ML-based predictions** — too speculative for v1.
- **Multi-month comparison** — single-month deltas only; longer windows
  would require sliding-window math.
- **Recommended actions** — no "set a budget on Food" buttons in this
  phase.
- **Insight history** — no "yesterday's insights" log; the tab is
  computed on demand from current data.
- **Custom thresholds** — every insight has a fixed rule; no settings
  to tune the trigger.
- **Push notifications** — not in scope; insights are pull-only inside
  the app.
- **Per-account rollups** — no auth in v1 (per CLAUDE.md).