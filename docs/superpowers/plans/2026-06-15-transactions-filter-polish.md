# Phase 2.9 — Transactions Filter Polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two focused polish features to the Transactions search/filter surface (v0.7.0): search highlighting in the result list and an amount range (min/max) filter.

**Architecture:** Extend the existing `TransactionFilters` data class with two new fields (min/max amount in home-currency minor units). Extend the pure `filterTransactions` helper with two new params (home currency + FX rates) so the amount range can be compared in the home currency. Add a pure `highlightMatches` helper that produces an `AnnotatedString` from a string and a query. Add two `OutlinedTextField`s to the Transactions screen, and add a `searchQuery` parameter to the existing `TransactionRow` (in `TransactionComponents.kt`).

**Tech Stack:** Kotlin, Jetpack Compose (`AnnotatedString` + `SpanStyle` + `buildAnnotatedString`), Material 3, Hilt, JUnit 4.

**Working directory:** `F:/AndroidApp/ExpenseTracker`

**Required env (Windows):** `JAVA_HOME=C:/tools/jdk-21.0.5+11` (AGP 8.13.2 + bundled Kotlin choke on Java 8 and on Java 25+). Run gradle as:
```bash
export JAVA_HOME="C:/tools/jdk-21.0.5+11" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew <task>
```

**Commit identity:** All commits use inline author (no Co-Authored-By trailer):
```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "..."
```

---

## Task 1: Pure data layer — extend `TransactionFilters` + `filterTransactions` + add `highlightMatches` (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt`

This task extends the `TransactionFilters` data class with `minAmount` and `maxAmount` fields, extends the pure `filterTransactions` helper with two new params (`homeCurrency`, `fxRates`) for FX normalization, and adds a new pure `highlightMatches` helper. 10 new JUnit tests.

- [ ] **Step 1: Append 10 new tests to `FiltersTest.kt`**

Open `app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt` and add these 10 tests just before the closing `}` of the class (right after the `filterTransactions_preservesInputOrder` test):

```kotlin
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
        )
        // min > max → swap to (max, min) → range is $50 .. $10 = (10, 50) effectively.
        // So this is equivalent to the prior test, but we pass inverted values.
        val out = filterTransactions(
            rows,
            TransactionFilters(minAmount = 10_000L, maxAmount = 500L),
            categories(),
            date(2026, 6, 15),
        )
        assertEquals(listOf(rows[1]), out)
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
        // 100 EUR = €100.00 → in home (USD) = 100 / 1.5 * 100 = $66.67.
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
        val fxRates = mapOf("EUR_to_USD" to 1.5)
        val out = filterTransactions(
            rows,
            TransactionFilters(minAmount = 5_000L, maxAmount = 8_000L),  // $50 .. $80
            categories(),
            date(2026, 6, 15),
            homeCurrency = "USD",
            fxRates = fxRates,
        )
        assertEquals(1, out.size, "EUR (~$66.67) should fall inside the 5000..8000 cents USD range")
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
```


- [ ] **Step 2: Run tests to verify they fail (function/type mismatch)**

Run: `./gradlew testDebugUnitTest --tests "*FiltersTest"`
Expected: 10/10 new tests fail to compile (missing `minAmount`/`maxAmount` fields on `TransactionFilters`, missing `homeCurrency`/`fxRates` params on `filterTransactions`, missing `highlightMatches` function).

