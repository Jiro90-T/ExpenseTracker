# Investment Account — Live Market Price Linking — Design

> **For engineers:** REQUIRED SUB-SKILL: Use superpowers:writing-plans to author the implementation plan before touching code.

**Goal:** Add an `INVESTMENT` account type that holds multiple positions (symbol × quantity × cost basis) and links each to a live market price from a free, no-key data feed, with FX-converted rollup to the account's display currency.

**Architecture:** Three new Room tables (`investment_holdings`, `cached_quotes`, plus an account-type migration to add `INVESTMENT`). A new `MarketDataClient` interface with a `YahooMarketDataClient` impl. A `QuoteRepository` that owns cache write-through. A separate `InvestmentAccountDetailScreen` rendered when `account.type == "INVESTMENT"`. Existing cash-account code paths are untouched.

**Tech Stack:** Room, Hilt, Kotlin Coroutines + Flow, OkHttp (already on classpath), `org.json` (already on test classpath), Jetpack Compose. No new production dependencies.

---

## 1. Background

The existing `AccountEntity.type` is a free-form string with five presets (`CASH`, `BANK`, `CREDIT_CARD`, `EWALLET`, `OTHER`) and a custom-text fallback. Account balance is computed as `Σ transaction.amountMinor` for that account. There is no concept of holdings (symbol × quantity), no market data integration, and no notion of unrealized gain/loss.

Users want to track investment portfolios (stocks and crypto) alongside their regular cash accounts, with **automatic price updates** so they don't have to type the current value every time they open the app. Cost-basis entry is manual for v1 (no buy/sell transactions).

## 2. Goals & Non-Goals

**Goals**
- `Investment` appears as a 6th preset in the account-type dropdown
- Investment accounts hold N positions: symbol, quantity, cost basis (total), per-holding currency
- A free no-key market feed (Yahoo Finance public endpoint) provides current prices for both stocks and crypto
- Each holding shows current value = `quantity × cachedQuote.price`, plus unrealized gain/loss vs. cost basis
- Account total rolls up via existing `FxConverter` to the account's display currency
- Quotes are cached in Room; account detail auto-refreshes on open; cached values persist offline with a stale badge
- Symbol entry is manual; no autocomplete/search
- Per-holding row + total + refresh control + add/edit/delete holding

**Non-goals**
- No buy/sell transaction types in v1 (holdings entered with starting quantity + total cost basis)
- No price history / chart in v1 (current snapshot only)
- No dividends, splits, or corporate actions
- No API key management (free tier, no key)
- No WorkManager / background polling (on-demand + cached only)
- No symbol search/autocomplete
- No cross-account portfolio rollup in v1 (one investment account at a time on screen)
- No FX rate fetching — reuses manually-entered rates already in `SettingsRepository`

## 3. Data Model

### 3.1 `accounts` table — schema unchanged

