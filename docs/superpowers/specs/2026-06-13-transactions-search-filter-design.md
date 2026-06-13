# Phase 2.7 — Transactions Search & Filter — Design

**Status:** Approved 2026-06-13
**Phase:** 2.7
**Predecessors:** Phases 1.x–2.6 ship the transactions data layer, the Home/Transactions screens, the `HomeViewModel` (which `TransactionsScreen` already uses), and the `SettingsRepository` SharedPreferences pattern that the new `FiltersRepository` mirrors.

## Goal

Make the Transactions list findable and narrowable. Currently the screen is a flat chronological list with no controls — once a user has more than a few months of data, finding a specific transaction is painful. Phase 2.7 adds four filter controls (search box, type chips, category dropdown, date range dropdown) that compose as AND-filters and persist across app restarts.

Out of scope (deferred): amount range filter, multi-select categories, saved filters, filter from Home dashboard, recurring-only filter, search highlighting, sort options.

## User-visible behavior

When the user opens the Transactions tab:

- A search field is at the top of the screen with the hint "Search title, note, category, or amount". The user types a query; matching transactions are filtered live (debounced ~300ms).
- Below the search field is a row of filter controls:
  - **Type chips** (`All Types` / `Income` / `Expense`) — single-select.
  - **Category dropdown** (`All categories` + each category, alphabetical) — single-select.
  - **Date range dropdown** with 5 presets (`Any`, `Last 7 days`, `Last 30 days`, `This month`, `This year`) plus `Custom…`. `Custom…` opens a `DateRangePicker` dialog.
- A `Clear filters` text button appears below the controls whenever any filter is active. Tap to reset all four to their defaults.
- Below the controls, the existing day-grouped list renders the filtered transactions. The same swipe-to-delete and tap-to-edit behaviors apply (Phase 1.x + Phase 2.5).
- When filters are active and yield zero results, the list area shows a "No transactions match your current filters" message with a `Clear filters` button.
- When no filters are active and the list is empty (no transactions in the DB), the existing "No transactions yet" message shows.
- Filters persist across app restarts via SharedPreferences. The user comes back to the same filtered view they left.

## Data model

**No schema changes.** No new tables or columns. New types and a pure helper are added to the transactions feature module.

**New types** in `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt`:

```kotlin
sealed interface DateRangePreset {
    data object Any : DateRangePreset
    data object Last7Days : DateRangePreset
    data object Last30Days : DateRangePreset
    data object ThisMonth : DateRangePreset
    data object ThisYear : DateRangePreset
    /** User picked explicit from/to via the DateRangePicker. */
    data class Custom(val fromMs: Long, val toMsExclusive: Long) : DateRangePreset
}

enum class TypeFilter { ALL, INCOME, EXPENSE }

data class TransactionFilters(
    val searchQuery: String = "",       // "" = no text filter
    val categoryId: Long? = null,       // null = all categories
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val dateRange: DateRangePreset = DateRangePreset.Any,
) {
    /** True when no filter is active — list shows everything. */
    val isEmpty: Boolean
        get() = searchQuery.isEmpty() && categoryId == null
            && typeFilter == TypeFilter.ALL && dateRange is DateRangePreset.Any
}
```

**New pure function** (in the same file):

```kotlin
fun filterTransactions(
    rows: List<TransactionWithCategory>,
    filters: TransactionFilters,
    allCategories: List<CategoryEntity>,   // needed to match category name in search
    nowMs: Long,
): List<TransactionWithCategory>
```

A pure, JVM-testable function that applies the four filters as a chain (each row passes only if it passes every filter). Returns the filtered list in the **same order as input** so the day-grouping the screen does later still works.

**Filter semantics:**

1. **Search query** (case-insensitive substring match). Empty/blank query = no filter. The query is trimmed. It matches if **any** of these contains the trimmed query as a substring:
   - `transaction.title` (always non-null)
   - `transaction.note` (only if non-null)
   - The category's `name`, looked up from `allCategories` by `transaction.categoryId`
   - The formatted amount (e.g. `MoneyFormat.formatAmountForEdit(transaction.amountMinor)` for `$12.00`, `$120.00`, `$1,200.00`)
