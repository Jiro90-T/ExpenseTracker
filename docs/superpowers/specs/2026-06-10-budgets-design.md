# Phase 2.3 — Per-Category Monthly Budgets — Design

**Status:** Approved 2026-06-10
**Phase:** 2.3
**Predecessor:** Phase 2.2 (multi-currency, FX to home currency) is required — budgets and the dashboard share the same conversion helper.

## Goal

Let the user set a monthly spending limit per expense category and see, in the Budget tab, how much they've spent against that limit and whether they've gone over.

Out of scope (later phases): rollover, notifications, global cap, "set for all months" helper, dashboard surfacing, multi-currency budgets, income-category budgets.

## User-visible behavior

The **Budget** bottom-nav tab (currently a "Coming soon" stub) becomes a real screen. The user can:

1. See one row per expense category, for the current calendar month.
2. Each row shows the category name, a progress bar of `spent / limit`, the absolute amounts ("$45.20 / $200.00"), the percent used, and an "Over by $X" badge when applicable.
3. Tap a row to open an edit dialog. The dialog has a number field for the limit (in the home currency), plus **Save**, **Clear**, and **Cancel**.
4. "Clear" deletes the budget row; after clearing, the row reappears in the screen as "No budget set" with no progress bar.
5. The screen header shows the current month and year (e.g. "June 2026") and a subtle warning row if any spent total had to fall back to a 1:1 conversion because the FX rate was missing.

