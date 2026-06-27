# CSV Balance Import — Design Spec

> **Status:** Draft, pending user review.

**Goal:** Allow the user to bulk-create accounts and/or update existing accounts' opening balances from a 4-column CSV file.

**Non-goals:** Importing transactions, exporting CSV (the existing `CsvExporter` is unrelated and unused), importing categories, importing budgets, full backup/restore.

---

## User-facing behavior

### CSV format

Strict 4-column CSV, UTF-8 with optional BOM, RFC 4180 quoting, English-style numbers (`.` decimal, `,` field delimiter). First row is the header; the order is fixed.

```csv
name,type,currency,balance
Cash,CASH,USD,250.00
BPI Savings,BANK,PHP,15000.00
AmEx Credit,CREDIT_CARD,USD,-120.50
```

- `name` — non-empty; matched case-insensitively against existing account names.
- `type` — one of `CASH`, `BANK`, `CREDIT_CARD`, `EWALLET`, `OTHER` (or any other string → falls back to `OTHER` for icon/color).
- `currency` — 3-letter ISO code; uppercased on parse.
- `balance` — signed decimal. Negative values are accepted (e.g., a credit card with debt).

### Semantics

For each CSV row, in order:

1. **Match** the `name` (case-insensitive) against existing accounts.
2. **If no match** → row will create a new account. `type` from CSV drives icon/color defaults; `openingBalanceMinor = balance`.
3. **If match** → row will update that account's `openingBalanceMinor` to `balance`, **provided**:
   - The account's existing `currencyCode` equals the CSV's `currency` (else: rejected).
   - The account has zero transactions (else: rejected). The user must delete the account's transactions (or delete the account, since v0.16.0) before retrying.

The current account balance = `openingBalanceMinor + Σ(transactions)`. Updating opening balance directly is the only way to preserve the meaning of "this is what the account was worth when I started tracking it." Creating adjustment transactions would conflate seed data with mid-stream corrections.

### Entry point

Settings screen, new section between **Backup** and any other sections. Titled **Import accounts from CSV**. Subtitle explains the 4-column format. Button **Choose CSV file** opens the system file picker filtered to CSV MIMEs.

### Preview

After file selection, a full-screen dialog shows every row with its resolved status:

- 🟢 **Will create** — `Name (TYPE, CURR) → BALANCE` with the icon/color preview.
- 🔵 **Will update** — `Name (existing) → new opening balance: BALANCE`.
- 🔴 **Rejected — &lt;reason&gt;** — accounts that won't be touched.

Top summary: `X new accounts, Y updates, Z rejected`. Bottom buttons: **Cancel** and **Apply X+Y rows** (disabled if `X+Y == 0`).

### Apply

On confirm, all `WillCreate` and `WillUpdate` rows are written in a single Room `@Transaction`. Rejected rows are no-ops. On success: dialog dismisses, snackbar `Imported X accounts, updated Y`. On failure: snackbar `Import failed: <reason>` and dialog stays open so the user can retry.

### Error messages (snackbar copy)

- `Couldn't read file.` — I/O error (file moved, permission revoked).
- `Header must be: name,type,currency,balance` — wrong/missing header.
- `File is empty.` — zero-byte file or only blank lines.
- `Import failed: <reason>` — apply-time error.

---

## Architecture

### File layout

**New files:**

| Path | Purpose |
| --- | --- |
| `data/accountimport/AccountImportRepository.kt` | Hilt-injected. Reads `Uri` via `ContentResolver`, orchestrates parse → resolve → apply. Two methods: `preview(uri)` and `apply(preview)`. |
| `data/accountimport/AccountImportRepositoryImpl.kt` | The implementation, takes `Application`, `AccountRepository`, and the pure helpers. Bound via Hilt module. |
| `data/accountimport/AccountImportParser.kt` | Pure Kotlin. Bytes → `ParseResult`. Tiny RFC 4180 parser. |
| `data/accountimport/AccountImportResolver.kt` | Pure Kotlin. Raw rows + current accounts + txn counts → `List<ResolvedImportRow>`. |
| `data/accountimport/AccountTypeDefaults.kt` | Pure constants. `iconFor(type)`, `colorFor(type)`. |
| `data/accountimport/ImportModels.kt` | `RawImportRow`, `ResolvedImportRow`, `ImportStatus`, `ImportPreview`, `ImportApplyResult`, `ParseResult`. |

