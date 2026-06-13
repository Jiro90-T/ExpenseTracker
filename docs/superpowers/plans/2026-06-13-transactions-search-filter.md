# Phase 2.7 — Transactions Search & Filter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Transactions list findable and narrowable. Add four filter controls (search box, type chips, category dropdown, date range dropdown) that compose as AND-filters and persist across app restarts.

**Architecture:** A pure `filterTransactions(rows, filters, allCategories, nowMs)` helper does the work — JVM-testable. A SharedPreferences-backed `FiltersRepository` persists filters across restarts (mirroring the existing `SettingsRepository` pattern). The `HomeViewModel` is extended to expose `filters`, `allCategories`, and `filteredTransactions`. `TransactionsScreen` renders the controls and two empty states.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 `OutlinedTextField`, `FilterChip`, `ExposedDropdownMenuBox`, `DateRangePickerDialog`), Hilt, JUnit 4.

**Working directory:** `F:/AndroidApp/ExpenseTracker`

**Required env (Windows):** `JAVA_HOME=C:/tools/jdk-21.0.5+11` (AGP 8.13.2 + bundled Kotlin choke on Java 8 and on Java 25+). Run gradle as:
```bash
export JAVA_HOME="C:/tools/jdk-21.0.5+11" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew <task>
```

**Commit identity:** All commits use inline author (no Co-Authored-By trailer):
```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "..."
```

**Cumulative string-resource warning:** `R.string.filter_*` references are int constants generated at build time. If you reference a string that doesn't exist in `strings.xml`, the build fails with "unresolved reference". The plan adds the strings in the task that first needs them. If a verify-compile step says "missing string", add it to `strings.xml` immediately and re-run.

---

## Task 1: Pure data layer + JUnit tests (TDD)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt`

