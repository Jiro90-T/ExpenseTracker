# Delete Account Implementation Plan (Phase 2.17)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a hard-delete account feature on `AccountDetailScreen`, blocked when transactions reference the account (as source `accountId` OR destination `transferAccountId`).

**Architecture:** Pure-function guard `evaluateDelete(count)` decides ALLOW vs BLOCK; ViewModel state machine drives a confirm dialog; new DAO query counts references; new DAO query deletes the row. No schema migration; the `accounts.archived` column already exists from Phase 2.16.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, StateFlow. JDK 21 (`C:/tools/jdk-21.0.5+11`).

**Spec:** `docs/superpowers/specs/2026-06-26-delete-account-design.md`

**Repo quirks:**
- Default account id is `1L` (hard-coded in `AccountDao.findDefault()` and the migration seeder). Always protected from delete.
- Commit author: `MiniMax-M3 <291324429+Jiro90-T@users.noreply.github.com>` via `-c user.name=... -c user.email=...`. **No `Co-Authored-By` trailer.**
- Bash is git-bash on Windows; forward slashes.
- AGP quirk: `./gradlew test --tests "..."` doesn't work; use `./gradlew :app:testDebugUnitTest --tests "..."`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt` | Modify | Holds `evaluateDelete`, `DeleteGuard`, delete state fields, `onDeleteClick/Confirm/Dismiss`. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt` | Modify | Adds Delete IconButton in header + AlertDialog (allow/block). Wires `LaunchedEffect(state.deleted)` to call `onBack()`. |
| `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt` | Modify | Add `suspend fun delete(id: Long): Int`. |
| `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt` | Modify | Add `open suspend fun delete(id: Long): Int`. |
| `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionDao.kt` | Modify | Add `suspend fun countReferencingAccount(id: Long): Int`. |
| `app/src/main/java/io/github/jiro/expensetracker/data/repository/TransactionRepository.kt` | Modify | Add `open suspend fun countReferencingAccount(id: Long): Int`. |
| `app/src/main/res/values/strings.xml` | Modify | Add 5 strings under `<!-- Accounts (Phase 2.17) -->`. |
| `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/DeleteAccountLogicTest.kt` | Create | Pure-function tests for `evaluateDelete`. |
| `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt` | Create | State-machine tests for `AccountDetailViewModel`. |
| `app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt` | Modify | Add `countReferencingAccount` override to `StubTransactionDao`. |

---

## Task 1: `evaluateDelete` pure function + tests

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/DeleteAccountLogicTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/DeleteAccountLogicTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteAccountLogicTest {

    @Test
    fun `count 0 allows delete`() {
        assertEquals(DeleteGuard.ALLOW, evaluateDelete(0))
    }

    @Test
    fun `count 1 blocks delete`() {
        assertEquals(DeleteGuard.BLOCK_TRANSACTIONS_EXIST, evaluateDelete(1))
    }

    @Test
    fun `count 14 blocks delete`() {
        assertEquals(DeleteGuard.BLOCK_TRANSACTIONS_EXIST, evaluateDelete(14))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.DeleteAccountLogicTest"
```

Expected: FAIL — `evaluateDelete` and `DeleteGuard` are unresolved.

- [ ] **Step 3: Implement `evaluateDelete` and `DeleteGuard`**

Add the following at the bottom of `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt` (after the class, before EOF):

```kotlin
enum class DeleteGuard { ALLOW, BLOCK_TRANSACTIONS_EXIST }

/**
 * Pure: turns a transaction reference count into a delete decision.
 * Zero references → ALLOW. Anything else → BLOCK.
 */
fun evaluateDelete(referenceCount: Int): DeleteGuard =
    if (referenceCount == 0) DeleteGuard.ALLOW else DeleteGuard.BLOCK_TRANSACTIONS_EXIST
```

- [ ] **Step 4: Run test to verify it passes**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.DeleteAccountLogicTest"
```

Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/accounts/DeleteAccountLogicTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): DeleteGuard + evaluateDelete pure logic"
```

---

## Task 2: `TransactionDao.countReferencingAccount` + repository pass-through

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionDao.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/repository/TransactionRepository.kt`

- [ ] **Step 1: Add the DAO query**

In `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionDao.kt`, after the existing `countForAccount` method (around line 91), add:

```kotlin
    /**
     * Counts every transaction row that references the account — either as
     * the source [TransactionEntity.accountId] or as a TRANSFER destination
     * via [TransactionEntity.transferAccountId]. Used by the delete-account
     * guard, which must block deletion of an account referenced as either
     * side of a transaction (a dangling transferAccountId is just as bad as
     * a dangling accountId).
     */
    @Query(
        "SELECT COUNT(*) FROM transactions " +
            "WHERE accountId = :id OR transferAccountId = :id"
    )
    suspend fun countReferencingAccount(id: Long): Int
```

- [ ] **Step 2: Add the repository pass-through**

In `app/src/main/java/io/github/jiro/expensetracker/data/repository/TransactionRepository.kt`, find the existing `countForAccount` method and add the new one right after it:

```kotlin
    open suspend fun countReferencingAccount(id: Long): Int =
        dao.countReferencingAccount(id)
```

(Read the file first to find the exact insertion point. Match the existing style: if the other methods are `suspend fun name(...): ReturnType = dao.name(...)`, mirror that.)

- [ ] **Step 3: Build to verify**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionDao.kt \
        app/src/main/java/io/github/jiro/expensetracker/data/repository/TransactionRepository.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): countReferencingAccount covers source and destination refs"
