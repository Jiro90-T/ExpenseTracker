# Account Detail Date Display + Add Transaction Pre-fill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the transaction date and (when different) the added date on each row of the Account Detail screen, and add a FAB that navigates to the existing Add Transaction screen with the account pre-filled.

**Architecture:** Add an opt-in `showTransactionDate: Boolean = false` parameter to `TransactionRow` (default keeps existing behavior). Extend the `ADD_EDIT` route to accept an optional `accountId` query argument. Have `AddEditTransactionViewModel` read that argument and seed `selectedAccountId` in add mode only. Add an `ExtendedFloatingActionButton` to `AccountDetailScreen` that calls the new helper.

**Tech Stack:** Jetpack Compose, Hilt, Navigation Compose, `SavedStateHandle`, `StateFlow`.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/res/values/strings.xml` | Add `account_add_transaction`, `transaction_date_on` |
| `app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt` | `TransactionRow` gains `showTransactionDate`; both `StandardRow` and `TransferRow` get the two-line date branch |
| `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt` | Add `Routes.ADD_EDIT_WITH_ACCOUNT` + `Routes.ADD_EDIT_ARG_ACCOUNT_ID`; add `addEditRoute(accountId)` helper overload; add optional `accountId` `navArgument` to the `ADD_EDIT` composable; wire `onAddTransaction` in the `ACCOUNT_DETAIL` composable |
| `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt` | New `onAddTransaction: (Long) -> Unit` callback; pass `showTransactionDate = true` to the row; add `floatingActionButton` slot with `ExtendedFloatingActionButton` (hidden when account null or archived) |
| `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt` | Read `accountId` from `SavedStateHandle`; seed `selectedAccountId` in the initial state when `transactionId == null` |
| `app/src/test/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModelTest.kt` (new) | One test: `init_addModeWithAccountIdArg_seedsSelectedAccountId` |

`DAY_MS` (`24L * 60L * 60L * 1000L`) is already defined at the bottom of `TransactionComponents.kt:445` — reuse it for the day-difference comparison.

---

### Task 1: Add two new strings to strings.xml

**Files:**
- Modify: `app/src/main/res/values/strings.xml:391`

- [ ] **Step 1: Add the strings**

Open `app/src/main/res/values/strings.xml`. After line 391 (`<string name="transaction_added_on">Added %1$s</string>`), add:

```xml
    <string name="account_add_transaction">Add transaction</string>
    <string name="transaction_date_on">Date %1$s</string>
```

(Indent with 4 spaces to match the surrounding entries.)

- [ ] **Step 2: Verify the build still compiles**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(strings): add account_add_transaction + transaction_date_on"
```

---