- [ ] **Step 3: Extend `Filters.kt`**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt` and make 3 changes:

**(a)** Add 4 new imports at the top (after the existing `import io.github.jiro.expensetracker.data.local.TransactionWithCategory` line):

```kotlin
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.github.jiro.expensetracker.domain.FxConverter
```

(Note: `FxConverter` may already be imported. Check first — skip the duplicate.)

**(b)** Replace the `TransactionFilters` data class with this version (adds 2 new fields and updates `isEmpty`):

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

**(c)** Replace the `filterTransactions` function with this version (adds 2 new params, adds the amount range filter step):

```kotlin
fun filterTransactions(
    rows: List<TransactionWithCategory>,
    filters: TransactionFilters,
    allCategories: List<CategoryEntity>,
    nowMs: Long,
    homeCurrency: String = "USD",
    fxRates: Map<String, Double> = emptyMap(),
): List<TransactionWithCategory> {
    val trimmedQuery = filters.searchQuery.trim()
    val hasQuery = trimmedQuery.isNotEmpty()
    val categoryNameById = allCategories.associate { it.id to it.name }
    val (rangeFrom, rangeToExclusive) = resolveDateRange(filters.dateRange, nowMs)
    val (minAmount, maxAmount) = resolveAmountRange(filters.minAmount, filters.maxAmount)

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

        // Amount range (in home currency).
        if (minAmount != null || maxAmount != null) {
            val homeMinor = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
                ?: t.amountMinor
            if (minAmount != null && homeMinor < minAmount) return@filter false
            if (maxAmount != null && homeMinor > maxAmount) return@filter false
        }

        true
    }
}

private fun resolveAmountRange(
    minAmount: Long?,
    maxAmount: Long?,
): Pair<Long?, Long?> = when {
    minAmount == null && maxAmount == null -> null to null
    minAmount == null -> null to maxAmount
    maxAmount == null -> minAmount to null
    minAmount <= maxAmount -> minAmount to maxAmount
    else -> maxAmount to minAmount  // auto-swap
}
```

**(d)** Add the new `highlightMatches` helper at the end of the file (after the `startOfYear` helper):

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
): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        while (cursor < text.length) {
            val hit = lowerText.indexOf(lowerQuery, cursor)
            if (hit < 0) {
                append(text.substring(cursor))
                break
            }
            if (hit > cursor) append(text.substring(cursor, hit))
            withStyle(highlightStyle) { append(text.substring(hit, hit + query.length)) }
            cursor = hit + query.length
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*FiltersTest"`
Expected: 44/44 pass (34 prior + 10 new).

- [ ] **Step 5: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/transactions/Filters.kt \
  app/src/test/java/io/github/jiro/expensetracker/ui/transactions/FiltersTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Transactions: amount range filter + search highlight helper (10 tests)"
```

---

## Task 2: `FiltersRepository` — add 2 new keys for min/max

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/FiltersRepository.kt`