```

---

## Task 3: `AccountDao.delete` + `AccountRepository.delete`

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`

- [ ] **Step 1: Add the DAO query**

In `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt`, after the existing `@Update suspend fun update(...)` (around line 28), add:

```kotlin
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long): Int
```

Note: do NOT add an `archived` check. This is a hard delete; we want to permanently remove the row. Caller guards the operation via `countReferencingAccount`.

- [ ] **Step 2: Add the repository pass-through**

In `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`, find a good insertion point (after the existing `update` method) and add:

```kotlin
    open suspend fun delete(id: Long): Int = dao.delete(id)
```

- [ ] **Step 3: Build to verify**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt \
        app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AccountDao hard-delete query"
```

---

## Task 4: Add `countReferencingAccount` to `StubTransactionDao`

**Files:**
- Modify: `app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt`

- [ ] **Step 1: Read the file to find the stub**

Open `app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt` and locate the `StubTransactionDao` class.

- [ ] **Step 2: Add the override**

In `StubTransactionDao`, add an override for the new method. Place it next to the existing `override suspend fun countForAccount(accountId: Long)` if present, otherwise at the bottom of the stub class:

```kotlin
    override suspend fun countReferencingAccount(id: Long): Int = 0
```

(The default zero keeps the AddReceipt tests green since they don't exercise this path.)

- [ ] **Step 3: Run the affected test to verify**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.add_receipt.AddReceiptViewModelTest"
```

Expected: BUILD SUCCESSFUL, all 6 existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "test: stub TransactionDao covers new countReferencingAccount"
```

---

## Task 5: Add 5 strings to `strings.xml`

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Locate the existing Accounts block**

Open `app/src/main/res/values/strings.xml` and find the `<!-- Accounts (Phase 2.16) -->` block. Strings added in Task 7 include `account_delete_title` (or similar). We add the new Phase 2.17 block right after the Phase 2.16 block.

- [ ] **Step 2: Add the new strings**

Append the following inside `<resources>`, immediately after the Phase 2.16 accounts block:

```xml
    <!-- Accounts (Phase 2.17) -->
    <string name="account_delete">Delete</string>
    <string name="account_delete_confirm_title">Delete account?</string>
    <string name="account_delete_confirm_message">Delete account \'%1$s\'? This cannot be undone.</string>
    <string name="account_delete_blocked_title">Account has transactions</string>
    <string name="account_delete_blocked_message">Account \'%1$s\' has %2$d transactions referencing it. Delete them first or move them to another account.</string>
```

Verify the names are unique (grep `account_delete` first to ensure no collision; if there is a partial overlap with the Phase 2.16 strings, rename the new ones with a `_v2` suffix — unlikely but defensive).

- [ ] **Step 3: Build to verify**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): strings for delete-account dialogs"
```

