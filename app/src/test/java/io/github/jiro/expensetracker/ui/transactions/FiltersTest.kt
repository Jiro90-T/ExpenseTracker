package io.github.jiro.expensetracker.ui.transactions

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import androidx.compose.ui.text.SpanStyle
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

    // ---- search amount: thousands-separator normalization (Phase 2.11 polish) ----

    @Test
    fun filterTransactions_searchAmountMatchesWithComma() {
        val rows = listOf(
            txn(3L, "C", 120_000L, "EXPENSE", 1L, date(2026, 6, 14), null),   // $1,200.00
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "1,200"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchAmountMatchesWithSpace() {
        val rows = listOf(
            txn(3L, "C", 120_000L, "EXPENSE", 1L, date(2026, 6, 14), null),   // $1,200.00
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "1 200"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_searchAmountMatchesNoSeparators_regressionGuard() {
        val rows = listOf(
            txn(3L, "C", 120_000L, "EXPENSE", 1L, date(2026, 6, 14), null),   // $1,200.00
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "1200"),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
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
            txn(1L, "Alpha", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(2L, "Beta", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(3L, "Gamma", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(4L, "Delta", 100L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        // Filter excludes Beta and Gamma.
        val out = filterTransactions(
            rows,
            TransactionFilters(searchQuery = "l"),  // matches Alpha and Delta only (case-insensitive)
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0], rows[3]), out)
    }

    // ---- amount range ----

    @Test
    fun filterTransactions_amountRangeEmpty_isNoOp() {
        val rows = listOf(
            txn(1L, "A", 1_000L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(2L, "B", 5_000L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(),  // both minAmount and maxAmount are null
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(rows, out)
    }

    @Test
    fun filterTransactions_amountRangeOnlyMin_filtersHigher() {
        val rows = listOf(
            txn(1L, "Cheap", 500L, "EXPENSE", 1L, date(2026, 6, 14), null),    // $5
            txn(2L, "Mid", 3_000L, "EXPENSE", 1L, date(2026, 6, 14), null),     // $30
            txn(3L, "Pricey", 20_000L, "EXPENSE", 1L, date(2026, 6, 14), null),  // $200
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(minAmount = 10_000L),  // min $100
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[2]), out)
    }

    @Test
    fun filterTransactions_amountRangeOnlyMax_filtersLower() {
        val rows = listOf(
            txn(1L, "Cheap", 500L, "EXPENSE", 1L, date(2026, 6, 14), null),    // $5
            txn(2L, "Mid", 3_000L, "EXPENSE", 1L, date(2026, 6, 14), null),     // $30
            txn(3L, "Pricey", 20_000L, "EXPENSE", 1L, date(2026, 6, 14), null),  // $200
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(maxAmount = 5_000L),  // max $50
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0], rows[1]), out)
    }

    @Test
    fun filterTransactions_amountRangeBoth_filtersBetween() {
        val rows = listOf(
            txn(1L, "Cheap", 500L, "EXPENSE", 1L, date(2026, 6, 14), null),    // $5
            txn(2L, "Mid", 3_000L, "EXPENSE", 1L, date(2026, 6, 14), null),     // $30
            txn(3L, "Pricey", 20_000L, "EXPENSE", 1L, date(2026, 6, 14), null),  // $200
        )
        val out = filterTransactions(
            rows,
            TransactionFilters(minAmount = 1_000L, maxAmount = 10_000L),  // $10 .. $100
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[1]), out)
    }

    @Test
    fun filterTransactions_amountRangeInverted_swapped() {
        val rows = listOf(
            txn(1L, "Cheap", 500L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(2L, "Mid", 3_000L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(3L, "Pricey", 20_000L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        // min > max → swap to (max, min) → range is $5 .. $100 (500..10000 cents).
        // Cheap=500 and Mid=3000 both fall in the swapped window; Pricey=20000 is out.
        val out = filterTransactions(
            rows,
            TransactionFilters(minAmount = 10_000L, maxAmount = 500L),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0], rows[1]), out)
    }

    @Test
    fun filterTransactions_amountRangeEqual_singleValueWindow() {
        val rows = listOf(
            txn(1L, "Exact", 3_000L, "EXPENSE", 1L, date(2026, 6, 14), null),
            txn(2L, "Other", 4_000L, "EXPENSE", 1L, date(2026, 6, 14), null),
        )
        // min == max == $30 → only the exact $30 amount passes.
        val out = filterTransactions(
            rows,
            TransactionFilters(minAmount = 3_000L, maxAmount = 3_000L),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[0]), out)
    }

    @Test
    fun filterTransactions_amountRangeWithFxNormalized_usesHomeCurrency() {
        // USD home, EUR transaction. Rate: 1 EUR = 1.5 USD.
        // 100 EUR = €100.00 → in home (USD) = 100 * 0.6667 = $66.67.
        // We use min = $50 (5000 cents) and max = $80 (8000 cents).
        // So the EUR tx ($66.67 equivalent) should pass.
        val eur = TransactionEntity(
            id = 1L,
            title = "EUR",
            amountMinor = 10_000L,
            currencyCode = "EUR",
            type = "EXPENSE",
            categoryId = 1L,
            occurredAtEpochMillis = date(2026, 6, 14),
            note = null,
            createdAtEpochMillis = date(2026, 6, 14),
        )
        val cat = categories().first { it.id == 1L }
        val rows = listOf(TransactionWithCategory(eur, cat))
        val fxRates = mapOf("EUR_to_USD" to 0.6667)
        val out = filterTransactions(
            rows,
            TransactionFilters(minAmount = 5_000L, maxAmount = 8_000L),  // $50 .. $80
            categories(),
            date(2026, 6, 15),
            homeCurrency = "USD",
            fxRates = fxRates,
        )
        assertEquals("EUR (~$66.67) should fall inside the 5000..8000 cents USD range", 1, out.size)
    }

    @Test
    fun highlightMatches_emptyQuery_returnsUnstyledText() {
        val out = highlightMatches("Hello world", "", SpanStyle())
        assertEquals("Hello world", out.text)
        assertEquals(0, out.spanStyles.size)
    }

    @Test
    fun highlightMatches_queryMatches_substringWrappedInStyle() {
        val style = SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        val out = highlightMatches("foobar baz", "foo", style)
        assertEquals("foobar baz", out.text)
        assertEquals(1, out.spanStyles.size)
        val range = out.spanStyles[0]
        assertEquals(0, range.start)
        assertEquals(3, range.end)
    }

    @Test
    fun highlightMatches_queryCaseInsensitive() {
        val style = SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        val out = highlightMatches("Foobar", "FOO", style)
        assertEquals(1, out.spanStyles.size)
        val range = out.spanStyles[0]
        assertEquals(0, range.start)
        assertEquals(3, range.end)
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