Soft migration: `type` now also accepts `"INVESTMENT"`. No column added, no schema version bump. Existing queries ignore it (investment account balance = `Σ transaction.amountMinor`, which is `0` since investments don't have transactions).

The account's `currencyCode` becomes meaningful for investment accounts: it is the **display/rollup currency** the user wants to see totals in. The home currency in `SettingsRepository.homeCurrency` is unrelated.

### 3.2 `investment_holdings` table (new)

```kotlin
@Entity(
    tableName = "investment_holdings",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["symbol"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InvestmentHoldingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val accountId: Long,
    /** Uppercased ticker, e.g. "AAPL", "BTC", "7203.T". */
    val symbol: String,
    /** Fractional shares allowed (crypto, DRIP). */
    val quantity: Double,
    /** Total cost in `currencyCode` minor units. */
    val costBasisMinor: Long,
    /** ISO 4217 code matching the symbol's native currency (USD, JPY, ...). */
    val currencyCode: String,
    val createdAtEpochMillis: Long,
)
```

### 3.3 `cached_quotes` table (new)

```kotlin
@Entity(tableName = "cached_quotes")
data class CachedQuoteEntity(
    /** Uppercased ticker. */
    @PrimaryKey val symbol: String,
    /** Latest known price in `currencyCode` minor units. */
    val priceMinor: Long,
    val currencyCode: String,
    val fetchedAtEpochMillis: Long,
)
```

One row per symbol, shared across all accounts that hold it.

### 3.4 Cost basis and currency

- **Cost basis** = total amount paid, stored in minor units. Per-share display = `costBasisMinor / quantity` rounded to 2dp.
- **Currency per holding** = the symbol's native currency (what the price feed returns). The account-level `currencyCode` is the rollup currency.
- **Account total** = `Σ FxConverter.convertMinor(holding.marketValueMinor, holding.currency, account.currency, fxRates)` for each holding that has a cached quote AND a known FX rate.

## 4. Market Data Layer

### 4.1 `MarketDataClient` interface

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/data/market/MarketDataClient.kt
interface MarketDataClient {
    /**
     * Fetches latest quotes for [symbols]. Returns one Quote? per requested
     * symbol, in the same order; null for symbols the feed didn't recognize.
     * Throws [MarketDataException] on transport failure only.
     */
    suspend fun fetchQuotes(symbols: List<String>): List<Quote?>
}

data class Quote(
    val symbol: String,
    val priceMinor: Long,
    val currencyCode: String,
    val asOfEpochMillis: Long,
)

class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

### 4.2 `YahooMarketDataClient` (impl)

Endpoint: `GET https://query1.finance.yahoo.com/v7/finance/quote?symbols=AAPL,BTC-USD,7203.T`

Response: `quoteResponse.result[]` with `regularMarketPrice` (double), `currency` (String), `regularMarketTime` (long seconds), `marketState` (String).

Scaling: Yahoo prices are in the symbol's natural precision. `MoneyFormat.toMinor(price, currency)` converts to minor units using existing per-currency scaling (USD=100, JPY=1, BTC=100).

User-Agent: `okhttp` default. Yahoo occasionally rejects requests without a UA — `OkHttpClient.Builder().addInterceptor { addHeader("User-Agent", "Mozilla/5.0") }`.

### 4.3 `QuoteRepository`

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/data/market/QuoteRepository.kt
@Singleton
class QuoteRepository @Inject constructor(
    private val client: MarketDataClient,
    private val quoteDao: CachedQuoteDao,
) {
    fun observeCached(symbol: String): Flow<CachedQuoteEntity?>
    fun observeAllCached(symbols: List<String>): Flow<Map<String, CachedQuoteEntity>>
    /**
     * Fetches and writes-through. Per-symbol outcome reflects what
     * actually happened; throws only on full transport failure.
     */
    suspend fun refresh(symbols: List<String>): RefreshOutcome
}

sealed interface SymbolOutcome {
    object Fresh : SymbolOutcome
    object Unknown : SymbolOutcome
    data class Failed(val reason: String) : SymbolOutcome
}

data class RefreshOutcome(
    val perSymbol: Map<String, SymbolOutcome>,
)
```

Unknown symbols do **not** overwrite the existing cache row. Transport failures do **not** clear the cache.

### 4.4 Per-currency amount scaling

Yahoo prices are doubles in the symbol's natural precision (USD=2dp, JPY=0dp, BTC=2dp at low values). The client uses `MoneyFormat.toMinor(price, currency)` to produce a Long. `MoneyFormat.formatForDisplay(minor)` for display.

When Yahoo returns BTC at e.g. `67234.56`, `MoneyFormat.toMinor(67234.56, "BTC")` = `6723456` (BTC scaling is 100). Display formats that back to `67234.56`. Existing `MoneyFormat` already encodes currency scaling; no new helper needed.

## 5. UI & Navigation

### 5.1 Account list — minimal change

`AddEditAccountViewModel.ACCOUNT_TYPE_PRESETS` adds `"INVESTMENT"`. `presetLabel("INVESTMENT")` returns the new string resource `account_type_investment`. `ACCOUNT_ICON_CHOICES` adds `"📈"`. The list screen renders all account types uniformly; no branching in `AccountsListScreen`.

### 5.2 Add/Edit account — no change

Existing form handles the new preset via the dropdown. No new fields.

### 5.3 Nav additions (`AppNav.kt`)

```kotlin
object Routes {
    ...
    const val INVESTMENT_ACCOUNT_DETAIL = "investment_account/{accountId}"
    const val INVESTMENT_ACCOUNT_DETAIL_ARG_ID = "accountId"
    // holdingId absent (default -1L) = Add; present = Edit. Mirrors the
    // existing add_edit?id={id} pattern in this file.
    const val INVESTMENT_HOLDING_EDIT = "investment_account/{accountId}/holding?id={holdingId}"
    const val INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID = "accountId"
    const val INVESTMENT_HOLDING_EDIT_ARG_HOLDING_ID = "holdingId"
}
```

`AccountsListScreen.onAccountClick` branches by `account.type`:
- `"INVESTMENT"` → `INVESTMENT_ACCOUNT_DETAIL`
- everything else → existing `ACCOUNT_DETAIL`

This is the **only** change to existing cash-account code paths. The investment detail screen's edit-account icon navigates to the existing `ACCOUNT_EDIT_WITH_ID` route; the close-account icon calls into a new `InvestmentAccountDetailViewModel.onCloseClick` that mirrors `AccountDetailViewModel`'s close flow (reuses `AccountRepository.close`).

### 5.4 `InvestmentAccountDetailScreen`

Top app bar: account name, refresh icon (top-right), edit-account icon, close-account icon (only on existing close flow), no transactions/delete icons (handled differently — see §6.1).

Body, top to bottom:
1. **Account total card**: large amount in `account.currencyCode`, subtitle "Across N holdings, M currencies" (only when M > 1).
2. **Cost basis line**: "Total invested: X · Current: Y · Unrealized: ±Z (color)" — `Z = currentValue − totalCost`, both FX-converted to account currency.
3. **FX warning chip** (only when ≥1 holding's FX rate is missing): "Rate missing: JPY → USD — add in Settings".
4. **Holdings list**: each row
   - `SYMBOL · qty @ avg cost/share` (subtitle)
   - Current value (holding currency), right-aligned
   - Unrealized gain/loss in account currency (small text, color-coded)
   - Stale badge if `cachedQuote.fetchedAtEpochMillis < now − 6h` ("Updated 8h ago")
   - Tap → edit holding
5. **Empty state**: "No holdings yet" + Add holding button (FAB)
6. **Add holding FAB** (or top app bar action when non-empty)

Refresh behavior:
- `InvestmentAccountDetailViewModel.init` calls `QuoteRepository.refresh(uniqueSymbols)` once after holdings emit.
- User-tapped refresh button → calls `refresh()` again, shows inline progress indicator.
- Network failure → snackbar "Couldn't refresh prices — showing last cached"; cache unchanged.

### 5.5 `AddEditHoldingScreen`

Fields:
- Symbol (text, uppercased on save, validated as non-blank + ≤12 chars)
- Quantity (decimal, must be > 0)
- Cost basis total (decimal, in `currencyCode` minor units after parse)
- Currency (dropdown: USD, JPY, EUR, GBP, BTC, ETH — same list as `SettingsRepository` for currencies; default inferred from symbol suffix on initial entry: `.T` → JPY, else USD; user can override)

No live symbol validation against the feed — that's deferred to the refresh button. Unknown symbols surface as "No price" rows on the detail screen.

Long-press on a holding row → context menu (Edit / Delete).

## 6. Edge Cases

### 6.1 Account deletion with holdings

Extend `DeleteGuard` with `BLOCK_HOLDINGS_EXIST`. `AccountDetailViewModel.onDeleteClick` already counts transactions; add holdings count from `InvestmentHoldingDao.countByAccount(id)`. If `holdings > 0`, show the same blocked-dialog pattern.

### 6.2 FX rate missing

`FxConverter.convertMinor` returns null when `"${holding.currency}_to_${account.currency}"` isn't in `SettingsRepository.fxRates`. That holding's contribution is excluded from the total. Per-holding row still shows in the holding's native currency. A summary chip lists missing rates.

### 6.3 Stale threshold

`private const val STALE_THRESHOLD_MS = 6L * 60L * 60L * 1000L` in `InvestmentAccountDetailViewModel`. Drives the badge; not user-configurable in v1.

### 6.4 Unknown symbol after refresh

Holding row shows `—` for current value with "No price" hint. Refresh skips it; cache untouched. The user can edit/delete the holding normally.

### 6.5 Empty account (zero holdings)

Detail screen shows the total card as `0.00`, empty-state list, FAB to add. Refresh is a no-op.

### 6.6 Offline

No network on detail open → no refresh call (skip in VM init when `ConnectivityManager` reports no network — or alternatively always call and let the client throw → caught by repository → empty `RefreshOutcome`). v1: skip on no network via `ConnectivityManager.activeNetwork`, otherwise call.

### 6.7 Account currency vs. holding currency

The account's `currencyCode` is the user's choice at account creation (e.g. "USD" even when holding Japanese stocks). FX rates are relative to home currency, but `FxConverter.convertMinor` only needs `${from}_to_${to}` — it does not care what "home" is. Documented in the existing `FxConverter` source.

## 7. Testing

| File | Type | Covers |
|------|------|--------|
| `YahooMarketDataClientTest.kt` | Unit + MockWebServer | happy path, partial response, empty result, 500, malformed JSON, network timeout |
| `QuoteRepositoryTest.kt` | Unit, fake client + in-memory Room | cache write-through, unknown-symbol leaves cache untouched, transport failure leaves cache untouched |
| `MoneyFormatScalingTest.kt` | Unit | Yahoo BTC/USD/JPY price → minor unit round-trip |
| `InvestmentAccountDetailViewModelTest.kt` | Unit, fakes | FX rollup math (mixed USD+JPY holdings), FX-missing exclusion, stale detection, refresh triggers network once |
| `AddEditHoldingViewModelTest.kt` | Unit | quantity > 0, cost basis parse, symbol uppercased, currency inferred from suffix |
| `DeleteGuardEvaluateTest.kt` | Unit | `BLOCK_HOLDINGS_EXIST` triggers when N≥1 |
| Smoke test plan in this spec | Manual | open account → add 2 holdings → refresh → total updates → offline → cached |

Manual smoke (Phase plan verification):
1. Add account "Brokerage", type=Investment, currency=USD
2. Add holding AAPL qty=10 cost=$1500 USD
3. Add holding 7203.T qty=100 cost=¥200,000 JPY
4. Set FX rate USD→JPY = 150 in Settings (already works)
5. Open account detail → refresh fires → prices appear → rollup shows USD total
6. Toggle airplane mode → re-open account → still shows cached values with stale badge

## 8. File Structure

**New files:**
- `app/src/main/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingEntity.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingDao.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/local/CachedQuoteEntity.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/local/CachedQuoteDao.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/market/Quote.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/market/MarketDataClient.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/market/MarketDataException.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/market/YahooMarketDataClient.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/market/QuoteRepository.kt`
- `app/src/main/java/io/github/jiro/expensetracker/di/MarketDataModule.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailScreen.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModel.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingScreen.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModel.kt`

**Modified files:**
- `app/src/main/java/io/github/jiro/expensetracker/data/local/ExpenseDatabase.kt` — bump version, add new entities/DAOs (or use `fallbackToDestructiveMigration` for v1 since investment accounts are new)
- `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt` — add `countHoldings(accountId)` for delete guard
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModel.kt` — emit `account.type` alongside balance for branching
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt` — add `INVESTMENT` to `ACCOUNT_TYPE_PRESETS`, add `📈` to `ACCOUNT_ICON_CHOICES`
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt` — extend `evaluateDelete` to consider holdings count
- `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt` — add 2 routes, branch `onAccountClick` by `account.type`
- `app/src/main/res/values/strings.xml` — `account_type_investment`, refresh, stale, no-price, fx-missing, holding labels
- `app/src/main/AndroidManifest.xml` — `INTERNET` permission (verify present; app already calls Drive/Dropbox so likely already declared)
- `gradle/libs.versions.toml` — no new deps

## 9. Out-of-Scope / Future

- Buy/sell transactions feeding into holdings via FIFO/avg-cost
- Symbol search / autocomplete via Yahoo search endpoint
- Background periodic refresh (WorkManager)
- Price history / charts
- Dividends, splits, corporate actions
- Cross-account portfolio rollup
- Multiple API providers (Finnhub, Alpha Vantage) with provider selection
- API key support for paid tiers
- Tax reporting / realized gain/loss
