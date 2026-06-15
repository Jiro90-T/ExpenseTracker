# Phase 2.9 — Transactions Filter Polish — Design

**Status:** Approved 2026-06-15
**Phase:** 2.9
**Predecessors:** Phase 2.7 (Transactions search & filter) ships the 4 filter controls and the `TransactionFilters` data class. Phase 2.9 adds two focused polish features: search highlighting in the result list, and an amount range (min/max) filter.

## Goal

Round out the Transactions search/filter experience (v0.7.0) with two power-user features that ship in the same v0.9.0 release:

1. **Search highlighting** — when the search field is non-empty, the matching substring in each row's title (and note if present) is rendered with a bold + accent color, so the user can see *why* each row matched.
2. **Amount range filter** — two text fields (Min, Max) in the home currency. The filter matches transactions whose home-currency-converted amount is in the range. Both fields are optional.

Out of scope (intentional, deferred): multi-select categories, saved filters, filter from Home dashboard, recurring-only filter, sort options, range slider for amount, search highlighting in category name or amount, per-txn-currency amount range.

## User-visible behavior

When the user opens the Transactions tab:

- A new row in the filter controls shows two `OutlinedTextField`s labeled "Min" and "Max". Both are optional. The user types a decimal number (e.g. "10" or "10.50"). The filter is applied live (debounced ~300ms, matching the search field's pattern).
- Below the filter controls, the day-grouped list renders the filtered transactions. When a search query is non-empty, each row's title and (if present) note are rendered with the matching substring wrapped in a bold + accent-color span. The amount and category name are not highlighted.
- Min and Max are both in the home currency. A EUR transaction with a 10.00 EUR amount matches a "10" min if the home currency is EUR. If the home currency is USD, the same transaction's amount is first converted via the FX rates (or 1:1 fallback if no rate) and then compared.
- Min and Max persist across app restarts (via SharedPreferences, same pattern as the v0.7.0 filters).
- A "Clear filters" button still works as before and now also clears the amount range.

## Data model

**`TransactionFilters` (extended)** — `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt`:

```kotlin
data class TransactionFilters(
    val searchQuery: String = "",
    val categoryId: Long? = null,
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val dateRange: DateRangePreset = DateRangePreset.Any,
    val minAmount: Long? = null,   // minor units, home currency; null = no min
    val maxAmount: Long? = null,   // minor units, home currency; null = no max
) {
    val isEmpty: Boolean
        get() = searchQuery.isEmpty() && categoryId == null
            && typeFilter == TypeFilter.ALL && dateRange is DateRangePreset.Any
            && minAmount == null && maxAmount == null
}
```

**`filterTransactions` (extended signature)** — the pure helper gains 2 new params:

```kotlin
fun filterTransactions(
    rows: List<TransactionWithCategory>,
    filters: TransactionFilters,
    allCategories: List<CategoryEntity>,
    nowMs: Long,
    homeCurrency: String = "USD",
    fxRates: Map<String, Double> = emptyMap(),
): List<TransactionWithCategory>
```

The amount range filter is applied as a chain step:
- If `minAmount == null && maxAmount == null` → no filter (passes all rows).
- If both are set and `minAmount > maxAmount`, swap them (matching the Custom date range auto-swap pattern).
- For each row, compute `homeMinor = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates) ?: t.amountMinor` (1:1 fallback if no rate).
- If `homeMinor < min || homeMinor > max` → filter out.

**Why pass `homeCurrency` + `fxRates` to the filter helper instead of precomputing a per-row `homeMinor` map:** consistent with `computeBudgetAlerts` and `computeDashboardSummary` which take these params for the same reason (per-row FX normalization inline). The function remains pure; `nowMs` is the only time-dependent param.

**New pure helper** in `Filters.kt` (or a new `Highlight.kt`):

```kotlin
/**
 * Pure: returns an [AnnotatedString] where every case-insensitive occurrence
 * of [query] within [text] is wrapped in [highlightStyle]. Empty/blank query
 * returns the unstyled text. Used by the Transactions list to bold the
 * matching substring when a search query is active.
 */
fun highlightMatches(
    text: String,
    query: String,
    highlightStyle: SpanStyle,
): AnnotatedString
```

Implementation: `buildAnnotatedString` with `append(text.substring(0, start))`, `withStyle(highlightStyle) { append(text.substring(start, end)) }`, `append(text.substring(end))`, repeated for each match.

The `SpanStyle` is constructed at the call site (in the Compose composable) with `SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)`. The helper takes the style as a parameter so it stays UI-agnostic and JVM-testable.

## Components

| File | Purpose |
| --- | --- |
| `ui/transactions/Filters.kt` (modified) | `TransactionFilters` gains 2 fields, `filterTransactions` gains 2 params, new `highlightMatches` helper. |
| `ui/transactions/FiltersRepository.kt` (modified) | 2 new SharedPreferences keys for min/max. |
| `ui/home/HomeViewModel.kt` (modified) | `filteredTransactions` flow passes `homeCurrency` + `fxRates` to `filterTransactions` (matches the budget alerts pattern). |
| `ui/home/SwipeableTransactionRow.kt` (modified) | Renders title + note with `AnnotatedString` highlighting when a search query is active. |
| `ui/transactions/TransactionsScreen.kt` (modified) | Adds the Min/Max text fields to the filter controls, and passes `searchQuery` to the row. |
| `res/values/strings.xml` (modified) | 4 new strings. |
| `app/src/test/.../ui/transactions/FiltersTest.kt` (modified) | ~10 new tests (7 for amount range, 3 for highlight). |

## `SwipeableTransactionRow` change

Currently takes a `TransactionWithCategory` and renders title + amount + category. We extend it to optionally take a `searchQuery: String?` (default `null`). When non-null and non-blank, the title and note (if present) are rendered via the `highlightMatches` helper. The amount and category name are NOT highlighted (per the design choice).

The Transactions screen passes the active `searchQuery`; the Home screen passes `null` (no highlight). The `Search` filter UI is only present on the Transactions tab, so `searchQuery` is always effectively empty/null on Home.

The row's composable signature becomes:
```kotlin
@Composable
fun SwipeableTransactionRow(
    row: TransactionWithCategory,
    onEdit: (Long) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,  // NEW
)
```

## `FiltersRepository` persistence

Two new keys:
- `KEY_FILTER_MIN_AMOUNT` (Long, default `Long.MIN_VALUE` as "absent" sentinel → `null`)
- `KEY_FILTER_MAX_AMOUNT` (Long, default `Long.MIN_VALUE` as "absent" sentinel → `null`)

Round-trip via SharedPreferences. Same pattern as the existing `KEY_FILTER_CATEGORY_ID` (which uses `-1L` as the sentinel — we use `Long.MIN_VALUE` here because category ids are positive longs and `Long.MIN_VALUE` is the unambiguous "not present" marker for amount range).

The decoder:
```kotlin
prefs.getLong(KEY_FILTER_MIN_AMOUNT, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
```

## Amount range UI

Two `OutlinedTextField`s in a new row in the filter controls. Both use `MoneyFormat.parseAmountToMinor` (existing helper) to parse the input. Invalid input is treated as "no filter" (no crash). On input change, the field updates a local `mutableStateOf<String>` (immediate UI update), then `LaunchedEffect` with `delay(300)` commits to the VM via `setMinAmount(minor)` / `setMaxAmount(minor)` (matching the search field's debounce pattern).

The fields stay visible always (like the type chips). They're not gated by any preset.

Clear behavior: each field has a trailing `Icons.Filled.Close` icon (visible only when the field is non-empty) that clears just that field.

## Tests

In `FiltersTest.kt`, add ~10 new tests. The existing 34 still pass (the new `homeCurrency` + `fxRates` params default to `"USD"` + `emptyMap()`, so existing tests don't need changes).

### Amount range (7 tests)
1. `filterTransactions_amountRangeEmpty_isNoOp` — both null, all rows pass.
2. `filterTransactions_amountRangeOnlyMin_filtersHigher` — min set, only rows with amount >= min.
3. `filterTransactions_amountRangeOnlyMax_filtersLower` — max set, only rows with amount <= max.
4. `filterTransactions_amountRangeBoth_filtersBetween` — both set, only rows in range.
5. `filterTransactions_amountRangeInverted_swapped` — min > max → swap to (max, min).
6. `filterTransactions_amountRangeEqual_singleValueWindow` — min == max, only exact match.
7. `filterTransactions_amountRangeWithFxNormalized_usesHomeCurrency` — a EUR txn ($100 EUR) with home=USD and rate USD_to_EUR=0.92 → 100/0.92 ≈ 108 → compared as ~$108.

### Highlight (3 tests)
1. `highlightMatches_emptyQuery_returnsUnstyledText` — empty query, no styles applied.
2. `highlightMatches_queryMatches_substringWrappedInStyle` — query "foo" in "foobar" → "foo" wrapped.
3. `highlightMatches_queryCaseInsensitive` — query "FOO" in "foobar" → "foo" wrapped.

(~10 new tests total. Total: 44 in `FiltersTest.kt`.)

## Strings to add

```
filter_amount_min      "Min"
filter_amount_max      "Max"
filter_amount_min_hint "Min amount"
filter_amount_max_hint "Max amount"
```

(4 new strings.)

## Edge cases

| Case | Behavior |
| --- | --- |
| Amount range with min == max | Single-value window — only the exact amount passes. |
| Amount range with min > max | Auto-swap (matching the Custom date range pattern). |
| Invalid amount input ("abc", "12.3.4") | `MoneyFormat.parseAmountToMinor` returns null; treat as no filter. |
| Empty min + filled max | Only max applies. |
| Filled min + empty max | Only min applies. |
| Transaction in a non-home currency with no FX rate | 1:1 fallback (consistent with budget alerts / dashboard). |
| Search query changes while user is scrolling | The list re-renders with new highlights. StateFlow handles this. |
| Highlight overlap (multiple matches) | All matches get the same style; no nesting issues (Compose `AnnotatedString` handles this). |
| Note is null | Title only is highlighted. |
| Title is empty | Empty AnnotatedString rendered (no highlight). |
| Min/max fields persist across app restarts | ✓ via SharedPreferences. |
| Min/max fields survive filter clear | The "Clear filters" button now also clears min/max. |

## Out of scope (intentional, deferred)

- Multi-select categories.
- Saved filters.
- Filter from Home dashboard.
- Recurring-only filter.
- Sort options.
- Search highlighting in category name or amount.
- Highlighting the row background instead of the text.
- Range slider for amount (text fields are simpler and ship faster).
- Currency selector for amount range (amounts are always in the home currency, matching the dashboard's `homeCurrency`).
- Highlighting in the dashboard card (e.g. summary "Top expense categories") — only the per-row title/note.
- "Real-time" amount range validation (e.g. showing an error if min > max) — auto-swap handles this.
- Caching the FX-converted `homeMinor` per row across multiple filter evaluations — the conversion is `O(n)` and the filter debounces, so caching is not worth the complexity.
- The `MoneyFormat.formatAmountForEdit` thousands-separators search fix (deferred to a future polish pass — search "1,200" still won't match $1,200.00 in v0.9.0).

## Open questions

None. Decisions taken one at a time during brainstorming and recorded in the User-visible behavior, Data model, Components, and Edge cases sections above.