2. **Category** (equality match). `null` = no filter. Matches if `transaction.categoryId == filters.categoryId`.
3. **Type** (equality match). `ALL` = no filter. Matches if `transaction.type` matches the enum's value (`"INCOME"` or `"EXPENSE"`).
4. **Date range**. Resolves the preset to a `[fromMs, toMsExclusive)` range against `nowMs`:
   - `Any` → `[Long.MIN_VALUE, Long.MAX_VALUE)` (no filter)
   - `Last7Days` → `[nowMs - 7×86_400_000L, Long.MAX_VALUE)`
   - `Last30Days` → `[nowMs - 30×86_400_000L, Long.MAX_VALUE)`
   - `ThisMonth` → `[startOfMonth(nowMs), Long.MAX_VALUE)`
   - `ThisYear` → `[startOfYear(nowMs), Long.MAX_VALUE)`
   - `Custom(from, to)` → `[from, to)` with **automatic swap** if `from > to` (so the range is always non-empty)
   - Matches if `transaction.occurredAtEpochMillis` is in the range.

The function uses `LocalDate.now()` semantics through `nowMs` for the date presets. The production caller passes `System.currentTimeMillis()` so the time anchor is "now at the moment of emission"; the test caller passes a fixed instant for determinism.

**Why a sealed interface for the date range:** the 5 presets are mutually exclusive, and `Custom` carries its own data. A sealed interface gives us exhaustive `when` checks and avoids a `null`-sentinel for "custom".

**Why pass `allCategories` into the filter function:** the search-query match against the category name needs a `CategoryEntity` lookup by `categoryId`. Passing the full list is simpler than building a map and is fine for the typical <50 categories the user has. The function is pure so the list is captured at call time.

## Components

