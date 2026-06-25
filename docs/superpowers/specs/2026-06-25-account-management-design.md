# Phase 2.16 — Account Management — Design

**Status:** Draft 2026-06-25
**Phase:** 2.16
**Predecessors:** Phase 2.x ships the offline-first data layer (`TransactionEntity`, `CategoryEntity`, Room v5), the Add/Edit transaction & Add Receipt screens (Phase 2.4 / 2.15), recurring transactions (Phase 2.1), per-tx currency with home-currency settings (Phase 2.2 / 2.6), and the bottom-navbar with a centered Add button (Phase 2.15 polish). Every transaction currently lives on no account — Phase 2.16 makes accounts a first-class entity and ties every transaction to one.

## Goal

Introduce financial accounts as a top-level entity: a user can create, edit, and archive accounts; every transaction is bound to one account; transfers move money between two accounts in a single row; balances are computed live and shown on a new Accounts list screen accessible from the More tab.

Out of scope (intentional, deferred): account-level reconciliation ("set expected balance"), cross-currency aggregation inside the Accounts list (each account shows its own currency; only the header card aggregates to home currency), per-account icon/color theming in the rest of the app, account archive UI (the `archived` column exists for future use; no screens touch it in this phase), account deletion via UI (accounts can be created and renamed but not deleted in v1 — see Risks for why).

## User-visible behavior

### Accounts list (`More → Accounts`)
- 2-column grid of compact tiles: icon + name + balance. ~80dp per tile. Handles 10–20 accounts with one screen + half of scroll.
- Header card: net balance in home currency + count ("across 12 accounts"). Conversion uses existing FX rates from `SettingsRepository.fxRates`.
- Tap a tile → account detail (transactions filtered by `accountId`).
- Long-press a tile → "Edit" (no delete option in this phase; see Out of scope).
- FAB (+) → Add Account.

### Add / Edit Account
- Single scrollable form: name, type (preset + custom), icon picker (8 emojis), color picker (8 swatches), currency (locked on Edit), opening balance (Add only; Edit uses "Adjust balance").
- Currency field is locked after first save. On Edit it shows disabled with a hint: *"Currency cannot be changed — create a new account if you need a different currency."*
- Icon and color are always editable, no restrictions.
- "Adjust balance" (Edit only, shown when ≥1 transaction exists against the account) opens a dialog where the user enters the new target balance; the app computes the delta and creates a new `ADJUSTMENT` transaction row. Never silently mutates the opening balance; the audit trail is preserved. (See Risks — ADJUSTMENT is a new transaction type introduced in this phase, stored like EXPENSE/INCOME but with no category and no transferAccountId; `amountMinor` can be positive or negative.)

### Add / Edit Transaction & Add Receipt review
- New "Account" dropdown between Type and Category.
- Default selection: **none** — the user must explicitly pick. The dropdown shows "Select an account" placeholder until chosen. (Confirmed: force-pick, no sticky last-used.)
- If only 1 account exists, the field renders as a static label (no dropdown).
- For TRANSFER type: a second "To account" dropdown appears below; both required and must differ.

### TRANSFER in the transactions list
- Single row, inline `X → Y` rendering: `25 Jun  Cash wallet → Maybank   RM 50`.
- Amount stored as positive (from-account perspective); rendered without `−` or `+` prefix.
- Transfers excluded from income/expense totals in stats & dashboard (`WHERE type != 'TRANSFER'`).
- Sorted by date like other transactions.

### More tab
- New "Accounts" entry above "Categories", below "Settings".

## Data model

### New `AccountEntity`
```kotlin
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["currencyCode"])]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: String,                   // enum: CASH, BANK, CREDIT_CARD, EWALLET, OTHER, or user custom
    val icon: String,                   // emoji
    val color: Int,                     // ARGB
    val currencyCode: String,           // 3-letter, locked at creation
    val openingBalanceMinor: Long = 0L,
    val createdAtEpochMillis: Long,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)
```