This task adds the `DateRangePreset` sealed interface, the `TypeFilter` enum, the `TransactionFilters` data class, the `filterTransactions` pure helper, and a JUnit suite (34 tests). All JVM-testable, no Android, no Compose, no Hilt.

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt` with this content:

```kotlin
package io.github.jiro.expensetracker.ui.transactions

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FiltersTest {

    // ---- empty / default filters ----

    @Test
    fun filterTransactions_emptyFilters_returnsAll() {
        val rows = listOf(
            txn(1L, "Coffee", 1_200L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(2L, "Salary", 200_000L, "INCOME", 2L, date(2026, 6, 1), null),
        )
        val out = filterTransactions(rows, TransactionFilters(), categories(), date(2026, 6, 15))
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_isEmpty_defaultTrue() {
        assertTrue(TransactionFilters().isEmpty)
    }

    @Test
    fun filterTransactions_isEmpty_searchQuerySet_false() {
        assertEquals(false, TransactionFilters(searchQuery = "x").isEmpty)
    }

    @Test
    fun filterTransactions_isEmpty_categorySet_false() {
        assertEquals(false, TransactionFilters(categoryId = 1L).isEmpty)
    }

    @Test
    fun filterTransactions_isEmpty_typeSet_false() {
        assertEquals(false, TransactionFilters(typeFilter = TypeFilter.INCOME).isEmpty)
    }

    @Test
    fun filterTransactions_isEmpty_dateRangePresetSet_false() {
        assertEquals(false, TransactionFilters(dateRange = DateRangePreset.Last7Days).isEmpty)
    }

    @Test
    fun filterTransactions_isEmpty_customDateSet_false() {
        assertEquals(false, TransactionFilters(dateRange = DateRangePreset.Custom(date(2026, 1, 1), date(2026, 3, 31))).isEmpty)
    }

    // ---- search ----

    @Test
    fun filterTransactions_searchTitle_matchesCaseInsensitive() {
        val rows = listOf(
            txn(1L, "Coffee Shop", 1_200L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "COF"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchTitle_doesNotMatchUnrelated() {
        val rows = listOf(
            txn(1L, "Coffee Shop", 1_200L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "tea"),
            categories(),
            date(2026, 6, 15),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun filterTransactions_searchNote_matches() {
        val rows = listOf(
            txn(1L, "Misc", 500L, "EXPENSE", 1L, date(2026, 6, 14), "Lunch with team"),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "team"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchNote_skipsNull() {
        val rows = listOf(
            txn(1L, "Misc", 500L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        // No crash; row only matches if other fields match.
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "anything"),
            categories(),
            date(2026, 6, 15),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun filterTransactions_searchCategoryName_matches() {
        // "Coffee Shop" title, category "Food" → query "food" matches.
        val rows = listOf(
            txn(1L, "Coffee Shop", 1_200L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "food"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchAmount_matchesSubstring() {
        val rows = listOf(
            txn(1L, "A", 1_200L, "EXPENSE", 1L, date(2026, 6, 14), null),     // $12.00
            txn(2L, "B", 12_000L, "EXPENSE", 1L, date(2026, 6, 14), null),    // $120.00
            txn(3L, "C", 120_000L, "EXPENSE", 1L, date(2026, 6, 14), null),   // $1,200.00
            txn(4L, "D", 1_250L, "EXPENSE", 1L, date(2026, 6, 14), null),     // $12.50
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "12"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchAmount_doesNotMatchUnrelated() {
        val rows = listOf(
            txn(1L, "A", 1_200L, "EXPENSE", 1L, date(2026, 6, 14), null),     // $12.00
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "99"),
            categories(),
            date(2026, 6, 15),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun filterTransactions_searchWhitespaceTrimmed() {
        val rows = listOf(
            txn(1L, "Coffee", 500L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "   coffee   "),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchEmptyString_isNoOp() {
        val rows = listOf(
            txn(1L, "Coffee", 500L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = ""),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchBlank_isNoOp() {
        val rows = listOf(
            txn(1L, "Coffee", 500L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "    "),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    // ---- category ----

    @Test
    fun filterTransactions_categoryFilter_onlyMatchingRows() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),  // cat 1
            txn(2L, "B", 100L, "EXPENSE", 2L, date(2026, 6, 14), null),  // cat 2
            txn(3L, "C", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),  // cat 1
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(categoryId = 1L),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0], rows[2]), out)
    }

    @Test
    fun filterTransactions_categoryNull_isNoOp() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(2L, "B", 100L, "EXPENSE", 2L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(categoryId = null),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    // ---- type ----

    @Test
    fun filterTransactions_typeIncome_onlyIncomeRows() {
        val rows = listOf(
            txn(1L, "A", 100L, "INCOME", 1L, date(2026, 6, 14), null),
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(typeFilter = TypeFilter.INCOME),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0]), out)
    }

    @Test
    fun filterTransactions_typeExpense_onlyExpenseRows() {
        val rows = listOf(
            txn(1L, "A", 100L, "INCOME", 1L, date(2026, 6, 14), null),
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(typeFilter = TypeFilter.EXPENSE),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[1]), out)
    }

    @Test
    fun filterTransactions_typeAll_isNoOp() {
        val rows = listOf(
            txn(1L, "A", 100L, "INCOME", 1L, date(2026, 6, 14), null),
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(typeFilter = TypeFilter.ALL),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    // ---- date range ----

    @Test
    fun filterTransactions_dateRangeAny_isNoOp() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 1, 1), null),
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2025, 1, 1), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.Any),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_dateRangeLast7Days_onlyRecent() {
        val now = date(2026, 6, 15, hour = 12)
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 14, hour = 12), null),  // 1 day ago — in
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 6, 8, hour = 12), null),   // 7 days ago — at boundary
            txn(3L, "C", 100L, "EXPENSE", 1L, date(2026, 6, 7, hour = 12), null),   // 8 days ago — out
            txn(4L, "D", 100L, "EXPENSE", 1L, date(2026, 1, 1), null),               // way old — out
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.Last7Days),
            categories(),
            now,
        )
        assertEquals(listOf(rows[0], rows[1]), out)
    }

    @Test
    fun filterTransactions_dateRangeLast30Days_onlyRecent() {
        val now = date(2026, 6, 15, hour = 12)
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 14, hour = 12), null),  // 1 day ago — in
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 5, 16, hour = 12), null),  // 30 days ago — at boundary
            txn(3L, "C", 100L, "EXPENSE", 1L, date(2026, 5, 15, hour = 12), null),  // 31 days ago — out
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.Last30Days),
            categories(),
            now,
        )
        assertEquals(listOf(rows[0], rows[1]), out)
    }

    @Test
    fun filterTransactions_dateRangeThisMonth() {
        val now = date(2026, 6, 15)
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 1), null),   // in
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 6, 30), null),  // in
            txn(3L, "C", 100L, "EXPENSE", 1L, date(2026, 5, 31), null),  // out
            txn(4L, "D", 100L, "EXPENSE", 1L, date(2026, 7, 1), null),   // out
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.ThisMonth),
            categories(),
            now,
        )
        assertEquals(listOf(rows[0], rows[1]), out)
    }

    @Test
    fun filterTransactions_dateRangeThisMonth_januaryEdge() {
        val now = date(2026, 1, 15)
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 1, 1), null),   // in
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2025, 12, 31), null),  // out
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.ThisMonth),
            categories(),
            now,
        )
        assertEquals(listOf(rows[0]), out)
    }

    @Test
    fun filterTransactions_dateRangeThisYear() {
        val now = date(2026, 6, 15)
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 1, 1), null),
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 12, 31), null),
            txn(3L, "C", 100L, "EXPENSE", 1L, date(2025, 12, 31), null),  // out
            txn(4L, "D", 100L, "EXPENSE", 1L, date(2027, 1, 1), null),   // out
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.ThisYear),
            categories(),
            now,
        )
        assertEquals(listOf(rows[0], rows[1]), out)
    }

    @Test
    fun filterTransactions_dateRangeCustom() {
        val from = date(2026, 1, 1)
        val to = date(2026, 3, 31)
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 1, 15), null),   // in
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 3, 30), null),   // in
            txn(3L, "C", 100L, "EXPENSE", 1L, date(2026, 4, 1), null),    // out
            txn(4L, "D", 100L, "EXPENSE", 1L, date(2025, 12, 31), null),  // out
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.Custom(from, to)),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0], rows[1]), out)
    }

    @Test
    fun filterTransactions_dateRangeCustomInverted_swapped() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 2, 15), null),  // in Q1-Q4
        )
        // Custom(to, from) should auto-swap to Custom(from, to).
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.Custom(date(2026, 12, 1), date(2026, 1, 1))),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_dateRangeCustomInverted_equalStaysEqual() {
        val t = date(2026, 6, 15)
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, t, null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(dateRange = DateRangePreset.Custom(t, t)),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    // ---- combined ----

    @Test
    fun filterTransactions_combinedFilters_intersected() {
        val rows = listOf(
            // matches: food in Restaurants (cat 2), EXPENSE, last 30 days, title "lunch"
            txn(1L, "Lunch", 1_500L, "EXPENSE", 2L, date(2026, 6, 14), null),
            // doesn't match: wrong category (cat 1 = Food)
            txn(2L, "Lunch", 1_500L, "EXPENSE", 1L, date(2026, 6, 14), null),
            // doesn't match: wrong type
            txn(3L, "Lunch", 1_500L, "INCOME", 2L, date(2026, 6, 14), null),
            // doesn't match: out of date range
            txn(4L, "Lunch", 1_500L, "EXPENSE", 2L, date(2025, 1, 1), null),
            // doesn't match: title doesn't contain "lunch"
            txn(5L, "Dinner", 1_500L, "EXPENSE", 2L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(
                searchQuery = "lunch",
                categoryId = 2L,
                typeFilter = TypeFilter.EXPENSE,
                dateRange = DateRangePreset.Last30Days,
            ),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0]), out)
    }

    @Test
    fun filterTransactions_combinedFilters_emptyResult() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        // Contradictory: type=INCOME AND category=2 (no INCOME row in cat 2).
        val out = filterTransactions(
            rows,
            TransactionFilters(
                typeFilter = TypeFilter.INCOME,
                categoryId = 2L,
            ),
            categories(),
            date(2026, 6, 15),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun filterTransactions_preservesInputOrder() {
        val rows = listOf(
            txn(1L, "A", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(2L, "B", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(3L, "C", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(4L, "D", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        // Filter excludes B and C.
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "a"),  // matches A and D only (case-insensitive)
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0], rows[3]), out)
    }

    // ---- helpers ----

    private fun txn(
        id: Long,
        title: String,
        amountMinor: Long,
        type: String,
        categoryId: Long,
        occurredAt: Long,
        note: String?,
    ): TransactionWithCategory {
        val t = TransactionEntity(
            id = id,
            title = title,
            amountMinor = amountMinor,
            currencyCode = "USD",
            type = type,
            categoryId = categoryId,
            occurredAtEpochMillis = occurredAt,
            note = note,
            createdAtEpochMillis = occurredAt,
        )
        val c = categories().first { it.id == categoryId }
        return TransactionWithCategory(t, c)
    }

    private fun categories(): List<CategoryEntity> = listOf(
        CategoryEntity(id = 1L, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = true),
        CategoryEntity(id = 2L, name = "Restaurants", type = "EXPENSE", sortOrder = 0, isBuiltIn = true),
        CategoryEntity(id = 3L, name = "Salary", type = "INCOME", sortOrder = 0, isBuiltIn = true),
    )

    private fun date(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
    ): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (function/type missing)**

Run: `./gradlew testDebugUnitTest --tests "*FiltersTest"`
Expected: Compile error — `DateRangePreset`, `TypeFilter`, `TransactionFilters`, `filterTransactions` are unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt` with this content:

```kotlin
package io.github.jiro.expensetracker.ui.transactions

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.local.MoneyFormat
import java.util.Calendar

/**
 * A date range for the Transactions filter. The five presets resolve to
 * `[fromMs, toMsExclusive)` ranges against the production "now" passed
 * to [filterTransactions]. [Custom] carries its own bounds and is
 * auto-swapped if the user picks from > to.
 */
sealed interface DateRangePreset {
    data object Any : DateRangePreset
    data object Last7Days : DateRangePreset
    data object Last30Days : DateRangePreset
    data object ThisMonth : DateRangePreset
    data object ThisYear : DateRangePreset
    data class Custom(val fromMs: Long, val toMsExclusive: Long) : DateRangePreset
}

/** The type-filter enum. `ALL` means no type filter. */
enum class TypeFilter { ALL, INCOME, EXPENSE }

/**
 * The four filter dimensions for the Transactions list. Default is
 * "all-empty" — [isEmpty] returns true. Any non-default value flips
 * [isEmpty] to false.
 */
data class TransactionFilters(
    val searchQuery: String = "",
    val categoryId: Long? = null,
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val dateRange: DateRangePreset = DateRangePreset.Any,
) {
    val isEmpty: Boolean
        get() = searchQuery.isEmpty() && categoryId == null
            && typeFilter == TypeFilter.ALL && dateRange is DateRangePreset.Any
}

/**
 * Pure filter. Applies the four dimensions to [rows] and returns the
 * filtered list in the same order. [allCategories] is needed for the
 * "search by category name" match. [nowMs] anchors the date presets.
 *
 *   - Search: case-insensitive substring match on title, note (if non-null),
 *     category name, and the formatted amount. Empty/blank query is a no-op.
 *   - Category: equality match. `null` is a no-op.
 *   - Type: equality match. `ALL` is a no-op.
 *   - Date range: resolves the preset to `[from, toExclusive)` and filters
 *     by `transaction.occurredAtEpochMillis`. `Custom(from, to)` auto-swaps
 *     so the range is non-empty.
 */
fun filterTransactions(
    rows: List<TransactionWithCategory>,
    filters: TransactionFilters,
    allCategories: List<CategoryEntity>,
    nowMs: Long,
): List<TransactionWithCategory> {
    val trimmedQuery = filters.searchQuery.trim()
    val hasQuery = trimmedQuery.isNotEmpty()
    val categoryNameById = allCategories.associate { it.id to it.name }
    val (rangeFrom, rangeToExclusive) = resolveDateRange(filters.dateRange, nowMs)

    return rows.filter { row ->
        val t = row.transaction

        // Search query: must match at least one of the searched fields.
        if (hasQuery) {
            val titleMatch = t.title.contains(trimmedQuery, ignoreCase = true)
            val noteMatch = t.note?.contains(trimmedQuery, ignoreCase = true) == true
            val categoryMatch = categoryNameById[t.categoryId]
                ?.contains(trimmedQuery, ignoreCase = true) == true
            val amountMatch = MoneyFormat.formatAmountForEdit(t.amountMinor)
                .contains(trimmedQuery, ignoreCase = true)
            if (!(titleMatch || noteMatch || categoryMatch || amountMatch)) return@filter false
        }

        // Category.
        if (filters.categoryId != null && t.categoryId != filters.categoryId) return@filter false

        // Type.
        when (filters.typeFilter) {
            TypeFilter.ALL -> Unit
            TypeFilter.INCOME -> if (t.type != "INCOME") return@filter false
            TypeFilter.EXPENSE -> if (t.type != "EXPENSE") return@filter false
        }

        // Date range.
        if (t.occurredAtEpochMillis !in rangeFrom until rangeToExclusive) return@filter false

        true
    }
}

private fun resolveDateRange(
    preset: DateRangePreset,
    nowMs: Long,
): Pair<Long, Long> = when (preset) {
    DateRangePreset.Any -> Long.MIN_VALUE to Long.MAX_VALUE
    DateRangePreset.Last7Days -> (nowMs - 7L * 86_400_000L) to Long.MAX_VALUE
    DateRangePreset.Last30Days -> (nowMs - 30L * 86_400_000L) to Long.MAX_VALUE
    DateRangePreset.ThisMonth -> startOfMonth(nowMs) to Long.MAX_VALUE
    DateRangePreset.ThisYear -> startOfYear(nowMs) to Long.MAX_VALUE
    is DateRangePreset.Custom -> {
        if (preset.fromMs <= preset.toMsExclusive) {
            preset.fromMs to preset.toMsExclusive
        } else {
            preset.toMsExclusive to preset.fromMs
        }
    }
}

private fun startOfMonth(epochMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

private fun startOfYear(epochMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.MONTH, Calendar.JANUARY)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*FiltersTest"`
Expected: 34/34 pass.

- [ ] **Step 5: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt \
  app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Transactions: pure filterTransactions + 34 tests"
```

---

## Task 2: `FiltersRepository` (SharedPreferences persistence)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/FiltersRepository.kt`

This task adds a `@Singleton` SharedPreferences-backed repository that holds the `TransactionFilters` state and persists it across app restarts. Mirrors the `SettingsRepository` pattern.

- [ ] **Step 1: Create `FiltersRepository.kt`**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/FiltersRepository.kt` with this content:

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
 * Persists the user's [TransactionFilters] across app restarts. SharedPreferences
 * round-trips four keys — one per field. Mirrors [io.github.jiro.expensetracker.preferences.SettingsRepository].
 */
@Singleton
class FiltersRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _filters = MutableStateFlow(loadFilters())
    val filters: StateFlow<TransactionFilters> = _filters.asStateFlow()

    fun setFilters(filters: TransactionFilters) {
        if (_filters.value == filters) return
        prefs.edit()
            .putString(KEY_SEARCH_QUERY, filters.searchQuery)
            .putLong(KEY_CATEGORY_ID, filters.categoryId ?: CATEGORY_ID_ALL)
            .putString(KEY_TYPE_FILTER, filters.typeFilter.name)
            .putString(KEY_DATE_RANGE, encodeDateRange(filters.dateRange))
        _filters.value = filters
    }

    private fun loadFilters(): TransactionFilters = TransactionFilters(
        searchQuery = prefs.getString(KEY_SEARCH_QUERY, "").orEmpty(),
        categoryId = prefs.getLong(KEY_CATEGORY_ID, CATEGORY_ID_ALL)
            .takeIf { it != CATEGORY_ID_ALL },
        typeFilter = runCatching {
            TypeFilter.valueOf(prefs.getString(KEY_TYPE_FILTER, null) ?: TypeFilter.ALL.name)
        }.getOrDefault(TypeFilter.ALL),
        dateRange = decodeDateRange(prefs.getString(KEY_DATE_RANGE, null)),
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
        const val CATEGORY_ID_ALL = -1L
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `FiltersRepository` is self-contained; nothing else references it yet.

- [ ] **Step 3: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/transactions/FiltersRepository.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Transactions: FiltersRepository (SharedPreferences persistence)"
```

---

## Task 3: Extend `HomeViewModel` for filters + `filteredTransactions`

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt`

The VM gains `filters: StateFlow<TransactionFilters>` (sourced from `FiltersRepository`), `allCategories: StateFlow<List<CategoryEntity>>` (from `CategoryRepository`), `filteredTransactions: StateFlow<List<TransactionWithCategory>>` (combines observe + filters + categories + nowMs), and a `setFilters(...)` mutator plus thin convenience setters. The existing `allTransactions` flow stays for the unfiltered view.

- [ ] **Step 1: Add the new fields, imports, and constructor param to `HomeViewModel.kt`**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt` and make these changes:

**(a)** Add new imports at the top (right after the existing `import io.github.jiro.expensetracker.domain.model.TransactionType` line):

```kotlin
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.ui.transactions.DateRangePreset
import io.github.jiro.expensetracker.ui.transactions.FiltersRepository
import io.github.jiro.expensetracker.ui.transactions.TransactionFilters
import io.github.jiro.expensetracker.ui.transactions.TypeFilter
import io.github.jiro.expensetracker.ui.transactions.filterTransactions
```

**(b)** Update the constructor signature (currently `@Inject constructor(private val repository: TransactionRepository, private val settingsRepository: SettingsRepository)`) to also take `CategoryRepository` and `FiltersRepository`:

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val settingsRepository: SettingsRepository,
    private val categoryRepository: CategoryRepository,
    private val filtersRepository: FiltersRepository,
) : ViewModel() {
```

**(c)** Add the three new flows (right after the `monthlyTotals` flow, before the `_undo` declaration):

```kotlin
    /** All categories, used by the Transactions tab's category dropdown and filter. */
    val allCategories: StateFlow<List<CategoryEntity>> = categoryRepository
        .observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** The current filter state, sourced from FiltersRepository. */
    val filters: StateFlow<TransactionFilters> = filtersRepository.filters

    /** All transactions filtered by the current [filters]. */
    val filteredTransactions: StateFlow<List<TransactionWithCategory>> =
        combine(
            repository.observeAll(),
            filters,
            allCategories,
        ) { rows, f, cats -> Triple(rows, f, cats) }
            .map { (rows, f, cats) ->
                filterTransactions(rows, f, cats, nowMs = System.currentTimeMillis())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )
```

**(d)** Add the setters at the end of the class (right before the closing `}` of the class, after the `dismissUndo` function):

```kotlin
    fun setFilters(filters: TransactionFilters) {
        filtersRepository.setFilters(filters)
    }

    fun setSearchQuery(q: String) = setFilters(filters.value.copy(searchQuery = q))
    fun setCategoryFilter(id: Long?) = setFilters(filters.value.copy(categoryId = id))
    fun setTypeFilter(t: TypeFilter) = setFilters(filters.value.copy(typeFilter = t))
    fun setDateRange(d: DateRangePreset) = setFilters(filters.value.copy(dateRange = d))
    fun clearFilters() = filtersRepository.setFilters(TransactionFilters())
}
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `HomeViewModel` is self-contained at the call-site level; the new fields/imports are valid.

- [ ] **Step 3: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Transactions: VM exposes filters, allCategories, filteredTransactions"
```

---

## Task 4: `TransactionsScreen` UI + 14 new strings

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

This task renders the four filter controls (search field, type chips, category dropdown, date range dropdown), a `Clear filters` button, the new no-matches empty state, and the date-range picker dialog. It also adds the 14 new strings.

- [ ] **Step 1: Add the 14 new strings to `strings.xml`**

Open `app/src/main/res/values/strings.xml` and add these lines at the end (before the closing `</resources>` tag, or after the last existing `</string>` line):

```xml
    <string name="filter_search_hint">Search title, note, category, or amount</string>
    <string name="filter_type_all">All Types</string>
    <string name="filter_type_income">Income</string>
    <string name="filter_type_expense">Expense</string>
    <string name="filter_category_all">All categories</string>
    <string name="filter_date_any">Any</string>
    <string name="filter_date_last_7_days">Last 7 days</string>
    <string name="filter_date_last_30_days">Last 30 days</string>
    <string name="filter_date_this_month">This month</string>
    <string name="filter_date_this_year">This year</string>
    <string name="filter_date_custom">Custom…</string>
    <string name="filter_clear">Clear filters</string>
    <string name="filter_no_matches_title">No matches</string>
    <string name="filter_no_matches_body">No transactions match your current filters.</string>
```

- [ ] **Step 2: Replace the entire contents of `TransactionsScreen.kt`**

```kotlin
package io.github.jiro.expensetracker.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.ui.home.DayHeader
import io.github.jiro.expensetracker.ui.home.HomeViewModel
import io.github.jiro.expensetracker.ui.home.SwipeableTransactionRow
import io.github.jiro.expensetracker.ui.home.groupByDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onTransactionClick: (Long) -> Unit = {},
    reselectTrigger: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val filteredTransactions by viewModel.filteredTransactions.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()
    val undoState by viewModel.undo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)
    val deletedLabel = stringResource(R.string.snackbar_transaction_deleted)
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState()
    LaunchedEffect(reselectTrigger) {
        if (reselectTrigger > 0) listState.animateScrollToItem(0)
    }

    LaunchedEffect(undoState) {
        val pending = undoState ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedLabel,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.dismissUndo()
        }
    }

    // Debounce the search text: the local `searchInput` updates immediately for
    // UI responsiveness, the actual filter is committed 300ms after the last keystroke.
    var searchInput by remember { mutableStateOf(filters.searchQuery) }
    LaunchedEffect(filters.searchQuery) {
        // When the repo's filters change (e.g. after a "clear filters"), reset the local input.
        if (filters.searchQuery != searchInput) {
            searchInput = filters.searchQuery
        }
    }
    LaunchedEffect(searchInput) {
        delay(300)
        if (searchInput != filters.searchQuery) {
            viewModel.setSearchQuery(searchInput)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.transactions_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            FilterControls(
                searchInput = searchInput,
                onSearchInputChange = { searchInput = it },
                filters = filters,
                categories = allCategories,
                onTypeChange = viewModel::setTypeFilter,
                onCategoryChange = viewModel::setCategoryFilter,
                onDateRangeChange = viewModel::setDateRange,
                onClear = viewModel::clearFilters,
            )
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (filteredTransactions.isEmpty()) {
                    EmptyState(
                        isFiltered = !filters.isEmpty,
                        onClear = viewModel::clearFilters,
                    )
                } else {
                    val grouped = remember(filteredTransactions) { groupByDay(filteredTransactions) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        grouped.forEach { group ->
                            item(key = "day_${group.dayStartMs}") {
                                DayHeader(group.dayStartMs)
                            }
                            items(group.items, key = { it.transaction.id }) { row ->
                                SwipeableTransactionRow(
                                    row = row,
                                    onEdit = { onTransactionClick(row.transaction.id) },
                                    onDelete = { viewModel.delete(row) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterControls(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    filters: TransactionFilters,
    categories: List<CategoryEntity>,
    onTypeChange: (TypeFilter) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onDateRangeChange: (DateRangePreset) -> Unit,
    onClear: () -> Unit,
) {
    var showDateDialog by remember { mutableStateOf(false) }
    var pendingDateRange by remember { mutableStateOf<DateRangePreset>(DateRangePreset.Any) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = searchInput,
            onValueChange = onSearchInputChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.filter_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (searchInput.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchInputChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.filter_clear),
                        )
                    }
                }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeChip(
                label = stringResource(R.string.filter_type_all),
                selected = filters.typeFilter == TypeFilter.ALL,
                onClick = { onTypeChange(TypeFilter.ALL) },
            )
            TypeChip(
                label = stringResource(R.string.filter_type_income),
                selected = filters.typeFilter == TypeFilter.INCOME,
                onClick = { onTypeChange(TypeFilter.INCOME) },
            )
            TypeChip(
                label = stringResource(R.string.filter_type_expense),
                selected = filters.typeFilter == TypeFilter.EXPENSE,
                onClick = { onTypeChange(TypeFilter.EXPENSE) },
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryDropdown(
                categories = categories,
                selectedCategoryId = filters.categoryId,
                onSelect = onCategoryChange,
                modifier = Modifier.weight(1f),
            )
            DateRangeDropdown(
                selected = filters.dateRange,
                onPresetSelected = onDateRangeChange,
                onCustomRequested = { showDateDialog = true },
                modifier = Modifier.weight(1f),
            )
        }

        if (!filters.isEmpty) {
            TextButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.filter_clear))
            }
        }
    }

    if (showDateDialog) {
        DateRangePickerDialog(
            initialRange = (filters.dateRange as? DateRangePreset.Custom) ?: pendingDateRange,
            onDismiss = { showDateDialog = false },
            onConfirm = { from, to ->
                showDateDialog = false
                pendingDateRange = DateRangePreset.Custom(from, to)
                onDateRangeChange(DateRangePreset.Custom(from, to))
            },
        )
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    categories: List<CategoryEntity>,
    selectedCategoryId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = selectedCategoryId?.let { id -> categories.firstOrNull { it.id == id }?.name }
        ?: stringResource(R.string.filter_category_all)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayText,
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
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_category_all)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            categories.sortedBy { it.name.lowercase() }.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.name) },
                    onClick = {
                        onSelect(cat.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDropdown(
    selected: DateRangePreset,
    onPresetSelected: (DateRangePreset) -> Unit,
    onCustomRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = when (selected) {
        DateRangePreset.Any -> stringResource(R.string.filter_date_any)
        DateRangePreset.Last7Days -> stringResource(R.string.filter_date_last_7_days)
        DateRangePreset.Last30Days -> stringResource(R.string.filter_date_last_30_days)
        DateRangePreset.ThisMonth -> stringResource(R.string.filter_date_this_month)
        DateRangePreset.ThisYear -> stringResource(R.string.filter_date_this_year)
        is DateRangePreset.Custom -> formatCustomRange(selected.fromMs, selected.toMsExclusive)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayText,
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
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_any)) },
                onClick = { onPresetSelected(DateRangePreset.Any); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_last_7_days)) },
                onClick = { onPresetSelected(DateRangePreset.Last7Days); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_last_30_days)) },
                onClick = { onPresetSelected(DateRangePreset.Last30Days); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_this_month)) },
                onClick = { onPresetSelected(DateRangePreset.ThisMonth); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_this_year)) },
                onClick = { onPresetSelected(DateRangePreset.ThisYear); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_date_custom)) },
                onClick = { expanded = false; onCustomRequested() },
            )
        }
    }
}

