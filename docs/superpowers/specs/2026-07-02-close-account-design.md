# Close-Account Feature — Design Spec

**Date:** 2026-07-02
**Status:** Approved (pending spec review by user)

## Goal

Allow users to *close* a personal-finance account without deleting it. A
closed account disappears from every dropdown / picker / "select account"
surface but is retained in the local database, still contributes to net
balance totals, and still appears on historic transactions. Users can
reopen a closed account at any time.

## Non-goals

- Account hard-delete is unchanged (still gated by `DeleteGuard`).
- No cloud-sync changes; account close/reopen is local-only.
- No new analytics or reports. Closed accounts are not a separate
  category in any chart.
- No audit log of close/reopen events (just the most-recent `archivedAt`).

## Decisions (resolved during brainstorming)

1. **Reopen is supported.** A *Closed accounts* filter on the list and a
   *Reopen account* overflow item on the detail screen give users a path
   back. Close is not one-way.
2. **Transactions display the closed account's name and icon.** Add a
   sibling projection `TransactionWithRelations` that joins
   `AccountEntity` for both `accountId` and `transferAccountId`.
   `TransactionWithCategory` is untouched so the dozen existing callers
   don't break.
3. **Closed accounts contribute to net balance / dashboard totals.**
   "Close" affects visibility, not the user's real money.
4. **Timestamps:** `TransactionEntity.createdAtEpochMillis` already
   exists and is set to `System.currentTimeMillis()` on insert — no
   schema change. Add a new nullable `archivedAtEpochMillis` column on
   `AccountEntity`, set on close, cleared on reopen.
5. **UI surfacing:** `createdAtEpochMillis` is shown on transaction list
   rows and on the transaction detail screen. `archivedAtEpochMillis`
   is shown on `AccountDetailScreen` when archived.

## Schema changes

### MIGRATION_7_8

```sql
ALTER TABLE accounts ADD COLUMN archivedAtEpochMillis INTEGER;
```

Nullable. `null` = active. Non-null = closed at that wall-clock
instant. Existing rows default to `null` (still active, with no
recorded close time — a user-visible "Closed on —" line falls back to
"Closed (date unknown)" or stays hidden for these legacy rows; see UI
section).

`@Database(version = 8)` bump on `AppDatabase`.

### `AccountEntity`

```kotlin
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: String,
    val icon: String,
    val color: Int,
    val currencyCode: String,
    val openingBalanceMinor: Long = 0L,
    val createdAtEpochMillis: Long,
    val archived: Boolean = false,
    val archivedAtEpochMillis: Long? = null,   // new
    val sortOrder: Int = 0,
)
```

## Data layer

### `AccountDao` — new methods

```kotlin
@Query("UPDATE accounts SET archived = 1, archivedAtEpochMillis = :now WHERE id = :id")
suspend fun close(id: Long, now: Long)

@Query("UPDATE accounts SET archived = 0, archivedAtEpochMillis = NULL WHERE id = :id")
suspend fun reopen(id: Long)

@Query(
    """
    SELECT a.*,
      (a.openingBalanceMinor + COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amountMinor
                                                WHEN t.type = 'EXPENSE' THEN -t.amountMinor
                                                ELSE 0 END), 0)) AS balanceMinor
    FROM accounts a
    LEFT JOIN transactions t ON t.accountId = a.id OR t.transferAccountId = a.id
    GROUP BY a.id
    ORDER BY a.sortOrder, a.name
    """
)
fun observeAllWithBalances(): Flow<List<AccountWithBalance>>

@Query("SELECT * FROM accounts")
suspend fun listAllOnce(): List<AccountEntity>

@Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY id ASC LIMIT 1")
suspend fun findActiveDefault(): AccountEntity?    // replaces findDefault()
```

The existing `findDefault()` (hardcoded `WHERE id = 1`) is **removed**.
`AccountRepository.findDefault()` is renamed to `findActiveDefault()`,
returns `AccountEntity?` (null when no active accounts exist), and
selects the lowest-id active row. This is a behavior change: after
closing the seeded `id = 1` "Cash wallet", the default falls through
to the next active account by id. All callers (verified: only
`AccountRepository.findDefault` itself today, plus a single
`AddEditTransactionViewModel` default-account preselect path) must be
updated in the same change. If a UI path requires a non-null default
and all accounts are archived, it must degrade gracefully (e.g. show
"Add an account first" rather than crash).