Unique index on `(name, archived)` via a partial index in the migration (SQLite supports this; Room via `@Index(value = ["name"], unique = true)` is a simpler approximation that we'll use and accept the slight looseness).

### Modified `TransactionEntity`
```kotlin
@Entity(...)                              // existing
data class TransactionEntity(
    ...                                   // existing fields unchanged
    val accountId: Long,                  // NEW, NOT NULL, FK accounts(id) ON DELETE RESTRICT
    val transferAccountId: Long? = null,  // NEW, NULL except for TRANSFER, FK accounts(id)
    val categoryId: Long? = null,         // CHANGED: was NOT NULL, now nullable (TRANSFER has no category)
)

// TransactionType enum gains two new values:
//   TRANSFER  — money moved between two accounts
//   ADJUSTMENT — manual balance correction (created only via the Adjust balance dialog)
```

## Migration v5 → v6

```sql
-- 1. Create accounts
CREATE TABLE accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    icon TEXT NOT NULL,
    color INTEGER NOT NULL,
    currencyCode TEXT NOT NULL,
    openingBalanceMinor INTEGER NOT NULL DEFAULT 0,
    createdAtEpochMillis INTEGER NOT NULL,
    archived INTEGER NOT NULL DEFAULT 0,
    sortOrder INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX index_accounts_name ON accounts(name);

-- 2. Seed the default Cash wallet (placeholder currency)
INSERT INTO accounts (id, name, type, icon, color, currencyCode, openingBalanceMinor,
                      createdAtEpochMillis, archived, sortOrder)
VALUES (1, 'Cash wallet', 'CASH', '💵', -14934489, 'USD', 0, 0, 0, 0);
-- (color -14934489 = #1976D2 blue, createdAt updated by the migration via System.currentTimeMillis)

-- 3. Add columns to transactions
ALTER TABLE transactions ADD COLUMN accountId INTEGER NOT NULL DEFAULT 1;
ALTER TABLE transactions ADD COLUMN transferAccountId INTEGER REFERENCES accounts(id);

-- 4. Make categoryId nullable (Room can't relax NOT NULL in-place)
CREATE TABLE transactions_new (
    ... same as transactions but categoryId INTEGER
);
INSERT INTO transactions_new SELECT ... FROM transactions;
DROP TABLE transactions;
ALTER TABLE transactions_new RENAME TO transactions;
-- Recreate all indices on transactions.
```

### Two-phase default-account currency
The migration seeds `currencyCode='USD'` as a placeholder. A one-shot init runs on first DB open after migration:
1. `SettingsRepository.homeCurrency` is read (synchronous SharedPreferences read or first-emission of the StateFlow).
2. If `accounts WHERE id = 1 AND currencyCode != settingsHomeCurrency`, the row is updated.
3. Idempotent: subsequent opens no-op.

The placeholder account is invisible to the user (no screen renders accounts before this init completes — the init runs in `App.onCreate` after the first DB open, before any UI is composed).

### Risks of the migration
- **Low.** Purely additive: new table, new columns with safe defaults, one nullable change.
- Existing rows get `accountId=1` (the seeded default).
- No data loss.

## Domain layer

### `Account` model (pure)
```kotlin
data class Account(
    val id: Long,
    val name: String,
    val type: String,
    val icon: String,
    val color: Int,
    val currencyCode: String,
    val openingBalanceMinor: Long,
    val balanceMinor: Long,            // computed, not stored
    val createdAtEpochMillis: Long,
    val archived: Boolean,
    val sortOrder: Int,
)
```

### `AccountRepository`
- `observeAll(): Flow<List<Account>>` — all non-archived accounts ordered by `sortOrder, name`.
- `observeWithBalances(): Flow<List<Account>>` — joins with transactions to compute `balanceMinor` (see formula below).
- `findById(id: Long): Account?`
- `add(account: Account): Long`
- `update(account: Account)`
- `getDefault(): Account?` — returns the seeded default (id=1) if it exists.

### Balance formula
Computed in the repository as a Room `@Query` returning a map of `accountId → balanceMinor`:

```sql
SELECT a.id AS accountId,
       a.openingBalanceMinor
       + COALESCE((SELECT SUM(amountMinor) FROM transactions
                   WHERE accountId = a.id AND type IN ('INCOME','EXPENSE','ADJUSTMENT')), 0)
       - COALESCE((SELECT SUM(amountMinor) FROM transactions
                   WHERE accountId = a.id AND type = 'TRANSFER'), 0)
       + COALESCE((SELECT SUM(amountMinor) FROM transactions
                   WHERE transferAccountId = a.id AND type = 'TRANSFER'), 0)
       AS balanceMinor
FROM accounts a
WHERE a.archived = 0;
```

CREDIT_CARD balances display as negative when owed (`−RM 432` = "you owe RM 432"). The sign convention is applied in the UI, not the formula.

## TRANSFER mechanics

**Single-row approach.** A TRANSFER is one `TransactionEntity` row with:
- `type = 'TRANSFER'`
- `accountId = <from>`
- `transferAccountId = <to>`
- `categoryId = null`
- `amountMinor > 0` (from-account perspective)

**Why single-row:** stats & aggregations stay sane. No double-counting, no `transferGroupId` linkage. The transfer is excluded from income/expense totals with `WHERE type != 'TRANSFER'` and added to both source and destination balances via the formula.

**Constraints** (validated in `AddEditTransactionViewModel.save()`):
- `accountId != transferAccountId`
- `categoryId == null`
- `amountMinor > 0`

## ADJUSTMENT mechanics

A new transaction type introduced in this phase. Stored like EXPENSE/INCOME but with no category and no transferAccountId:
- `type = 'ADJUSTMENT'`
- `accountId = <target account>`
- `transferAccountId = null`
- `categoryId = null`
- `amountMinor` can be positive or negative (it's a delta against the current balance)
- `note = "Balance adjustment: <X> → <Y>"` (auto-filled; user can't override)

**Created by:** the "Adjust balance" action on Edit Account. The user enters a new target balance; the app computes `delta = target - currentBalance` and creates one ADJUSTMENT row.

**Effect on balance:** added directly via the formula (`type IN ('INCOME','EXPENSE','ADJUSTMENT')`). The opening balance field on the account remains untouched.

**Not user-creatable** through the normal Add Transaction flow — only via the dedicated Adjust balance dialog. The Type picker on Add Transaction shows EXPENSE / INCOME / TRANSFER only.

## UI layer

### Screens
- `AccountsListScreen` — 2-col grid, header card, FAB. Compose route: `accounts_list`.
- `AccountDetailScreen` — filtered transactions list for one account + balance + edit FAB. Route: `account_detail/{accountId}`.
- `AddEditAccountScreen` — single scrollable form, routes `account_edit` (new) and `account_edit/{accountId}` (edit).
- `AddEditTransactionScreen` — gains the Account dropdown between Type and Category.
- `AddReceiptScreen` — review screen gains the same Account dropdown.
- `MoreScreen` — gains the Accounts entry above Categories.

### ViewModels
- `AccountsListViewModel` — exposes `state: StateFlow<AccountsListUiState>` with `accounts`, `netBalanceInHome`, `isLoading`.
- `AddEditAccountViewModel` — handles form state, save, validation (unique name, currency lock on edit, opening balance only on add).
- `AddEditTransactionViewModel` — gains `accountId`, `transferAccountId` in state; new `onAccountChange`, `onTransferAccountChange` handlers; save() validation extended. The Type picker exposes EXPENSE / INCOME / TRANSFER only (ADJUSTMENT is never user-selectable on this screen).
- `AddReceiptViewModel` — gains the same Account fields; the OCR auto-fill does NOT touch the account (it's a user-pick, not OCR-extractable).

### Navigation
- `AppNav` adds routes: `accounts_list`, `account_detail/{accountId}`, `account_edit`, `account_edit/{accountId}`.
- The Add button on the bottom nav remains centered and unchanged; it still opens the Add Transaction / Add Receipt split as it does today.

## Tests

### Unit (JVM)
1. `BalanceFormulaTest` — table-driven tests covering:
   - Opening balance alone
   - Opening + income
   - Opening + expense
   - Opening + transfer out
   - Opening + transfer in
   - Opening + transfer out + transfer in (round trip)
   - CREDIT_CARD with negative balance
2. `AccountRepositoryTest` — CRUD with an in-memory Room DB, including:
   - Adding an account returns an id
   - `observeAll` excludes archived accounts
   - Unique-name constraint enforced
3. `MigrationTest` — `MigrationTestHelper` from Room:
   - Seed v5 schema with sample data (3 transactions across 2 categories).
   - Run `MIGRATION_5_6`.
   - Verify `accounts` table exists with row id=1 named "Cash wallet" with `currencyCode='USD'`.
   - Verify all transactions have `accountId=1` and `categoryId` unchanged.
   - Verify `transactions_new` has the nullable `categoryId`.
   - Verify indices recreated.

### Instrumented / Compose UI
4. `AccountsListScreenTest` — render with 0, 1, 3, 12 accounts; verify grid layout + FAB visibility.
5. `AddEditAccountScreenTest` — render Add mode (currency enabled, opening balance visible) and Edit mode (currency disabled, "Adjust balance" visible).
6. `AddEditTransactionScreenTest` — single-account mode (field is a static label); multi-account mode (field is a dropdown); TRANSFER type reveals "To account".
7. `AddReceiptScreenTest` — same Account dropdown visible in the review form.

### Manual smoke checklist
- Create 3 accounts with different currencies (MYR, USD, SGD). Switch home currency. Verify header card updates.
- Create a TRANSFER between two accounts. Verify both balances change correctly.
- Force-stop and restart the app. Verify all accounts and balances persist.

## Out of scope (deferred)

- **Account archive UI.** The `archived` column exists in the schema for future use but no screens touch it.
- **Cross-currency aggregation.** Each account's balance is shown in its own currency. Only the header card aggregates to home currency using existing FX rates.
- **Per-account icon/color theming in the rest of the app.** The icon/color shows only on the Accounts screen and the account picker.
- **Account-level reconciliation.** Setting an "expected balance" and computing variance is out of scope. Balance is purely computed.
- **Account deletion via UI.** Accounts can be created, edited (except currency), and effectively soft-archived via a future "delete" affordance, but in this phase there's no UI to remove an account. The risk of orphaned `accountId` FK references in `transactions` is too high to ship delete in v1 without a careful "move transactions to another account" prompt.

## Risks & open questions

1. **Currency code injection into the migration.** The migration seeds `currencyCode='USD'` as a placeholder and updates it post-migration from `SettingsRepository.homeCurrency`. If `SettingsRepository.homeCurrency` is empty/uninitialized (first install, no settings written yet), the placeholder stays 'USD'. Mitigation: `SettingsRepository.homeCurrency` defaults to 'USD' on first read; verified.
2. **Unique-name partial index.** SQLite supports `CREATE UNIQUE INDEX ... WHERE archived = 0` but Room's `@Index(unique=true)` doesn't express this. We accept the looser constraint: two archived accounts can share a name with an active one. Documented.
3. **Default account deletion.** If the user could delete the seeded default (not exposed in this phase but possible via direct DB), all existing transactions would FK-fail on delete. We protect against this by NOT exposing delete UI in v1.
4. **TRANSFER + currency mismatch.** A TRANSFER between two accounts in different currencies has an FX implication we don't handle: the `amountMinor` is stored as-is from the source currency, but the destination's balance increments by the same `amountMinor` (effectively pretending the amount is in the destination's currency). This is a known limitation; documented; deferring real cross-currency transfers to a future phase.
5. **Phase ordering.** Account picker on Add/Edit Transaction changes the required-field validation. The current single-account static-label mode means the picker never blocks save. The multi-account mode makes account a required field. Rollout plan: ship Account schema + migration first (no UI yet), then Add Account screen, then Account picker on Add Transaction, then More tab entry, then Accounts list screen. Each step is a separate commit so a rollback is clean.

## Decisions captured this session

- Financial accounts (not cloud auth).
- Preset + custom types: CASH, BANK, CREDIT_CARD, EWALLET, OTHER + user custom.
- Account is required on every transaction (recommended path).
- TRANSFER as 3rd transaction type + opening balance + full balance formula.
- Per-account currency (immutable at creation).
- Accounts list: 2-col compact tiles (handles 10+ accounts).
- Add/Edit Account: single scrollable form.
- Currency locked at creation, icon/color always editable, opening balance adjustment via TRANSACTION.
- Account field position: between Type and Category.
- Single-account mode: static label. TRANSFER: reveals "To account".
- Default selection: force-pick (no sticky last-used).
- TRANSFER list rendering: inline `X → Y` with amount.
- Out of scope: no archive UI, no cross-currency aggregation in list, no color theming, no reconciliation, no delete UI.