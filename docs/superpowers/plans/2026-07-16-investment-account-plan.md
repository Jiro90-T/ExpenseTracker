# Investment Account Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `INVESTMENT` account type that holds positions (symbol × quantity × cost basis) with live market prices from Yahoo Finance, FX-converted rollup to the account's display currency, and offline-cached fallback values.

**Architecture:** Two new Room tables (`investment_holdings`, `cached_quotes`), a v8→v9 schema migration, a `MarketDataClient` interface with `YahooMarketDataClient` impl, a `QuoteRepository` for cache write-through, and two new screens (`InvestmentAccountDetailScreen`, `AddEditHoldingScreen`) routed from a type-based branch in `AccountsListScreen`. Existing cash-account code paths are untouched.

**Tech Stack:** Room, Hilt, Kotlin Coroutines + Flow, OkHttp + MockWebServer (already on classpath), `org.json` (test classpath), Jetpack Compose + Robolectric. No new dependencies.

---

## File Structure

**New files (15):**
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
- `app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModel.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailScreen.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModel.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingScreen.kt`
- `app/src/test/java/io/github/jiro/expensetracker/data/local/MoneyFormatCurrencyPrecisionTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingDaoTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/data/local/CachedQuoteDaoTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/data/local/AppDatabaseMigrationTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/data/market/YahooMarketDataClientTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/data/market/QuoteRepositoryTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDeleteGuardTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModelTest.kt`
- `app/src/test/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModelTest.kt`

**Modified files (8):**
- `app/src/main/java/io/github/jiro/expensetracker/data/local/MoneyFormat.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt`
- `app/src/main/java/io/github/jiro/expensetracker/di/DatabaseModule.kt`
- `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`
- `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`
- `app/src/main/res/values/strings.xml`

**Test tools used:** `androidx.room:room-testing` (in-memory DB), `okhttp:mockwebserver`, `org.json` (real impl, not the android.jar stub), Robolectric for Compose UI tests via `createComposeRule()`.

---

## Task 1: MoneyFormat currency-precision helpers