If the user tries to delete a category that has any budget rows (current or historical), the deletion fails with the existing FK error; the error is surfaced as a snackbar with the message "Delete the budgets for this category first." (We don't need a "delete all budgets" affordance — the user can go to the Budget tab and clear them there.)

## Data model

Schema migration **v3 → v4** adds one new table:

```sql
CREATE TABLE budgets (
    categoryId           INTEGER NOT NULL,
    monthStartEpochMs    INTEGER NOT NULL,
    amountMinor          INTEGER NOT NULL,
    PRIMARY KEY (categoryId, monthStartEpochMs),
    FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS index_budgets_monthStartEpochMs ON budgets (monthStartEpochMs);
```

Conventions:
- `monthStartEpochMs` is the epoch millisecond at midnight (00:00:00.000) on the 1st of the month, in the **device's local timezone**. `BudgetRepository.currentMonthStart()` produces this; it is the canonical way the rest of the app gets the bucket key.
- `amountMinor` is in the **home currency** (denominated in minor units, e.g. cents). Single currency per budget for MVP.
- Missing row = no budget set for that (category, month). The Budget screen renders "No budget set" for these.

The migration is a single `CREATE TABLE` plus the index; no data is touched. Bump `AppDatabase.version` from 3 to 4 and add a `MIGRATION_3_4` to the companion list.

## Components

| File | Purpose |
| --- | --- |
| `data/local/BudgetEntity.kt` | The row. `@Entity(tableName = "budgets")`, composite PK via `@Entity(primaryKeys = ["categoryId", "monthStartEpochMs"])`. |
| `data/local/BudgetDao.kt` | `observeByMonth(monthStart: Long): Flow<List<BudgetEntity>>`, `upsert(budget: BudgetEntity)`, `deleteByKey(categoryId, monthStart)`. |
| `data/local/AppDatabase.kt` | Add entity, bump version, add `MIGRATION_3_4`. |
| `data/repository/BudgetRepository.kt` | Thin wrapper over the DAO. Owns `currentMonthStart()` (Local-time midnight on the 1st) and `nextMonthStart()` helpers so call sites don't all reinvent that. |
| `domain/budget/ComputeSpent.kt` | Pure function `computeSpentByCategory(rows: List<TransactionWithCategory>, bounds: LongRange, homeCurrency: String, fxRates: Map<String, Double>): SpentSummary`. `SpentSummary` is `(byCategoryMinor: Map<Long, Long>, missingRateCount: Int)`. Mirrors the `computeDashboardSummary` FX-fallback pattern (1:1 fallback, count of conversions that fell back). |
| `ui/budget/BudgetViewModel.kt` | Exposes `StateFlow<BudgetScreenUiState>` for the current month. Combines `categoryRepository.observeByType(EXPENSE)`, `budgetRepository.observeByMonth(...)`, `repository.observeAll()` (for the spent computation), `settingsRepository.homeCurrency`, and `settingsRepository.fxRates`. |
| `ui/budget/BudgetScreen.kt` | Replaces the stub. Hosts the list + the edit dialog. |
| `ui/budget/BudgetRow.kt` (private in same file) | Progress bar + amounts + overspend badge. |
| `ui/budget/BudgetEditDialog.kt` (private in same file) | AlertDialog with number field. |
| `app/src/main/res/values/strings.xml` | New strings. |

### `BudgetScreenUiState`

```kotlin
data class BudgetScreenUiState(
    val monthLabel: String,           // "June 2026" — for the header
    val homeCurrency: String,
    val rows: List<BudgetRowUiState>,  // one per expense category, sorted by name
    val missingRateCount: Int,         // total across all rows
    val isLoaded: Boolean = false,
)

data class BudgetRowUiState(
    val categoryId: Long,
    val categoryName: String,
    val limitMinor: Long?,            // null = no budget set
    val spentMinor: Long,              // always present; 0 if no transactions
    val isOverspent: Boolean,         // spent > limit (only meaningful when limit is set)
)
```

### `BudgetViewModel` behavior

- On `init`, compute `monthStart = currentMonthStart()` and start collecting the five upstream flows.
- `setLimit(categoryId: Long, amountMinor: Long?)`:
  - Validates `amountMinor != null && amountMinor > 0`; if null/0, calls `clearLimit(categoryId)`.
  - Calls `budgetRepository.upsert(BudgetEntity(categoryId, monthStart, amountMinor))`.
- `clearLimit(categoryId: Long)`: deletes the row for the current month, no-op if not present.
- `editDialog` (transient `MutableStateFlow<BudgetEditDialogState?>`): when non-null, the screen shows the dialog. `openEdit(categoryId)`, `closeEdit()`, `submitEdit(amountText)`.

## Edit dialog

- Title: "Set budget for [Category Name]" (or "Edit budget" if a limit already exists).
- Body: a single `OutlinedTextField` with `KeyboardType.Decimal`. The label is the home currency code (e.g. "Amount (USD)"). Helper text shows what the value will be in major units as the user types.
- Buttons: **Cancel** (dismisses), **Save** (parses, validates > 0, calls `viewModel.setLimit`, dismisses), **Clear** (only shown if a limit already exists; calls `viewModel.clearLimit`, dismisses).
- The parser reuses `AddEditTransactionViewModel.parseAmountToMinor`-style logic. To avoid making it public, factor it out into a small `data/local/MoneyFormat.kt` util: `fun parseAmountToMinor(input: String): Long?`. The AddEdit viewmodel starts using it too (no behavior change).

## Overspend indicator

- `spentMinor > limitMinor` → `isOverspent = true`. Use a single error color token from the theme (e.g. `MaterialTheme.colorScheme.error` for the badge and a tinted track for the progress bar).
- The progress bar is clamped to a max of 100% for the visual, but the text below the bar shows the real value ("$245 / $200 — Over by $45"). The bar fills to 100% and then a small overflow indicator is appended.
- No "warning at 80%" heuristic in MVP; the user can see the percentage and decide. Easy to add later as a setting.

## Error handling

| Failure | Surfaced as |
| --- | --- |
| User tries to delete a category that has any budget rows | `CategoryManagementViewModel.delete` already catches `SQLiteConstraintException` from the `transactions.categoryId` FK. The new `budgets.categoryId` FK with the same `RESTRICT` action will throw the same exception, caught by the same block. **Change the message** in that catch from "Cannot delete: category is in use by transactions" to "Cannot delete: category is in use by transactions or budgets" — one-line edit, no other change to the delete path. |
| Save with amount ≤ 0 or unparseable | Dialog stays open, field shows inline error "Enter an amount greater than 0". Save button disabled while invalid. |
| A transaction's currency has no FX rate to home | `missingRateCount` increments in the spent row. A single warning row at the top of the screen reads "Some transactions are missing FX rates — totals may be inaccurate." Only shown when `missingRateCount > 0`. |

## Tests

| Test | File | What it asserts |
| --- | --- | --- |
| `computeSpentByCategory_emptyList` | `ComputeSpentTest.kt` | Returns `(emptyMap, 0)`. |
| `computeSpentByCategory_onlyIncome_ignored` | `ComputeSpentTest.kt` | Income transactions don't contribute and `missingRateCount = 0`. |
| `computeSpentByCategory_groupsByCategory` | `ComputeSpentTest.kt` | Multiple expenses for the same category sum into one map entry. |
| `computeSpentByCategory_excludesOutsideRange` | `ComputeSpentTest.kt` | Transactions outside the month range are ignored. |
| `computeSpentByCategory_fxConversion` | `ComputeSpentTest.kt` | EUR transaction in USD-home with `EUR_to_USD=1.1` is converted. |
| `computeSpentByCategory_missingRate` | `ComputeSpentTest.kt` | Unknown currency falls back to 1:1 and `missingRateCount` increments. |
| `parseAmountToMinor_valid` | `MoneyFormatTest.kt` | Whole, fractional, padded cases. |
| `parseAmountToMinor_invalid` | `MoneyFormatTest.kt` | Empty, two dots, negative, too-large. |

No Room/DAO test in MVP (the DAO is a thin wrapper; smoke-tested via instrumented build only).

## Strings to add

```
budgets_title                "Budgets"           (replaces budget_title in the tab)
budgets_month_format         "MMMM yyyy"         (date pattern, formatted via SimpleDateFormat at runtime — used as a format string, not a UI string)
budgets_no_budget            "No budget set"
budgets_progress_format      "%1$s / %2$s"       (spent / limit)
budgets_percent_format       "%1$d%% used"
budgets_overspent_format     "Over by %1$s"
budgets_set                  "Set budget"
budgets_edit                 "Edit budget"
budgets_clear                "Clear"
budgets_amount_label         "Amount (%1$s)"     (currency code)
budgets_amount_helper        "e.g. 200 or 200.50"
budgets_amount_invalid       "Enter an amount greater than 0"
budgets_fx_missing_warning   "Some transactions are missing FX rates — totals may be inaccurate."
budgets_category_delete_blocked "Delete the budgets for this category first."
```

## Files touched (summary)

**New:** `BudgetEntity.kt`, `BudgetDao.kt`, `BudgetRepository.kt`, `ComputeSpent.kt`, `BudgetViewModel.kt`, `ComputeSpentTest.kt`, `MoneyFormat.kt`, `MoneyFormatTest.kt`.

**Modified:** `AppDatabase.kt` (entity, version, migration), `strings.xml`, `AddEditTransactionViewModel.kt` (use the shared parser), `CategoryManagementViewModel.kt` (broaden the catch's error message), `BudgetScreen.kt` (replace stub).

## Out of scope (intentional)

- Rollover of unused budget to next month.
- Push/notification alerts on overspend.
- Per-budget currency (always home currency for MVP).
- Income-category budgets.
- Global monthly cap.
- "Set the same limit for every month" helper.
- Surfacing overspend on the Home dashboard (deferred to a later phase).
- Editing past months from this screen (the rows are visible in the DB but not surfaced in the UI for MVP; can be added later).

## Open questions

None. Decisions were taken one at a time during brainstorming and recorded in the User-visible behavior, Data model, and Components sections above.