**Modified files:**

| Path | Change |
| --- | --- |
| `data/local/AccountDao.kt` | Add `@Transaction` suspend fun `applyAccountImport(rows, now)` — single transaction with conditional INSERT/UPDATE per row. |
| `data/repository/AccountRepository.kt` | Delegate `applyAccountImport` to DAO. |
| `di/AccountManagementModule.kt` | Bind `AccountImportRepository` interface to its impl. |
| `ui/settings/SettingsScreen.kt` | New section + state-driven preview dialog. |
| `ui/settings/SettingsViewModel.kt` | New state for `pendingImportPreview`, `applied`, and the three handlers. |
| `res/values/strings.xml` | New strings (see below). |
| `app/src/test/java/.../data/accountimport/AccountImportParserTest.kt` | Pure parser tests. |
| `app/src/test/java/.../data/accountimport/AccountImportResolverTest.kt` | Pure resolver tests. |
| `app/src/test/java/.../data/accountimport/AccountTypeDefaultsTest.kt` | Defaults tests. |
| `app/src/androidTest/java/.../data/accountimport/AccountImportRepositoryTest.kt` | Room in-memory end-to-end. |

### Key types

```kotlin
data class RawImportRow(
    val lineNumber: Int,
    val name: String,
    val type: String,
    val currency: String,
    val balanceMinor: Long,
)

data class ResolvedImportRow(
    val raw: RawImportRow,
    val status: ImportStatus,
)

sealed interface ImportStatus {
    data object WillCreate : ImportStatus
    data object WillUpdate : ImportStatus
    data class Rejected(val reason: String) : ImportStatus
}

sealed interface ParseResult {
    /**
     * @param rows valid rows in input order
     * @param rejected rows the parser couldn't accept (lineNumber, reason)
     */
    data class Ok(
        val rows: List<RawImportRow>,
        val rejected: List<Pair<Int, String>>,
    ) : ParseResult
    data class Failed(val reason: String) : ParseResult
}

data class ImportPreview(
    val fileName: String,
    val rows: List<ResolvedImportRow>,
)

data class ImportApplyResult(val created: Int, val updated: Int)
```

### Repository contract

```kotlin
interface AccountImportRepository {
    /** Read URI, parse, resolve against current accounts. Throws on I/O error. */
    suspend fun preview(uri: Uri): ImportPreview

    /** Apply a previously-previewed import in a single transaction. */
    suspend fun apply(preview: ImportPreview): ImportApplyResult
}
```

The interface lives next to its impl. The VM depends on the interface (mockable in tests).

---

## Component details

### `AccountImportParser`

- **Input:** `ByteArray` (UTF-8 CSV bytes, possibly BOM-prefixed).
- **Output:** `ParseResult`.
- **Behavior:**
  - If bytes start with `EF BB BF`, skip those three bytes.
  - Decode UTF-8, split on `\r\n` / `\n`. Track line number for error messages.
  - Skip fully-empty lines.
  - Hand-rolled RFC 4180 tokenizer: handles `"`-quoted fields, `""` as escaped quote, commas inside quotes.
  - Line 1 must be the header `name,type,currency,balance` (case-insensitive header values, exact column count).
  - Lines 2+ become `RawImportRow`. Validation:
    - 4 fields → else added to `rejected` list with `"line N: expected 4 columns, got X"`.
    - `name` non-empty → else `"line N: name is required"`.
    - `currency` matches `[A-Za-z]{3}` → else `"line N: currency must be a 3-letter code"`.
    - `balance` parses via `MoneyFormat.parseSignedAmountToMinor` → else `"line N: balance is not a valid amount"`.
  - Returns `ParseResult.Ok(validRows, rejectedRows)`. The repository interleaves rejected rows into the preview before passing valid rows to the resolver.

