# Delete Account Design (Phase 2.17)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:writing-plans then superpowers:subagent-driven-development to turn this spec into tasks and execute task-by-task. Steps in this doc use checkbox (`- [ ]`) syntax for the high-level outline; the per-task plan lives in a sibling `plans/` file.

**Goal:** let the user hard-delete an account from `AccountDetailScreen`, with a transaction-count guard that blocks deletion when transactions still reference the account.

**Architecture:** pure-function guard for the count check, ViewModel-driven confirm dialog state, single DAO delete query. Mirrors the existing `countForAccount` pattern but generalises to count destination-side transfer references too.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, StateFlow.

---

## 1. Background & motivation

Phase 2.16 (Account Management) shipped `AddEditAccountScreen`, `AccountsListScreen`, and `AccountDetailScreen`. It intentionally omitted delete because destructive flows deserve their own spec. Users now need a way to remove accounts they added by mistake or no longer use.

The schema already has `accounts.archived` (set up for a future archive UI). This spec uses hard delete; soft-delete / archive is a deliberate follow-up.

---

## 2. Scope

In scope:
- Hard-delete an account from `AccountDetailScreen` header.
- Transaction-count guard that BLOCKS deletion if any transaction references the account (as source `accountId` OR destination `transferAccountId`).
- Confirmation dialog with copy that differs between empty (destructive confirm) and non-empty (blocking message).
- New DAO query + repository method.
- Unit tests for the guard logic and ViewModel state transitions.

Out of scope (deferred):
- Bulk-move transactions to another account.
- Undo after delete.
- Archive UI (schema is already there; no exposure yet).
- Deleting the default account (id=1) — always protected.

---

## 3. User experience

### 3.1 Happy path (no transactions)

1. User navigates to `AccountsListScreen`.
2. Taps an account tile → `AccountDetailScreen` opens.
3. Sees three header actions: **Adjust balance**, **Edit**, **Delete** (Delete only when `account.id != 1L`).
4. Taps **Delete** → confirmation dialog: *"Delete account 'Cash wallet'? This cannot be undone."*
5. Taps **Delete** → row is deleted; `popBackStack()` returns to `AccountsListScreen`. The tile is gone.

### 3.2 Blocked path (transactions exist)

1. Same as 3.1 steps 1–3.
2. Taps **Delete** → blocking dialog: *"Account 'Maybank' has 14 transactions referencing it. Delete them first or move them to another account."*
3. Only an **OK** button. No data mutation.

### 3.3 Default account

1. Tapping on the default "Cash wallet" account opens `AccountDetailScreen`.
2. No **Delete** button rendered.
3. No way to delete via this surface.

---

## 4. Data layer

### 4.1 `AccountDao.delete`

```kotlin
@Query("DELETE FROM accounts WHERE id = :id")
suspend fun delete(id: Long): Int
```

Returns rows affected (1 = deleted, 0 = already gone). Standard Room behaviour.

### 4.2 `TransactionDao.countReferencingAccount`

```kotlin
@Query(
    "SELECT COUNT(*) FROM transactions " +
        "WHERE accountId = :id OR transferAccountId = :id"
)
suspend fun countReferencingAccount(id: Long): Int
```

Generalises the existing `countForAccount` (which counts only `accountId`). Both source-side and destination-side references count, because deleting an account that is a TRANSFER destination would leave a dangling `transferAccountId` on those rows.

The existing `countForAccount` stays as-is — it is still used by `AddEditAccountViewModel.firstSnapshotForAccount`. New code uses `countReferencingAccount`.

### 4.3 `TransactionRepository.countReferencingAccount`

Thin pass-through to the DAO, `open suspend fun` so tests can override.

### 4.4 `AccountRepository.delete`

```kotlin
open suspend fun delete(id: Long): Int = dao.delete(id)
```

---

## 5. Domain / pure logic