This task adds SharedPreferences persistence for the new `minAmount` and `maxAmount` fields. The sentinel for "absent" is `Long.MIN_VALUE` (the txn's minor-unit amount will never be that extreme — `Long.MIN_VALUE` cents is ~-$92 quadrillion).

- [ ] **Step 1: Open `FiltersRepository.kt` and apply 5 changes**

**(a)** Update the `TransactionFilters` reconstruction in `loadFilters()` to include the 2 new fields. Find the `TransactionFilters(...)` constructor call inside `loadFilters()` and add 2 args at the end:

```kotlin
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
```

**(b)** Update `setFilters` to write the 2 new keys. Find the `prefs.edit() { ... }` block and add 2 lines:

```kotlin
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
```

**(c)** Add 2 new constants to the `companion object` (after the existing `KEY_DATE_RANGE` line):

```kotlin
        const val KEY_FILTER_MIN_AMOUNT = "filters.minAmount"
        const val KEY_FILTER_MAX_AMOUNT = "filters.maxAmount"
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Nothing else uses the new fields yet.)

- [ ] **Step 3: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/transactions/FiltersRepository.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Transactions: persist min/max amount filters"
```

---

## Task 3: `HomeViewModel` — pass `homeCurrency` + `fxRates` to `filterTransactions`

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt`

The `filteredTransactions` flow currently uses 3-arg `combine` (rows, filters, categories). To support the amount range filter, it needs to be 5-arg `combine` (rows, filters, categories, homeCurrency, fxRates) and pass those last two to the new `filterTransactions` signature.

- [ ] **Step 1: Update the `filteredTransactions` flow in `HomeViewModel.kt`**

Find the `filteredTransactions` flow (currently uses a 3-arg `combine` producing a `Triple`) and replace it with this 5-arg `combine` version:

```kotlin
    /** All transactions filtered by the current [filters]. */
    val filteredTransactions: StateFlow<List<TransactionWithCategory>> =
        combine(
            repository.observeAll(),
            filters,
            allCategories,
            settingsRepository.homeCurrency,
            settingsRepository.fxRates,
        ) { rows, f, cats, home, rates ->
            filterTransactions(
                rows = rows,
                filters = f,
                allCategories = cats,
                nowMs = System.currentTimeMillis(),
                homeCurrency = home,
                fxRates = rates,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
```

- [ ] **Step 2: Add 2 new setters for the amount range**

Add these 2 new methods to the bottom of the `HomeViewModel` class (after the existing `clearFilters` method):

```kotlin
    fun setMinAmount(minor: Long?) = setFilters(filters.value.copy(minAmount = minor))
    fun setMaxAmount(minor: Long?) = setFilters(filters.value.copy(maxAmount = minor))
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. The new fields are persisted via `FiltersRepository` (Task 2) and consumed by the filter helper (Task 1), so the VM is self-consistent.

- [ ] **Step 4: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: 44/44 pass.

- [ ] **Step 5: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Home: filteredTransactions uses home currency + FX for amount range"
```

---

## Task 4: UI — `TransactionRow` highlight + `TransactionsScreen` amount text fields + 4 new strings

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

This task adds:
- A `searchQuery: String?` parameter to `TransactionRow` (the row that renders title + amount + category + note). When non-null and non-blank, the title and note are rendered via `highlightMatches`.
- The `SwipeableTransactionRow` wrapper passes the query through.
- The `TransactionsScreen` adds 2 amount text fields (Min, Max) with debounce, and passes `filters.searchQuery` to the row.
- 4 new strings.

- [ ] **Step 1: Add 4 new strings to `strings.xml`**

Open `app/src/main/res/values/strings.xml` and add these lines at the end (after the existing Phase 2.7 filter strings):

```xml
    <string name="filter_amount_min">Min</string>
    <string name="filter_amount_max">Max</string>
    <string name="filter_amount_min_hint">Min amount</string>
    <string name="filter_amount_max_hint">Max amount</string>
```

- [ ] **Step 2: Modify `TransactionComponents.kt` to add `searchQuery` highlighting**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt` and make 3 changes:

**(a)** Add 4 new imports (after the existing `import io.github.jiro.expensetracker.ui.theme.IncomeGreen` line):

```kotlin
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.jiro.expensetracker.ui.transactions.highlightMatches
```

**(b)** Replace the `TransactionRow` composable with this version (adds `searchQuery: String?` and uses `highlightMatches` for title + note):

```kotlin
@Composable
internal fun TransactionRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String? = null,
) {
    val txn = row.transaction
    val category = row.category
    val type = TransactionType.fromStorage(txn.type)
    val sign = if (type == TransactionType.EXPENSE) "-" else "+"
    val amountColor = if (type == TransactionType.EXPENSE) {
        MaterialTheme.colorScheme.error
    } else {
        IncomeGreen
    }
    val trimmed = searchQuery?.trim().orEmpty()
    val highlightStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        CategoryIconBadge(name = category.name, size = 40)
        Spacer(Modifier.padding(start = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = highlightMatches(txn.title, trimmed, highlightStyle),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (txn.recurringGroupId != null) {
                    Icon(
                        imageVector = Icons.Filled.Autorenew,
                        contentDescription = stringResource(R.string.recurring_indicator),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = "${category.name} · ${txn.currencyCode} " +
                    "$sign${txn.amountMinor / 100}.${"%02d".format(txn.amountMinor % 100)}",
                style = MaterialTheme.typography.bodySmall,
                color = amountColor,
            )
            if (!txn.note.isNullOrBlank()) {
                Text(
                    text = highlightMatches(txn.note, trimmed, highlightStyle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

**(c)** Update the `SwipeableTransactionRow` composable to take and pass through the `searchQuery`:

```kotlin
@Composable
internal fun SwipeableTransactionRow(
    row: TransactionWithCategory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    searchQuery: String? = null,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { DeleteBackground(dismissState.dismissDirection) },
    ) {
        TransactionRow(row = row, onClick = onEdit, searchQuery = searchQuery)
    }
}
```

- [ ] **Step 3: Modify `TransactionsScreen.kt` to add the amount text fields and pass `searchQuery` to the row**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt` and make 4 changes:

**(a)** Add 3 new imports (after the existing `import io.github.jiro.expensetracker.ui.charts.MonthlyTrend` line — or wherever `MoneyFormat` is imported):

```kotlin
import androidx.compose.foundation.layout.Arrangement
import io.github.jiro.expensetracker.data.local.MoneyFormat
import kotlinx.coroutines.delay
```

(Note: `Arrangement` may already be imported. Skip the duplicate.)

**(b)** Add 2 `mutableStateOf` declarations for the local min/max text input (right after the existing `var searchInput by remember { mutableStateOf(filters.searchQuery) }`):

```kotlin
    var minInput by remember { mutableStateOf(filters.minAmount?.toString() ?: "") }
    var maxInput by remember { mutableStateOf(filters.maxAmount?.toString() ?: "") }
```

**(c)** Add 2 debounce `LaunchedEffect`s (right after the existing search debounce `LaunchedEffect(searchInput)`):

```kotlin
    LaunchedEffect(minInput) {
        delay(300)
        val parsed = if (minInput.isBlank()) null else MoneyFormat.parseAmountToMinor(minInput)
        if (parsed != filters.minAmount) viewModel.setMinAmount(parsed)
    }
    LaunchedEffect(maxInput) {
        delay(300)
        val parsed = if (maxInput.isBlank()) null else MoneyFormat.parseAmountToMinor(maxInput)
        if (parsed != filters.maxAmount) viewModel.setMaxAmount(parsed)
    }

    // Sync the local inputs when the repo's filters change externally.
    LaunchedEffect(filters.minAmount) {
        val expected = filters.minAmount?.toString() ?: ""
        if (expected != minInput) minInput = expected
    }
    LaunchedEffect(filters.maxAmount) {
        val expected = filters.maxAmount?.toString() ?: ""
        if (expected != maxInput) maxInput = expected
    }
```

**(d)** In the `FilterControls` private composable, add a new `Row` with 2 `OutlinedTextField`s for Min and Max. Place this new `Row` right after the existing `Row` with the type chips (BEFORE the existing category/date dropdown `Row`). The relevant block of `FilterControls` should look like this (only the new `Row` is added — the rest is unchanged):

```kotlin
        // ... existing type chips Row ...

        // NEW: amount range row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = minInput,
                onValueChange = { minInput = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.filter_amount_min)) },
                placeholder = { Text(stringResource(R.string.filter_amount_min_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = maxInput,
                onValueChange = { maxInput = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.filter_amount_max)) },
                placeholder = { Text(stringResource(R.string.filter_amount_max_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }

        // ... existing category/date dropdown Row ...
```

Note: the new `Row` uses `minInput` and `maxInput` — these are passed in as parameters to `FilterControls`. You need to update the `FilterControls` signature to accept them. Find the existing `FilterControls` composable signature and add the 2 new params:

```kotlin
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
    minInput: String,                   // NEW
    onMinInputChange: (String) -> Unit, // NEW
    maxInput: String,                   // NEW
    onMaxInputChange: (String) -> Unit, // NEW
) {
    // ... existing body ...
```

Then in the call site (the `TransactionsScreen` composable where `FilterControls(...)` is called), pass the new params:

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
                minInput = minInput,                        // NEW
                onMinInputChange = { minInput = it },        // NEW
                maxInput = maxInput,                        // NEW
                onMaxInputChange = { maxInput = it },        // NEW
            )
```

**(e)** Pass `searchQuery` to each `SwipeableTransactionRow` call in the `LazyColumn`. Find the call (inside the `LazyColumn { grouped.forEach { group -> ... } }` block) and add the param:

```kotlin
                            items(group.items, key = { it.transaction.id }) { row ->
                                SwipeableTransactionRow(
                                    row = row,
                                    onEdit = { onTransactionClick(row.transaction.id) },
                                    onDelete = { viewModel.delete(row) },
                                    searchQuery = filters.searchQuery,  // NEW
                                )
                            }
```

- [ ] **Step 4: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: 44/44 pass.

- [ ] **Step 6: Commit**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" add \
  app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt \
  app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt \
  app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "UI: search highlight + amount range fields on Transactions tab"
```

---

## Task 5: Final verification (assembleDebug + full test pass)

**Files:** none (read-only verification).

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. APK written to `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: 44/44 pass in `FiltersTest`; full suite total grows by 10 (44 + 134 prior from v0.8.0 = 178 expected total, but the test count includes all suites). Use the test-results XML tally:
```bash
cd "F:/AndroidApp/ExpenseTracker" && echo "TOTAL: $(find app/build/test-results/testDebugUnitTest -name '*.xml' 2>/dev/null | xargs -I {} grep -ho 'tests=\"[0-9]*\"' {} 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END {print s}')"; echo "FAILURES: $(find app/build/test-results/testDebugUnitTest -name '*.xml' 2>/dev/null | xargs -I {} grep -ho 'failures=\"[0-9]*\"' {} 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END {print s}')"; echo "ERRORS: $(find app/build/test-results/testDebugUnitTest -name '*.xml' 2>/dev/null | xargs -I {} grep -ho 'errors=\"[0-9]*\"' {} 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END {print s}')"
```

Expected: 188/188 pass, 0 failures, 0 errors (178 prior from v0.8.0 + 10 new = 188).

- [ ] **Step 3: Sanity-check git state**

Run: `git log --oneline v0.8.0..HEAD`
Expected: 4 implementation commits (one per task: Task 1, 2, 3, 4) plus the 1 doc commit (spec) that landed before Task 1.

- [ ] **Step 4: Report**

Report: build pass, test pass, commit count, and any smoke-test notes from the implementer. The on-device smoke test (type a search query, see highlighted title; enter "10" in Min, see only transactions >= $10; clear amount fields, see all transactions) is described in the final review checklist.

---

## Self-review notes (already applied)

- **Spec coverage:** Every spec section maps to a task. Task 1 covers the 7 amount range tests + 3 highlight tests. Task 2 covers SharedPreferences persistence. Task 3 covers the VM wiring (5-arg combine + 2 new setters). Task 4 covers the UI (TransactionRow searchQuery, TransactionsScreen 2 amount fields, 4 new strings).
- **Placeholder scan:** No "TBD" or "implement later" anywhere. All code is complete.
- **Type consistency:** `TransactionFilters(searchQuery, categoryId, typeFilter, dateRange, minAmount, maxAmount)` (6 fields, 2 new). `filterTransactions(rows, filters, allCategories, nowMs, homeCurrency, fxRates)` (6 params, 2 new with defaults). `highlightMatches(text, query, highlightStyle): AnnotatedString` (new). `FiltersRepository.KEY_FILTER_MIN_AMOUNT` / `KEY_FILTER_MAX_AMOUNT` (new). `HomeViewModel.filteredTransactions` is now 5-arg `combine`. All consistent across Tasks 1, 2, 3, 4.
- **Cumulative string-resource warning:** All 4 new strings are added in Task 4 Step 1, before any UI code references them.
- **Param ordering for `filterTransactions`:** The new params `homeCurrency` and `fxRates` come AFTER `nowMs` (the existing time-anchor param) and BEFORE the next time-anchor-ish param would go. The 34 prior tests don't pass these (they use the defaults `"USD"` + `emptyMap()`), so they continue to work without modification.

## Out of scope (intentional, deferred)

- Multi-select categories.
- Saved filters.
- Filter from Home dashboard.
- Recurring-only filter.
- Sort options.
- Search highlighting in category name or amount.
- Highlighting the row background instead of the text.
- Range slider for amount.
- Currency selector for amount range.
- The `MoneyFormat.formatAmountForEdit` thousands-separators search fix (search "1,200" still won't match $1,200.00).