| File | Purpose |
| --- | --- |
| `ui/transactions/Filters.kt` (new) | `DateRangePreset`, `TypeFilter`, `TransactionFilters`, `filterTransactions` pure helper. |
| `ui/transactions/FiltersRepository.kt` (new) | SharedPreferences-backed `@Singleton`. Exposes `filters: StateFlow<TransactionFilters>` and `setFilters(filters)`. Mirrors `SettingsRepository`. |
| `ui/home/HomeViewModel.kt` (modified) | Add `filters: StateFlow<TransactionFilters>`, `allCategories: StateFlow<List<CategoryEntity>>` (from existing `CategoryRepository`), `filteredTransactions: StateFlow<List<TransactionWithCategory>>` (combines observe + filters + categories + nowMs). Add `setFilters(filters)` and convenience setters. |
| `ui/transactions/TransactionsScreen.kt` (modified) | Renders the new filter controls above the existing list. Reads/writes `viewModel.filters` and `viewModel.setFilters`. Two empty-states: "no transactions yet" (existing) and "no transactions match your filters" (new). |
| `di/.../someModule.kt` (modified) | Provide `FiltersRepository` via Hilt (or just rely on the `@Inject` constructor since it's `@Singleton`). |
| `res/values/strings.xml` (modified) | 15 new strings (see below). |
| `app/src/test/.../ui/transactions/FiltersTest.kt` (new) | JUnit tests for `filterTransactions`. |

### `HomeViewModel` (extended)

```kotlin
val filters: StateFlow<TransactionFilters> = filtersRepo.filters

val allCategories: StateFlow<List<CategoryEntity>> = categoryRepository
    .observeAll()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

val filteredTransactions: StateFlow<List<TransactionWithCategory>> =
    combine(
        repository.observeAll(),
        filters,
        allCategories,
    ) { rows, f, cats -> Triple(rows, f, cats) }
        .map { (rows, f, cats) ->
            filterTransactions(rows, f, cats, nowMs = System.currentTimeMillis())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

fun setFilters(filters: TransactionFilters) { filtersRepo.setFilters(filters) }
fun setSearchQuery(q: String) = setFilters(filters.value.copy(searchQuery = q))
fun setCategoryFilter(id: Long?) = setFilters(filters.value.copy(categoryId = id))
fun setTypeFilter(t: TypeFilter) = setFilters(filters.value.copy(typeFilter = t))
fun setDateRange(d: DateRangePreset) = setFilters(filters.value.copy(dateRange = d))
fun clearFilters() = filtersRepo.setFilters(TransactionFilters())
```

**Why `nowMs` is captured inside the `.map`:** the calculation is anchored to the moment the data arrives, matching the Phase 2.6 `TrendsViewModel` pattern. The user can re-tap a filter to refresh if the date boundary is important.

**Why `setFilters` is the only mutation point and the setters are thin wrappers:** keeps the surface small and the `FiltersRepository` as the single source of truth. Setters are no-ops when the value is unchanged (avoids unnecessary StateFlow emissions, which would otherwise trigger filter recomputation).

**The Home dashboard continues to observe the unfiltered list** (we don't filter the Home tab — only the Transactions tab). The existing `monthlyTotals` / `summary` flows stay as-is. Only `TransactionsScreen` switches to `viewModel.filteredTransactions`.

### `FiltersRepository`

A `@Singleton`, injected with `@ApplicationContext`. Uses `SharedPreferences` directly (no DataStore dep) — consistent with `SettingsRepository`. Four keys:

| Key | Type | Default | Encodes |
| --- | --- | --- | --- |
| `filters.searchQuery` | `String` | `""` | `filters.searchQuery` |
| `filters.categoryId` | `Long` | `-1L` (means "all", mapped to `null`) | `filters.categoryId ?: -1L` |
| `filters.typeFilter` | `String` | `"ALL"` (enum name) | `filters.typeFilter.name` |
| `filters.dateRange` | `String` | `"Any"` (preset name) | `"Any"`, `"Last7Days"`, etc., or `"Custom\|<fromMs>\|<toMsExclusive>"` for `Custom` |

Reads synchronously in the constructor (matching `SettingsRepository.loadTheme()`). Exposes `StateFlow<TransactionFilters>` backed by a `MutableStateFlow`. The `setFilters(...)` method writes the new state to both the flow and SharedPreferences. Re-emits only on actual change (compared to current value).

### `TransactionsScreen` UI

```
┌─────────────────────────────────────────┐
│  TopAppBar: "Transactions"               │
├─────────────────────────────────────────┤
│  ┌─────────────────────────────────┐    │  ← Search field (OutlinedTextField)
│  │ 🔍 Search title, note, category│    │
│  └─────────────────────────────────┘    │
│  [All Types] [Income] [Expense]         │  ← Type chips (FilterChip row)
│  [All categories ▾]   [Date: Any ▾]     │  ← Two ExposedDropdownMenuBox
│  [Clear filters]                        │  ← TextButton, only when !isEmpty
│                                         │
│  ┌─────────────────────────────────┐    │
│  │ Today                           │    │  ← Existing day-grouped list
│  │ Lunch         -$12.50  Food     │    │
│  │ Salary       +$2,000  Income    │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

**Search field:**
- Material 3 `OutlinedTextField` with a `Search` leading icon, `Clear` trailing icon when non-empty.
- Local `mutableStateOf<String>` holds the displayed text (immediate UI update on each keystroke).
- A `LaunchedEffect(text)` with `delay(300)` debounces the value before calling `viewModel.setSearchQuery(...)`. If the user types fast, only the final value is committed.
- The VM's `filters.searchQuery` is the source of truth for the actual filter — the local state is just for input ergonomics.

**Type chips:**
- A `Row` of three `FilterChip`s: "All Types", "Income", "Expense". Single-select. `selected = filters.typeFilter == TypeFilter.X`.

**Category dropdown:**
- `ExposedDropdownMenuBox` with a `TextField` styled as a read-only display ("All categories" or the selected category's name).
- `DropdownMenu` lists "All" + each `CategoryEntity.name` (alphabetical). `onItemClick` calls `viewModel.setCategoryFilter(id)`.

**Date range dropdown:**
- Same `ExposedDropdownMenuBox` pattern as the category dropdown.
- "Any", "Last 7 days", "Last 30 days", "This month", "This year", "Custom…".
- "Custom…" closes the menu and opens a `DateRangePicker` dialog (Material 3's `DateRangePickerDialog`). On confirm, calls `viewModel.setDateRange(Custom(from, to))`.
- The selected preset (or "Custom: Mar 1 – Mar 31") is shown in the read-only display.

**Clear filters button:**
- `TextButton` with `Icons.Filled.Close` and label "Clear filters". Visible only when `!filters.isEmpty`. Calls `viewModel.clearFilters()`.

**Empty states:**
- If `filteredTransactions.isEmpty() && filters.isEmpty` → existing "No transactions yet" message (unchanged from Phase 1.x).
- If `filteredTransactions.isEmpty() && !filters.isEmpty` → new "No transactions match your current filters" message + a `Clear filters` button.

### `DateRangePicker` integration

Material 3's `DateRangePicker` lives in `androidx.compose.material3` and renders a full calendar UI. The dialog uses `DateRangePickerDialog(state)` (added in Material 3 1.2+). The project is on Material 3 1.3+ (per `gradle/libs.versions.toml`), so this is available.

State held locally:
```kotlin
val dateRangePickerState = rememberDateRangePickerState()
val showDateRangeDialog = remember { mutableStateOf(false) }
```

When the user picks a range and confirms, the dialog is dismissed and `viewModel.setDateRange(Custom(state.selectedStartDateMillis!!, state.selectedEndDateMillis!! + 86_400_000L))` is called. The `+1` day on the end date converts the inclusive Material 3 picker output to the exclusive `[from, to)` form the filter expects.

If the user cancels (no range selected), the dialog is dismissed without state change.

## Edge cases

| Case | Behavior |
| --- | --- |
| No transactions at all | Existing "No transactions yet" message. |
| Filters active, no matches | New "No transactions match your current filters" + Clear button. |
| Search "12" matches $12.00, $120.00, $1,200.00 | All match (substring on `formatAmountForEdit`). |
| Search "12.50" matches $12.50 only | Exact decimal substring match. |
| Search with leading/trailing whitespace | Trimmed before matching. |
| Search matches note that is null | Note match is skipped silently; title/category/amount still match. |
| Date range "This Month" on Jan 1 | Window is just Jan 1. May match 0 or 1 transaction. |
| Date range "Custom" with from > to | Auto-swapped so the range is non-empty. |
| Category filter set to a category that was deleted | `filters.categoryId` is non-null but no rows match. Empty result, not an error. |
| Filter persistence: app upgraded, prefs cleared | Default filters (all empty) — clean state. |
| 4 filter changes in rapid succession | StateFlow coalesces; final state wins. No debounce on the filter itself (only on the search input). |
| 1000+ transactions | Filter is `O(n)` per filter, ~4n comparisons. Negligible. No pagination. |
| User clears the search field | Local state goes to `""`; debounce fires; VM state goes to `""`; filter shows everything (subject to other filters). |
| `Material 3 DateRangePicker` requires material3 >= 1.2 | Project is on a newer version. Verified in `libs.versions.toml`. |
| User picks a Custom range, then picks a preset | The Custom range is replaced by the preset. (State replaces, not appends.) |
| Filter state during process death | SharedPreferences round-trip is synchronous on the next start, so the filters are restored before the first frame. No flicker. |

## Error handling

| Failure | Surfaced as |
| --- | --- |
| SharedPreferences read fails (corrupt prefs file) | Default filters (the `?: ""` / `?: -1L` / `?: TypeFilter.ALL` defaults). Defensive. |
| SharedPreferences write fails (disk full, etc.) | `Log.w` + state stays in memory but doesn't persist. The next `setFilters` will retry. No crash. |
| `DateRangePicker` selected dates are null (user dismissed without picking) | Dialog closes, no state change. No crash. |
| `filterTransactions` called with a row whose `categoryId` references a missing category | The category-name match falls back to no match. The category filter (`transaction.categoryId == filters.categoryId`) still works as a pure equality match. |

## Tests

| Test | File | What it asserts |
| --- | --- | --- |
| `filterTransactions_emptyFilters_returnsAll` | `FiltersTest.kt` | Default filters + non-empty rows → all rows in input order. |
| `filterTransactions_searchTitle_matchesCaseInsensitive` | same | Query "COF" matches "Coffee Shop" (uppercase query, mixed-case title). |
| `filterTransactions_searchTitle_doesNotMatchPartialWord` | same | Query "cof" matches "Coffee Shop" (substring match, not word-boundary). |
| `filterTransactions_searchNote_matches` | same | Query matches a transaction's note. |
| `filterTransactions_searchNote_skipsNull` | same | A transaction with `note = null` doesn't crash; it's filtered out only if other fields don't match. |
| `filterTransactions_searchCategoryName_matches` | same | Query "food" matches transactions in the "Food" category. |
| `filterTransactions_searchAmount_matchesSubstring` | same | Query "12" matches $12.00, $120.00, $1,200.00, $12.50. |
| `filterTransactions_searchAmount_doesNotMatchUnrelated` | same | Query "99" doesn't match $12.00. |
| `filterTransactions_searchWhitespaceTrimmed` | same | Leading/trailing whitespace stripped. |
| `filterTransactions_searchEmptyString_isNoOp` | same | Empty query = all rows pass. |
| `filterTransactions_searchBlank_isNoOp` | same | Whitespace-only query = all rows pass. |
| `filterTransactions_categoryFilter_onlyMatchingRows` | same | Category = X → only rows with `categoryId == X`. |
| `filterTransactions_categoryNull_isNoOp` | same | `null` category = all rows. |
| `filterTransactions_typeIncome_onlyIncomeRows` | same | Type = INCOME → only INCOME rows. |
| `filterTransactions_typeExpense_onlyExpenseRows` | same | Type = EXPENSE → only EXPENSE rows. |
| `filterTransactions_typeAll_isNoOp` | same | Type = ALL → all rows. |
| `filterTransactions_dateRangeAny_isNoOp` | same | Any → all rows. |
| `filterTransactions_dateRangeLast7Days_onlyRecent` | same | nowMs = 2026-06-15 12:00 UTC → only rows with `occurredAtEpochMillis >= 2026-06-08 12:00 UTC`. |
| `filterTransactions_dateRangeLast30Days_onlyRecent` | same | Similar to Last7Days but 30 days. |
| `filterTransactions_dateRangeThisMonth` | same | nowMs = 2026-06-15 → only rows in June 2026. |
| `filterTransactions_dateRangeThisMonth_januaryEdge` | same | nowMs = 2026-01-15 → only rows in January 2026. |
| `filterTransactions_dateRangeThisYear` | same | nowMs = 2026-06-15 → only rows in 2026. |
| `filterTransactions_dateRangeCustom` | same | Custom(2026-01-01, 2026-03-31) → only rows in Q1. |
| `filterTransactions_dateRangeCustomInverted_swapped` | same | Custom(2026-12-01, 2026-01-01) → treated as Custom(2026-01-01, 2026-12-01). |
| `filterTransactions_dateRangeCustomInverted_equalStaysEqual` | same | Custom(t, t) → just `t` (single-instant window). |
| `filterTransactions_combinedFilters_intersected` | same | Search "food" + Category=Restaurants + Type=EXPENSE + Date=Last30Days → all four must match. |
| `filterTransactions_combinedFilters_emptyResult` | same | Contradictory filters → empty list (no crash). |
| `filterTransactions_preservesInputOrder` | same | Input `[A, B, C, D]`, filter excludes B → output `[A, C, D]`. |
| `filterTransactions_isEmpty_defaultTrue` | same | `TransactionFilters()` is "empty". |
| `filterTransactions_isEmpty_searchQuerySet_false` | same | Setting `searchQuery = "x"` → `isEmpty == false`. |
| `filterTransactions_isEmpty_categorySet_false` | same | Setting `categoryId = 1L` → `isEmpty == false`. |
| `filterTransactions_isEmpty_typeSet_false` | same | Setting `typeFilter = INCOME` → `isEmpty == false`. |
| `filterTransactions_isEmpty_dateRangeSet_false` | same | Setting `dateRange = Last7Days` → `isEmpty == false`. |
| `filterTransactions_isEmpty_customDateSet_false` | same | Setting `dateRange = Custom(...)` → `isEmpty == false`. |

(~33 tests, all JUnit 4, no Compose.)

No Compose UI test for the filter UI. The search/filter behavior is exercised on device in the manual smoke test (Phase 2.7 Task 6).

## Strings to add

```
filter_search_hint              "Search title, note, category, or amount"
filter_type_all                 "All Types"
filter_type_income              "Income"
filter_type_expense             "Expense"
filter_category_all             "All categories"
filter_date_any                 "Any"
filter_date_last_7_days         "Last 7 days"
filter_date_last_30_days        "Last 30 days"
filter_date_this_month          "This month"
filter_date_this_year           "This year"
filter_date_custom              "Custom…"
filter_clear                    "Clear filters"
filter_no_matches_title         "No matches"
filter_no_matches_body          "No transactions match your current filters."
```

(15 new strings.)

## Files touched (summary)

**New:** `Filters.kt`, `FiltersRepository.kt`, `FiltersTest.kt`.
**Modified:** `HomeViewModel.kt`, `TransactionsScreen.kt`, `strings.xml`, and possibly a Hilt module (one `@Provides` line — or just `@Inject` on the constructor since `FiltersRepository` is `@Singleton`).

## Out of scope (intentional)

- **Amount range filter** (min/max). The text search matches amount as a substring, which is good enough for "find the $47 charge".
- **Multi-select categories.** Single-select covers the common case.
- **Saved filters** ("show this every time").
- **Filter from Home dashboard.** Only the Transactions tab.
- **Recurring transactions filter** ("show only recurring").
- **Search highlighting** (bold the matching substring).
- **Sort options** (by amount, by category). Currently sorted by date desc (existing behavior).
- **Material 3 `FilterChip` for the category and date range controls.** ExposedDropdownMenuBox is more appropriate for >3 options.
- **"Recent" filter memory** (e.g. "the 3 most recent transactions"). Out of scope.
- **Filter pill on the Home tab badge** (e.g. "12 transactions match filter"). Defer.

## Open questions

None. Decisions were taken one at a time during brainstorming and recorded in the User-visible behavior, Data model, Components, and Edge cases sections above.