### Task 2: TransactionRow — opt-in showTransactionDate

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt:69-96, 99-173, 175-227`

The change is Compose-only; no unit test exists for `TransactionRow` in this codebase. Verification is `compileDebugKotlin` + the manual smoke in Task 6.

- [ ] **Step 1: Add the param to TransactionRow and pass it down**

In `TransactionComponents.kt`, find the `TransactionRow` function (line 69). Replace:

```kotlin
@Composable
internal fun TransactionRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String? = null,
) {
    val txn = row.transaction
    val type = TransactionType.fromStorage(txn.type)
    val trimmed = searchQuery?.trim().orEmpty()
    val highlightStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )

    when (type) {
        TransactionType.TRANSFER -> TransferRow(
            row = row,
            onClick = onClick,
            searchQuery = trimmed,
            highlightStyle = highlightStyle,
        )
        else -> StandardRow(
            row = row,
            onClick = onClick,
            searchQuery = trimmed,
            highlightStyle = highlightStyle,
        )
    }
}
```

with:

```kotlin
@Composable
internal fun TransactionRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String? = null,
    showTransactionDate: Boolean = false,
) {
    val txn = row.transaction
    val type = TransactionType.fromStorage(txn.type)
    val trimmed = searchQuery?.trim().orEmpty()
    val highlightStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )

    when (type) {
        TransactionType.TRANSFER -> TransferRow(
            row = row,
            onClick = onClick,
            searchQuery = trimmed,
            highlightStyle = highlightStyle,
            showTransactionDate = showTransactionDate,
        )
        else -> StandardRow(
            row = row,
            onClick = onClick,
            searchQuery = trimmed,
            highlightStyle = highlightStyle,
            showTransactionDate = showTransactionDate,
        )
    }
}
```

- [ ] **Step 2: Replace StandardRow with the date-aware variant**

Find `private fun StandardRow(` (line 99) and replace the entire function body through its closing `}` (line 173) with:

```kotlin
@Composable
private fun StandardRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String,
    highlightStyle: SpanStyle,
    showTransactionDate: Boolean = false,
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
    val displayCategoryName = category?.name ?: stringResource(R.string.type_transfer)
    val ctx = LocalContext.current
    val txnDateText = remember(txn.occurredAtEpochMillis) {
        DateUtils.formatDateTime(
            ctx,
            txn.occurredAtEpochMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
    }
    val addedDateText = remember(txn.createdAtEpochMillis) {
        if (txn.createdAtEpochMillis == 0L) null else {
            DateUtils.formatDateTime(
                ctx,
                txn.createdAtEpochMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
            )
        }
    }
    val showAddedDate = addedDateText != null &&
        (txn.createdAtEpochMillis - txn.occurredAtEpochMillis) > DAY_MS
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        CategoryIconBadge(name = displayCategoryName, size = 40)
        Spacer(Modifier.padding(start = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = highlightMatches(txn.title, searchQuery, highlightStyle),
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
            if (showTransactionDate) {
                Text(
                    text = stringResource(R.string.transaction_date_on, txnDateText),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (showAddedDate) {
                    Text(
                        text = stringResource(R.string.transaction_added_on, addedDateText!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (addedDateText != null) {
                Text(
                    text = stringResource(R.string.transaction_added_on, addedDateText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "$displayCategoryName · ${txn.currencyCode} " +
                    "$sign${txn.amountMinor / 100}.${"%02d".format(txn.amountMinor % 100)}",
                style = MaterialTheme.typography.bodySmall,
                color = amountColor,
            )
            if (!txn.note.isNullOrBlank()) {
                Text(
                    text = highlightMatches(txn.note, searchQuery, highlightStyle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Replace TransferRow with the date-aware variant**

Find `private fun TransferRow(` (line 175) and replace the entire function body through its closing `}` (line 227) with:

```kotlin
@Composable
private fun TransferRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String,
    highlightStyle: SpanStyle,
    showTransactionDate: Boolean = false,
) {
    val txn = row.transaction
    val amountText = "${txn.amountMinor / 100}.${"%02d".format(txn.amountMinor % 100)} ${txn.currencyCode}"
    val destLabel = txn.transferAccountId?.let { "acct#$it" } ?: "—"
    val ctx = LocalContext.current
    val txnDateText = remember(txn.occurredAtEpochMillis) {
        DateUtils.formatDateTime(
            ctx,
            txn.occurredAtEpochMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
    }
    val addedDateText = remember(txn.createdAtEpochMillis) {
        if (txn.createdAtEpochMillis == 0L) null else {
            DateUtils.formatDateTime(
                ctx,
                txn.createdAtEpochMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
            )
        }
    }
    val showAddedDate = addedDateText != null &&
        (txn.createdAtEpochMillis - txn.occurredAtEpochMillis) > DAY_MS
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        CategoryIconBadge(name = "↔", size = 40)
        Spacer(Modifier.padding(start = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(txn.title, searchQuery, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
            )
            if (showTransactionDate) {
                Text(
                    text = stringResource(R.string.transaction_date_on, txnDateText),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (showAddedDate) {
                    Text(
                        text = stringResource(R.string.transaction_added_on, addedDateText!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (addedDateText != null) {
                Text(
                    text = stringResource(R.string.transaction_added_on, addedDateText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // TODO(Phase 2.16+): extend TransactionWithCategory to embed the
            // destination account entity for TRANSFER rows so we can render the
            // account name instead of the id.
            Text(
                text = "→ $destLabel · $amountText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 4: Verify build**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt
git commit -m "feat(home): TransactionRow opt-in showTransactionDate for two-line date display"
```

---

### Task 3: AppNav.kt — Routes, helper overload, navArgument, onAddTransaction wiring

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt:52-91, 189-212, 252-263`

- [ ] **Step 1: Extend the Routes object**

In `AppNav.kt`, find the `Routes` object (line 52). After line 57 (`const val ADD_EDIT_NO_ID = "add_edit"`), add:

```kotlin
    const val ADD_EDIT_WITH_ACCOUNT = "add_edit?accountId={accountId}"
    const val ADD_EDIT_ARG_ACCOUNT_ID = "accountId"
```

- [ ] **Step 2: Add the addEditRoute(accountId) helper overload**

Find the existing `addEditRoute` helper (line 93). After that function's closing `}`, add:

```kotlin
fun addEditRoute(accountId: Long): String =
    "add_edit?accountId=$accountId"
```

- [ ] **Step 3: Add the optional accountId navArgument to the ADD_EDIT composable**

Find the `composable(route = Routes.ADD_EDIT, ...)` block (line 189). Replace:

```kotlin
            composable(
                route = Routes.ADD_EDIT,
                arguments = listOf(
                    navArgument(Routes.ADD_EDIT_ARG_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) {
```

with:

```kotlin
            composable(
                route = Routes.ADD_EDIT,
                arguments = listOf(
                    navArgument(Routes.ADD_EDIT_ARG_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument(Routes.ADD_EDIT_ARG_ACCOUNT_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) {
```

- [ ] **Step 4: Wire onAddTransaction in the ACCOUNT_DETAIL composable**

Find the `composable(route = Routes.ACCOUNT_DETAIL, ...)` block (line 252). Replace:

```kotlin
            composable(
                route = Routes.ACCOUNT_DETAIL,
                arguments = listOf(
                    navArgument(Routes.ACCOUNT_DETAIL_ARG_ID) { type = NavType.LongType },
                ),
            ) {
                AccountDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEditAccount = { id -> navController.navigate("account_edit/$id") },
                    onTransactionClick = { txnId -> navController.navigate(addEditRoute(txnId)) },
                )
            }
```

with:

```kotlin
            composable(
                route = Routes.ACCOUNT_DETAIL,
                arguments = listOf(
                    navArgument(Routes.ACCOUNT_DETAIL_ARG_ID) { type = NavType.LongType },
                ),
            ) {
                AccountDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEditAccount = { id -> navController.navigate("account_edit/$id") },
                    onTransactionClick = { txnId -> navController.navigate(addEditRoute(txnId)) },
                    onAddTransaction = { accountId -> navController.navigate(addEditRoute(accountId)) },
                )
            }
```

- [ ] **Step 5: Verify build**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt
git commit -m "feat(nav): ADD_EDIT route accepts accountId arg + AccountDetailScreen FAB wiring"
```

---

### Task 4: AccountDetailScreen — pass flag, add FAB, accept callback

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt:52-57, 14-19, 84-150, 20-31, 39-42`

The screen needs three new imports (`ExtendedFloatingActionButton`, `Icons.Filled.Add`), one new callback parameter, one prop-pass in the `TransactionRow` call, and a `floatingActionButton` slot on the `Scaffold`.

- [ ] **Step 1: Add imports**

Find the import block (lines 14-19). After `import androidx.compose.material.icons.filled.Edit`, add:

```kotlin
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
```

- [ ] **Step 2: Add the onAddTransaction parameter and pass showTransactionDate = true**

Find the `AccountDetailScreen` function signature (line 52). Replace:

```kotlin
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
```

with:

```kotlin
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onTransactionClick: (Long) -> Unit,
    onAddTransaction: (accountId: Long) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
```

Find the `TransactionRow` call (line 148). Replace:

```kotlin
            items(state.transactions, key = { it.transaction.id }) { row ->
                TransactionRow(row = row, onClick = { onTransactionClick(row.transaction.id) })
            }
```

with:

```kotlin
            items(state.transactions, key = { it.transaction.id }) { row ->
                TransactionRow(
                    row = row,
                    onClick = { onTransactionClick(row.transaction.id) },
                    showTransactionDate = true,
                )
            }
```

- [ ] **Step 3: Add the FAB to the Scaffold**

Find the `Scaffold(` call (line 84). Replace:

```kotlin
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
```

with:

```kotlin
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (aw != null && !aw.account.archived) {
                ExtendedFloatingActionButton(
                    onClick = { onAddTransaction(aw.account.id) },
                    text = { Text(stringResource(R.string.account_add_transaction)) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
        },
        topBar = {
```

- [ ] **Step 4: Verify build**

Run:
```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt
git commit -m "feat(accounts): AccountDetailScreen shows both dates + Add-tx FAB pre-fills account"
```

---

### Task 5: AddEditTransactionViewModel — read presetAccountId (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt:113-124`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModelTest.kt`

The VM is an `AndroidViewModel`. Tests need a fake `Application` and the same set of constructor fakes used by `AddReceiptViewModelTest` (which already exists at `app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt`). Those fakes (`NoopApplication`, `FakeTransactionRepo`, `FakeCategoryRepo`, `FakeReceiptRepo`, `FakeOcrProcessor`, `FakeSettingsRepository`) are top-level and `public` — the new test file imports them. Only `FakeAccountRepo` from `add_receipt` is unsuitable because its underlying `StubAccountDao` returns an empty list, so the account-clamping collector would null-out the preset; the test must use a custom `AccountRepository` whose `observeActive()` returns the preset id.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModelTest.kt` with this content:

```kotlin
package io.github.jiro.expensetracker.ui.add_edit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow
import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.ui.add_receipt.FakeCategoryRepo
import io.github.jiro.expensetracker.ui.add_receipt.FakeOcrProcessor
import io.github.jiro.expensetracker.ui.add_receipt.FakeReceiptRepo
import io.github.jiro.expensetracker.ui.add_receipt.FakeSettingsRepository
import io.github.jiro.expensetracker.ui.add_receipt.FakeTransactionRepo
import io.github.jiro.expensetracker.ui.add_receipt.NoopApplication
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.sync.TransactionMutationBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditTransactionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun accountEntity(id: Long): AccountEntity = AccountEntity(
        id = id,
        name = "Test",
        type = "CASH",
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = "USD",
        openingBalanceMinor = 0L,
        createdAtEpochMillis = 0L,
    )

    private fun buildVm(
        accountIdArg: Long? = null,
        transactionIdArg: Long? = null,
        accounts: List<AccountEntity> = emptyList(),
    ): AddEditTransactionViewModel {
        val savedState = SavedStateHandle()
        if (accountIdArg != null) savedState["accountId"] = accountIdArg
        if (transactionIdArg != null) savedState["id"] = transactionIdArg
        return AddEditTransactionViewModel(
            application = NoopApplication(),
            savedStateHandle = savedState,
            transactionRepository = FakeTransactionRepo(),
            categoryRepository = FakeCategoryRepo(),
            accountRepository = FakeAccountRepository(accounts),
            receiptRepository = FakeReceiptRepo(),
            receiptOcrProcessor = FakeOcrProcessor(OcrFields(null, null, null)),
            settingsRepository = FakeSettingsRepository("USD"),
            transactionMutationBus = TransactionMutationBus(),
        )
    }

    @Test
    fun init_addModeWithAccountIdArg_seedsSelectedAccountId() = runTest(testDispatcher) {
        val vm = buildVm(accountIdArg = 7L, accounts = listOf(accountEntity(7L)))
        advanceUntilIdle()
        assertEquals(7L, vm.state.value.selectedAccountId)
    }
}

// ---- custom fakes (only what's needed for the preset-account-id test) ----

private class FakeAccountRepository(accounts: List<AccountEntity>) : AccountRepository(
    dao = StubAccountDao(accounts),
    holdingDao = NoopInvestmentHoldingDao,
)

private object NoopInvestmentHoldingDao : InvestmentHoldingDao {
    override suspend fun insert(row: InvestmentHoldingEntity): Long = 0L
    override suspend fun update(row: InvestmentHoldingEntity) = Unit
    override suspend fun delete(id: Long) = Unit
    override fun observeByAccount(accountId: Long): Flow<List<InvestmentHoldingEntity>> =
        MutableStateFlow<List<InvestmentHoldingEntity>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long): InvestmentHoldingEntity? = null
    override suspend fun countByAccount(accountId: Long): Int = 0
}

private class StubAccountDao(private val accounts: List<AccountEntity>) : AccountDao {
    override fun observeActive(): Flow<List<AccountEntity>> =
        MutableStateFlow(accounts).asStateFlow()
    override suspend fun listActiveOnce(): List<AccountEntity> = accounts
    override suspend fun findById(id: Long): AccountEntity? = accounts.find { it.id == id }
    override suspend fun findActiveDefault(): AccountEntity? = accounts.minByOrNull { it.id }
    override suspend fun insert(account: AccountEntity): Long = 0L
    override suspend fun insertAllReplacing(accounts: List<AccountEntity>): List<Long> = emptyList()
    override suspend fun update(account: AccountEntity): Int = 0
    override suspend fun delete(id: Long): Int = 0
    override suspend fun deleteAll(): Int = 0
    override suspend fun updateDefaultCurrency(code: String): Int = 0
    override suspend fun countActive(): Int = accounts.size
    override fun observeBalances(): Flow<List<AccountBalanceRow>> =
        MutableStateFlow(accounts.map { AccountBalanceRow(it.id, 0L) }).asStateFlow()
    override fun observeAllBalances(): Flow<List<AccountBalanceRow>> =
        MutableStateFlow(accounts.map { AccountBalanceRow(it.id, 0L) }).asStateFlow()
    override fun observeAllEntities(): Flow<List<AccountEntity>> =
        MutableStateFlow(accounts).asStateFlow()
    override suspend fun listAllOnce(): List<AccountEntity> = accounts
    override suspend fun close(id: Long, now: Long) = Unit
    override suspend fun reopen(id: Long) = Unit
    override suspend fun maxSortOrder(): Int = accounts.maxOfOrNull { it.sortOrder } ?: 0
    override suspend fun updateOpeningBalanceByName(name: String, balance: Long): Int = 0
    override suspend fun applyAccountImport(
        rows: List<ResolvedImportRow>,
        nowEpochMs: Long,
    ) = Unit
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.add_edit.AddEditTransactionViewModelTest.init_addModeWithAccountIdArg_seedsSelectedAccountId"
```

Expected: FAIL with `expected:<7L> but was:<null>` — the VM currently ignores the `accountId` nav arg and `selectedAccountId` stays null.

- [ ] **Step 3: Implement the preset-account-id read in the VM**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt`. Find the `transactionId` declaration and the `_state` initializer (lines 113-124). Replace:

```kotlin
    private val transactionId: Long? = savedStateHandle
        .get<Long>("id")
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(
        AddEditTransactionUiState(
            id = transactionId,
            // Default new transactions to the user's home currency. Editing
            // an existing row overrides this with its own currencyCode below.
            currency = if (transactionId == null) settingsRepository.homeCurrency.value else "USD",
        )
    )
    val state: StateFlow<AddEditTransactionUiState> = _state.asStateFlow()
```

with:

```kotlin
    private val transactionId: Long? = savedStateHandle
        .get<Long>("id")
        ?.takeIf { it >= 0 }

    /**
     * Optional preset from the `accountId` nav arg (set when navigating from
     * AccountDetailScreen's FAB). In add mode only — edit mode pre-fills from
     * the existing row in the if-branch below and ignores this.
     */
    private val presetAccountId: Long? = savedStateHandle
        .get<Long>("accountId")
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(
        AddEditTransactionUiState(
            id = transactionId,
            selectedAccountId = if (transactionId == null) presetAccountId else null,
            // Default new transactions to the user's home currency. Editing
            // an existing row overrides this with its own currencyCode below.
            currency = if (transactionId == null) settingsRepository.homeCurrency.value else "USD",
        )
    )
    val state: StateFlow<AddEditTransactionUiState> = _state.asStateFlow()
```

The existing collector on `accountRepository.observeActive()` already clamps `selectedAccountId` to a valid id (or null if the preset was deleted). No other change needed — the edit-mode prefill in the `if (transactionId != null)` block at line 165 still sets `selectedAccountId = existing.accountId`, overriding the preset.

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.add_edit.AddEditTransactionViewModelTest.init_addModeWithAccountIdArg_seedsSelectedAccountId"
```

Expected: PASS.

- [ ] **Step 5: Run the full unit test suite to confirm nothing regressed**

Run:
```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS — every existing test still green plus the new one.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModelTest.kt
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt
git commit -m "feat(add-edit): VM seeds selectedAccountId from accountId nav arg in add mode"
```

---

### Task 6: Manual smoke test (verification gate)

**Files:** none

Per the spec's verification approach and the project's pattern, run the app on an emulator/device and verify the end-to-end behavior.

- [ ] **Step 1: Build and install the debug APK**

Run:
```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL, app installs.

- [ ] **Step 2: Launch the app, navigate to an account detail**

Open the app. From Home (or More → Manage accounts), tap an account to open `AccountDetailScreen`. Confirm:
- Each transaction row now shows **two lines** under the title: a "Date …" line (medium weight) on top, and (only when the added date is more than a day after the transaction date) an "Added …" line in small muted text below it.
- Transactions added today with the same transaction date show only the "Date …" line (no "Added" line).

- [ ] **Step 3: Verify the FAB navigates with the account pre-filled**

Tap the **"Add transaction"** FAB on the Account Detail screen. Confirm:
- The Add screen opens.
- The Account dropdown is pre-selected to the account you were viewing (not blank, not "default").

- [ ] **Step 4: Verify the back-dated transaction renders both dates**

From the Add screen, fill in a transaction (or use an existing one), set the date to 5 days ago via the date picker, save. Return to Account Detail. Confirm the row for that transaction shows **both** "Date …" and "Added …" lines (the latter is today, more than a day after the back-dated transaction).

- [ ] **Step 5: Verify the bottom-bar "+" still works without a preset**

Back out of Account Detail. Tap the "+" button in the bottom navigation bar. Confirm:
- The Add screen opens with **no** account pre-selected (Account dropdown shows the placeholder/empty state) — this is the existing baseline behavior, unchanged.

- [ ] **Step 6: Verify the FAB is hidden on archived accounts**

Find an account you can close (or use an existing archived one). Open its detail page. Confirm:
- The "Add transaction" FAB is **not visible** (the page still shows the balance header, transactions, and the existing edit/delete/close/reopen icons in the top bar).

- [ ] **Step 7: Commit the verification**

If you discovered and fixed any issue during the smoke test, commit that fix separately before reporting completion. (Per the project rule: no Co-Authored-By trailer in commit messages.)

```bash
git add <files-fixed-during-smoke>
git commit -m "fix(accounts): <describe the smoke-found issue>"
```

(No commit if the smoke was clean — Tasks 1-5 are already on master.)

---

## Notes for Implementers

- **No Co-Authored-By trailer** in any commit message. The user has corrected this twice.
- **Direct-to-master**: this project ships by pushing commits to `master` and tagging a release. No feature branches or PRs.
- **Verify R.string names before committing**: this plan introduces `account_add_transaction` and `transaction_date_on`. They are added in Task 1 and referenced in Tasks 2 and 4 — do not commit Task 2 or 4 before Task 1 lands.
- **JDK 21 required**: the Gradle build will fail on JDK 25. Use JDK 21.
- **`DAY_MS`** is already a `private const val` at the bottom of `TransactionComponents.kt` — reuse it for the day-difference comparison; do not redefine.