### `AccountImportResolver`

- **Input:** `List<RawImportRow>` (already filtered to valid rows by the parser), `Map<String, AccountEntity>` (lowercased name → entity), `Map<Long, Int>` (account id → transaction count).
- **Output:** `List<ResolvedImportRow>` in input order, preserving `lineNumber`.
- **Logic per row:**
  1. If `seenNames` already contains `raw.name.lowercase()` → `Rejected("duplicate name in file (also on line N)")` where N is the prior row's `lineNumber`.
  2. `val existing = accountsByName[raw.name.lowercase()]`.
  3. If `existing == null` → `WillCreate`.
  4. If `existing.currencyCode != raw.currency` → `Rejected("currency mismatch: account is X, CSV says Y")`.
  5. If `txnCountsByAccountId[existing.id] ?: 0 > 0` → `Rejected("account has N transactions; delete them first")`.
  6. Else → `WillUpdate`.
- After resolving a non-rejected row, add `raw.name.lowercase()` to `seenNames` so subsequent rows in the same import can be flagged.
- **Caller responsibility:** the repository injects parser-rejected rows into the returned `ImportPreview.rows` (mapped to `ResolvedImportRow` with `Rejected(reason)`) **before** passing them to the resolver, so the user sees all rows in one place.

### `AccountTypeDefaults`

```kotlin
object AccountTypeDefaults {
    private val ICON_BY_TYPE = mapOf(
        "CASH" to "💵",
        "BANK" to "🏦",
        "CREDIT_CARD" to "💳",
        "EWALLET" to "📱",
        "OTHER" to "💰",
    )
    private val COLOR_BY_TYPE = mapOf(
        "CASH" to 0xFF43A047.toInt(),         // green
        "BANK" to 0xFF1976D2.toInt(),         // blue
        "CREDIT_CARD" to 0xFFC62828.toInt(),  // red
        "EWALLET" to 0xFFF57C00.toInt(),      // orange
        "OTHER" to 0xFF455A64.toInt(),        // slate
    )
    fun iconFor(type: String): String = ICON_BY_TYPE[type.uppercase()] ?: "💵"
    fun colorFor(type: String): Int = COLOR_BY_TYPE[type.uppercase()] ?: 0xFF1976D2.toInt()
}
```

The fallbacks (💵, blue) match the existing `AddEditAccountViewModel` defaults — visually identical to a freshly-created default account.

### `AccountDao.applyAccountImport`

```kotlin
@Transaction
suspend fun applyAccountImport(rows: List<ResolvedImportRow>, nowEpochMs: Long) {
    var nextSortOrder = maxSortOrder() + 1
    for (row in rows) {
        when (val s = row.status) {
            ImportStatus.WillCreate -> {
                val icon = AccountTypeDefaults.iconFor(row.raw.type)
                val color = AccountTypeDefaults.colorFor(row.raw.type)
                insert(
                    AccountEntity(
                        id = 0,
                        name = row.raw.name,
                        type = row.raw.type,
                        icon = icon,
                        color = color,
                        currencyCode = row.raw.currency,
                        openingBalanceMinor = row.raw.balanceMinor,
                        createdAtEpochMillis = nowEpochMs,
                        sortOrder = nextSortOrder++,
                    )
                )
            }
            is ImportStatus.WillUpdate -> {
                updateOpeningBalanceByName(row.raw.name, row.raw.balanceMinor, nowEpochMs)
            }
            is ImportStatus.Rejected -> Unit  // no-op
        }
    }
}
```