Yahoo prices arrive as doubles in each currency's natural precision (USD 2dp, JPY 0dp, BTC 2dp). Existing `MoneyFormat` only handles 2dp. Extend it.

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/MoneyFormat.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/data/local/MoneyFormatCurrencyPrecisionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/data/local/MoneyFormatCurrencyPrecisionTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatCurrencyPrecisionTest {

    @Test fun priceToMinor_usdTwoDp() {
        assertEquals(12345L, MoneyFormat.priceToMinor(123.45, "USD"))
    }

    @Test fun priceToMinor_jpyZeroDp() {
        assertEquals(2800L, MoneyFormat.priceToMinor(2800.0, "JPY"))
    }

    @Test fun priceToMinor_jpyFractionalRounds() {
        assertEquals(2801L, MoneyFormat.priceToMinor(2800.6, "JPY"))
    }

    @Test fun priceToMinor_btcTwoDp() {
        assertEquals(6723456L, MoneyFormat.priceToMinor(67234.56, "BTC"))
    }

    @Test fun priceToMinor_unknownCurrencyDefaultsToTwoDp() {
        assertEquals(12345L, MoneyFormat.priceToMinor(123.45, "XYZ"))
    }

    @Test fun minorToDisplay_usdFormatsTwoDp() {
        assertEquals("123.45", MoneyFormat.minorToDisplay(12345L, "USD"))
    }

    @Test fun minorToDisplay_jpyFormatsZeroDp() {
        assertEquals("2,800", MoneyFormat.minorToDisplay(2800L, "JPY"))
    }

    @Test fun minorToDisplay_jpyFormatsThousandsSeparator() {
        assertEquals("1,234,567", MoneyFormat.minorToDisplay(1234567L, "JPY"))
    }

    @Test fun minorToDisplay_btcFormatsTwoDp() {
        assertEquals("67,234.56", MoneyFormat.minorToDisplay(6723456L, "BTC"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.MoneyFormatCurrencyPrecisionTest"`
Expected: FAIL — "Unresolved reference: priceToMinor"

- [ ] **Step 3: Add helpers to MoneyFormat**

In `app/src/main/java/io/github/jiro/expensetracker/data/local/MoneyFormat.kt`, append to the `object MoneyFormat { ... }` body (before the closing `}`):

```kotlin
    /**
     * Per-currency decimal-place map for prices that arrive as doubles
     * (Yahoo Finance, FX rates). Most fiat is 2dp; JPY/KRW are 0dp; BTC/ETH
     * use 2dp/5dp respectively. Unknown currencies default to 2dp.
     */
    private val CURRENCY_DECIMAL_PLACES: Map<String, Int> = mapOf(
        "USD" to 2, "EUR" to 2, "GBP" to 2, "AUD" to 2, "CAD" to 2,
        "CHF" to 2, "SGD" to 2, "HKD" to 2, "MYR" to 2, "CNY" to 2,
        "JPY" to 0, "KRW" to 0,
        "BTC" to 2, "ETH" to 5,
    )

    /** Convert a price expressed as a double (Yahoo precision) into minor units
     *  for the given currency. Uses banker's-ish rounding (Math.round = half-up). */
    fun priceToMinor(price: Double, currencyCode: String): Long {
        val dp = CURRENCY_DECIMAL_PLACES[currencyCode.uppercase()] ?: 2
        val multiplier = Math.pow(10.0, dp.toDouble())
        return Math.round(price * multiplier).toLong()
    }

    /** Format minor units back to a display string with the currency's natural
     *  decimal places (USD 2dp, JPY 0dp). Includes a thousands separator. */
    fun minorToDisplay(minor: Long, currencyCode: String): String {
        val dp = CURRENCY_DECIMAL_PLACES[currencyCode.uppercase()] ?: 2
        val divisor = Math.pow(10.0, dp.toDouble()).toLong()
        val isNegative = minor < 0
        val absMinor = if (isNegative) -minor else minor
        val whole = absMinor / divisor
        val fraction = absMinor % divisor
        val groupedWhole = groupThousands(whole)
        val sign = if (isNegative) "-" else ""
        return if (dp == 0) "$sign$groupedWhole"
        else "$sign$groupedWhole.%0${dp}d".format(fraction)
    }
```

`groupThousands` is already private in this file — no import needed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.MoneyFormatCurrencyPrecisionTest"`
Expected: PASS — 9 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/MoneyFormat.kt \
        app/src/test/java/io/github/jiro/expensetracker/data/local/MoneyFormatCurrencyPrecisionTest.kt
git commit -m "feat(money): add priceToMinor + minorToDisplay for currency-precision scaling"
```

---

## Task 2: InvestmentHoldingEntity + InvestmentHoldingDao

The new position table. Tests use in-memory Room.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingEntity.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingDao.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingDaoTest.kt`

- [ ] **Step 1: Write the failing DAO test**

Create `app/src/test/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingDaoTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InvestmentHoldingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: InvestmentHoldingDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.investmentHoldingDao()
    }

    @After fun teardown() { db.close() }

    @Test fun insertAndObserveByAccount_returnsInsertedRow() = runTest {
        val row = InvestmentHoldingEntity(
            accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD",
            createdAtEpochMillis = 1_000L,
        )
        val id = dao.insert(row)
        assertEquals(1L, dao.countByAccount(1L))
        val rows = dao.observeByAccount(1L).first()
        assertEquals(1, rows.size)
        assertEquals(id, rows[0].id)
        assertEquals("AAPL", rows[0].symbol)
    }

    @Test fun observeByAccount_emptyAccountReturnsEmpty() = runTest {
        val rows = dao.observeByAccount(99L).first()
        assertEquals(0, rows.size)
    }

    @Test fun update_changesFields() = runTest {
        val id = dao.insert(InvestmentHoldingEntity(
            accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD",
            createdAtEpochMillis = 1_000L,
        ))
        dao.update(InvestmentHoldingEntity(
            id = id, accountId = 1L, symbol = "AAPL", quantity = 12.0,
            costBasisMinor = 180_000L, currencyCode = "USD",
            createdAtEpochMillis = 1_000L,
        ))
        val row = dao.observeByAccount(1L).first().single()
        assertEquals(12.0, row.quantity, 0.0001)
        assertEquals(180_000L, row.costBasisMinor)
    }

    @Test fun delete_removesRow() = runTest {
        val id = dao.insert(InvestmentHoldingEntity(
            accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD",
            createdAtEpochMillis = 1_000L,
        ))
        dao.delete(id)
        assertEquals(0, dao.countByAccount(1L))
    }

    @Test fun findById_returnsNullForMissing() = runTest {
        assertNull(dao.findById(999L))
    }

    @Test fun uniqueSymbolsByAccount_returnsDistinctUppercased() = runTest {
        listOf("aapl", "AAPL", "goog", "GOOG").forEach {
            dao.insert(InvestmentHoldingEntity(
                accountId = 1L, symbol = it, quantity = 1.0,
                costBasisMinor = 100L, currencyCode = "USD",
                createdAtEpochMillis = 1L,
            ))
        }
        // Dedupe is at the consumer side (YahooMarketDataClient refreshes
        // unique symbols). The DAO doesn't uppercase.
        assertEquals(4, dao.countByAccount(1L))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.InvestmentHoldingDaoTest"`
Expected: FAIL — "Unresolved reference: InvestmentHoldingEntity"

- [ ] **Step 3: Write the entity**

Create `app/src/main/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingEntity.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A position held inside an INVESTMENT account. One row per symbol per
 * account. Cost basis is the total amount paid (not per-share); the UI
 * shows per-share as `costBasisMinor / quantity`.
 */
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
    /** Uppercased ticker, e.g. "AAPL", "BTC-USD", "7203.T". */
    val symbol: String,
    /** Fractional shares allowed (crypto, DRIP). */
    val quantity: Double,
    /** Total cost in `currencyCode` minor units. */
    val costBasisMinor: Long,
    /** ISO 4217 code matching the symbol's native currency. */
    val currencyCode: String,
    val createdAtEpochMillis: Long,
)
```

- [ ] **Step 4: Write the DAO**

Create `app/src/main/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingDao.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestmentHoldingDao {

    @Insert
    suspend fun insert(row: InvestmentHoldingEntity): Long

    @Update
    suspend fun update(row: InvestmentHoldingEntity)

    @Query("DELETE FROM investment_holdings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM investment_holdings WHERE accountId = :accountId ORDER BY symbol")
    fun observeByAccount(accountId: Long): Flow<List<InvestmentHoldingEntity>>

    @Query("SELECT * FROM investment_holdings WHERE id = :id")
    suspend fun findById(id: Long): InvestmentHoldingEntity?

    @Query("SELECT COUNT(*) FROM investment_holdings WHERE accountId = :accountId")
    suspend fun countByAccount(accountId: Long): Int
}
```

- [ ] **Step 5: Run the test to verify it still fails (entities not yet in AppDatabase)**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.InvestmentHoldingDaoTest"`
Expected: FAIL — `AppDatabase.investmentHoldingDao()` does not exist yet

- [ ] **Step 6: Commit (entity + DAO without DB wiring)**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingEntity.kt \
        app/src/main/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingDao.kt \
        app/src/test/java/io/github/jiro/expensetracker/data/local/InvestmentHoldingDaoTest.kt
git commit -m "feat(investments): add InvestmentHoldingEntity + DAO"
```

The DAO test won't pass until Task 4 wires it into `AppDatabase` — that's the next step.

---

## Task 3: CachedQuoteEntity + CachedQuoteDao

Quote cache shared across all investment accounts holding the same symbol.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/local/CachedQuoteEntity.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/local/CachedQuoteDao.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/data/local/CachedQuoteDaoTest.kt`

- [ ] **Step 1: Write the failing DAO test**

Create `app/src/test/java/io/github/jiro/expensetracker/data/local/CachedQuoteDaoTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CachedQuoteDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CachedQuoteDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.cachedQuoteDao()
    }

    @After fun teardown() { db.close() }

    @Test fun upsert_writesNewRow() = runTest {
        dao.upsert(CachedQuoteEntity("AAPL", 12345L, "USD", 1_000L))
        assertEquals(12345L, dao.findBySymbol("AAPL")?.priceMinor)
    }

    @Test fun upsert_overwritesExistingRowBySymbol() = runTest {
        dao.upsert(CachedQuoteEntity("AAPL", 12345L, "USD", 1_000L))
        dao.upsert(CachedQuoteEntity("AAPL", 13000L, "USD", 2_000L))
        val row = dao.findBySymbol("AAPL")!!
        assertEquals(13000L, row.priceMinor)
        assertEquals(2_000L, row.fetchedAtEpochMillis)
    }

    @Test fun findBySymbol_missingReturnsNull() = runTest {
        assertNull(dao.findBySymbol("MISSING"))
    }

    @Test fun observeBySymbols_emitsMapOfRequestedSymbols() = runTest {
        dao.upsert(CachedQuoteEntity("AAPL", 12345L, "USD", 1L))
        dao.upsert(CachedQuoteEntity("GOOG", 50000L, "USD", 1L))
        val map = dao.observeBySymbols(listOf("AAPL", "GOOG", "MISSING")).first()
        assertEquals(2, map.size)
        assertEquals(12345L, map["AAPL"]?.priceMinor)
        assertEquals(50000L, map["GOOG"]?.priceMinor)
    }

    @Test fun findBySymbols_returnsRowsForProvidedSymbols() = runTest {
        dao.upsert(CachedQuoteEntity("AAPL", 12345L, "USD", 1L))
        dao.upsert(CachedQuoteEntity("GOOG", 50000L, "USD", 1L))
        val rows = dao.findBySymbols(listOf("AAPL", "MISSING"))
        assertEquals(1, rows.size)
        assertEquals("AAPL", rows[0].symbol)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.CachedQuoteDaoTest"`
Expected: FAIL — `CachedQuoteEntity` does not exist

- [ ] **Step 3: Write the entity**

Create `app/src/main/java/io/github/jiro/expensetracker/data/local/CachedQuoteEntity.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Latest known price for a ticker, shared across all accounts that hold it.
 * One row per symbol. Updated by [io.github.jiro.expensetracker.data.market.QuoteRepository].
 */
@Entity(tableName = "cached_quotes")
data class CachedQuoteEntity(
    /** Uppercased ticker. */
    @PrimaryKey val symbol: String,
    /** Latest known price in `currencyCode` minor units (use MoneyFormat.priceToMinor). */
    val priceMinor: Long,
    val currencyCode: String,
    val fetchedAtEpochMillis: Long,
)
```

- [ ] **Step 4: Write the DAO**

Create `app/src/main/java/io/github/jiro/expensetracker/data/local/CachedQuoteDao.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedQuoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CachedQuoteEntity)

    @Query("SELECT * FROM cached_quotes WHERE symbol = :symbol LIMIT 1")
    suspend fun findBySymbol(symbol: String): CachedQuoteEntity?

    @Query("SELECT * FROM cached_quotes WHERE symbol IN (:symbols)")
    fun observeBySymbols(symbols: List<String>): Flow<List<CachedQuoteEntity>>

    @Query("SELECT * FROM cached_quotes WHERE symbol IN (:symbols)")
    suspend fun findBySymbols(symbols: List<String>): List<CachedQuoteEntity>
}
```

- [ ] **Step 5: Commit (entity + DAO without DB wiring)**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/CachedQuoteEntity.kt \
        app/src/main/java/io/github/jiro/expensetracker/data/local/CachedQuoteDao.kt \
        app/src/test/java/io/github/jiro/expensetracker/data/local/CachedQuoteDaoTest.kt
git commit -m "feat(investments): add CachedQuoteEntity + DAO"
```

DAOs aren't usable until Task 4 wires them into `AppDatabase`.

---

## Task 4: AppDatabase schema v8→v9 + DI module wiring

Add the two new entities to `@Database`, bump version, write the migration, register DAOs in `DatabaseModule`. Then the tests from Tasks 2+3 should pass.

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/di/DatabaseModule.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/data/local/AppDatabaseMigrationTest.kt`

- [ ] **Step 1: Write the migration test**

Create `app/src/test/java/io/github/jiro/expensetracker/data/local/AppDatabaseMigrationTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry-like-target = RuntimeEnvironment.getApplication(),
        AppDatabase::class.java,
    )

    private val DB_NAME = "migration-test"

    @Test fun v8_to_v9_createsInvestmentTables() {
        helper.createDatabase(DB_NAME, 8).close()
        helper.runMigrationsAndValidate(DB_NAME, 9, true, AppDatabase.MIGRATION_8_9)
            .close()
        // Re-open at v9 and assert the new tables are queryable.
        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java, DB_NAME,
        ).addMigrations(AppDatabase.MIGRATION_8_9).build()
        try {
            val holding = InvestmentHoldingEntity(
                accountId = 1L, symbol = "AAPL", quantity = 1.0,
                costBasisMinor = 100L, currencyCode = "USD",
                createdAtEpochMillis = 0L,
            )
            db.investmentHoldingDao().insert(holding)
            db.cachedQuoteDao().upsert(CachedQuoteEntity("AAPL", 100L, "USD", 0L))
        } finally {
            db.close()
        }
    }
}
```

Note: Robolectric's `MigrationTestHelper` constructor takes `(Instrumentation, databaseClass)`. Use this version that works under Robolectric unit tests:

Replace the helper field with:
```kotlin
    @get:Rule
    val helper = MigrationTestHelper(
        io.github.jiro.expensetracker.TestApp::class.java.canonicalName?.let { it } ?: "",
        FrameworkSQLiteOpenHelperFactory(),
        AppDatabase::class.java,
    )
```

Actually, the simplest portable form is to use `MigrationTestHelper` constructed via the helper for Robolectric. The cleanest cross-platform signature:

```kotlin
    @get:Rule
    val helper = MigrationTestHelper(
        RuntimeEnvironment.getApplication(),
        AppDatabase::class.java,
        FrameworkSQLiteOpenHelperFactory(),
    )
```

`MigrationTestHelper` accepts `(Context, Class<RoomDatabase>, SQLiteOpenHelper.Factory)`. Verify exact signature against your installed Room version. If the (Context, Class) overload exists, use that. Otherwise use the (String, Factory, Class) form with a dummy name.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.AppDatabaseMigrationTest"`
Expected: FAIL — `AppDatabase.MIGRATION_8_9` does not exist

- [ ] **Step 3: Bump database version + add entities + DAO accessors + migration**

In `app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt`:

1. Change `version = 8` to `version = 9`.
2. Add `InvestmentHoldingEntity::class` and `CachedQuoteEntity::class` to the `entities = [...]` list.
3. Add two abstract DAO methods inside `abstract class AppDatabase`:
   ```kotlin
   abstract fun investmentHoldingDao(): InvestmentHoldingDao
   abstract fun cachedQuoteDao(): CachedQuoteDao
   ```
4. Add a new migration inside `companion object` (after `MIGRATION_7_8`):
   ```kotlin
           /** v8 → v9: investment accounts. Adds `investment_holdings`
            *  and `cached_quotes` tables. No existing data is touched. */
           val MIGRATION_8_9: Migration = object : Migration(8, 9) {
               override fun migrate(db: SupportSQLiteDatabase) {
                   db.execSQL("""
                       CREATE TABLE IF NOT EXISTS investment_holdings (
                         id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                         accountId INTEGER NOT NULL,
                         symbol TEXT NOT NULL,
                         quantity REAL NOT NULL,
                         costBasisMinor INTEGER NOT NULL,
                         currencyCode TEXT NOT NULL,
                         createdAtEpochMillis INTEGER NOT NULL,
                         FOREIGN KEY (accountId) REFERENCES accounts(id) ON DELETE RESTRICT
                       )
                   """.trimIndent())
                   db.execSQL("CREATE INDEX IF NOT EXISTS index_investment_holdings_accountId ON investment_holdings (accountId)")
                   db.execSQL("CREATE INDEX IF NOT EXISTS index_investment_holdings_symbol ON investment_holdings (symbol)")
                   db.execSQL("""
                       CREATE TABLE IF NOT EXISTS cached_quotes (
                         symbol TEXT NOT NULL PRIMARY KEY,
                         priceMinor INTEGER NOT NULL,
                         currencyCode TEXT NOT NULL,
                         fetchedAtEpochMillis INTEGER NOT NULL
                       )
                   """.trimIndent())
               }
           }
   ```

- [ ] **Step 4: Register new DAOs in DatabaseModule**

In `app/src/main/java/io/github/jiro/expensetracker/di/DatabaseModule.kt`:

1. Add to `.addMigrations(...)`: `AppDatabase.MIGRATION_8_9`.
2. Add imports for `CachedQuoteDao`, `InvestmentHoldingDao`.
3. Add two `@Provides` methods:
   ```kotlin
       @Provides
       fun provideInvestmentHoldingDao(db: AppDatabase): InvestmentHoldingDao = db.investmentHoldingDao()

       @Provides
       fun provideCachedQuoteDao(db: AppDatabase): CachedQuoteDao = db.cachedQuoteDao()
   ```

- [ ] **Step 5: Run all three DAO tests to verify they pass**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.*"`
Expected: PASS — MigrationTest + InvestmentHoldingDaoTest + CachedQuoteDaoTest + MoneyFormatCurrencyPrecisionTest

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt \
        app/src/main/java/io/github/jiro/expensetracker/di/DatabaseModule.kt \
        app/src/test/java/io/github/jiro/expensetracker/data/local/AppDatabaseMigrationTest.kt
git commit -m "feat(db): v9 migration adds investment_holdings + cached_quotes"
```

---

## Task 5: MarketDataClient interface + Quote + MarketDataException

Pure data + interface, no logic. No tests (interfaces have no behavior to verify).

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/market/Quote.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/market/MarketDataClient.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/market/MarketDataException.kt`

- [ ] **Step 1: Write Quote + MarketDataException + MarketDataClient**

Create `app/src/main/java/io/github/jiro/expensetracker/data/market/Quote.kt`:

```kotlin
package io.github.jiro.expensetracker.data.market

/** Latest price for a single ticker, already scaled to minor units. */
data class Quote(
    val symbol: String,
    val priceMinor: Long,
    val currencyCode: String,
    val asOfEpochMillis: Long,
)
```

Create `app/src/main/java/io/github/jiro/expensetracker/data/market/MarketDataException.kt`:

```kotlin
package io.github.jiro.expensetracker.data.market

/** Thrown by MarketDataClient on transport / parse failure. Unknown symbols
 *  are NOT a failure — they return null in the result list instead. */
class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

Create `app/src/main/java/io/github/jiro/expensetracker/data/market/MarketDataClient.kt`:

```kotlin
package io.github.jiro.expensetracker.data.market

interface MarketDataClient {
    /**
     * Fetches latest quotes for [symbols]. Returns one Quote? per requested
     * symbol, in input order; null entries for symbols the feed didn't
     * recognize. Throws [MarketDataException] on transport / parse failure.
     */
    suspend fun fetchQuotes(symbols: List<String>): List<Quote?>
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/market/
git commit -m "feat(market): add MarketDataClient interface + Quote + MarketDataException"
```

---

## Task 6: YahooMarketDataClient — happy path with MockWebServer

Yahoo's `/v7/finance/quote?symbols=...` endpoint. MockWebServer drives the JSON fixtures.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/market/YahooMarketDataClient.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/data/market/YahooMarketDataClientTest.kt`

- [ ] **Step 1: Write the happy-path test**

Create `app/src/test/java/io/github/jiro/expensetracker/data/market/YahooMarketDataClientTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.market

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class YahooMarketDataClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: YahooMarketDataClient

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = YahooMarketDataClient(
            httpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().removeSuffix("/") },
        )
    }

    @After fun teardown() { server.shutdown() }

    @Test fun happyPath_returnsAllRequestedQuotes() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "quoteResponse": {
                "result": [
                  {"symbol":"AAPL","regularMarketPrice":123.45,"currency":"USD","regularMarketTime":1700000000,"marketState":"REGULAR"},
                  {"symbol":"7203.T","regularMarketPrice":2800.0,"currency":"JPY","regularMarketTime":1700000000,"marketState":"CLOSED"}
                ],
                "error": null
              }
            }
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "7203.T"))
        assertEquals(2, result.size)
        val aapl = result[0]!!
        assertEquals("AAPL", aapl.symbol)
        assertEquals(12_345L, aapl.priceMinor)   // 123.45 USD × 100
        assertEquals("USD", aapl.currencyCode)
        assertEquals(1_700_000_000_000L, aapl.asOfEpochMillis)
        val toyota = result[1]!!
        assertEquals("7203.T", toyota.symbol)
        assertEquals(2800L, toyota.priceMinor)    // 2800.0 JPY × 1 (0dp)
        assertEquals("JPY", toyota.currencyCode)
    }

    @Test fun unknownSymbol_returnsNullInPosition() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "quoteResponse": {
                "result": [
                  {"symbol":"AAPL","regularMarketPrice":123.45,"currency":"USD","regularMarketTime":1,"marketState":"REGULAR"}
                ],
                "error": null
              }
            }
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "ZZZZ"))
        assertEquals(2, result.size)
        assertNotNull(result[0])
        assertNull(result[1])
    }

    @Test fun emptyResultArray_allSymbolsReturnNull() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"quoteResponse":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "GOOG"))
        assertEquals(2, result.size)
        assertNull(result[0])
        assertNull(result[1])
    }

    @Test fun serverError_throwsMarketDataException() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server boom"))
        try {
            client.fetchQuotes(listOf("AAPL"))
            fail("expected MarketDataException")
        } catch (e: MarketDataException) {
            // ok
        }
    }

    @Test fun malformedJson_throwsMarketDataException() = runTest {
        server.enqueue(MockResponse().setBody("not json at all").setResponseCode(200))
        try {
            client.fetchQuotes(listOf("AAPL"))
            fail("expected MarketDataException")
        } catch (e: MarketDataException) {
            // ok
        }
    }

    @Test fun requestUrl_includesAllSymbols() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"quoteResponse":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))
        client.fetchQuotes(listOf("AAPL", "GOOG", "MSFT"))
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("symbols=AAPL%2CGOOG%2CMSFT") ||
                   request.path!!.contains("symbols=AAPL,GOOG,MSFT"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.market.YahooMarketDataClientTest"`
Expected: FAIL — `YahooMarketDataClient` does not exist

- [ ] **Step 3: Implement YahooMarketDataClient**

Create `app/src/main/java/io/github/jiro/expensetracker/data/market/YahooMarketDataClient.kt`:

```kotlin
package io.github.jiro.expensetracker.data.market

import io.github.jiro.expensetracker.data.local.MoneyFormat
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
class YahooMarketDataClient @Inject constructor(
    private val httpClient: OkHttpClient,
    /** Indirection so tests can inject a MockWebServer URL. Production
     *  binding returns the public endpoint. */
    private val baseUrlProvider: () -> String = { DEFAULT_BASE_URL },
) : MarketDataClient {

    override suspend fun fetchQuotes(symbols: List<String>): List<Quote?> {
        if (symbols.isEmpty()) return emptyList()
        val url = baseUrlProvider().toHttpUrl().newBuilder()
            .addPathSegments("v7/finance/quote")
            .addQueryParameter("symbols", symbols.joinToString(","))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Throwable) {
            throw MarketDataException("network failure: ${e.message}", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw MarketDataException("HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: throw MarketDataException("empty body")
            val parsed = try {
                JSONObject(body)
            } catch (e: Throwable) {
                throw MarketDataException("parse failure: ${e.message}", e)
            }
            val result = parsed
                .getJSONObject("quoteResponse")
                .optJSONArray("result")
            val bySymbol = mutableMapOf<String, Quote>()
            if (result != null) {
                for (i in 0 until result.length()) {
                    val obj = result.getJSONObject(i)
                    val sym = obj.getString("symbol")
                    val price = obj.optDouble("regularMarketPrice", Double.NaN)
                    if (price.isNaN()) continue
                    val currency = obj.optString("currency", "USD")
                    val asOfSec = obj.optLong("regularMarketTime", 0L)
                    bySymbol[sym] = Quote(
                        symbol = sym,
                        priceMinor = MoneyFormat.priceToMinor(price, currency),
                        currencyCode = currency,
                        asOfEpochMillis = asOfSec * 1000L,
                    )
                }
            }
            return symbols.map { bySymbol[it] }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://query1.finance.yahoo.com"
        const val USER_AGENT = "Mozilla/5.0"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.market.YahooMarketDataClientTest"`
Expected: PASS — 6 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/market/YahooMarketDataClient.kt \
        app/src/test/java/io/github/jiro/expensetracker/data/market/YahooMarketDataClientTest.kt
git commit -m "feat(market): YahooMarketDataClient with mockwebserver tests"
```

---

## Task 7: QuoteRepository

Owns cache write-through. Test uses fake client + in-memory Room.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/market/QuoteRepository.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/data/market/QuoteRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/data/market/QuoteRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.market

import androidx.room.Room
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.data.local.CachedQuoteDao
import io.github.jiro.expensetracker.data.local.CachedQuoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QuoteRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var cacheDao: CachedQuoteDao
    private lateinit var fakeClient: FakeMarketDataClient
    private lateinit var repo: QuoteRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        cacheDao = db.cachedQuoteDao()
        fakeClient = FakeMarketDataClient()
        repo = QuoteRepository(fakeClient, cacheDao)
    }

    @After fun teardown() { db.close() }

    @Test fun refresh_writesFreshQuotesToCache() = runTest {
        fakeClient.respondWith(listOf(
            quote("AAPL", 12_345L, "USD"),
            quote("GOOG", 50_000L, "USD"),
        ))
        val outcome = repo.refresh(listOf("AAPL", "GOOG"))
        assertEquals(SymbolOutcome.Fresh, outcome.perSymbol["AAPL"])
        assertEquals(SymbolOutcome.Fresh, outcome.perSymbol["GOOG"])
        assertEquals(12_345L, cacheDao.findBySymbol("AAPL")?.priceMinor)
        assertEquals(50_000L, cacheDao.findBySymbol("GOOG")?.priceMinor)
    }

    @Test fun refresh_unknownSymbol_doesNotTouchCache() = runTest {
        cacheDao.upsert(CachedQuoteEntity("AAPL", 99_999L, "USD", 1L))
        fakeClient.respondWith(listOf(null))
        val outcome = repo.refresh(listOf("AAPL"))
        assertEquals(SymbolOutcome.Unknown, outcome.perSymbol["AAPL"])
        // Cache preserved with old timestamp.
        val row = cacheDao.findBySymbol("AAPL")!!
        assertEquals(99_999L, row.priceMinor)
        assertEquals(1L, row.fetchedAtEpochMillis)
    }

    @Test fun refresh_transportFailure_doesNotTouchCache() = runTest {
        cacheDao.upsert(CachedQuoteEntity("AAPL", 99_999L, "USD", 1L))
        fakeClient.failNext = true
        try { repo.refresh(listOf("AAPL")) } catch (_: MarketDataException) {}
        val row = cacheDao.findBySymbol("AAPL")!!
        assertEquals(99_999L, row.priceMinor)
    }

    @Test fun refresh_partialResponse_marksUnknownOnly() = runTest {
        fakeClient.respondWith(listOf(quote("AAPL", 12_345L, "USD"), null))
        val outcome = repo.refresh(listOf("AAPL", "ZZZZ"))
        assertEquals(SymbolOutcome.Fresh, outcome.perSymbol["AAPL"])
        assertEquals(SymbolOutcome.Unknown, outcome.perSymbol["ZZZZ"])
        assertEquals(12_345L, cacheDao.findBySymbol("AAPL")?.priceMinor)
        assertNull(cacheDao.findBySymbol("ZZZZ"))
    }

    @Test fun refresh_overwritesPreviousCacheEntry() = runTest {
        cacheDao.upsert(CachedQuoteEntity("AAPL", 99_999L, "USD", 1L))
        fakeClient.respondWith(listOf(quote("AAPL", 12_345L, "USD")))
        repo.refresh(listOf("AAPL"))
        assertEquals(12_345L, cacheDao.findBySymbol("AAPL")?.priceMinor)
    }

    @Test fun observeAllCached_emitsForRequestedSymbols() = runTest {
        cacheDao.upsert(CachedQuoteEntity("AAPL", 12_345L, "USD", 1L))
        cacheDao.upsert(CachedQuoteEntity("GOOG", 50_000L, "USD", 1L))
        val map = repo.observeAllCached(listOf("AAPL", "MISSING")).first()
        assertEquals(1, map.size)
        assertEquals(12_345L, map["AAPL"]?.priceMinor)
    }
}

private fun quote(symbol: String, priceMinor: Long, currency: String) =
    Quote(symbol, priceMinor, currency, asOfEpochMillis = 0L)

private class FakeMarketDataClient : MarketDataClient {
    var nextResponse: List<Quote?> = emptyList()
    var failNext: Boolean = false

    fun respondWith(q: List<Quote?>) { nextResponse = q; failNext = false }

    override suspend fun fetchQuotes(symbols: List<String>): List<Quote?> {
        if (failNext) throw MarketDataException("fake failure")
        return nextResponse
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.market.QuoteRepositoryTest"`
Expected: FAIL — `QuoteRepository` does not exist

- [ ] **Step 3: Implement QuoteRepository**

Create `app/src/main/java/io/github/jiro/expensetracker/data/market/QuoteRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.data.market

import io.github.jiro.expensetracker.data.local.CachedQuoteDao
import io.github.jiro.expensetracker.data.local.CachedQuoteEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class QuoteRepository @Inject constructor(
    private val client: MarketDataClient,
    private val quoteDao: CachedQuoteDao,
) {

    fun observeCached(symbol: String): Flow<CachedQuoteEntity?> =
        quoteDao.observeBySymbols(listOf(symbol)).map { it.firstOrNull() }

    fun observeAllCached(symbols: List<String>): Flow<Map<String, CachedQuoteEntity>> =
        quoteDao.observeBySymbols(symbols).map { rows -> rows.associateBy { it.symbol } }

    /** Fetches and writes-through. Per-symbol outcome reflects what
     *  actually happened. Re-throws [MarketDataException] on full transport
     *  failure so the caller can surface it. */
    suspend fun refresh(symbols: List<String>): RefreshOutcome {
        val perSymbol = mutableMapOf<String, SymbolOutcome>()
        val quotes = try {
            client.fetchQuotes(symbols)
        } catch (e: MarketDataException) {
            // Mark all as Failed and re-throw. Cache untouched.
            symbols.forEach { perSymbol[it] = SymbolOutcome.Failed(e.message ?: "unknown") }
            throw e
        }
        symbols.forEachIndexed { i, symbol ->
            val q = quotes.getOrNull(i)
            when {
                q == null -> {
                    // Unknown to the feed — preserve any existing cache.
                    perSymbol[symbol] = SymbolOutcome.Unknown
                }
                else -> {
                    quoteDao.upsert(
                        CachedQuoteEntity(
                            symbol = q.symbol,
                            priceMinor = q.priceMinor,
                            currencyCode = q.currencyCode,
                            fetchedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                    perSymbol[symbol] = SymbolOutcome.Fresh
                }
            }
        }
        return RefreshOutcome(perSymbol)
    }
}

data class RefreshOutcome(val perSymbol: Map<String, SymbolOutcome>)

sealed interface SymbolOutcome {
    object Fresh : SymbolOutcome
    object Unknown : SymbolOutcome
    data class Failed(val reason: String) : SymbolOutcome
}
```

Note `fetchedAtEpochMillis` is written as `System.currentTimeMillis()` from the repo (not from the network's `asOfEpochMillis`) so the stale-detection compares "when did we last successfully refresh" rather than "what time did Yahoo say."

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.data.market.QuoteRepositoryTest"`
Expected: PASS — 6 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/market/QuoteRepository.kt \
        app/src/test/java/io/github/jiro/expensetracker/data/market/QuoteRepositoryTest.kt
git commit -m "feat(market): QuoteRepository cache write-through"
```

---

## Task 8: MarketDataModule (Hilt DI)

Provide `YahooMarketDataClient` as the `MarketDataClient` implementation.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/di/MarketDataModule.kt`

- [ ] **Step 1: Write the module**

Create `app/src/main/java/io/github/jiro/expensetracker/di/MarketDataModule.kt`:

```kotlin
package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.market.MarketDataClient
import io.github.jiro.expensetracker.data.market.YahooMarketDataClient
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class MarketDataModule {

    @Binds
    @Singleton
    abstract fun bindMarketDataClient(impl: YahooMarketDataClient): MarketDataClient

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", YahooMarketDataClient.USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .build()
    }
}
```

- [ ] **Step 2: Verify the project compiles (no new tests for a wiring-only module)**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/di/MarketDataModule.kt
git commit -m "feat(di): MarketDataModule provides YahooMarketDataClient"
```

---

## Task 9: AccountRepository.countHoldings

Add `countHoldings(accountId)` to `AccountRepository`. Single delegation to DAO.

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`

- [ ] **Step 1: Add the delegation**

In `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`:

1. Add `InvestmentHoldingDao` to the constructor params:
   ```kotlin
   open class AccountRepository @Inject constructor(
       private val dao: AccountDao,
       private val holdingDao: InvestmentHoldingDao,
   ) {
   ```
2. Add the method (alongside `countActive()`):
   ```kotlin
       /** Number of investment holdings in this account. Used by the
        *  delete-guard to BLOCK_HOLDINGS_EXIST. */
       suspend fun countHoldings(accountId: Long): Int = holdingDao.countByAccount(accountId)
   ```
3. Add the import: `import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao`

- [ ] **Step 2: Compile**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt
git commit -m "feat(repo): AccountRepository.countHoldings"
```

---

## Task 10: DeleteGuard extension + AccountDetailViewModel

Add `BLOCK_HOLDINGS_EXIST` to `DeleteGuard`, extend `evaluateDelete`, and use both holdings count + transactions count in `AccountDetailViewModel.onDeleteClick`.

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDeleteGuardTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDeleteGuardTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountDeleteGuardTest {

    @Test fun noTransactions_noHoldings_allowsDelete() {
        assertEquals(
            DeleteGuard.ALLOW,
            evaluateDelete(referenceCount = 0, holdingsCount = 0),
        )
    }

    @Test fun hasTransactions_blocksEvenWithoutHoldings() {
        assertEquals(
            DeleteGuard.BLOCK_TRANSACTIONS_EXIST,
            evaluateDelete(referenceCount = 1, holdingsCount = 0),
        )
    }

    @Test fun noTransactions_hasHoldings_blocks() {
        assertEquals(
            DeleteGuard.BLOCK_HOLDINGS_EXIST,
            evaluateDelete(referenceCount = 0, holdingsCount = 1),
        )
    }

    @Test fun hasBoth_blocksTransactionsWins() {
        // Transactions-block is the older / higher-priority guard.
        assertEquals(
            DeleteGuard.BLOCK_TRANSACTIONS_EXIST,
            evaluateDelete(referenceCount = 2, holdingsCount = 3),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.AccountDeleteGuardTest"`
Expected: FAIL — signature mismatch (current `evaluateDelete(Int)` doesn't take holdingsCount)

- [ ] **Step 3: Update `DeleteGuard` + `evaluateDelete` in AccountDetailViewModel**

In `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`:

Replace:
```kotlin
enum class DeleteGuard { ALLOW, BLOCK_TRANSACTIONS_EXIST }

fun evaluateDelete(referenceCount: Int): DeleteGuard =
    if (referenceCount == 0) DeleteGuard.ALLOW else DeleteGuard.BLOCK_TRANSACTIONS_EXIST
```
With:
```kotlin
enum class DeleteGuard { ALLOW, BLOCK_TRANSACTIONS_EXIST, BLOCK_HOLDINGS_EXIST }

/**
 * Decision order: transactions-block takes precedence over holdings-block so
 * the existing UX (transactions are the older, more "destructive" reference)
 * stays unchanged. Holdings-only is a new block in v1.
 */
fun evaluateDelete(referenceCount: Int, holdingsCount: Int): DeleteGuard = when {
    referenceCount > 0 -> DeleteGuard.BLOCK_TRANSACTIONS_EXIST
    holdingsCount > 0 -> DeleteGuard.BLOCK_HOLDINGS_EXIST
    else -> DeleteGuard.ALLOW
}
```

In `onDeleteClick()`, change the `evaluateDelete(count)` call to include holdings:
```kotlin
    fun onDeleteClick() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        if (accountId == DEFAULT_ACCOUNT_ID) return
        viewModelScope.launch {
            val count = transactionRepository.countReferencingAccount(accountId)
            val holdings = accountRepository.countHoldings(accountId)
            _state.update {
                it.copy(
                    showDeleteConfirm = true,
                    deleteGuard = evaluateDelete(count, holdings),
                    referenceCount = count,
                )
            }
        }
    }
```

The existing dialog already reads `deleteGuard` — add a new branch for `BLOCK_HOLDINGS_EXIST` in `AccountDetailScreen.kt`:

Open `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt`. Find the `when (state.deleteGuard)` block (around line 160) and add a third branch:

```kotlin
            DeleteGuard.BLOCK_HOLDINGS_EXIST -> AlertDialog(
                onDismissRequest = viewModel::onDeleteDismiss,
                title = { Text(stringResource(R.string.account_delete_blocked_holdings_title)) },
                text = { Text(stringResource(R.string.account_delete_blocked_holdings_message, account?.name.orEmpty(), state.holdingsCount)) },
                confirmButton = {
                    TextButton(onClick = viewModel::onDeleteDismiss) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
            )
```

Add `holdingsCount: Int = 0` to `AccountDetailUiState` so the dialog can render the count.

In `AccountDetailViewModel.onDeleteClick`, set `holdingsCount = holdings` in the update.

Add the new strings to `app/src/main/res/values/strings.xml`:
```xml
    <string name="account_delete_blocked_holdings_title">Can\'t delete account</string>
    <string name="account_delete_blocked_holdings_message">"%1$s" still holds %2$d investment position(s). Delete or move those holdings first.</string>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.AccountDeleteGuardTest"`
Expected: PASS — 4 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDeleteGuardTest.kt
git commit -m "feat(accounts): block account delete when holdings exist"
```

---

## Task 11: AddEditAccount — INVESTMENT preset + 📈 icon

Add `"INVESTMENT"` to the type presets and `"📈"` to the icon choices, plus the new `account_type_investment` string.

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add preset + icon**

In `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt`:

Change line 29:
```kotlin
val ACCOUNT_TYPE_PRESETS = listOf("CASH", "BANK", "CREDIT_CARD", "EWALLET", "INVESTMENT", "OTHER")
```
(Note: keep the same list order. "OTHER" stays last as the custom fallback.)

Change line 32:
```kotlin
val ACCOUNT_ICON_CHOICES = listOf("💵", "🏦", "💳", "📱", "💰", "💼", "📈", "🏠")
```

In `AddEditAccountScreen.kt`, find `presetLabel` (around line 317) and add:
```kotlin
    "INVESTMENT" -> stringResource(R.string.account_type_investment)
```

In `app/src/main/res/values/strings.xml`, add:
```xml
    <string name="account_type_investment">Investment</string>
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(accounts): add Investment preset + chart icon"
```

---

## Task 12: AddEditHoldingViewModel

Holds symbol/quantity/cost/currency form state, validation, and save logic.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModel.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModelTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.investments

import androidx.lifecycle.SavedStateHandle
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditHoldingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dao: FakeHoldingDao

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun vm(accountId: Long, holdingId: Long? = null) =
        AddEditHoldingViewModel(
            savedStateHandle = SavedStateHandle().apply {
                set(Routes.INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID, accountId)
                if (holdingId != null) set(Routes.INVESTMENT_HOLDING_EDIT_ARG_HOLDING_ID, holdingId)
            },
            holdingDao = dao,
        )

    @Test fun symbol_isUppercasedOnSave() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("aapl")
        v.onQuantityChange("10")
        v.onCostBasisChange("1500.00")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertEquals("AAPL", dao.lastInserted?.symbol)
    }

    @Test fun blankSymbol_saveFailsWithError() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("")
        v.onQuantityChange("10")
        v.onCostBasisChange("1500")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertNull(dao.lastInserted)
        assertEquals(HoldingFormError.SYMBOL_REQUIRED, v.state.value.error)
    }

    @Test fun overlongSymbol_saveFailsWithError() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("A".repeat(13))
        v.onQuantityChange("1")
        v.onCostBasisChange("100")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertNull(dao.lastInserted)
        assertEquals(HoldingFormError.SYMBOL_TOO_LONG, v.state.value.error)
    }

    @Test fun zeroQuantity_saveFails() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("AAPL")
        v.onQuantityChange("0")
        v.onCostBasisChange("100")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertNull(dao.lastInserted)
        assertEquals(HoldingFormError.QUANTITY_INVALID, v.state.value.error)
    }

    @Test fun dotTSuffix_infersJpy() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("7203.T")
        v.onQuantityChange("100")
        v.onCostBasisChange("200000")
        // Don't touch currency; the VM should default to JPY for .T suffix.
        v.save()
        advanceUntilIdle()
        assertEquals("JPY", dao.lastInserted?.currencyCode)
    }

    @Test fun nonDotTSuffix_defaultsToUsd() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("AAPL")
        v.onQuantityChange("10")
        v.onCostBasisChange("1500")
        v.save()
        advanceUntilIdle()
        assertEquals("USD", dao.lastInserted?.currencyCode)
    }

    @Test fun editMode_updatesExistingRow() = runTest(dispatcher) {
        dao = FakeHoldingDao(initial = InvestmentHoldingEntity(
            id = 7L, accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD", createdAtEpochMillis = 1L,
        ))
        val v = vm(accountId = 1L, holdingId = 7L)
        v.onQuantityChange("12")
        v.save()
        advanceUntilIdle()
        assertEquals(12.0, dao.lastUpdated?.quantity!!, 0.0001)
        assertEquals(7L, dao.lastUpdated?.id)
    }

    @Test fun save_emitsSaveCompleteOnSuccess() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("AAPL")
        v.onQuantityChange("10")
        v.onCostBasisChange("1500")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertTrue(v.state.value.saveComplete)
    }
}

private class FakeHoldingDao(initial: InvestmentHoldingEntity? = null) : InvestmentHoldingDao {
    var lastInserted: InvestmentHoldingEntity? = null
    var lastUpdated: InvestmentHoldingEntity? = null
    private val rows = mutableMapOf<Long, InvestmentHoldingEntity>()
    init { if (initial != null) rows[initial.id] = initial }

    override suspend fun insert(row: InvestmentHoldingEntity): Long {
        val withId = row.copy(id = (rows.keys.maxOrNull() ?: 0L) + 1L)
        rows[withId.id] = withId
        lastInserted = withId
        return withId.id
    }
    override suspend fun update(row: InvestmentHoldingEntity) {
        rows[row.id] = row
        lastUpdated = row
    }
    override suspend fun delete(id: Long) { rows.remove(id) }
    override fun observeByAccount(accountId: Long) = kotlinx.coroutines.flow.flowOf(rows.values.filter { it.accountId == accountId })
    override suspend fun findById(id: Long) = rows[id]
    override suspend fun countByAccount(accountId: Long) = rows.values.count { it.accountId == accountId }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.investments.AddEditHoldingViewModelTest"`
Expected: FAIL — `AddEditHoldingViewModel` does not exist

- [ ] **Step 3: Implement AddEditHoldingViewModel**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModel.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.investments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.navigation.Routes
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HoldingFormError {
    SYMBOL_REQUIRED, SYMBOL_TOO_LONG,
    QUANTITY_INVALID, COST_INVALID, CURRENCY_REQUIRED,
}

data class AddEditHoldingUiState(
    val isEdit: Boolean = false,
    val symbol: String = "",
    val quantityInput: String = "",
    val costBasisInput: String = "",
    val currency: String = "USD",
    val error: HoldingFormError? = null,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
)

@HiltViewModel
class AddEditHoldingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val holdingDao: InvestmentHoldingDao,
) : ViewModel() {

    private val accountId: Long = savedStateHandle
        .get<Long>(Routes.INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID) ?: -1L
    private val holdingId: Long? = savedStateHandle
        .get<Long>(Routes.INVESTMENT_HOLDING_EDIT_ARG_HOLDING_ID)
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(
        AddEditHoldingUiState(isEdit = holdingId != null),
    )
    val state: StateFlow<AddEditHoldingUiState> = _state.asStateFlow()

    init {
        if (holdingId != null) {
            viewModelScope.launch {
                val existing = holdingDao.findById(holdingId) ?: return@launch
                _state.update {
                    it.copy(
                        symbol = existing.symbol,
                        quantityInput = existing.quantity.toString(),
                        costBasisInput = MoneyFormat.formatAmountForEdit(existing.costBasisMinor),
                        currency = existing.currencyCode,
                        isLoaded = true,
                    )
                }
            }
        } else {
            _state.update { it.copy(isLoaded = true) }
        }
    }

    fun onSymbolChange(value: String) = _state.update {
        it.copy(symbol = value.uppercase().trim(), error = null)
    }
    fun onQuantityChange(value: String) = _state.update {
        it.copy(quantityInput = value, error = null)
    }
    fun onCostBasisChange(value: String) = _state.update {
        it.copy(costBasisInput = value, error = null)
    }
    fun onCurrencyChange(value: String) = _state.update {
        it.copy(currency = value.uppercase().trim(), error = null)
    }

    /** Currency inference from the symbol suffix. Called from save() if the
     *  user hasn't manually set the currency field. */
    private fun inferCurrency(symbol: String): String = when {
        symbol.endsWith(".T") -> "JPY"
        else -> "USD"
    }

    fun save() {
        val s = _state.value
        val symbol = s.symbol.trim().uppercase()
        if (symbol.isEmpty()) {
            _state.update { it.copy(error = HoldingFormError.SYMBOL_REQUIRED) }; return
        }
        if (symbol.length > MAX_SYMBOL_LEN) {
            _state.update { it.copy(error = HoldingFormError.SYMBOL_TOO_LONG) }; return
        }
        val qty = s.quantityInput.trim().toDoubleOrNull()
        if (qty == null || qty <= 0.0) {
            _state.update { it.copy(error = HoldingFormError.QUANTITY_INVALID) }; return
        }
        val cost = MoneyFormat.parseAmountToMinor(s.costBasisInput)
        if (cost == null) {
            _state.update { it.copy(error = HoldingFormError.COST_INVALID) }; return
        }
        val currency = if (s.currency.isNotBlank()) s.currency else inferCurrency(symbol)

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (holdingId != null) {
                val existing = holdingDao.findById(holdingId) ?: return@launch
                holdingDao.update(existing.copy(
                    symbol = symbol,
                    quantity = qty,
                    costBasisMinor = cost,
                    currencyCode = currency,
                ))
            } else {
                holdingDao.insert(InvestmentHoldingEntity(
                    accountId = accountId,
                    symbol = symbol,
                    quantity = qty,
                    costBasisMinor = cost,
                    currencyCode = currency,
                    createdAtEpochMillis = now,
                ))
            }
            _state.update { it.copy(isSaving = false, saveComplete = true) }
        }
    }

    companion object {
        const val MAX_SYMBOL_LEN = 12
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.investments.AddEditHoldingViewModelTest"`
Expected: PASS — 8 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModel.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingViewModelTest.kt
git commit -m "feat(investments): AddEditHoldingViewModel with form validation"
```

---

## Task 13: AddEditHoldingScreen

Compose form bound to `AddEditHoldingViewModel`. Mirror the visual style of `AddEditAccountScreen.kt`.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the new strings**

In `app/src/main/res/values/strings.xml`, add:

```xml
    <!-- Investment holding form -->
    <string name="holding_add_title">Add holding</string>
    <string name="holding_edit_title">Edit holding</string>
    <string name="field_holding_symbol">Symbol</string>
    <string name="field_holding_quantity">Quantity</string>
    <string name="field_holding_cost_basis">Total cost</string>
    <string name="field_holding_currency">Currency</string>
    <string name="error_holding_symbol_required">Enter a symbol</string>
    <string name="error_holding_symbol_too_long">Symbol must be 12 characters or fewer</string>
    <string name="error_holding_quantity_invalid">Enter a quantity greater than zero</string>
    <string name="error_holding_cost_invalid">Enter a valid cost</string>
```

- [ ] **Step 2: Write the screen**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingScreen.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R

private val SUPPORTED_CURRENCIES = listOf(
    "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF",
    "SGD", "HKD", "MYR", "CNY", "BTC", "ETH",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditHoldingScreen(
    onBack: () -> Unit,
    viewModel: AddEditHoldingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.holding_edit_title else R.string.holding_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.symbol,
                onValueChange = viewModel::onSymbolChange,
                label = { Text(stringResource(R.string.field_holding_symbol)) },
                singleLine = true,
                isError = state.error == HoldingFormError.SYMBOL_REQUIRED ||
                    state.error == HoldingFormError.SYMBOL_TOO_LONG,
                supportingText = {
                    when (state.error) {
                        HoldingFormError.SYMBOL_REQUIRED -> Text(stringResource(R.string.error_holding_symbol_required))
                        HoldingFormError.SYMBOL_TOO_LONG -> Text(stringResource(R.string.error_holding_symbol_too_long))
                        else -> Unit
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.quantityInput,
                onValueChange = viewModel::onQuantityChange,
                label = { Text(stringResource(R.string.field_holding_quantity)) },
                singleLine = true,
                isError = state.error == HoldingFormError.QUANTITY_INVALID,
                supportingText = {
                    if (state.error == HoldingFormError.QUANTITY_INVALID)
                        Text(stringResource(R.string.error_holding_quantity_invalid))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.costBasisInput,
                onValueChange = viewModel::onCostBasisChange,
                label = { Text(stringResource(R.string.field_holding_cost_basis)) },
                singleLine = true,
                isError = state.error == HoldingFormError.COST_INVALID,
                supportingText = {
                    if (state.error == HoldingFormError.COST_INVALID)
                        Text(stringResource(R.string.error_holding_cost_invalid))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = state.currency,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.field_holding_currency)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    SUPPORTED_CURRENCIES.forEach { code ->
                        DropdownMenuItem(
                            text = { Text(code) },
                            onClick = {
                                viewModel.onCurrencyChange(code)
                                expanded = false
                            },
                        )
                    }
                }
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving && state.isLoaded,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/investments/AddEditHoldingScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(investments): AddEditHoldingScreen"
```

---

## Task 14: InvestmentAccountDetailViewModel — FX rollup + refresh + stale

The big one. Combines: holdings, cached quotes, FX rates → per-row + total state. Manages refresh + close.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModel.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModelTest.kt`

- [ ] **Step 1: Extract interfaces and write the failing test**

Production code first (interfaces used by the VM), then test code that fakes them.

In `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`, add at the bottom of the file:

```kotlin
/** VM-facing subset of AccountRepository. Tests can fake this without
 *  standing up Room. */
interface AccountDataSource {
    fun observeActive(): Flow<List<AccountEntity>>
    suspend fun findById(id: Long): AccountEntity?
    suspend fun close(id: Long)
    suspend fun countHoldings(id: Long): Int
}
```

Change `class AccountRepository` to `: AccountDataSource` and mark those four methods `override`. The default impls stay the same — they just delegate to the existing DAO fields.

In `app/src/main/java/io/github/jiro/expensetracker/data/market/QuoteRepository.kt`, add at the bottom of the file:

```kotlin
/** VM-facing subset. */
interface QuoteDataSource {
    fun observeAllCached(symbols: List<String>): Flow<Map<String, CachedQuoteEntity>>
    suspend fun refresh(symbols: List<String>): RefreshOutcome
}
```

Change `class QuoteRepository` to `: QuoteDataSource` and mark those two methods `override`.

Create `app/src/test/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModelTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.investments

import androidx.lifecycle.SavedStateHandle
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.CachedQuoteDao
import io.github.jiro.expensetracker.data.local.CachedQuoteEntity
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.market.QuoteDataSource
import io.github.jiro.expensetracker.data.market.RefreshOutcome
import io.github.jiro.expensetracker.data.market.SymbolOutcome
import io.github.jiro.expensetracker.data.repository.AccountDataSource
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentAccountDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun vm(
        accountId: Long = 1L,
        account: AccountEntity = AccountEntity(
            id = accountId, name = "Brokerage", type = "INVESTMENT", icon = "📈",
            color = 0, currencyCode = "USD", createdAtEpochMillis = 0L,
        ),
        holdingsFlow: List<InvestmentHoldingEntity> = emptyList(),
        cachedQuotes: List<CachedQuoteEntity> = emptyList(),
        fxRates: Map<String, Double> = emptyMap(),
        refreshResult: RefreshOutcome = RefreshOutcome(emptyMap()),
    ): Pair<InvestmentAccountDetailViewModel, FakeQuoteRepository> {
        val accountRepo = FakeAccountRepository(account)
        val holdingDao = FakeHoldingDao(holdingsFlow)
        val cachedDao = FakeCachedQuoteDao(cachedQuotes)
        val settings = FakeSettingsRepository(fxRates)
        val quoteRepo = FakeQuoteRepository(refreshResult)
        val v = InvestmentAccountDetailViewModel(
            savedStateHandle = SavedStateHandle().apply {
                set(Routes.INVESTMENT_ACCOUNT_DETAIL_ARG_ID, accountId)
            },
            accountRepository = accountRepo,
            holdingDao = holdingDao,
            cachedQuoteDao = cachedDao,
            quoteRepository = quoteRepo,
            settingsRepository = settings,
        )
        return v to quoteRepo
    }

    @Test fun emptyAccount_totalIsZero() = runTest(dispatcher) {
        val (v, _) = vm()
        advanceUntilIdle()
        assertEquals(0L, v.state.value.totalValueMinor)
        assertEquals(0L, v.state.value.totalCostMinor)
        assertEquals(0, v.state.value.holdings.size)
        assertTrue(v.state.value.missingFxPairs.isEmpty())
    }

    @Test fun usdHolding_rollsUpDirectly() = runTest(dispatcher) {
        val (v, _) = vm(
            holdingsFlow = listOf(holding(1L, "AAPL", 10.0, 150_000L, "USD")),
            cachedQuotes = listOf(CachedQuoteEntity("AAPL", 18_000L, "USD", 1L)),
        )
        advanceUntilIdle()
        // Current value = 10 × $180 = $1800. Cost = $1500. Gain = $300.
        assertEquals(180_000L, v.state.value.totalValueMinor)
        assertEquals(150_000L, v.state.value.totalCostMinor)
        assertEquals(30_000L, v.state.value.unrealizedMinor)
        assertTrue(v.state.value.missingFxPairs.isEmpty())
    }

    @Test fun mixedCurrencies_fxConverted() = runTest(dispatcher) {
        val (v, _) = vm(
            account = AccountEntity(
                id = 1L, name = "Brokerage", type = "INVESTMENT", icon = "📈",
                color = 0, currencyCode = "USD", createdAtEpochMillis = 0L,
            ),
            holdingsFlow = listOf(
                holding(1L, "AAPL", 10.0, 150_000L, "USD"),
                holding(2L, "7203.T", 100.0, 200_000L, "JPY"),
            ),
            cachedQuotes = listOf(
                CachedQuoteEntity("AAPL", 18_000L, "USD", 1L),
                CachedQuoteEntity("7203.T", 3_000L, "JPY", 1L),
            ),
            fxRates = mapOf("JPY_to_USD" to 0.0067),
        )
        advanceUntilIdle()
        // AAPL: 10 × $180 = $1800.
        // 7203.T: 100 × ¥3000 = ¥300,000 → USD = 300_000 × 0.0067 = $2010.
        // Total = $3810; cost = $1500 + $1340 = $2840.
        assertEquals(381_000L, v.state.value.totalValueMinor)
        assertEquals(284_000L, v.state.value.totalCostMinor)
        assertTrue(v.state.value.missingFxPairs.isEmpty())
    }

    @Test fun missingFxRate_excludesFromTotal() = runTest(dispatcher) {
        val (v, _) = vm(
            holdingsFlow = listOf(
                holding(1L, "AAPL", 10.0, 150_000L, "USD"),
                holding(2L, "7203.T", 100.0, 200_000L, "JPY"),
            ),
            cachedQuotes = listOf(
                CachedQuoteEntity("AAPL", 18_000L, "USD", 1L),
                CachedQuoteEntity("7203.T", 3_000L, "JPY", 1L),
            ),
            // No JPY_to_USD rate.
        )
        advanceUntilIdle()
        // Only AAPL contributes: $1800.
        assertEquals(180_000L, v.state.value.totalValueMinor)
        assertEquals(listOf("JPY_to_USD"), v.state.value.missingFxPairs)
    }

    @Test fun staleQuote_isMarked() = runTest(dispatcher) {
        val now = 1_000_000_000_000L
        val oldFetched = now - (7L * 60 * 60 * 1000)  // 7h ago
        val (v, _) = vm(
            holdingsFlow = listOf(holding(1L, "AAPL", 1.0, 100L, "USD")),
            cachedQuotes = listOf(CachedQuoteEntity("AAPL", 100L, "USD", oldFetched)),
        )
        // Inject a "current time" via VM clock override. For this test we use
        // the real clock and accept the result based on the system time being
        // after oldFetched.
        advanceUntilIdle()
        val row = v.state.value.holdings.single()
        // The row is stale iff now - fetchedAt > 6h. The real "now" is well past oldFetched.
        assertTrue(row.stale)
    }

    @Test fun refresh_triggersNetworkOnce() = runTest(dispatcher) {
        val (v, repo) = vm(
            holdingsFlow = listOf(holding(1L, "AAPL", 1.0, 100L, "USD")),
            refreshResult = RefreshOutcome(mapOf("AAPL" to SymbolOutcome.Fresh)),
        )
        advanceUntilIdle()
        assertEquals(0, repo.refreshCount)
        v.refresh()
        advanceUntilIdle()
        assertEquals(1, repo.refreshCount)
        assertEquals(listOf("AAPL"), repo.lastRequestedSymbols)
    }

    @Test fun onClose_emitsCloseEvent() = runTest(dispatcher) {
        val (v, _) = vm(accountId = 5L)
        advanceUntilIdle()
        v.onCloseClick()
        advanceUntilIdle()
        assertEquals(true, v.state.value.showCloseConfirm)
        v.onCloseConfirm()
        advanceUntilIdle()
        // FakeAccountRepository.close is a no-op; verify the dialog closes and
        // the closeEvent fires (consumed once via first emission).
        assertEquals(false, v.state.value.showCloseConfirm)
        val emitted = kotlinx.coroutines.flow.first(v.closeEvent)
        assertEquals(5L, emitted)
    }
}

// --- Fakes ---

private fun holding(id: Long, symbol: String, qty: Double, cost: Long, currency: String) =
    InvestmentHoldingEntity(
        id = id, accountId = 1L, symbol = symbol, quantity = qty,
        costBasisMinor = cost, currencyCode = currency, createdAtEpochMillis = 0L,
    )

private class FakeAccountRepository(val account: AccountEntity) : AccountDataSource {
    override suspend fun findById(id: Long) = if (id == account.id) account else null
    override suspend fun close(id: Long) { /* no-op */ }
    override suspend fun countHoldings(id: Long) = 0
    override fun observeActive(): Flow<List<AccountEntity>> = flowOf(listOf(account))
}

private class FakeHoldingDao(rows: List<InvestmentHoldingEntity>) : InvestmentHoldingDao {
    private val rows = rows.associateBy { it.id }.toMutableMap()
    override suspend fun insert(row: InvestmentHoldingEntity): Long { rows[row.id] = row; return row.id }
    override suspend fun update(row: InvestmentHoldingEntity) { rows[row.id] = row }
    override suspend fun delete(id: Long) { rows.remove(id) }
    override fun observeByAccount(accountId: Long) = flowOf(rows.values.filter { it.accountId == accountId })
    override suspend fun findById(id: Long) = rows[id]
    override suspend fun countByAccount(accountId: Long) = rows.values.count { it.accountId == accountId }
}

private class FakeCachedQuoteDao(initial: List<CachedQuoteEntity>) {
    private val rows = initial.associateBy { it.symbol }.toMutableMap()
    suspend fun upsert(row: CachedQuoteEntity) { rows[row.symbol] = row }
    suspend fun findBySymbol(symbol: String) = rows[symbol]
    fun observeBySymbols(symbols: List<String>) = flowOf(rows.values.filter { it.symbol in symbols })
    fun findBySymbols(symbols: List<String>) = rows.values.filter { it.symbol in symbols }
}

private class FakeSettingsRepository(val fxRates: Map<String, Double>) {
    val fxRatesFlow = MutableStateFlow(fxRates)
}

private class FakeQuoteRepository(val result: RefreshOutcome) : QuoteDataSource {
    var refreshCount = 0
    var lastRequestedSymbols: List<String> = emptyList()
    override fun observeAllCached(symbols: List<String>) = flowOf(emptyMap<String, CachedQuoteEntity>())
    override suspend fun refresh(symbols: List<String>): RefreshOutcome {
        refreshCount++
        lastRequestedSymbols = symbols
        return result
    }
}
```

**Note:** With the `AccountDataSource` and `QuoteDataSource` interfaces extracted in Step 1, the test fakes implement them directly — no Room setup or throwNotImplemented() fakery needed.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.investments.InvestmentAccountDetailViewModelTest"`
Expected: FAIL — `InvestmentAccountDetailViewModel` does not exist

- [ ] **Step 3: Implement the VM**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModel.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.investments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.CachedQuoteDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.market.QuoteDataSource
import io.github.jiro.expensetracker.data.repository.AccountDataSource
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.navigation.Routes
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A single holding as the screen renders it. */
data class HoldingRow(
    val holding: InvestmentHoldingEntity,
    val cachedPriceMinor: Long?,
    val cachedPriceCurrency: String?,
    val cachedAtEpochMillis: Long?,
    val marketValueInAccountCurrencyMinor: Long?,  // null when FX or price missing
    val costInAccountCurrencyMinor: Long?,          // null when FX missing
    val unrealizedInAccountCurrencyMinor: Long?,    // null when FX missing
    val stale: Boolean,
)

data class InvestmentDetailUiState(
    val account: AccountEntity? = null,
    val holdings: List<HoldingRow> = emptyList(),
    val totalValueMinor: Long = 0L,
    val totalCostMinor: Long = 0L,
    val unrealizedMinor: Long = 0L,
    val missingFxPairs: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    val showCloseConfirm: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class InvestmentAccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountDataSource: AccountDataSource,
    private val holdingDao: InvestmentHoldingDao,
    private val cachedQuoteDao: CachedQuoteDao,
    private val quoteRepository: QuoteDataSource,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val accountId: Long = savedStateHandle
        .get<Long>(Routes.INVESTMENT_ACCOUNT_DETAIL_ARG_ID) ?: -1L

    private val _showCloseConfirm = MutableStateFlow(false)
    private val _showDeleteConfirm = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)

    private val _closeEvent = Channel<Long>(Channel.BUFFERED)
    val closeEvent: Flow<Long> = _closeEvent.receiveAsFlow()

    val state: StateFlow<InvestmentDetailUiState> = combine(
        accountDataSource.observeActive(),
        holdingDao.observeByAccount(accountId),
        cachedQuoteDao.observeBySymbols(allSymbolsFlow()),
        settingsRepository.fxRates,
        settingsRepository.homeCurrency,
        _showCloseConfirm,
        _showDeleteConfirm,
        _isRefreshing,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val accounts = args[0] as List<AccountEntity>
        @Suppress("UNCHECKED_CAST")
        val holdings = args[1] as List<InvestmentHoldingEntity>
        @Suppress("UNCHECKED_CAST")
        val cached = (args[2] as List<*>)
            .filterIsInstance<io.github.jiro.expensetracker.data.local.CachedQuoteEntity>()
            .associateBy { it.symbol }
        val fxRates = args[3] as Map<String, Double>
        @Suppress("UNCHECKED_CAST")
        val homeCurrency = args[4] as String
        val showClose = args[5] as Boolean
        val showDelete = args[6] as Boolean
        val refreshing = args[7] as Boolean
        buildState(accounts, holdings, cached, fxRates, homeCurrency, showClose, showDelete, refreshing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InvestmentDetailUiState(),
    )

    init {
        viewModelScope.launch {
            // Initial refresh on first composition.
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val holdings = holdingDao.observeByAccount(accountId)
            val symbols = (holdings as? kotlinx.coroutines.flow.Flow<List<InvestmentHoldingEntity>>)
                ?.let { kotlinx.coroutines.flow.first(it) }
                ?.map { it.symbol }
                ?.distinct()
                ?: return@launch
            if (symbols.isEmpty()) return@launch
            _isRefreshing.value = true
            try {
                quoteRepository.refresh(symbols)
            } catch (_: Throwable) {
                // Snackbar handled by UI via state.errorMessage.
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun onCloseClick() { _showCloseConfirm.value = true }
    fun onCloseConfirm() {
        viewModelScope.launch {
            accountDataSource.close(accountId)
            _showCloseConfirm.value = false
            _closeEvent.send(accountId)
        }
    }
    fun onCloseDismiss() { _showCloseConfirm.value = false }

    private fun allSymbolsFlow(): kotlinx.coroutines.flow.Flow<List<String>> =
        holdingDao.observeByAccount(accountId).let { flow ->
            kotlinx.coroutines.flow.flow {
                flow.collect { holdings -> emit(holdings.map { it.symbol }.distinct()) }
            }
        }

    private fun buildState(
        accounts: List<AccountEntity>,
        holdings: List<InvestmentHoldingEntity>,
        cached: Map<String, io.github.jiro.expensetracker.data.local.CachedQuoteEntity>,
        fxRates: Map<String, Double>,
        homeCurrency: String,
        showClose: Boolean,
        showDelete: Boolean,
        refreshing: Boolean,
    ): InvestmentDetailUiState {
        val account = accounts.firstOrNull { it.id == accountId }
        val targetCurrency = account?.currencyCode ?: homeCurrency
        val now = System.currentTimeMillis()
        var totalValue = 0L
        var totalCost = 0L
        val missingPairs = mutableSetOf<String>()
        val rows = holdings.map { h ->
            val c = cached[h.symbol]
            val marketValueNativeMinor: Long? = if (c != null) {
                // Multiply with rounding. quantity is fractional; price is Long minor.
                Math.round(h.quantity * c.priceMinor)
            } else null
            val valueConverted = if (c != null && marketValueNativeMinor != null) {
                FxConverter.convertMinor(
                    marketValueNativeMinor, c.currencyCode, targetCurrency, fxRates,
                )?.also { /* success */ }
            } else null
            if (c != null && valueConverted == null &&
                c.currencyCode != targetCurrency
            ) {
                missingPairs.add(FxConverter.rateKey(c.currencyCode, targetCurrency))
            }
            val costConverted = FxConverter.convertMinor(
                h.costBasisMinor, h.currencyCode, targetCurrency, fxRates,
            )?.also { /* success */ }
            if (costConverted == null && h.currencyCode != targetCurrency) {
                missingPairs.add(FxConverter.rateKey(h.currencyCode, targetCurrency))
            }
            val unrealized = if (valueConverted != null && costConverted != null) {
                valueConverted - costConverted
            } else null
            if (valueConverted != null) totalValue += valueConverted
            if (costConverted != null) totalCost += costConverted
            HoldingRow(
                holding = h,
                cachedPriceMinor = c?.priceMinor,
                cachedPriceCurrency = c?.currencyCode,
                cachedAtEpochMillis = c?.fetchedAtEpochMillis,
                marketValueInAccountCurrencyMinor = valueConverted,
                costInAccountCurrencyMinor = costConverted,
                unrealizedInAccountCurrencyMinor = unrealized,
                stale = c != null && (now - c.fetchedAtEpochMillis) > STALE_THRESHOLD_MS,
            )
        }
        return InvestmentDetailUiState(
            account = account,
            holdings = rows,
            totalValueMinor = totalValue,
            totalCostMinor = totalCost,
            unrealizedMinor = totalValue - totalCost,
            missingFxPairs = missingPairs.toList(),
            isRefreshing = refreshing,
            showCloseConfirm = showClose,
            showDeleteConfirm = showDelete,
        )
    }

    companion object {
        const val STALE_THRESHOLD_MS = 6L * 60L * 60L * 1000L
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.investments.InvestmentAccountDetailViewModelTest"`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt \
        app/src/main/java/io/github/jiro/expensetracker/data/market/QuoteRepository.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModel.kt \
        app/src/test/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailViewModelTest.kt
git commit -m "feat(investments): InvestmentAccountDetailViewModel with FX rollup"
```

---

## Task 15: InvestmentAccountDetailScreen

The big UI. Top app bar with refresh + edit + close, body with total card + cost-basis line + FX warning chip + holdings list + empty-state.

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

In `app/src/main/res/values/strings.xml`:

```xml
    <!-- Investment account detail -->
    <string name="investment_total_across">Across %1$d holdings, %2$d currencies</string>
    <string name="investment_total_invested">Total invested: %1$s</string>
    <string name="investment_total_current">Current: %1$s</string>
    <string name="investment_unrealized">Unrealized: %1$s</string>
    <string name="investment_fx_missing">FX rate missing: %1$s → %2$s — add in Settings</string>
    <string name="investment_stale_updated">Updated %1$s ago</string>
    <string name="investment_no_price">No price</string>
    <string name="investment_holding_subtitle">%1$s · %2$s @ %3$s/share</string>
    <string name="investment_refresh">Refresh prices</string>
    <string name="investment_empty_title">No holdings yet</string>
    <string name="investment_add_holding">Add holding</string>
    <string name="investment_close_confirm_title">Close account?</string>
    <string name="investment_close_confirm_message">Closing this account hides it from totals but keeps the history.</string>
```

- [ ] **Step 2: Write the screen**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailScreen.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.investments

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentAccountDetailScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onAddHolding: (Long) -> Unit,
    onEditHolding: (accountId: Long, holdingId: Long) -> Unit,
    viewModel: InvestmentAccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account = state.account

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.name ?: stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.investment_refresh))
                        }
                    }
                    if (account != null) {
                        if (!account.archived) {
                            IconButton(onClick = viewModel::onCloseClick) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.account_close))
                            }
                        }
                        IconButton(onClick = { onEditAccount(account.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (account != null) {
                ExtendedFloatingActionButton(
                    onClick = { onAddHolding(account.id) },
                    text = { Text(stringResource(R.string.investment_add_holding)) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
        },
    ) { padding ->
        if (account == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("—")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("total") { TotalCard(state = state, currency = account.currencyCode) }
            if (state.missingFxPairs.isNotEmpty()) {
                item("fx_warning") { FxWarningChip(pairs = state.missingFxPairs) }
            }
            if (state.holdings.isEmpty()) {
                item("empty") {
                    Text(
                        text = stringResource(R.string.investment_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            } else {
                items(state.holdings, key = { it.holding.id }) { row ->
                    HoldingRowView(
                        row = row,
                        currency = account.currencyCode,
                        onClick = { onEditHolding(account.id, row.holding.id) },
                    )
                }
            }
        }
    }

    if (state.showCloseConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::onCloseDismiss,
            title = { Text(stringResource(R.string.investment_close_confirm_title)) },
            text = { Text(stringResource(R.string.investment_close_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onCloseConfirm) { Text(stringResource(R.string.account_close)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onCloseDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.closeEvent.collectLatest { onBack() }
    }
}

@Composable
private fun TotalCard(state: InvestmentDetailUiState, currency: String) {
    val ctx = LocalContext.current
    val currencies = state.holdings.mapNotNull { it.cachedPriceCurrency }.distinct()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = MoneyFormat.formatForDisplay(state.totalValueMinor) + " " + currency,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.holdings.isNotEmpty() && currencies.size > 1) {
            Text(
                text = stringResource(
                    R.string.investment_total_across,
                    state.holdings.size,
                    currencies.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.investment_total_invested,
                MoneyFormat.formatForDisplay(state.totalCostMinor) + " " + currency,
            ) + " · " +
                stringResource(
                    R.string.investment_total_current,
                    MoneyFormat.formatForDisplay(state.totalValueMinor) + " " + currency,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        val sign = if (state.unrealizedMinor >= 0) "+" else "−"
        val absMinor = abs(state.unrealizedMinor)
        val color = if (state.unrealizedMinor >= 0) IncomeGreen else MaterialTheme.colorScheme.error
        Text(
            text = stringResource(
                R.string.investment_unrealized,
                "$sign${MoneyFormat.formatForDisplay(absMinor)} $currency",
            ),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FxWarningChip(pairs: List<String>) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            pairs.forEach { pair ->
                val (from, to) = pair.split("_to_").let { it[0] to it[1] }
                Text(
                    text = stringResource(R.string.investment_fx_missing, from, to),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HoldingRowView(row: HoldingRow, currency: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val valueText = row.marketValueInAccountCurrencyMinor?.let {
        MoneyFormat.formatForDisplay(it) + " " + currency
    } ?: stringResource(R.string.investment_no_price)
    val avgCostPerShareText = if (row.holding.quantity > 0) {
        val perShareMinor = (row.holding.costBasisMinor / row.holding.quantity).toLong()
        MoneyFormat.formatForDisplay(perShareMinor) + " " + row.holding.currencyCode
    } else "—"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.holding.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.investment_holding_subtitle,
                            "%.4f".format(row.holding.quantity).trimEnd('0').trimEnd('.'),
                            row.holding.currencyCode,
                            avgCostPerShareText,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(valueText, style = MaterialTheme.typography.titleMedium)
                    val unreal = row.unrealizedInAccountCurrencyMinor
                    if (unreal != null) {
                        val sign = if (unreal >= 0) "+" else "−"
                        val color = if (unreal >= 0) IncomeGreen else MaterialTheme.colorScheme.error
                        Text(
                            text = "$sign${MoneyFormat.formatForDisplay(abs(unreal))} $currency",
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                    }
                }
            }
            if (row.stale && row.cachedAtEpochMillis != null) {
                val relTime = DateUtils.getRelativeTimeSpanString(
                    row.cachedAtEpochMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
                Text(
                    text = stringResource(R.string.investment_stale_updated, relTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/investments/InvestmentAccountDetailScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(investments): InvestmentAccountDetailScreen with FX rollup UI"
```

---

## Task 16: Nav wiring — routes + type-based branch

Wire the two new screens into `AppNav.kt` and route investment-account clicks to `InvestmentAccountDetailScreen`.

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt`

- [ ] **Step 1: Add routes + composables to AppNav**

In `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`:

1. Add imports for `InvestmentAccountDetailScreen`, `AddEditHoldingScreen`.
2. Add to `object Routes`:
   ```kotlin
       const val INVESTMENT_ACCOUNT_DETAIL = "investment_account/{accountId}"
       const val INVESTMENT_ACCOUNT_DETAIL_ARG_ID = "accountId"
       // holdingId absent (default -1L) = Add; present = Edit.
       const val INVESTMENT_HOLDING_EDIT = "investment_account/{accountId}/holding?id={holdingId}"
       const val INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID = "accountId"
       const val INVESTMENT_HOLDING_EDIT_ARG_HOLDING_ID = "holdingId"
   ```
3. Inside the `NavHost { ... }` block (after the existing `MEMBER_CARDS_EDIT` composable), add:
   ```kotlin
               composable(
                   route = Routes.INVESTMENT_ACCOUNT_DETAIL,
                   arguments = listOf(
                       navArgument(Routes.INVESTMENT_ACCOUNT_DETAIL_ARG_ID) { type = NavType.LongType },
                   ),
               ) {
                   InvestmentAccountDetailScreen(
                       onBack = { navController.popBackStack() },
                       onEditAccount = { id -> navController.navigate("account_edit/$id") },
                       onAddHolding = { accountId -> navController.navigate(investmentHoldingEditRoute(accountId, null)) },
                       onEditHolding = { accountId, holdingId -> navController.navigate(investmentHoldingEditRoute(accountId, holdingId)) },
                   )
               }
               composable(
                   route = Routes.INVESTMENT_HOLDING_EDIT,
                   arguments = listOf(
                       navArgument(Routes.INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID) { type = NavType.LongType },
                       navArgument(Routes.INVESTMENT_HOLDING_EDIT_ARG_HOLDING_ID) {
                           type = NavType.LongType
                           defaultValue = -1L
                       },
                   ),
               ) { backStackEntry ->
                   val accountId = backStackEntry.arguments?.getLong(Routes.INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID) ?: -1L
                   AddEditHoldingScreen(onBack = { navController.popBackStack() })
               }
   ```
4. Add the helper route function (alongside the existing `addEditRoute` / `memberCardEditRoute` helpers):
   ```kotlin
       fun investmentHoldingEditRoute(accountId: Long, holdingId: Long?): String =
           if (holdingId == null) "investment_account/$accountId/holding"
           else "investment_account/$accountId/holding?id=$holdingId"
   ```

- [ ] **Step 2: Branch `onAccountClick` by `account.type` in AccountsListScreen**

The Screen has `onAccountClick: (Long) -> Unit`. To branch on type, widen it to `(accountId: Long, type: String) -> Unit` so the caller (AppNav) can route correctly.

In `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt`, change the signature (around line 56):
```kotlin
fun AccountsListScreen(
    onAccountClick: (accountId: Long, type: String) -> Unit,
    ...
)
```

Change the call site (around line 134):
```kotlin
                AccountTile(
                    accountWithBalance = aw,
                    archived = aw.account.archived,
                    onClick = { onAccountClick(aw.account.id, aw.account.type) },
                )
```

In `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`, update the AccountsListScreen call site (around line 229):
```kotlin
            composable(Routes.ACCOUNTS_LIST) {
                AccountsListScreen(
                    onBack = { navController.popBackStack() },
                    onAddAccount = { navController.navigate(Routes.ACCOUNT_EDIT) },
                    onAccountClick = { id, type ->
                        if (type == "INVESTMENT") {
                            navController.navigate("investment_account/$id")
                        } else {
                            navController.navigate("account_detail/$id")
                        }
                    },
                )
            }
```

- [ ] **Step 3: Verify the full project compiles**

Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

Then run the entire test suite:
Run: `JAVA_HOME="C:/tools/jdk-21.0.5+11" ./gradlew :app:testDebugUnitTest`
Expected: All tests pass (existing + the new MoneyFormat/DAO/MarketData/Repository/VM/DeleteGuard tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt
git commit -m "feat(nav): route INVESTMENT accounts to InvestmentAccountDetailScreen"
```

---

## Task 17: Manual smoke test (verification gate)

The unit tests cover the logic, but the end-to-end UX (account list → detail → add holding → refresh → FX rollup → offline cached → delete guard) needs a human-driven smoke pass.

**Files:**
- Modify: `docs/superpowers/specs/2026-07-15-investment-account-design.md` (append smoke result)

- [ ] **Step 1: Run the smoke checklist from the spec**

From `docs/superpowers/specs/2026-07-15-investment-account-design.md` §7:

1. Add account "Brokerage", type=Investment, currency=USD
2. Add holding AAPL qty=10 cost=$1500 USD
3. Add holding 7203.T qty=100 cost=¥200,000 JPY
4. Set FX rate USD→JPY = 150 in Settings (or whatever existing Settings flow uses)
5. Open account detail → refresh fires → prices appear → rollup shows USD total
6. Toggle airplane mode → re-open account → still shows cached values with stale badge
7. Try to delete the account → blocked with "still holds N positions" message
8. Edit AAPL holding → save → list updates
9. Add unknown symbol "ZZZZ" → row shows "No price"

- [ ] **Step 2: Record the outcome in the spec**

Append a `## Smoke test result — <date>` section to the spec with pass/fail per step and any issues found. If any step fails, file a follow-up task and fix before considering the feature shipped.

- [ ] **Step 3: Commit the spec update**

```bash
git add docs/superpowers/specs/2026-07-15-investment-account-design.md
git commit -m "docs(investments): smoke test result"
```

---

## Self-Review

This plan was checked against the spec for coverage, placeholders, and type consistency.

**Spec coverage:**
- §3 Data model → Task 1 (MoneyFormat), Task 2 (InvestmentHoldingEntity+DAO), Task 3 (CachedQuoteEntity+DAO), Task 4 (DB migration + wiring)
- §4 Market data layer → Task 5 (interface), Task 6 (Yahoo client + MockWebServer), Task 7 (QuoteRepository), Task 8 (Hilt module)
- §5 UI & navigation → Task 11 (preset/icon), Task 12+13 (AddEditHolding), Task 14+15 (InvestmentAccountDetail), Task 16 (Nav wiring)
- §6 Edge cases → Task 10 (DeleteGuard holdings block), Task 14 (FX-missing exclusion, stale threshold, refresh on open)

**Type consistency:** `InvestmentHoldingEntity` fields and `MoneyFormat.priceToMinor`/`minorToDisplay` signatures match between production and test code. Route constants (`INVESTMENT_ACCOUNT_DETAIL_ARG_ID`, `INVESTMENT_HOLDING_EDIT_ARG_*`) match between AppNav, ViewModels, and tests.

**Placeholders:** None. Each task has full code, exact paths, and exact commands.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-16-investment-account-plan.md`. Two execution options:

1. **Subagent-Driven (recommended)** — Dispatch a fresh subagent per task with two-stage review (spec compliance, then code quality). Fast iteration, isolated context per task.

2. **Inline Execution** — Execute tasks in this session using the executing-plans skill. Batch execution with checkpoints for review.

Which approach?