### 5.1 `DeleteGuard` enum + `evaluateDelete`

```kotlin
enum class DeleteGuard { ALLOW, BLOCK_TRANSACTIONS_EXIST }

/**
 * Pure: turns a reference count into a guard decision.
 * Empty → ALLOW. Anything else → BLOCK.
 */
fun evaluateDelete(referenceCount: Int): DeleteGuard =
    if (referenceCount == 0) DeleteGuard.ALLOW else DeleteGuard.BLOCK_TRANSACTIONS_EXIST
```

Co-located with `AccountDetailViewModel` (top-level). Unit-testable without Hilt.

---

## 6. ViewModel

### 6.1 `AccountDetailUiState` additions

```kotlin
data class AccountDetailUiState(
    val accountWithBalance: AccountWithBalance? = null,
    val transactions: List<TransactionWithCategory> = emptyList(),
    val isLoading: Boolean = true,
    // Phase 2.17 delete flow:
    val showDeleteConfirm: Boolean = false,
    val deleteGuard: DeleteGuard? = null,
    val referenceCount: Int = 0,
    val deleted: Boolean = false,    // signal to UI: pop back stack
    val errorMessage: String? = null,// surfaced as a snackbar
)
```

### 6.2 `AccountDetailViewModel` additions

Constructor gains `transactionRepository: TransactionRepository`.

```kotlin
fun onDeleteClick() {
    val accountId = state.value.accountWithBalance?.account?.id ?: return
    if (accountId == 1L) return // default account is protected
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
    val guard = s.deleteGuard ?: return
    if (guard != DeleteGuard.ALLOW) return
    val accountId = s.accountWithBalance?.account?.id ?: return
    viewModelScope.launch {
        accountRepository.delete(accountId)
        _state.update { it.copy(showDeleteConfirm = false, deleted = true) }
    }
}

fun onDeleteDismiss() {
    _state.update { it.copy(showDeleteConfirm = false) }
}
```

Notes:
- The default-account guard at `accountId == 1L` is a defensive belt-and-braces — the UI doesn't render the button, but if a future code path triggers `onDeleteClick` for the default, this short-circuits cleanly.
- `errorMessage` is reserved for unrecoverable errors (e.g. repository throws); the happy and blocked paths don't set it.

### 6.3 `defaultAccountId` constant

Defined in `AccountDetailViewModel.kt` as `private const val DEFAULT_ACCOUNT_ID = 1L`. Matches `AccountDao.findDefault()` which hard-codes the same id.

---

## 7. UI

### 7.1 `AccountDetailScreen` header

Current header has **Adjust balance** and **Edit** actions. Add **Delete** as a third action, between them and Edit, with `Icons.Filled.Delete` and `tint = MaterialTheme.colorScheme.error` to signal destructive intent. Visible only when `state.accountWithBalance?.account?.id != 1L`.

```kotlin
if (state.accountWithBalance?.account?.id != 1L) {
    IconButton(onClick = viewModel::onDeleteClick) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.account_delete),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
```

### 7.2 Confirmation dialog

```kotlin
if (state.showDeleteConfirm) {
    val account = state.accountWithBalance?.account
    when (state.deleteGuard) {
        DeleteGuard.ALLOW -> AlertDialog(
            onDismissRequest = viewModel::onDeleteDismiss,
            title = { Text(stringResource(R.string.account_delete_confirm_title)) },
            text = { Text(stringResource(R.string.account_delete_confirm_message, account?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteConfirm) {
                    Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error)
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
            text = { Text(stringResource(R.string.account_delete_blocked_message, account?.name.orEmpty(), state.referenceCount)) },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteDismiss) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
        null -> Unit // dialog shouldn't show in this state; defensive no-op
    }
}
```

### 7.3 Pop-back trigger

A `LaunchedEffect(state.deleted)` in the screen calls `onBack()` once when `state.deleted` flips true. State is then cleared by the ViewModel's `onDeleteDismiss` to avoid re-triggering.

