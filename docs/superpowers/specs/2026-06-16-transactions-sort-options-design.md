# Phase 2.12 — Transactions Sort Options — Design

**Status:** Approved 2026-06-16
**Phase:** 2.12
**Predecessors:** Phase 2.7 introduced filter persistence in `FiltersRepository` and the `TransactionFilters` data class that sort lives alongside. Phase 2.9 introduced the FX-converted amount range filter in `homeCurrency`, which Phase 2.12 mirrors for the amount sort. Tagged v0.11.0.

## Goal

Add explicit sort options to the Transactions tab. The list is currently ordered by `occurredAtEpochMillis DESC` (newest first) at the DAO level — fine for a default but unchangeable. Phase 2.12 lets the user sort by date, amount, title, or category, with an asc/desc toggle, persisted across restarts. ~120 lines of production code, ~150 lines of test code, 7 new strings, 0 new abstractions.

Out of scope (intentional, deferred): multi-criteria sort, grouped-by-currency sort, custom sort presets, sort from Home dashboard, animated reorder, sort indicator on a column header.

## User-visible behavior

On the Transactions tab, a new compact row sits inside the filter controls (between the type chips and the amount range):

```
Sort: [Date ▾]   [↓]
```

- The dropdown shows the current sort field: **Date** (default), **Amount**, **Title**, **Category**.
- The icon button to the right shows the current direction: **↓** (Desc) or **↑** (Asc). Single tap to flip.
- Default: **Date + Desc** — matches current behavior, newest first. No visible change for users who don't touch it.
- Sort applies after filters (filter, then sort).
- Day-grouped list headers: render when `sort.field == DATE` (unchanged). Suppressed for Amount/Title/Category (would be arbitrary once date order is broken).
- Sort persists across app restarts via the same SharedPreferences pattern as the existing filters.
- The "Clear filters" button does **not** reset sort — sort is a view preference, not a filter.

## Data model

**No schema changes.** No new tables, columns, or files. New types added to the existing `Filters.kt` and the existing `FiltersRepository` extended with two more keys.

**New types** in `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt`:

```kotlin
enum class SortField { DATE, AMOUNT, TITLE, CATEGORY }
enum class SortDirection { ASC, DESC }

data class TransactionSort(
    val field: SortField = SortField.DATE,
    val direction: SortDirection = SortDirection.DESC,
)
```

The new `TransactionSort` data class is separate from `TransactionFilters` (sort is conceptually a view preference, not a filter) but lives in the same file and is persisted by the same repository.

## Components

| File | Purpose |
| --- | --- |
| `ui/transactions/Filters.kt` (modified) | +`SortField`, +`SortDirection`, +`TransactionSort`, +`sortTransactions` pure function. |
| `ui/transactions/FiltersRepository.kt` (modified) | +`sort: StateFlow<TransactionSort>`, +`setSort(...)`, 2 new SharedPreferences keys. |
| `ui/home/HomeViewModel.kt` (modified) | +`sort` flow, +`setSortField` / +`setSortDirection` / +`flipSortDirection` mutators, `filteredTransactions` extends `combine` to 6 sources (vararg form) and pipes the filtered list through `sortTransactions`. |
| `ui/transactions/TransactionsScreen.kt` (modified) | +sort row in `FilterControls`, gate `groupByDay` on `sort.field == DATE`. |
| `app/src/test/.../ui/transactions/FiltersTest.kt` (modified) | +14 tests for `sortTransactions`. |
| `res/values/strings.xml` (modified) | +7 strings. |

### `sortTransactions` (pure helper)

```kotlin
fun sortTransactions(
    rows: List<TransactionWithCategory>,
    sort: TransactionSort,
    allCategories: List<CategoryEntity>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
): List<TransactionWithCategory>
```

Sort logic, by field:

| `sort.field` | Primary key | Tie-breaker |
| --- | --- | --- |
| `DATE` | `transaction.occurredAtEpochMillis` (already in the rows, no FX) | none — timestamps are unique in practice |
| `AMOUNT` | FX-converted `amountMinor` in `homeCurrency` (fallback to raw `amountMinor` when FX rate is missing — same behavior as the Phase 2.9 amount range filter) | `occurredAtEpochMillis` desc |
| `TITLE` | `transaction.title` lowercase | `occurredAtEpochMillis` desc |
| `CATEGORY` | `categoryName.lowercase()` looked up from `allCategories` by `transaction.categoryId` (empty/null sorts to the end, regardless of asc/desc) | `occurredAtEpochMillis` desc |

Direction applies last — `ASC` is `sortedBy` / `sortedBy { key }`; `DESC` is `sortedByDescending` / `sortedByDescending { key }`. Use Kotlin's stable `sortedBy` so the secondary tie-breaker (`thenByDescending { occurredAtEpochMillis }`) is honored.

**Empty input** returns `emptyList()`. **Single row** returns it unchanged. The function is pure and JVM-testable; production callers pass `settingsRepository.homeCurrency` and `settingsRepository.fxRates` as captured at call time.

### `FiltersRepository` (extended)

