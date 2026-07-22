# Account Detail — Both Dates Visible + Add Transaction Pre-fill

## Goal

In the **Account detail** screen only:

1. Each transaction row shows **both** the transaction date (`occurredAtEpochMillis`) and the added date (`createdAtEpochMillis`). Transaction date is the prominent label; added date is a smaller subtitle.
2. The list is sorted by **transaction date** descending (already true — no behavior change here, but stated explicitly so the spec matches the user's intent).
3. A **"Add transaction"** FAB on the Account detail screen navigates to the existing Add/Edit screen with the account pre-filled to the one being viewed.

## Scope

**In scope:** `AccountDetailScreen` rendering and entry point. `TransactionRow` signature change (opt-in). Nav route for `ADD_EDIT` (optional `accountId` arg). `AddEditTransactionViewModel` reads `accountId` arg in add mode.

**Out of scope:** Home, TransactionsScreen, investment account detail, transaction list filtering, sort direction change (DESC stays), any data model migration (no schema change).

## Design

### 1. TransactionRow — opt-in transaction-date display

File: `app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt`

Add an optional boolean parameter to `TransactionRow`:

```kotlin
@Composable
internal fun TransactionRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String? = null,
    showTransactionDate: Boolean = false,   // NEW
)
```

Default `false` keeps today's behavior for every existing caller. When `true`, the row renders:

- **First subtitle line (medium weight):** `R.string.transaction_date_on` with the formatted `occurredAtEpochMillis`.
- **Second subtitle line (small, muted):** `R.string.transaction_added_on` with the formatted `createdAtEpochMillis` — but **only if it differs from the transaction date by more than one calendar day**. Same-day entries collapse to a single line, matching the user's likely mental model ("if I added it the same day I did the transaction, why show two dates?").

This applies to both `StandardRow` and `TransferRow` paths inside `TransactionRow`. TransferRow currently doesn't render any date subtitle — when `showTransactionDate = true` it gains the same two-line treatment.

The existing `LocalContext` + `DateUtils.formatDateTime` pattern (already used for the added-on line) is reused for the transaction-date line. Same `FORMAT_SHOW_DATE or FORMAT_ABBREV_MONTH or FORMAT_NO_YEAR` flags.

### 2. AccountDetailScreen — use the new flag + add FAB

File: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt`

Two changes:

a. Pass the flag at the call site:

```kotlin
items(state.transactions, key = { it.transaction.id }) { row ->
    TransactionRow(
        row = row,
        onClick = { onTransactionClick(row.transaction.id) },
        showTransactionDate = true,
    )
}
```

b. Add a FAB. Use `ExtendedFloatingActionButton` with the Add icon and `R.string.account_add_transaction` label:

```kotlin
floatingActionButton = {
    if (aw != null && !aw.account.archived) {
        ExtendedFloatingActionButton(
            onClick = { onAddTransaction(aw.account.id) },
            text = { Text(stringResource(R.string.account_add_transaction)) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        )
    }
}
```

The FAB is **hidden when the account is archived** — closing an account already hides new-transaction affordances elsewhere in the app, so this matches the existing pattern. The FAB is hidden when `aw == null` (account not yet loaded) to avoid flashing an unbound action.

Add a new function parameter:

```kotlin
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onTransactionClick: (Long) -> Unit,
    onAddTransaction: (accountId: Long) -> Unit,   // NEW
    viewModel: AccountDetailViewModel = hiltViewModel(),
)
```

### 3. Nav route — `ADD_EDIT` accepts optional `accountId`

File: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`

a. Extend `Routes`:

```kotlin
const val ADD_EDIT_WITH_ACCOUNT = "add_edit?accountId={accountId}"
const val ADD_EDIT_ARG_ACCOUNT_ID = "accountId"
```

b. Extend the route helper. Existing `addEditRoute(transactionId)` keeps its signature so existing callers don't change. Add an overload:

```kotlin
fun addEditRoute(accountId: Long): String =
    "add_edit?accountId=$accountId"
```

c. In the `composable(route = Routes.ADD_EDIT, ...)` block, add the new optional navArgument:

```kotlin
navArgument(Routes.ADD_EDIT_ARG_ACCOUNT_ID) {
    type = NavType.LongType
    defaultValue = -1L
}
```

The composable body still calls `AddEditTransactionScreen(...)` — the screen reads the arg via its VM. No callback signature change on the screen itself.

d. Wire `AccountDetailScreen` in its `composable(route = Routes.ACCOUNT_DETAIL, ...)` block to thread `onAddTransaction`:

```kotlin
AccountDetailScreen(
    onBack = { navController.popBackStack() },
    onEditAccount = { id -> navController.navigate("account_edit/$id") },
    onTransactionClick = { txnId -> navController.navigate(addEditRoute(txnId)) },
    onAddTransaction = { accountId -> navController.navigate(addEditRoute(accountId)) },
)
```

### 4. AddEditTransactionViewModel — read `accountId` arg

File: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt`

In `init {}`, after the existing `transactionId` read:

```kotlin
private val presetAccountId: Long? = savedStateHandle
    .get<Long>("accountId")
    ?.takeIf { it >= 0 }
```

In add mode (when `transactionId == null`), seed `selectedAccountId` from the preset. Edit mode is unaffected — the existing-row prefill in the `if (transactionId != null)` block already sets `selectedAccountId = existing.accountId`.

The `MutableStateFlow(AddEditTransactionUiState(...))` initializer needs the preset. Two options:

- **Option α (recommended):** extend the initial state to include `selectedAccountId = presetAccountId` and accept that the accounts list hasn't loaded yet. The `accountRepository.observeActive()` collector already clamps `selectedAccountId` to the valid set on every emission, so a stale id just becomes null until the user picks. Safe.
- **Option β:** defer the preset until the accounts list arrives and apply it via a one-shot effect. More code, no upside.

Go with α. The `takeIf { it >= 0 }` guard means a missing/negative arg leaves `selectedAccountId = null`, exactly today's behavior.

The validation path in `save()` already errors when `selectedAccountId == null` (`FormError.ACCOUNT_REQUIRED`), so a missing-accounts edge case doesn't silently save a transaction with no account.

## Strings

Add to `app/src/main/res/values/strings.xml`:

```xml
<!-- Account detail -->
<string name="account_add_transaction">Add transaction</string>

<!-- Transaction row -->
<string name="transaction_date_on">Date %1$s</string>
```

`transaction_added_on` already exists; reuse as-is for the small "Added" subtitle.

## Files touched

- `app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt` — param + branch
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt` — pass flag + FAB + callback
- `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt` — route arg + helper overload + wire callback
- `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt` — read arg + seed initial state
- `app/src/main/res/values/strings.xml` — two new strings

## Behavior matrix

| Caller | `showTransactionDate` | What shows |
|---|---|---|
| `HomeScreen` | `false` (default) | One line: "Added [date]" |
| `TransactionsScreen` | `false` (default) | One line: "Added [date]" |
| `AccountDetailScreen` | `true` | "Date [date]" line, then "Added [date]" if more than 1 day later |
| Account detail FAB → Add | n/a | Navigates with `?accountId=<id>`; VM seeds `selectedAccountId` |
| Bottom-bar "+" → Add | n/a | Navigates with no `accountId` arg; VM leaves `selectedAccountId = null` |
| Edit existing transaction | n/a | `transactionId` arg present; VM loads existing row → `selectedAccountId = existing.accountId`. `accountId` arg is ignored when `transactionId` is present. |

## Edge cases

- **Same-day add and transaction:** only the "Date [date]" line shows; "Added" subtitle suppressed (≤1 day apart).
- **Account archived:** FAB hidden.
- **Account not yet loaded (`aw == null`):** FAB hidden.
- **Preset account id doesn't exist (deleted between nav and VM init):** `accountRepository.observeActive()` collector's `takeIf { it in validIds }` clamps `selectedAccountId` to null; user picks from the dropdown.
- **Edit + accountId preset:** ignored — the existing-row prefill wins.
- **Bottom-bar add while on Account detail:** unchanged. User can still add to a different account; the preset on Account detail only fires from the FAB.

## Testing approach

- Unit: `AddEditTransactionViewModelTest` gets one new case — `init_addModeWithAccountIdArg_seedsSelectedAccountId`. Existing tests should keep passing (no signature changes that break fakes).
- Manual smoke (the verification gate per the project's pattern): add a transaction via the Account detail FAB, confirm the dropdown is pre-selected to that account; confirm a transaction added today shows one date; back-date the transaction by editing it to a date 5 days ago, confirm both dates render.

## Open questions

None. Ready for plan.