`updateOpeningBalanceByName` is a new DAO query:
```kotlin
@Query("UPDATE accounts SET openingBalanceMinor = :balance WHERE LOWER(name) = LOWER(:name)")
suspend fun updateOpeningBalanceByName(name: String, balance: Long, nowEpochMs: Long): Int
```

The `name` uniqueness is the same rule the resolver used to match, so the row is unambiguous. The update is atomic per-row inside the transaction.

### ViewModel flow

```kotlin
data class SettingsUiState(
    // ... existing fields ...
    val pendingImportPreview: ImportPreview? = null,
    val importInFlight: Boolean = false,
    val importAppliedResult: ImportApplyResult? = null,
)

fun onImportCsvPicked(uri: Uri) {
    _state.update { it.copy(importInFlight = true) }
    viewModelScope.launch {
        try {
            val preview = accountImportRepository.preview(uri)
            _state.update { it.copy(pendingImportPreview = preview, importInFlight = false) }
        } catch (e: Exception) {
            _state.update { it.copy(importInFlight = false) }
            emitSnack(R.string.import_csv_read_error)
        }
    }
}

fun onImportConfirm() {
    val preview = _state.value.pendingImportPreview ?: return
    _state.update { it.copy(importInFlight = true) }
    viewModelScope.launch {
        try {
            val result = accountImportRepository.apply(preview)
            _state.update { it.copy(pendingImportPreview = null, importAppliedResult = result, importInFlight = false) }
            emitSnack(R.string.import_csv_done, result.created, result.updated)
        } catch (e: Exception) {
            _state.update { it.copy(importInFlight = false) }
            emitSnack(R.string.import_csv_failed, e.message ?: "unknown error")
        }
    }
}

fun onImportDismiss() {
    _state.update { it.copy(pendingImportPreview = null) }
}
```

`emitSnack` writes to the existing `SnackbarHostState`-driven message flow.

---

## Edge cases & decisions

| Case | Behavior |
| --- | --- |
| Same name appears twice in CSV | Both rows kept. First occurrence applied; second is rejected `"line N: duplicate name in file"`. |
| Empty file or only blank lines | `ParseResult.Failed("File is empty.")`. Snackbar shown, no preview. |
| Header has wrong column count | `ParseResult.Failed("Header must be: name,type,currency,balance")`. |
| Header has extra columns | Same as wrong column count (we only read first 4). |
| All rows rejected | Preview shown, Apply button disabled, message "All rows were rejected; fix your CSV and try again." |
| Mixed create/update/reject | Apply runs only create + update; rejected rows surface in preview for awareness but are no-ops. |
| Account name match is exact-by-case after lowercase | Whitespace differences (leading/trailing) are trimmed at parse time. |
| Negative balance | Accepted everywhere it appears. |
| Zero balance | Accepted (account starts at zero). |
| Very large balance (> `MAX_AMOUNT_WHOLE`) | Rejected `"line N: balance exceeds maximum"` (matches existing `MoneyFormat` behavior). |
| Quoted field contains a newline | Accepted (RFC 4180). |
| File is a non-CSV that slipped through the picker | Header validation fails, snackbar with the header error. |
| User cancels file picker | `pendingImportPreview` stays null, no error. |
| User cancels preview dialog | `onImportDismiss()` clears state, no DB writes. |

---

## Strings (added)

```xml
<string name="import_csv_section_title">Import accounts from CSV</string>
<string name="import_csv_section_subtitle">Bulk-create accounts or update opening balances from a 4-column CSV file.</string>
<string name="import_csv_button">Choose CSV file</string>
<string name="import_csv_preview_title">Import %1$d rows from %2$s</string>
<string name="import_csv_summary">%1$d new accounts, %2$d updates, %3$d rejected</string>
<string name="import_csv_status_will_create">Will create</string>
<string name="import_csv_status_will_update">Will update</string>
<string name="import_csv_status_rejected">Rejected — %1$s</string>
<string name="import_csv_apply">Apply %1$d rows</string>
<string name="import_csv_cancel">Cancel</string>
<string name="import_csv_all_rejected">All rows were rejected; fix your CSV and try again.</string>
<string name="import_csv_read_error">Couldn\'t read file.</string>
<string name="import_csv_header_error">Header must be: name,type,currency,balance</string>
<string name="import_csv_empty_error">File is empty.</string>
<string name="import_csv_done">Imported %1$d accounts, updated %2$d.</string>
<string name="import_csv_failed">Import failed: %1$s</string>
```