### `AccountRepository` (interface + impl) — new methods

```kotlin
suspend fun close(id: Long)
suspend fun reopen(id: Long)
fun observeAllWithBalances(): Flow<List<AccountWithBalance>>
suspend fun listAllOnce(): List<AccountEntity>
```

`close(id)` calls `dao.close(id, System.currentTimeMillis())`. `reopen`
calls `dao.reopen(id)`.

### `TransactionWithRelations` (new projection, sibling to `TransactionWithCategory`)

```kotlin
data class TransactionWithRelations(
    @Embedded val transaction: TransactionEntity,
    @Relation(
        parentColumn = "accountId",
        entityColumn = "id",
    )
    val account: AccountEntity?,
    @Relation(
        parentColumn = "transferAccountId",
        entityColumn = "id",
    )
    val transferAccount: AccountEntity?,
    @Relation(
        parentColumn = "categoryId",
        entityColumn = "id",
    )
    val category: CategoryEntity?,
)
```

Used by transaction list rows that want to display "Added MMM d" (uses
`transaction.createdAtEpochMillis`) and by the account detail screen
when listing transactions for a closed account (uses `account.name`).

### `BackupFormat` — round-trip the new field

Add `archivedAtEpochMillis: Long?` to the per-account backup record.
Default to `null` when reading old backups (pre-7.8) so restore on a
fresh DB is a no-op for the field.

## ViewModel layer

### `AccountsListViewModel`

Exposes a new `state.showClosed: Boolean` (default `false`). The
`state.accounts` flow switches source based on this flag:
`observeActiveWithBalances()` when false, `observeAllWithBalances()`
when true.

The `state.netBalance` field is *always* computed from
`observeAllWithBalances()` regardless of the filter — closed accounts
still roll into the net balance label.

### `AccountDetailViewModel`

Resolves the account from `listAllOnce()` rather than the
active-combined flow, so the detail screen is reachable for closed
accounts. Existing delete flow (`DeleteGuard`) is unchanged. New
`onCloseClick()` / `onCloseConfirm()` and `onReopenClick()` /
`onReopenConfirm()` methods call the repository and emit a
`CloseEvent` / `ReopenEvent` on a one-shot channel for the screen to
turn into a snackbar.

## UI layer

### `AccountsListScreen`

- Top filter row above the LazyColumn: a Material `FilterChip` labeled
  "Show closed accounts". Toggling it flips the VM's `showClosed`
  state.
- Row rendering: when `account.archived`, the row's name and balance
  text drop to 60% alpha and a small `Text("Closed")` pill renders on
  the trailing edge after the balance. Tapping a closed-account row
  navigates to the detail screen via the existing
  `onAccountClick(id)` callback — no special routing.

### `AccountDetailScreen`

