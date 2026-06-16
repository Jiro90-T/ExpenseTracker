# Phase 2.12 — Transactions Sort Options — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit sort options (Date / Amount / Title / Category, with asc/desc toggle) to the Transactions tab, persisted across restarts. Sort defaults to Date/Desc (matches current behavior).

**Architecture:** Add a new `TransactionSort` data class and a pure `sortTransactions` helper in the existing `Filters.kt` (alongside `filterTransactions`); extend `FiltersRepository` with two more SharedPreferences keys; extend `HomeViewModel.filteredTransactions` to apply sort after filter via a 6-source `combine` (vararg form); add a compact sort row in `FilterControls` with a day-header gate on `sort.field == DATE`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Hilt, StateFlow, SharedPreferences, JUnit 4. Same as Phase 2.7.

**Reference:** Spec at `docs/superpowers/specs/2026-06-16-transactions-sort-options-design.md` (commit `28782fb`).

**Constraints carried forward from prior phases:**
- JDK 21 required: `export JAVA_HOME=C:/tools/jdk-21.0.5+11` before every Gradle command.
- Commit author must be `MiniMax-M3 <291324429+Jiro90-T@users.noreply.github.com>`. Do NOT add a `Co-Authored-By: Claude ...` trailer.
- `./gradlew test` for the full suite; `./gradlew :app:testDebugUnitTest --tests "*ClassName"` for a single test class (the root `test` task doesn't accept `--tests` on this project).
- Bash is git-bash on Windows. Use forward slashes in paths.

---

## Task 1: Pure types + `sortTransactions` (TDD)

**Files:**
- Modify: `app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt` (add 15 tests + 1 helper)
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt` (add `SortField`, `SortDirection`, `TransactionSort`, `sortTransactions`)

- [ ] **Step 1: Add 15 failing tests + a `txnCur` helper to `FiltersTest.kt`**

Append the following block to the end of `FiltersTest.kt` (just before the final `}` of the class, after the existing helpers section):

```kotlin
    // ---- sort ----

    @Test
    fun sortTransactions_dateDesc_returnsNewestFirst() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 1), null),
            txn(2L, "B", 200L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(3L, "C", 300L, "EXPENSE", 1L, date(2026, 6, 7), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.DATE, SortDirection.DESC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(listOf(2L, 3L, 1L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_dateAsc_returnsOldestFirst() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 1), null),
            txn(2L, "B", 200L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(3L, "C", 300L, "EXPENSE", 1L, date(2026, 6, 7), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.DATE, SortDirection.ASC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(listOf(1L, 3L, 2L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_amountDesc_usesHomeCurrency() {
        // Row 1: 5_000 USD (raw = converted = 5_000)
        // Row 2: 100_000 JPY with rate 0.01 = 1_000 USD-converted
        // Sort amount DESC: 5_000 > 1_000, so Row 1 (USD) is first.
        // If the sort used raw amountMinor, Row 2 (100_000) would be first.
        val rows = listOf(
            txnCur(1L, "A", 5_000L, "USD", "EXPENSE", 1L, date(2026, 6, 1), null),
            txnCur(2L, "B", 100_000L, "JPY", "EXPENSE", 1L, date(2026, 6, 2), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.AMOUNT, SortDirection.DESC),
            categories(),
            "USD",
            mapOf("JPY_to_USD" to 0.01),
        )
        assertEquals(listOf(1L, 2L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_amountAsc_smallestFirst() {
        val rows = listOf(
            txnCur(1L, "A", 5_000L, "USD", "EXPENSE", 1L, date(2026, 6, 1), null),
            txnCur(2L, "B", 100_000L, "JPY", "EXPENSE", 1L, date(2026, 6, 2), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.AMOUNT, SortDirection.ASC),
            categories(),
            "USD",
            mapOf("JPY_to_USD" to 0.01),
        )
        assertEquals(listOf(2L, 1L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_amount_usesFxWhenAvailable() {
        // JPY row with rate 0.01: 100_000 * 0.01 = 1_000 USD
        // USD row: 500 USD. USD < 1_000, so JPY row is first under DESC.
        // If FX were ignored, JPY raw 100_000 > USD raw 500, so the order would
        // still happen to be JPY first — that's why the prior test uses 5_000 USD
        // (it forces raw and converted to disagree). This test pins the
        // FxConverter contract.
        val rows = listOf(
            txnCur(1L, "USD-small", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 1), null),
            txnCur(2L, "JPY-big-converted", 100_000L, "JPY", "EXPENSE", 1L, date(2026, 6, 2), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.AMOUNT, SortDirection.DESC),
            categories(),
            "USD",
            mapOf("JPY_to_USD" to 0.01),
        )
        assertEquals(listOf(2L, 1L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_amount_fallsBackToRawWhenFxMissing() {
        // No FX rate for JPY: sort uses raw amountMinor.
        // 100_000 JPY (raw) > 5_000 USD (raw), so JPY is first under DESC.
        val rows = listOf(
            txnCur(1L, "A", 5_000L, "USD", "EXPENSE", 1L, date(2026, 6, 1), null),
            txnCur(2L, "B", 100_000L, "JPY", "EXPENSE", 1L, date(2026, 6, 2), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.AMOUNT, SortDirection.DESC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(listOf(2L, 1L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_titleAsc_caseInsensitive() {
        val rows = listOf(
            txn(1L, "banana", 100L, "EXPENSE", 1L, date(2026, 6, 1), null),
            txn(2L, "Apple", 200L, "EXPENSE", 1L, date(2026, 6, 2), null),
            txn(3L, "cherry", 300L, "EXPENSE", 1L, date(2026, 6, 3), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.TITLE, SortDirection.ASC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(listOf(2L, 1L, 3L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_titleDesc_reverseOrder() {
        val rows = listOf(
            txn(1L, "banana", 100L, "EXPENSE", 1L, date(2026, 6, 1), null),
            txn(2L, "Apple", 200L, "EXPENSE", 1L, date(2026, 6, 2), null),
            txn(3L, "cherry", 300L, "EXPENSE", 1L, date(2026, 6, 3), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.TITLE, SortDirection.DESC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(listOf(3L, 1L, 2L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_categoryAsc_alphabeticalByName() {
        // Categories: Food(1L), Restaurants(2L), Salary(3L)
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 3L, date(2026, 6, 1), null),  // Salary
            txn(2L, "B", 200L, "EXPENSE", 1L, date(2026, 6, 2), null),  // Food
            txn(3L, "C", 300L, "EXPENSE", 2L, date(2026, 6, 3), null),  // Restaurants
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.CATEGORY, SortDirection.ASC),
            categories(),
            "USD",
            emptyMap(),
        )
        // Food < Restaurants < Salary
        assertEquals(listOf(2L, 3L, 1L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_categoryDesc_reverseOrder() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 3L, date(2026, 6, 1), null),  // Salary
            txn(2L, "B", 200L, "EXPENSE", 1L, date(2026, 6, 2), null),  // Food
            txn(3L, "C", 300L, "EXPENSE", 2L, date(2026, 6, 3), null),  // Restaurants
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.CATEGORY, SortDirection.DESC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(listOf(1L, 3L, 2L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_categoryEmptyName_sortsToEnd_asc() {
        // A row with an empty-name category sorts to the end regardless of direction.
        // Build it directly because the `txn` helper hardcodes categories().
        val emptyCategory = CategoryEntity(
            id = 99L, name = "", type = "EXPENSE", sortOrder = 0, isBuiltIn = false,
        )
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 99L, date(2026, 6, 1), null),  // empty category
            txn(2L, "B", 200L, "EXPENSE", 1L, date(2026, 6, 2), null),   // Food
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.CATEGORY, SortDirection.ASC),
            listOf(emptyCategory) + categories(),
            "USD",
            emptyMap(),
        )
        // Food first (asc), then the empty-category row.
        assertEquals(listOf(2L, 1L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_categoryEmptyName_sortsToEnd_desc() {
        val emptyCategory = CategoryEntity(
            id = 99L, name = "", type = "EXPENSE", sortOrder = 0, isBuiltIn = false,
        )
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 99L, date(2026, 6, 1), null),  // empty category
            txn(2L, "B", 200L, "EXPENSE", 1L, date(2026, 6, 2), null),   // Food
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.CATEGORY, SortDirection.DESC),
            listOf(emptyCategory) + categories(),
            "USD",
            emptyMap(),
        )
        // Even under DESC, empty-category still sorts last.
        assertEquals(listOf(2L, 1L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_tieBreakerByDateDesc() {
        // Two rows with the same amount; the newer one is first under DESC.
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 1), null),  // older
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 6, 14), null), // newer
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.AMOUNT, SortDirection.DESC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(listOf(2L, 1L), out.map { it.transaction.id })
    }

    @Test
    fun sortTransactions_emptyInput_returnsEmpty() {
        val out = sortTransactions(
            emptyList<TransactionWithCategory>(),
            TransactionSort(SortField.DATE, SortDirection.DESC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(empty, out)
    }

    @Test
    fun sortTransactions_singleRow_returnsAsIs() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 1), null),
        )
        val out = sortTransactions(
            rows,
            TransactionSort(SortField.AMOUNT, SortDirection.ASC),
            categories(),
            "USD",
            emptyMap(),
        )
        assertEquals(rows, out)
    }

    private fun txnCur(
        id: Long,
        title: String,
        amountMinor: Long,
        currencyCode: String,
        type: String,
        categoryId: Long,
        occurredAt: Long,
        note: String?,
    ): TransactionWithCategory {
        val t = TransactionEntity(
            id = id,
            title = title,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            type = type,
            categoryId = categoryId,
            occurredAtEpochMillis = occurredAt,
            note = note,
            createdAtEpochMillis = occurredAt,
        )
        val c = categories().first { it.id == categoryId }
        return TransactionWithCategory(t, c)
    }
```

- [ ] **Step 2: Run the new tests to confirm they fail**

Run from the project root:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.transactions.FiltersTest.sortTransactions_*"
```
Expected: All 15 new tests fail with `Unresolved reference: SortField` (and friends). The 47 prior `filterTransactions_*` tests still pass.

- [ ] **Step 3: Add the new types and `sortTransactions` to `Filters.kt`**

Append the following at the end of `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt` (after the existing `highlightMatches` function):

```kotlin
enum class SortField { DATE, AMOUNT, TITLE, CATEGORY }
enum class SortDirection { ASC, DESC }

data class TransactionSort(
    val field: SortField = SortField.DATE,
    val direction: SortDirection = SortDirection.DESC,
)

/**
 * Pure: sorts [rows] in place-stably by [sort]. The pure filter helper
 * returns rows in input order; this function applies the user's chosen
 * sort after filtering.
 *
 * - DATE: occurredAtEpochMillis.
 * - AMOUNT: FX-converted amountMinor in [homeCurrency] (fallback to raw
 *   amountMinor when the FX rate is missing — matches the Phase 2.9
 *   amount range filter).
 * - TITLE: case-insensitive alphabetical on transaction.title.
 * - CATEGORY: case-insensitive alphabetical on the category name from
 *   [allCategories]. Empty/null names sort to the end regardless of
 *   direction.
 *
 * For non-Date sorts, ties break by occurredAtEpochMillis DESC (newer
 * first within the tie). Kotlin's `sortedBy` is stable, so this is
 * honored by chaining `thenByDescending`.
 */
fun sortTransactions(
    rows: List<TransactionWithCategory>,
    sort: TransactionSort,
    allCategories: List<CategoryEntity>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
): List<TransactionWithCategory> {
    if (rows.size < 2) return rows
    val nameById = allCategories.associate { it.id to it.name }
    val direction = sort.direction
    val key: (TransactionWithCategory) -> Comparable<*>? = when (sort.field) {
        SortField.DATE -> { row -> row.transaction.occurredAtEpochMillis }
        SortField.AMOUNT -> { row ->
            val t = row.transaction
            FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
                ?: t.amountMinor
        }
        SortField.TITLE -> { row -> row.transaction.title.lowercase() }
        SortField.CATEGORY -> { row ->
            val name = nameById[row.transaction.categoryId].orEmpty()
            if (name.isEmpty()) null else name.lowercase()
        }
    }
    return when (sort.field) {
        SortField.DATE -> if (direction == SortDirection.ASC) {
            rows.sortedBy { key(it) as Long }
        } else {
            rows.sortedByDescending { key(it) as Long }
        }
        SortField.AMOUNT -> if (direction == SortDirection.ASC) {
            rows.sortedBy { key(it) as Long }.thenByDescending { it.transaction.occurredAtEpochMillis }
        } else {
            rows.sortedByDescending { key(it) as Long }.thenByDescending { it.transaction.occurredAtEpochMillis }
        }
        SortField.TITLE -> if (direction == SortDirection.ASC) {
            rows.sortedBy { key(it) as String }.thenByDescending { it.transaction.occurredAtEpochMillis }
        } else {
            rows.sortedByDescending { key(it) as String }.thenByDescending { it.transaction.occurredAtEpochMillis }
        }
        SortField.CATEGORY -> {
            // Empty name sorts to the end regardless of direction.
            val (withName, withoutName) = rows.partition {
                !nameById[it.transaction.categoryId].isNullOrEmpty()
            }
            val sortedNamed = if (direction == SortDirection.ASC) {
                withName.sortedBy { nameById[it.transaction.categoryId]!!.lowercase() }
                    .thenByDescending { it.transaction.occurredAtEpochMillis }
            } else {
                withName.sortedByDescending { nameById[it.transaction.categoryId]!!.lowercase() }
                    .thenByDescending { it.transaction.occurredAtEpochMillis }
            }
            // The "without name" rows are ordered by date desc (newest first)
            // so the partition is deterministic.
            val sortedUnnamed = withoutName.sortedByDescending { it.transaction.occurredAtEpochMillis }
            sortedNamed + sortedUnnamed
        }
    }
}
```

- [ ] **Step 4: Run the new tests to confirm they pass**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.transactions.FiltersTest.sortTransactions_*"
```
Expected: All 15 new tests pass. 47 prior `filterTransactions_*` + `highlightMatches_*` tests still pass.

- [ ] **Step 5: Run the full test suite to confirm no regressions**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL. Total test count = 392 + 15 = **407/407 passing** (392 was the count after Phase 2.11 v0.10.1).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Sort: TransactionSort + sortTransactions pure helper (Phase 2.12)"
```

---

## Task 2: Repository extension (`FiltersRepository`)

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/FiltersRepository.kt`

- [ ] **Step 1: Add `sort` flow, `setSort`, and 2 SharedPreferences keys**

In `FiltersRepository.kt`:

1. Add the import for the new types (same package, no import needed).

2. Replace the class body so the new `_sort`, `sort`, `setSort`, `loadSort`, and updated `companion` look like the listing below. The constructor and existing `_filters`/`filters`/`setFilters`/`loadFilters`/date-range helpers are unchanged.

Final file content:

```kotlin
package io.github.jiro.expensetracker.ui.transactions

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's [TransactionFilters] and [TransactionSort] across app
 * restarts. SharedPreferences round-trips the keys. Mirrors
 * [io.github.jiro.expensetracker.preferences.SettingsRepository].
 */
@Singleton
class FiltersRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _filters = MutableStateFlow(loadFilters())
    val filters: StateFlow<TransactionFilters> = _filters.asStateFlow()

    private val _sort = MutableStateFlow(loadSort())
    val sort: StateFlow<TransactionSort> = _sort.asStateFlow()

    fun setFilters(filters: TransactionFilters) {
        if (_filters.value == filters) return
        prefs.edit()
            .putString(KEY_SEARCH_QUERY, filters.searchQuery)
            .putLong(KEY_CATEGORY_ID, filters.categoryId ?: CATEGORY_ID_ALL)
            .putString(KEY_TYPE_FILTER, filters.typeFilter.name)
            .putString(KEY_DATE_RANGE, encodeDateRange(filters.dateRange))
            .putLong(KEY_FILTER_MIN_AMOUNT, filters.minAmount ?: LONG_MIN_VALUE)
            .putLong(KEY_FILTER_MAX_AMOUNT, filters.maxAmount ?: LONG_MIN_VALUE)
        _filters.value = filters
    }

    fun setSort(sort: TransactionSort) {
        if (_sort.value == sort) return
        prefs.edit()
            .putString(KEY_SORT_FIELD, sort.field.name)
            .putString(KEY_SORT_DIRECTION, sort.direction.name)
        _sort.value = sort
    }

    private fun loadFilters(): TransactionFilters = TransactionFilters(
        searchQuery = prefs.getString(KEY_SEARCH_QUERY, "").orEmpty(),
        categoryId = prefs.getLong(KEY_CATEGORY_ID, CATEGORY_ID_ALL)
            .takeIf { it != CATEGORY_ID_ALL },
        typeFilter = runCatching {
            TypeFilter.valueOf(prefs.getString(KEY_TYPE_FILTER, null) ?: TypeFilter.ALL.name)
        }.getOrDefault(TypeFilter.ALL),
        dateRange = decodeDateRange(prefs.getString(KEY_DATE_RANGE, null)),
        minAmount = prefs.getLong(KEY_FILTER_MIN_AMOUNT, LONG_MIN_VALUE)
            .takeIf { it != LONG_MIN_VALUE },
        maxAmount = prefs.getLong(KEY_FILTER_MAX_AMOUNT, LONG_MIN_VALUE)
            .takeIf { it != LONG_MIN_VALUE },
    )

    private fun loadSort(): TransactionSort = TransactionSort(
        field = runCatching {
            SortField.valueOf(prefs.getString(KEY_SORT_FIELD, null) ?: SortField.DATE.name)
        }.getOrDefault(SortField.DATE),
        direction = runCatching {
            SortDirection.valueOf(prefs.getString(KEY_SORT_DIRECTION, null) ?: SortDirection.DESC.name)
        }.getOrDefault(SortDirection.DESC),
    )

    private fun encodeDateRange(preset: DateRangePreset): String = when (preset) {
        DateRangePreset.Any -> "Any"
        DateRangePreset.Last7Days -> "Last7Days"
        DateRangePreset.Last30Days -> "Last30Days"
        DateRangePreset.ThisMonth -> "ThisMonth"
        DateRangePreset.ThisYear -> "ThisYear"
        is DateRangePreset.Custom -> "Custom|${preset.fromMs}|${preset.toMsExclusive}"
    }

    private fun decodeDateRange(stored: String?): DateRangePreset {
        if (stored == null) return DateRangePreset.Any
        val parts = stored.split("|")
        return when (parts[0]) {
            "Any" -> DateRangePreset.Any
            "Last7Days" -> DateRangePreset.Last7Days
            "Last30Days" -> DateRangePreset.Last30Days
            "ThisMonth" -> DateRangePreset.ThisMonth
            "ThisYear" -> DateRangePreset.ThisYear
            "Custom" -> if (parts.size == 3) {
                val from = parts[1].toLongOrNull() ?: return DateRangePreset.Any
                val to = parts[2].toLongOrNull() ?: return DateRangePreset.Any
                DateRangePreset.Custom(from, to)
            } else {
                DateRangePreset.Any
            }
            else -> DateRangePreset.Any
        }
    }

    companion object {
        const val PREFS_NAME = "expense_tracker_filters"
        const val KEY_SEARCH_QUERY = "filters.searchQuery"
        const val KEY_CATEGORY_ID = "filters.categoryId"
        const val KEY_TYPE_FILTER = "filters.typeFilter"
        const val KEY_DATE_RANGE = "filters.dateRange"
        const val KEY_FILTER_MIN_AMOUNT = "filters.minAmount"
        const val KEY_FILTER_MAX_AMOUNT = "filters.maxAmount"
        const val KEY_SORT_FIELD = "sort.field"
        const val KEY_SORT_DIRECTION = "sort.direction"
        const val CATEGORY_ID_ALL = -1L
        const val LONG_MIN_VALUE = Long.MIN_VALUE
    }
}
```

- [ ] **Step 2: Compile-check**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Re-run the full test suite to confirm no regressions**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL, 407/407 passing (unchanged from Task 1).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/transactions/FiltersRepository.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Sort: persist TransactionSort in FiltersRepository (Phase 2.12)"
```

---

## Task 3: VM integration

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt`

- [ ] **Step 1: Add `sort` flow, mutators, and extend `filteredTransactions`**

The current `filteredTransactions` (around lines 130–152) combines 5 sources. We need 6. The `combine` overload for 6 sources uses the vararg form (`combine(vararg flows, transform)`). Replace the existing `filteredTransactions` block AND add the new `sort` flow + 3 mutators. The new code goes inside the `HomeViewModel` class body, near the existing `filters` declaration and `setFilters` / `setXxx` mutators.

Find the existing `filteredTransactions` block (it begins with `val filteredTransactions: StateFlow<List<TransactionWithCategory>> =`) and replace it with:

```kotlin
    /** The current sort state, sourced from FiltersRepository. */
    val sort: StateFlow<TransactionSort> = filtersRepository.sort

    /** All transactions filtered by the current [filters] then sorted by [sort]. */
    val filteredTransactions: StateFlow<List<TransactionWithCategory>> =
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
            val filtered = filterTransactions(
                rows = rows,
                filters = f,
                allCategories = cats,
                nowMs = System.currentTimeMillis(),
                homeCurrency = home,
                fxRates = rates,
            )
            sortTransactions(filtered, s, cats, home, rates)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun setSortField(field: SortField) =
        filtersRepository.setSort(sort.value.copy(field = field))

    fun setSortDirection(direction: SortDirection) =
        filtersRepository.setSort(sort.value.copy(direction = direction))

    fun flipSortDirection() = setSortDirection(
        if (sort.value.direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
    )
```

`setFilters` and the existing mutators (`setSearchQuery`, `setCategoryFilter`, `setTypeFilter`, `setDateRange`, `setMinAmount`, `setMaxAmount`, `clearFilters`) are unchanged.

- [ ] **Step 2: Compile-check**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Re-run the full test suite to confirm no regressions**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL, 407/407 passing.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Sort: VM mutators + 6-source combine for filterAndSort (Phase 2.12)"
```

---

## Task 4: UI (sort row + day-header gate) + 7 new strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (add 7 strings)
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt` (add sort row, day-header gate, 2 new icon imports)

- [ ] **Step 1: Add 7 new strings to `strings.xml`**

Insert the following block at the end of the existing strings, just before the closing `</resources>` (after the existing `Phase 2.10` block):

```xml
    <!-- Phase 2.12 — Transactions sort options -->
    <string name="filter_sort_label">Sort</string>
    <string name="filter_sort_field_date">Date</string>
    <string name="filter_sort_field_amount">Amount</string>
    <string name="filter_sort_field_title">Title</string>
    <string name="filter_sort_field_category">Category</string>
    <string name="filter_sort_direction_asc">Sort ascending</string>
    <string name="filter_sort_direction_desc">Sort descending</string>
```

- [ ] **Step 2: Add the sort row to `FilterControls` in `TransactionsScreen.kt`**

In `TransactionsScreen.kt`, make the following changes:

1. Add 2 new icon imports near the existing icon imports (around line 17-22):
```kotlin
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
```

2. In the `TransactionsScreen` composable, after `val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()` (around line 74), add a new state read:
```kotlin
    val sort by viewModel.sort.collectAsStateWithLifecycle()
```

3. In the `FilterControls(...)` call site (around line 143-156), add 3 new parameters:
```kotlin
            FilterControls(
                searchInput = searchInput,
                onSearchInputChange = { searchInput = it },
                filters = filters,
                categories = allCategories,
                onTypeChange = viewModel::setTypeFilter,
                onCategoryChange = viewModel::setCategoryFilter,
                onDateRangeChange = viewModel::setDateRange,
                onClear = viewModel::clearFilters,
                minInput = minInput,
                onMinInputChange = { minInput = it },
                maxInput = maxInput,
                onMaxInputChange = { maxInput = it },
                sort = sort,
                onSortFieldChange = viewModel::setSortField,
                onFlipDirection = viewModel::flipSortDirection,
            )
```

4. In the `FilterControls` composable signature (around line 196-209), add 3 new parameters:
```kotlin
private fun FilterControls(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    filters: TransactionFilters,
    categories: List<CategoryEntity>,
    onTypeChange: (TypeFilter) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onDateRangeChange: (DateRangePreset) -> Unit,
    onClear: () -> Unit,
    minInput: String,
    onMinInputChange: (String) -> Unit,
    maxInput: String,
    onMaxInputChange: (String) -> Unit,
    sort: TransactionSort,
    onSortFieldChange: (SortField) -> Unit,
    onFlipDirection: () -> Unit,
) {
```

5. In the `FilterControls` body, between the type-chips `Row` and the amount-range `Row` (around line 257), insert a new `Row` for the sort controls:
```kotlin
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stringResource(R.string.filter_sort_label)}:",
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

6. Append two new private composables at the end of the file (after the existing `DateRangePickerDialog` and before the existing `EmptyState`):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortFieldDropdown(
    selected: SortField,
    onSelect: (SortField) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = stringResource(selected.labelRes),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(stringResource(field.labelRes)) },
                    onClick = {
                        onSelect(field)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val SortField.labelRes: Int
    get() = when (this) {
        SortField.DATE -> R.string.filter_sort_field_date
        SortField.AMOUNT -> R.string.filter_sort_field_amount
        SortField.TITLE -> R.string.filter_sort_field_title
        SortField.CATEGORY -> R.string.filter_sort_field_category
    }
```

- [ ] **Step 3: Gate the day headers on `sort.field == DATE`**

In the `TransactionsScreen` body, the list rendering currently calls `groupByDay(filteredTransactions)` and iterates over the result. Replace the chunk inside the `else` branch (the one that renders the list when `filteredTransactions` is non-empty) — specifically the section that starts with `val grouped = remember(filteredTransactions) { groupByDay(filteredTransactions) }` and ends with the closing `}` of the `LazyColumn` block — with:

```kotlin
                } else {
                    val grouped = remember(filteredTransactions, sort.field) {
                        if (sort.field == SortField.DATE) groupByDay(filteredTransactions) else null
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (grouped != null) {
                            grouped.forEach { group ->
                                item(key = "day_${group.dayStartMs}") {
                                    DayHeader(group.dayStartMs)
                                }
                                items(group.items, key = { it.transaction.id }) { row ->
                                    SwipeableTransactionRow(
                                        row = row,
                                        onEdit = { onTransactionClick(row.transaction.id) },
                                        onDelete = { viewModel.delete(row) },
                                        searchQuery = filters.searchQuery,
                                    )
                                }
                            }
                        } else {
                            items(filteredTransactions, key = { it.transaction.id }) { row ->
                                SwipeableTransactionRow(
                                    row = row,
                                    onEdit = { onTransactionClick(row.transaction.id) },
                                    onDelete = { viewModel.delete(row) },
                                    searchQuery = filters.searchQuery,
                                )
                            }
                        }
                    }
                }
```

- [ ] **Step 4: Compile-check**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Re-run the full test suite to confirm no regressions**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL, 407/407 passing (UI changes don't add tests; the sort row is a thin wrapper around `ExposedDropdownMenuBox` and an `IconButton`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Sort: sort row in FilterControls + day-header gate (Phase 2.12)"
```

---

## Task 5: Final verification + v0.11.0 tag/push

**Files:** none (read-only verification + tag/push)

- [ ] **Step 1: Run the full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL, **407/407 passing** (392 from v0.10.1 + 15 new `sortTransactions_*` tests).

- [ ] **Step 2: Run the build**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify no stale references to old sort behavior**

```bash
git log --oneline -6
```
Expected: the 4 Phase 2.12 implementation commits are present (one per Task 1-4) plus the spec commit `28782fb` and the plan commit (added just before this task starts). No "Spec:" or "Plan:" commit should be missing.

- [ ] **Step 4: Tag v0.11.0 and push**

```bash
git tag v0.11.0
git push origin master --tags
```

If `git push` complains about identity, retry with the explicit author:
```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" push origin master --tags
```

- [ ] **Step 5: Check for any remaining working-tree changes**

```bash
git status
```
- If `.claude/settings.local.json` is the only modified file, that's pre-existing state — do NOT commit it.
- If there are other modified files, report what they are. Most likely there are none.

- [ ] **Step 6: Report**

Report: test pass count, build status, tag + push success, any leftover state.

---

## Self-review checklist (run before final commit)

1. **Spec coverage:** all 4 sort fields (Date, Amount, Title, Category), asc/desc split, compact row in FilterControls, day-header gate, persistence via SharedPreferences, default Date/Desc, FX-converted amount sort, FX-missing fallback, tie-breaker, empty/single-row no-ops, "Clear filters" doesn't reset sort — all covered.
2. **Placeholder scan:** no `TBD` / `TODO` / "implement later" / "fill in" markers.
3. **Type consistency:** `SortField` / `SortDirection` / `TransactionSort` / `sortTransactions` names match across Tasks 1-4. `setSortField` / `setSortDirection` / `flipSortDirection` VM methods match between Task 3 and the call site in Task 4. String keys (`filter_sort_*`) match between `strings.xml` (Task 4) and the `stringResource(...)` calls in `TransactionsScreen.kt`.
4. **Test count:** 15 new tests (47 → 62 in `FiltersTest.kt`); 392 + 15 = 407 project-wide.