Actually simpler: after `onDeleteConfirm` sets `deleted = true`, the screen consumes it once and the ViewModel also calls `_state.update { it.copy(deleted = false) }` inside `onDeleteDismiss`. Then `onBack()` fires from the screen. To avoid race: the screen calls `onBack()` directly in response to `state.deleted`, and the ViewModel clears `deleted` on the next state emission cycle. Acceptable.

### 7.4 Snackbar (optional, defensive)

If `state.errorMessage != null`, show via the existing snackbar host on the parent scaffold. Not strictly needed for the happy path; reserved for future error cases.

---

## 8. Strings

Add to `app/src/main/res/values/strings.xml`:

| Key | Value | Notes |
|---|---|---|
| `account_delete` | "Delete" | Icon content description + confirm button label. |
| `account_delete_confirm_title` | "Delete account?" | Dialog title (allow path). |
| `account_delete_confirm_message` | "Delete account '%1$s'? This cannot be undone." | `%1$s` = account name. |
| `account_delete_blocked_title` | "Account has transactions" | Dialog title (block path). |
| `account_delete_blocked_message` | "Account '%1$s' has %2$d transactions referencing it. Delete them first or move them to another account." | `%1$s` = name, `%2$d` = count. |

Pluralisation: avoid the `plurals` resource for v1 — the message is one sentence and works fine as a single string with the integer formatter. If localisation later needs it, swap to `<plurals>`.

---

## 9. Tests

### 9.1 `evaluateDelete` pure-function tests (new file)

`app/src/test/java/io/github/jiro/expensetracker/ui/accounts/DeleteAccountLogicTest.kt`:

- `count=0 → ALLOW`
- `count=1 → BLOCK`
- `count=14 → BLOCK`
- Boundary not needed; negative counts are impossible (Room COUNT returns ≥0).

### 9.2 `TransactionDao.countReferencingAccount` Room test

Covered by the existing in-memory Room test setup if present; otherwise skipped. The query is trivial SQL and the repository pass-through is exercised by the ViewModel test.

### 9.3 `AccountDetailViewModel` state-machine tests

`app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt` (new):

- Tap Delete on account with 0 references → `showDeleteConfirm=true`, `deleteGuard=ALLOW`, `referenceCount=0`.
- Tap Delete on account with 3 references → `showDeleteConfirm=true`, `deleteGuard=BLOCK_TRANSACTIONS_EXIST`, `referenceCount=3`.
- Tap Delete on default account (id=1) → state unchanged (no-op).
- Confirm with ALLOW → repository.delete called, `deleted=true`.
- Confirm with BLOCK → repository.delete NOT called, dialog stays open (or dismissed by user).
- Dismiss → `showDeleteConfirm=false`, no delete call.

Use the existing `StubTransactionDao` pattern (already used by `AddReceiptViewModelTest`). Add `countReferencingAccount` override to the stub.

---

## 10. Risk & rollback

- Risk: race where a transaction is added between count check and delete. Mitigated by the user-driven two-step (count → confirm), and the resulting dangling reference would only happen if a user concurrently added a transaction in another session — not currently possible (single-user, single-device). Acceptable.
- Rollback: revert the commit; no schema change, no destructive migration. `accounts.archived` stays untouched.

---

## 11. Spec self-review

- **Spec coverage:** every requirement above maps to a concrete file + function. ✓
- **Placeholders:** none — every behaviour is specified.
- **Internal consistency:** repository `delete` matches DAO `delete` signature; ViewModel error path is reserved (not exercised in this spec).
- **Ambiguity check:** "default account" = `id == 1L`; clarified in §4.1 and §6.3. Pluralisation decision stated in §8.

---

## 12. Execution handoff

Plan to be written to `docs/superpowers/plans/2026-06-26-delete-account.md`, then executed via subagent-driven-development.