private fun formatCustomRange(fromMs: Long, toMsExclusive: Long): String {
    val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
    return "${fmt.format(Date(fromMs))} – ${fmt.format(Date(toMsExclusive - 86_400_000L))}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(
    initialRange: DateRangePreset,
    onDismiss: () -> Unit,
    onConfirm: (fromMs: Long, toMsExclusive: Long) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (initialRange as? DateRangePreset.Custom)?.fromMs,
        initialSelectedEndDateMillis = (initialRange as? DateRangePreset.Custom)?.toMsExclusive?.minus(86_400_000L),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val from = state.selectedStartDateMillis
                    val end = state.selectedEndDateMillis
                    if (from != null && end != null) {
                        onConfirm(from, end + 86_400_000L)
                    } else {
                        onDismiss()
                    }
                },
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        text = {
            DateRangePicker(state = state, modifier = Modifier.heightIn(min = 200.dp, max = 600.dp))
        },
    )
}

@Composable
private fun EmptyState(isFiltered: Boolean, onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isFiltered) {
            Text(
                text = stringResource(R.string.filter_no_matches_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.filter_no_matches_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.filter_clear))
            }
        } else {
            Text(
                text = stringResource(R.string.home_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: 159/159 pass (125 prior + 34 new from Task 1).

- [ ] **Step 5: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt \
  app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Transactions: search field, filter chips, and dropdowns on Transactions tab"
```

---

## Task 5: Final verification (assembleDebug + full test pass)

**Files:** none (read-only verification).

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. APK written to `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: 159/159 pass, 0 failures, 0 errors.

- [ ] **Step 3: Sanity-check git state**

Run: `git log --oneline v0.6.0..HEAD`
Expected: 4 commits (one per implementation task: Task 1, 2, 3, 4) plus any spec/plan doc commits that landed.

- [ ] **Step 4: Report**

Report: build pass, test pass, commit count, and any smoke-test notes from the implementer. The on-device smoke test (type in search, see filter applied, pick a category, pick a date preset, clear filters, restart the app and see filters restored) is described in the final review checklist and exercised in the Phase 2.7 end-to-end code review.

---

## Self-review notes (already applied)

- **Spec coverage:** Every spec section maps to a task. Task 1 covers the 34 unit tests for `filterTransactions`. Task 2 covers the SharedPreferences persistence. Task 3 covers the VM wiring. Task 4 covers the UI (4 filter controls, 2 empty states, 14 strings, date-range picker dialog).
- **Placeholder scan:** No "TBD" or "implement later" anywhere. All code is complete.
- **Type consistency:** `DateRangePreset` (sealed interface with 6 cases), `TypeFilter` (3-value enum), `TransactionFilters(searchQuery, categoryId, typeFilter, dateRange)`, `filterTransactions(rows, filters, allCategories, nowMs)` — all consistent across Tasks 1, 2, 3, 4. The `FiltersRepository` companions (`PREFS_NAME`, `KEY_*`, `CATEGORY_ID_ALL`) are consistent. The `HomeViewModel` setters (`setFilters`, `setSearchQuery`, `setCategoryFilter`, `setTypeFilter`, `setDateRange`, `clearFilters`) are consistent.
- **String-resource warning:** All 14 new strings are added in Task 4 Step 1, before any UI code references them. No incremental `R.string.filter_*` surprises (unlike the Phase 2.6 trend polish, where strings were added incrementally across tasks).
- **DateRangePicker conversion:** The Material 3 picker returns `[startMillis, endMillis]` (inclusive). The filter expects `[fromMs, toExclusive)`. The dialog adds 1 day (86_400_000 ms) to the end before calling `onConfirm`, then the filter function auto-swaps if needed. The custom range display formats the bounds back as inclusive dates for the user.

## Out of scope (intentional, deferred)

- Amount range filter (min/max).
- Multi-select categories.
- Saved filters.
- Filter from Home dashboard.
- Recurring-only filter.
- Search highlighting.
- Sort options.
- "Recent" filter memory.
- Filter pill on the Home tab badge.