The existing `@Singleton` adds:

- `sort: StateFlow<TransactionSort>` — backed by `MutableStateFlow(loadSort())`.
- `setSort(sort: TransactionSort)` — writes both the flow and SharedPreferences; no-op if unchanged.

Two new SharedPreferences keys:

| Key | Type | Default | Encodes |
| --- | --- | --- | --- |
| `sort.field` | `String` | `"DATE"` | `sort.field.name` |
| `sort.direction` | `String` | `"DESC"` | `sort.direction.name` |

Reads use `runCatching { SortField.valueOf(...) }.getOrDefault(SortField.DATE)` (and likewise for `SortDirection`) so a corrupt or unknown stored value falls back to the default rather than throwing.

### `HomeViewModel` (extended)

```kotlin
val sort: StateFlow<TransactionSort> = filtersRepository.sort

fun setSortField(field: SortField) = setSort(sort.value.copy(field = field))
fun setSortDirection(direction: SortDirection) = setSort(sort.value.copy(direction = direction))
fun flipSortDirection() = setSortDirection(
    if (sort.value.direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
)
private fun setSort(s: TransactionSort) = filtersRepository.setSort(s)
```

`filteredTransactions` extends its `combine` from 5 to 6 sources (rows, filters, sort, allCategories, homeCurrency, fxRates) and pipes the filtered list through `sortTransactions`. The `combine` uses the vararg form:

```kotlin
combine(
    repository.observeAll(),
    filters,
    sort,
    allCategories,
    settingsRepository.homeCurrency,
    settingsRepository.fxRates,
) { values ->
    @Suppress("UNCHECKED_CAST")
    val rows = values[0] as List<TransactionWithCategory>
    @Suppress("UNCHECKED_CAST")
    val f = values[1] as TransactionFilters
    val s = values[2] as TransactionSort
    @Suppress("UNCHECKED_CAST")
    val cats = values[3] as List<CategoryEntity>
    val home = values[4] as String
    @Suppress("UNCHECKED_CAST")
    val rates = values[5] as Map<String, Double>
    val filtered = filterTransactions(rows, f, cats, System.currentTimeMillis(), home, rates)
    sortTransactions(filtered, s, cats, home, rates)
}
.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

### `TransactionsScreen` UI

A new compact row inside the existing `FilterControls` Column, placed between the type chips and the amount range. The row layout:

```kotlin
Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        text = stringResource(R.string.filter_sort_label),  // "Sort:"
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SortFieldDropdown(
        selected = sort.field,
        onSelect = onSortFieldChange,
        modifier = Modifier.weight(1f),
    )
    IconButton(onClick = onFlipDirection) {
        Icon(
            imageVector = if (sort.direction == SortDirection.ASC)
                Icons.Filled.ArrowUpward
            else
                Icons.Filled.ArrowDownward,
            contentDescription = stringResource(
                if (sort.direction == SortDirection.ASC)
                    R.string.filter_sort_direction_asc
                else
                    R.string.filter_sort_direction_desc
            ),
        )
    }
}
```

`SortFieldDropdown` uses the same `ExposedDropdownMenuBox` pattern as the existing `CategoryDropdown` / `DateRangeDropdown`. Items: Date, Amount, Title, Category.

**Day-header gate** in the list body — `groupByDay` is called only when `sort.field == DATE`; for other sort fields the rows render flat. The `LazyColumn` body looks roughly like:

```kotlin
val grouped = remember(filteredTransactions, sort.field) {
    if (sort.field == SortField.DATE) groupByDay(filteredTransactions) else null
}
if (grouped != null) {
    grouped.forEach { group ->
        item(key = "day_${group.dayStartMs}") { DayHeader(group.dayStartMs) }
        items(group.items, key = { it.transaction.id }) { row -> SwipeableTransactionRow(...) }
    }
} else {
    items(filteredTransactions, key = { it.transaction.id }) { row -> SwipeableTransactionRow(...) }
}
```

The `remember` key on `sort.field` clears the cached grouping when sort changes. The two `items(...)` branches are independent code paths; the user sees a flat list for non-Date sorts and a day-grouped list for Date.

## Strings to add

```
filter_sort_label              "Sort"
filter_sort_field_date         "Date"
filter_sort_field_amount       "Amount"
filter_sort_field_title        "Title"
filter_sort_field_category     "Category"
filter_sort_direction_asc      "Sort ascending"   (a11y contentDescription)
filter_sort_direction_desc     "Sort descending"  (a11y contentDescription)
```

(7 new strings.)

## Tests

In `app/src/test/.../ui/transactions/FiltersTest.kt`, add 14 tests:

1. `sortTransactions_dateDesc_returnsNewestFirst` — DATE/DESC on 3 rows returns newest→oldest.
2. `sortTransactions_dateAsc_returnsOldestFirst` — DATE/ASC returns oldest→newest.
3. `sortTransactions_amountDesc_usesHomeCurrency` — two rows in different currencies, the row with the larger home-currency value is first.
4. `sortTransactions_amountAsc_smallestFirst` — smallest home-currency amount first.
5. `sortTransactions_amount_usesFxWhenAvailable` — JPY row with FX rate to USD sorts by its USD equivalent.
6. `sortTransactions_amount_fallsBackToRawWhenFxMissing` — no FX rate for the row's currency, raw `amountMinor` is used (matches Phase 2.9).
7. `sortTransactions_titleAsc_caseInsensitive` — input "banana", "Apple", "cherry" → Apple, banana, cherry.
8. `sortTransactions_titleDesc_reverseOrder` — same input DESC → cherry, banana, Apple.
9. `sortTransactions_categoryAsc_alphabeticalByName` — looked up via `allCategories`.
10. `sortTransactions_categoryDesc_reverseOrder`.
11. `sortTransactions_categoryEmptyName_sortsToEnd_asc` — a row whose categoryId no longer exists sorts last in ASC.
12. `sortTransactions_categoryEmptyName_sortsToEnd_desc` — same row sorts last in DESC.
13. `sortTransactions_tieBreakerByDateDesc` — two rows with the same sort key, the newer one is first.
14. `sortTransactions_emptyInput_returnsEmpty` — `sortTransactions(emptyList(), sort, cats, "USD", emptyMap())` returns `emptyList()`.
15. `sortTransactions_singleRow_returnsAsIs` — a single-row input returns the same row regardless of sort.

(Split into two tests for granular failure messages, matching the existing per-case test naming pattern.)

(15 new tests. 47 prior in `FiltersTest.kt` → 62 total.)

No VM tests — the VM is a thin composition (`combine` + two pure functions). Pure-function tests cover the behavior. No Compose UI test — the sort row is a thin wrapper around `ExposedDropdownMenuBox` and an `IconButton`; the existing manual smoke-test protocol covers the visual.

## Edge cases

| Case | Behavior |
| --- | --- |
| Empty filter result | `sortTransactions` is a no-op (returns empty). |
| Single row | No-op. |
| All rows in same currency (common) | FX rate map is empty; sort uses raw `amountMinor` consistently. |
| Mixed currencies, some FX rates missing | Sort falls back to raw `amountMinor` for the missing-rate row (matches Phase 2.9). |
| Home currency changed mid-session | The `combine` re-runs the sort with the new `homeCurrency` and `fxRates`; list reorders on the next emission. |
| User flips direction on default state (Date/Desc → Date/Asc) | Instant reorder, no flicker. |
| App upgraded, prefs cleared | Defaults (Date/Desc) — same as first install. |
| Existing user with filter prefs but no sort keys | Sort reads null/missing keys, falls back to Date/Desc. No migration needed. |
| `sort.field == DATE` | Day headers render (current behavior). |
| `sort.field ∈ {AMOUNT, TITLE, CATEGORY}` | Day headers suppressed (would be arbitrary). |
| "Clear filters" button | Only clears filter state. Sort is unchanged. |
| Filter debounce (search 300ms) vs sort (immediate) | Sort change commits immediately, no debounce. |
| Category filter set to a category that was deleted | Sort's category lookup falls back to empty string; that row sorts last regardless of direction. |
| Filter persistence across process death | SharedPreferences round-trip is synchronous on next start; sort and filters are restored before the first frame. |

## Error handling

| Failure | Surfaced as |
| --- | --- |
| SharedPreferences read fails (corrupt file) | `runCatching { ... }.getOrDefault(SortField.DATE / SortDirection.DESC)` — defensive. |
| SharedPreferences write fails (disk full) | `Log.w` + state stays in memory; next `setSort` retries. No crash. |
| Unknown enum name in stored prefs | `runCatching { SortField.valueOf(...) }.getOrDefault(SortField.DATE)` — never throws. |
| Sort called with a row whose `categoryId` references a missing category | Category lookup returns `""`; the row sorts to the end in both directions. No crash. |

## Out of scope (intentional, deferred)

- **Multi-criteria sort** (primary + secondary user-defined). Single sort field is enough for v1; the date-desc tie-breaker handles the common case.
- **Grouped-by-currency display** when sorting by amount (the third option I offered; user picked the home-currency path).
- **Custom sort orders / saved sort presets** ("sort by amount, then category, asc, always").
- **Sort by receipt presence, recurrence, note content**, or any field other than date/amount/title/category. YAGNI.
- **Sort from the Home dashboard** — only the Transactions tab.
- **Animated reorder** on sort change — instant re-sort, consistent with the rest of the app.
- **Sort indicator** (chevron) on a column header — the list is flat, not tabular.
- **Per-account sort persistence** — no auth in v1 (per CLAUDE.md).
- **Sort applied to the export / share-CSV flow** — the export uses its own SQL ordering; sort preferences don't reach it. (Phase 2.7 deferred multi-select categories here too; consistent scope.)
- **Sort applied to the budget-alert "top expenses" list** — that flow is aggregated, not row-level.

## Open questions

None. Decisions taken one at a time through the AskUserQuestion flow and recorded in the User-visible behavior, Architecture, UI, and Edge cases sections above.