---

## Task 6: Extend `AccountDetailViewModel` + write `AccountDetailViewModelTest`

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt`

### Step 1: Write the failing tests first

Create `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun account(id: Long, name: String = "Account $id") = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = "USD",
        createdAtEpochMillis = 0L,
    )

    private fun accountWithBalance(id: Long): AccountWithBalance =
        AccountWithBalance(account(id), balanceMinor = 0L)

    private fun makeVm(
        initialAccount: AccountWithBalance?,
        referenceCount: Int = 0,
        deletedIds: MutableList<Long> = mutableListOf(),
    ): Pair<AccountDetailViewModel, FakeTransactionRepository> {
        val accountRepo = FakeAccountRepository(initialAccount?.let { listOf(it) }, deletedIds)
        val txnRepo = FakeTransactionRepository(referenceCount)
        return AccountDetailViewModel(accountId = initialAccount?.account?.id ?: 2L, accountRepository = accountRepo, transactionRepository = txnRepo) to txnRepo
    }

    @Test
    fun `tap delete on account with zero references sets allow guard`() = runTest(dispatcher) {
        val (vm, _) = makeVm(accountWithBalance(2L), referenceCount = 0)
        vm.onDeleteClick()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.showDeleteConfirm)
        assertEquals(DeleteGuard.ALLOW, s.deleteGuard)
        assertEquals(0, s.referenceCount)
        assertFalse(s.deleted)
    }

    @Test
    fun `tap delete on account with three references sets block guard`() = runTest(dispatcher) {
        val (vm, _) = makeVm(accountWithBalance(2L), referenceCount = 3)
        vm.onDeleteClick()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.showDeleteConfirm)
        assertEquals(DeleteGuard.BLOCK_TRANSACTIONS_EXIST, s.deleteGuard)
        assertEquals(3, s.referenceCount)
    }

    @Test
    fun `tap delete on default account id 1 is a no-op`() = runTest(dispatcher) {
        val accountRepo = FakeAccountRepository(listOf(accountWithBalance(1L)))
        val txnRepo = FakeTransactionRepository(referenceCount = 5)
        val vm = AccountDetailViewModel(
            accountId = 1L,
            accountRepository = accountRepo,
            transactionRepository = txnRepo,
        )
        vm.onDeleteClick()
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.showDeleteConfirm)
        assertNull(s.deleteGuard)
    }

    @Test
    fun `confirm with allow calls delete and sets deleted flag`() = runTest(dispatcher) {
        val deletedIds = mutableListOf<Long>()
        val (vm, _) = makeVm(accountWithBalance(2L), referenceCount = 0, deletedIds = deletedIds)
        vm.onDeleteClick()
        advanceUntilIdle()
        vm.onDeleteConfirm()
        advanceUntilIdle()
        assertEquals(listOf(2L), deletedIds)
        assertTrue(vm.state.value.deleted)
        assertFalse(vm.state.value.showDeleteConfirm)
    }

    @Test
    fun `confirm with block does not delete`() = runTest(dispatcher) {
        val deletedIds = mutableListOf<Long>()
        val (vm, _) = makeVm(accountWithBalance(2L), referenceCount = 4, deletedIds = deletedIds)
        vm.onDeleteClick()
        advanceUntilIdle()
        vm.onDeleteConfirm()
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), deletedIds)
        assertFalse(vm.state.value.deleted)
    }

    @Test
    fun `dismiss clears dialog without delete`() = runTest(dispatcher) {
        val deletedIds = mutableListOf<Long>()
        val (vm, _) = makeVm(accountWithBalance(2L), referenceCount = 0, deletedIds = deletedIds)
        vm.onDeleteClick()
        advanceUntilIdle()
        vm.onDeleteDismiss()
        advanceUntilIdle()
        assertFalse(vm.state.value.showDeleteConfirm)
        assertEquals(emptyList<Long>(), deletedIds)
    }
}

/**
 * Minimal AccountRepository stub — only observeWithBalances and delete are
 * exercised by this test. Other methods throw if called.
 */