- Below the account name: a `Text` line rendered only when
  `state.account.archived` is true.
  - If `archivedAtEpochMillis != null`, body is "Closed on MMM d,
    yyyy" via `DateUtils.formatDateTime(context, FORMAT_SHOW_DATE |
    FORMAT_ABBREV_MONTH)` — i.e. medium style with abbreviated month
    and the year included.
  - If `archivedAtEpochMillis == null` (legacy closed-but-pre-7.8
    row), body is just "Closed" with no date suffix.
- Overflow menu gains two new items, one or the other visible at a
  time:
  - *Close account* (when active)
  - *Reopen account* (when archived)
  - *Edit* and *Delete* remain unchanged.
- Confirmation dialogs:
  - **Close:** title "Close account?", body "Closed accounts stay in
    your records but won't appear in dropdowns. You can reopen it
    later." Buttons: Cancel / Close.
  - **Reopen:** title "Reopen account?", body "This account will
    reappear in dropdowns." Buttons: Cancel / Reopen.
- After close: snackbar "Account closed" with a 5s *Undo* action that
  immediately calls `reopen(id)`. After reopen: snackbar "Account
  reopened" with no undo.

### Transaction list rows

- Each row's existing layout (title / category / amount / date) gains a
  small subtitle line below the title: `Text("Added MMM d")` using a
  short date format (`DateUtils.formatDateTime(context, FORMAT_SHOW_DATE
  | FORMAT_ABBREV_MONTH | FORMAT_NO_YEAR)`). Hidden when
  `createdAtEpochMillis == 0L` (defensive — should never happen given
  the existing insert wiring, but covers a zero-edge case).

### Transaction detail / Add-Edit screen

- Below the transaction's title (when in detail / edit mode, not new),
  a subtitle line "Added MMM d, yyyy" using `DateUtils.formatDateTime(
  context, FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH)` (medium style with
  year). Hidden when `createdAtEpochMillis == 0L`.

## Strings

| Key | Value |
|---|---|
| `account_close` | "Close account" |
| `account_reopen` | "Reopen account" |
| `account_close_confirm_title` | "Close account?" |
| `account_close_confirm_message` | "Closed accounts stay in your records but won't appear in dropdowns. You can reopen it later." |
| `account_reopen_confirm_title` | "Reopen account?" |
| `account_reopen_confirm_message` | "This account will reappear in dropdowns." |
| `account_close_snackbar` | "Account closed" |
| `account_reopen_snackbar` | "Account reopened" |
| `account_undo` | "Undo" |
| `account_status_closed` | "Closed" |
| `account_filter_show_closed` | "Show closed accounts" |
| `account_closed_on` | "Closed on %1$s" |
| `transaction_added_on` | "Added %1$s" |

`%1$s` placeholders are filled at render time with the formatted date.

## Error handling

- **Close:** single-row `UPDATE` against an indexed primary key.
  Idempotent (UPDATEing `archived = 1` twice is a no-op). No failure
  mode.
- **Reopen:** symmetric — idempotent.
- **Race conditions:** close + UI state are sequential through Room's
  coroutine dispatcher; `Flow` emissions after `UPDATE` don't
  interfere with in-flight reads or writes. No locks needed.
- **Closing the seeded `id = 1` "Cash wallet":** allowed. After close,
  `findActiveDefault()` skips it and returns the next active account by
  id. Existing transactions still reference it; detail screen is
  reachable; FK RESTRICT is preserved.

## Testing

### Unit (Room in-memory)

- `AccountDaoTest`
  - `observeActive` excludes archived rows; `observeAllWithBalances`
    includes them.
  - `close` writes `archived = 1` and a fresh `archivedAtEpochMillis`.
  - `reopen` writes `archived = 0` and `archivedAtEpochMillis = null`.
  - `findActiveDefault` returns the lowest-id active row; returns null
    when all rows are archived.
  - Idempotency: `close` twice → still `archived = 1`, second
    `archivedAt` overwrites the first.

### Repository

- `AccountRepositoryImplTest`
  - `close(id)` passes `System.currentTimeMillis()` to DAO (verify via
    a `Clock` injection seam, or by capturing the value in a test DAO
    fake).
  - `observeAllWithBalances` flows correctly on a fresh DB and after
    close + reopen.

### ViewModel

- `AccountsListViewModelTest`
  - `setShowClosed(true)` swaps the source; net balance label
    unaffected by filter toggle.
- `AccountDetailViewModelTest`
  - `loadAccount(closedId)` resolves the closed account and exposes
    `archived = true` + `archivedAtEpochMillis`.
  - `onCloseConfirm` emits `CloseEvent` once and doesn't re-emit on
    recomposition.

### Smoke (manual, on hardware)

- New file `docs/superpowers/testdata/close-account.md`, mirroring the
  widget smoke structure. Covers: close from detail, dropdown hide,
  reopen from detail, dropdown restore, list filter toggle, net
  balance inclusion, transaction list "Added" line, account detail
  "Closed on" line, undo path, close-then-create-same-name.

## Files touched (summary)

- **Create:** `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionWithRelations.kt`
- **Modify:** `AccountEntity.kt`, `AccountDao.kt`, `AppDatabase.kt`,
  `AccountRepository.kt`, `AccountRepositoryImpl.kt`,
  `AccountsListViewModel.kt`, `AccountDetailViewModel.kt`,
  `AccountsListScreen.kt`, `AccountDetailScreen.kt`,
  `TransactionsScreen.kt`, `AddEditTransactionScreen.kt`,
  `BackupFormat.kt`, `app/src/main/res/values/strings.xml`
- **Test create:** `app/src/test/java/io/github/jiro/expensetracker/data/local/AccountDaoTest.kt`
  (extend existing if it exists), test extensions for repository /
  viewmodel above
- **Doc create:** `docs/superpowers/testdata/close-account.md`