---

## Testing strategy

### Pure helpers (JUnit, in `app/src/test/`)

- `AccountImportParserTest` (~12 cases):
  - happy path with 3 rows
  - strips UTF-8 BOM
  - handles CRLF and LF line endings
  - handles RFC 4180 quoted fields (`"Hello, world"`, `""` escaped quote)
  - skips blank lines
  - rejects invalid header
  - rejects wrong column count on a row
  - rejects blank name on a row
  - rejects non-3-letter currency on a row
  - rejects invalid balance on a row
  - accepts negative balance
  - empty file → `ParseResult.Failed`

- `AccountImportResolverTest` (~8 cases):
  - missing account → `WillCreate`
  - existing account, no txns, currency match → `WillUpdate`
  - existing account, currency mismatch → `Rejected`
  - existing account, has txns → `Rejected`
  - case-insensitive name match
  - unknown type falls back to 💵 + blue
  - preserves `lineNumber` and input order
  - duplicate name within same CSV → second row `Rejected("duplicate name in file")`

- `AccountTypeDefaultsTest` (~4 cases):
  - known types map to expected icon/color
  - unknown types fall back to defaults

### Repository + DAO (Room in-memory, in `app/src/androidTest/`)

- `AccountImportRepositoryTest`:
  - `preview_willCreateForNewAccounts`
  - `preview_willUpdateForExistingAccounts`
  - `preview_rejectedForCurrencyMismatch`
  - `preview_rejectedForAccountWithTxns`
  - `apply_persistsCreatedAccountsInSingleTransaction`
  - `apply_updatesOpeningBalanceOnly`
  - `apply_respectsTypeDefaultsForIconAndColor`
  - `apply_rejectedRowsAreNoOp`

### Manual test plan (user device)

1. Create `sample-accounts.csv` with 3 rows: one new account, one existing match, one existing-but-currency-mismatch.
2. Settings → Import accounts from CSV → Choose CSV file → pick `sample-accounts.csv`.
3. Verify preview shows 1 WillCreate (green), 1 WillUpdate (blue), 1 Rejected (red, currency mismatch).
4. Tap Apply → verify snackbar "Imported 1 accounts, updated 1."
5. Navigate to More → Accounts → verify new account present with correct icon/color and opening balance.
6. Re-pick the same CSV → all 3 rows now rejected (existing accounts now have opening balances / currency mismatches unchanged).
7. Delete the new account via Delete Account (v0.16.0). Repeat test with empty file → verify "File is empty." snackbar.

### Test fixture

Add `docs/superpowers/testdata/sample-accounts.csv` containing:
```csv
name,type,currency,balance
Cash,CASH,USD,250.00
BPI Savings,BANK,PHP,15000.00
AmEx Credit,CREDIT_CARD,USD,-120.50
```

This is checked into the repo (testdata is fine to commit) for the manual test plan. The unit tests build their own byte arrays.

---

## Open questions

None at design time. Resolved through brainstorming:
- CSV format = `name,type,currency,balance` (4 cols).
- Match = case-insensitive name.
- Semantic = opening balance, fail if existing has transactions.
- Currency mismatch = reject the row.
- Defaults = type-specific icon + color.
- Locale = English-style only.
- UX = preview dialog with row statuses.
- Entry point = Settings screen.
- Architecture = pure parser + pure resolver + new `AccountImportRepository`.