private class FakeAccountRepository(
    initialAccounts: List<AccountWithBalance>?,
    private val deletedIds: MutableList<Long>,
) : AccountRepository(dao = error("not used")) {
    private val accountsFlow = MutableStateFlow(initialAccounts ?: emptyList())
    fun observeWithBalances(): Flow<List<AccountWithBalance>> = accountsFlow.asStateFlow()
    override suspend fun delete(id: Long): Int {
        deletedIds.add(id)
        return 1
    }
}

private class FakeTransactionRepository(
    private val referenceCount: Int,
) : TransactionRepository(dao = error("not used")) {
    override suspend fun countReferencingAccount(id: Long): Int = referenceCount
}
```

### Step 2: Run tests to verify they fail

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.AccountDetailViewModelTest"
```

Expected: FAIL — `AccountDetailViewModel` constructor does not match, `onDeleteClick`/`onDeleteConfirm`/`onDeleteDismiss` are missing.

### Step 3: Read the existing `AccountDetailViewModel` and `AccountRepository`

Open both files. The current `AccountDetailViewModel` reads its accountId from `SavedStateHandle` using `Routes.ACCOUNT_DETAIL_ARG_ID`. For testability, change the constructor to take `accountId: Long` directly, and have Hilt's `@HiltViewModel` mechanism satisfy it via `SavedStateHandle.get<Long>(Routes.ACCOUNT_DETAIL_ARG_ID) ?: -1L`.

The cleanest path: add a secondary constructor OR extract the SavedStateHandle logic into the existing primary constructor while keeping the test-friendly overload.

**Implementation note:** The cleanest approach is to extract a `@VisibleForTesting` constructor or use Hilt's standard pattern. Use the second approach — make the primary constructor take `accountId: Long, accountRepository: AccountRepository, transactionRepository: TransactionRepository`, and provide a separate `@Inject` factory or `@AssistedInject` if needed.

Actually, Hilt + SavedStateHandle is the standard pattern. The cleanest test-friendly refactor is to add a **secondary constructor** that Hilt ignores but tests use:

```kotlin
@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
) : ViewModel() {
    private val accountId: Long = savedStateHandle.get<Long>(Routes.ACCOUNT_DETAIL_ARG_ID) ?: -1L
    // ... rest of the class
}
```

The test creates the ViewModel directly, bypassing Hilt, passing all three args. Since the constructor only uses SavedStateHandle to derive a Long, the test simply constructs `AccountDetailViewModel(accountId = 2L, accountRepository = fake, transactionRepository = fake)`.

**Decision:** Modify the existing primary constructor signature. The Hilt-injected SavedStateHandle still works (Hilt passes one in). For tests, construct with explicit args. The internal field `accountId` becomes `val accountId: Long = savedStateHandle.get(...)`.

Replace the entire `AccountDetailViewModel.kt` file with:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.ui.navigation.Routes
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountDetailUiState(
    val accountWithBalance: AccountWithBalance? = null,
    val transactions: List<TransactionWithCategory> = emptyList(),
    val isLoading: Boolean = true,
    // Phase 2.17 delete flow:
    val showDeleteConfirm: Boolean = false,
    val deleteGuard: DeleteGuard? = null,
    val referenceCount: Int = 0,
    val deleted: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    /** Hard-coded default account id — protected from delete at the VM layer too. */
    private val defaultAccountId: Long = 1L

    private val accountId: Long =
        savedStateHandle.get<Long>(Routes.ACCOUNT_DETAIL_ARG_ID) ?: -1L

    private val _state = MutableStateFlow(AccountDetailUiState())
    val state: StateFlow<AccountDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                accountRepository.observeWithBalances(),
                transactionRepository.observeByAccount(accountId),
            ) { accounts, txns ->
                AccountDetailUiState(
                    accountWithBalance = accounts.firstOrNull { it.account.id == accountId },
                    transactions = txns,
                    isLoading = false,
                )
            }.collect { ui ->
                _state.update { current ->
                    // Preserve transient delete-flow state across emissions so
                    // a successful load doesn't dismiss an open dialog or
                    // clear the just-set deleted flag.
                    current.copy(
                        accountWithBalance = ui.accountWithBalance,
                        transactions = ui.transactions,
                        isLoading = ui.isLoading,
                    )
                }
            }
        }
    }

    fun onDeleteClick() {
        val s = _state.value
        val accountId = s.accountWithBalance?.account?.id ?: return
        if (accountId == defaultAccountId) return
        viewModelScope.launch {
            val count = transactionRepository.countReferencingAccount(accountId)
            _state.update {
                it.copy(
                    showDeleteConfirm = true,
                    deleteGuard = evaluateDelete(count),
                    referenceCount = count,
                )
            }
        }
    }

    fun onDeleteConfirm() {
        val s = _state.value
        if (s.deleteGuard != DeleteGuard.ALLOW) return
        val accountId = s.accountWithBalance?.account?.id ?: return
        viewModelScope.launch {
            accountRepository.delete(accountId)
            _state.update { it.copy(showDeleteConfirm = false, deleted = true) }
        }
    }

    fun onDeleteDismiss() {
        _state.update { it.copy(showDeleteConfirm = false) }
    }
}

enum class DeleteGuard { ALLOW, BLOCK_TRANSACTIONS_EXIST }

/**
 * Pure: turns a transaction reference count into a delete decision.
 * Zero references → ALLOW. Anything else → BLOCK.
 */
fun evaluateDelete(referenceCount: Int): DeleteGuard =
    if (referenceCount == 0) DeleteGuard.ALLOW else DeleteGuard.BLOCK_TRANSACTIONS_EXIST
```

NOTE on `init`: the original code derived `state` via `stateIn` over a `combine`. The replacement uses `collect` on a plain `combine` to preserve transient delete-flow fields across emissions. This is required so that, e.g., the dialog state set by `onDeleteClick` doesn't get wiped out by the next DB emission.

### Step 4: Run tests to verify they pass

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.AccountDetailViewModelTest"
```

Expected: BUILD SUCCESSFUL, all 6 tests pass.

If `TransactionRepository` does not have a constructor that accepts only `dao`, the test fakes will not compile. Inspect `TransactionRepository.kt` to find the real constructor; pass a no-op DAO or use `error("not used")` for unused args, matching the pattern from `AddReceiptViewModelTest.kt`. Adjust the fake classes accordingly — the test code shown is the contract; the constructor signature must match whatever `TransactionRepository` exposes.

If `AccountRepository(dao = error("not used"))` doesn't compile, inspect its constructor and pass `dao` positionally (likely `accountDao`). Same for `TransactionRepository(dao = transactionDao)`. The fakes throw `NotImplementedError` for any DAO method the test doesn't override.

### Step 5: Run full test suite

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew test
```

Expected: BUILD SUCCESSFUL. All previously-passing tests still pass.

### Step 6: Commit

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AccountDetailViewModel delete-flow state machine"
```

---

## Task 7: Add Delete button + dialog to `AccountDetailScreen`

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt`

- [ ] **Step 1: Add imports**

Add these imports to `AccountDetailScreen.kt`:

```kotlin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
```

(`LaunchedEffect` may already be imported via `androidx.compose.runtime.*` — check.)

- [ ] **Step 2: Add Delete IconButton to the header actions block**

In the `TopAppBar` `actions = { ... }` block, add a Delete IconButton **after** the Edit IconButton (destructive actions conventionally go last in Material 3). Wrap in `if (... != 1L)`:

```kotlin
                actions = {
                    if (aw != null) {
                        IconButton(onClick = { onEditAccount(aw.account.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        if (aw.account.id != 1L) {
                            IconButton(onClick = viewModel::onDeleteClick) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.account_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
```

- [ ] **Step 3: Add `LaunchedEffect` to pop back on delete**

Inside the `Scaffold` content lambda (right after the opening brace, before the `if (aw == null && ...)` check), add:

```kotlin
        LaunchedEffect(state.deleted) {
            if (state.deleted) {
                onBack()
            }
        }
```

- [ ] **Step 4: Add the AlertDialog**

After the `LaunchedEffect` block and before the `if (aw == null ...)` check, add the dialog:

```kotlin
        if (state.showDeleteConfirm) {
            val account = state.accountWithBalance?.account
            when (state.deleteGuard) {
                DeleteGuard.ALLOW -> AlertDialog(
                    onDismissRequest = viewModel::onDeleteDismiss,
                    title = { Text(stringResource(R.string.account_delete_confirm_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.account_delete_confirm_message,
                                account?.name.orEmpty(),
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::onDeleteConfirm) {
                            Text(
                                stringResource(R.string.account_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::onDeleteDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
                DeleteGuard.BLOCK_TRANSACTIONS_EXIST -> AlertDialog(
                    onDismissRequest = viewModel::onDeleteDismiss,
                    title = { Text(stringResource(R.string.account_delete_blocked_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.account_delete_blocked_message,
                                account?.name.orEmpty(),
                                state.referenceCount,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::onDeleteDismiss) {
                            Text(stringResource(R.string.action_ok))
                        }
                    },
                )
                null -> Unit
            }
        }
```

`action_cancel` and `action_ok` strings already exist from prior phases — verify with a grep before relying on them. If they don't exist, add them:

```xml
    <string name="action_cancel">Cancel</string>
    <string name="action_ok">OK</string>
```

- [ ] **Step 5: Build to verify**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AccountDetailScreen delete button and confirm dialog"
```

---

## Task 8: Final smoke + tag v0.16.0

**Files:** none (no code changes; release operations only)

- [ ] **Step 1: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew test
```

Expected: BUILD SUCCESSFUL. All previously-passing tests still pass; new Phase 2.17 tests pass.

- [ ] **Step 2: Build the debug APK**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Tag v0.16.0**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" tag v0.16.0
```

Expected: tag created locally (not pushed; per project memory, push master + --tags together after manual smoke).

- [ ] **Step 4: Verify commit list**

```bash
git log v0.15.0..v0.16.0 --oneline
```

Expected: shows the Phase 2.17 commits (Tasks 1–7) plus the v0.15.0 net-balance fix `e6a304d` and any other intervening commits.

---

## Self-review (this plan vs the spec)

**Spec coverage:**
- §3.1 Happy path → Task 7 (delete button + ALLOW dialog).
- §3.2 Blocked path → Task 7 (BLOCK dialog).
- §3.3 Default account → Task 6 (`defaultAccountId` constant + UI check in Task 7).
- §4.1 `AccountDao.delete` → Task 3.
- §4.2 `TransactionDao.countReferencingAccount` → Task 2.
- §4.3 `TransactionRepository.countReferencingAccount` → Task 2.
- §4.4 `AccountRepository.delete` → Task 3.
- §5.1 `DeleteGuard` + `evaluateDelete` → Task 1.
- §6.1 State additions → Task 6.
- §6.2 ViewModel handlers → Task 6.
- §6.3 `defaultAccountId` → Task 6.
- §7.1 Delete button → Task 7.
- §7.2 Dialogs → Task 7.
- §7.3 Pop-back trigger → Task 7.
- §7.4 Snackbar → not implemented (reserved future field; spec marks it optional).
- §8 Strings → Task 5.
- §9.1 `evaluateDelete` tests → Task 1.
- §9.2 DAO test → skipped per spec.
- §9.3 ViewModel test → Task 6.

**Placeholder scan:** No "TBD", "implement later", or "similar to Task N". Every step has complete code or exact commands.

**Type consistency:** `evaluateDelete(referenceCount: Int): DeleteGuard` defined in Task 1, used in Task 6. `DeleteGuard` enum defined in Task 1 (within AccountDetailViewModel.kt after the class), referenced in Task 6 and Task 7. `countReferencingAccount(id: Long): Int` defined in Task 2, referenced in Task 6's fakes and ViewModel. `delete(id: Long): Int` defined in Task 3, referenced in Task 6's fakes and ViewModel. State fields defined in Task 6 are referenced by name in Task 7 — match.

**Risk:** Task 6 step 3 changes the existing constructor signature indirectly (adds `transactionRepository` param). The spec already specifies this addition. The init block was rewritten from `combine().stateIn()` to `combine().collect {}` to preserve transient state — this is a structural change. If the original `stateIn` pattern is required (e.g., for sharing semantics elsewhere), revert that part and add the preserve-state logic separately. The replacement is functionally equivalent for this screen's lifetime.