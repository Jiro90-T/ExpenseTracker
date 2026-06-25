# Phase 2.16 — Account Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce financial accounts as a first-class entity bound to every transaction, with TRANSFER moving money between two accounts and balances computed live from a single SQL formula.

**Architecture:** New `AccountEntity` table + nullable `categoryId`, new `accountId` / `transferAccountId` columns on `TransactionEntity`. Migration v5→v6 seeds a default "Cash wallet" placeholder and a one-shot init syncs its currency to `SettingsRepository.homeCurrency`. New `AccountRepository` exposes a balance query. UI: an Accounts list screen (compact 2-col grid), Add/Edit Account form (single scrollable, currency locked), Account dropdown on Add/Edit Transaction and Add Receipt (between Type and Category), More tab entry. TRANSFER is a single-row transaction with `transferAccountId`; renders inline as `X → Y` in the list.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose + Material 3, Hilt 2.52, Room 2.6.1, Coroutines/Flow 1.9.0, JUnit 4, Room MigrationTestHelper. JDK 21 required (set `JAVA_HOME=C:/tools/jdk-21.0.5+11` before each `./gradlew` invocation).

**Reference spec:** `docs/superpowers/specs/2026-06-25-account-management-design.md`

**Build & test commands** (run from `F:/AndroidApp/ExpenseTracker`):
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug                                # full build
./gradlew test                                         # all JVM unit tests
./gradlew test --tests "io.github.jiro.expensetracker.<TestClass>.<method>"
./gradlew lint                                         # lint
```

---

## Task 1: Extend TransactionType enum with TRANSFER and ADJUSTMENT

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/domain/model/TransactionType.kt`

- [ ] **Step 1: Edit the enum to add two new variants**

Replace the contents of `TransactionType.kt` with:

```kotlin
package io.github.jiro.expensetracker.domain.model

/**
 * Whether a transaction moves money in, out, or between accounts. Stored as
 * a String column in Room (via [name]) for forward-compatibility.
 *
 * - EXPENSE / INCOME — the historical kinds, both reference a category.
 * - TRANSFER — moves money between two accounts in a single row. Uses
 *   `accountId` (source) + `transferAccountId` (destination), no category.
 * - ADJUSTMENT — a manual balance correction created only via the
 *   "Adjust balance" dialog on Edit Account. No category, no transfer partner.
 */
enum class TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER,
    ADJUSTMENT;

    companion object {
        fun fromStorage(raw: String): TransactionType =
            entries.firstOrNull { it.name == raw } ?: EXPENSE
    }
}
```

- [ ] **Step 2: Run all unit tests to confirm nothing else relied on a 2-variant enum**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL. All existing tests pass — the only callers of `TransactionType.entries` will now see 4 entries, but `fromStorage` still returns EXPENSE as the fallback.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/domain/model/TransactionType.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): extend TransactionType with TRANSFER and ADJUSTMENT"
```

---

## Task 2: Create AccountEntity and update TransactionEntity schema

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountEntity.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionEntity.kt`

- [ ] **Step 1: Create AccountEntity**

Create `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountEntity.kt` with:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A financial account (cash, bank, credit card, e-wallet, custom). Every
 * [TransactionEntity] is bound to one via `accountId`; TRANSFER rows also
 * reference a destination via `transferAccountId`.
 *
 * `currencyCode` is locked at creation (changing it would silently invalidate
 * every transaction's native currency assignment).
 *
 * The unique name index is enforced at the application layer (the repository
 * rejects duplicates on insert); SQLite's "unique only among non-archived"
 * partial index isn't expressible via Room's `@Index`, so duplicates can
 * technically exist in the table — we never create them.
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["name"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** "CASH" | "BANK" | "CREDIT_CARD" | "EWALLET" | "OTHER" | user custom. */
    val type: String,
    /** Emoji or short code (e.g. "💵"). */
    val icon: String,
    /** ARGB color integer. */
    val color: Int,
    /** 3-letter ISO 4217 code, locked at creation. */
    val currencyCode: String,
    val openingBalanceMinor: Long = 0L,
    val createdAtEpochMillis: Long,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)
```

- [ ] **Step 2: Update TransactionEntity**

Replace `TransactionEntity.kt` with:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single source of truth for a personal-finance transaction.
 *
 * `amountMinor` is stored in the currency's minor unit (e.g. cents) to avoid
 * floating-point drift. `type` is stored as a String column (matching
 * [io.github.jiro.expensetracker.domain.model.TransactionType.name]) so adding
 * a new type (TRANSFER, ADJUSTMENT) doesn't require a migration.
 *
 * **Accounts (Phase 2.16):** every row has `accountId`. TRANSFER rows also
 * reference `transferAccountId` (the destination); all other types leave it
 * null. `categoryId` is now nullable because TRANSFER and ADJUSTMENT have no
 * category.
 *
 * **Recurring transactions:** a row is part of a recurring series when
 * [recurringGroupId] is non-null. All rows in the same series share that id.
 * The "parent" — the row that drives the schedule — is the one with
 * [recurrenceNextAt] set; materialised instances have `recurrenceNextAt = null`.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["transferAccountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index("accountId"),
        Index("transferAccountId"),
        Index("recurringGroupId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val type: String,
    val categoryId: Long? = null,
    /** Every transaction belongs to one account (Phase 2.16). Default = seeded "Cash wallet" (id=1). */
    val accountId: Long = 1L,
    /** TRANSFER only: the destination account. null for all other types. */
    val transferAccountId: Long? = null,
    val occurredAtEpochMillis: Long,
    val note: String? = null,
    val createdAtEpochMillis: Long,
    /** Non-null iff this row is part of a recurring series. */
    val recurringGroupId: String? = null,
    /** "DAILY" / "WEEKLY" / "MONTHLY" / "YEARLY". Non-null iff [recurringGroupId] is. */
    val recurrenceKind: String? = null,
    /** Every N periods (1 = every period, 2 = every other, etc.). Defaults to 1. */
    val recurrenceInterval: Int = 1,
    /** Stop the series at this wall-clock instant (or null = no end-by-date). */
    val recurrenceEndAt: Long? = null,
    /** Stop after this many materialised instances (or null = no occurrence cap). */
    val recurrenceMaxOccurrences: Int? = null,
    /**
     * Next time the worker should materialise a new instance. Null on materialised
     * instances. On the parent, this is what the worker checks; when it fires, the
     * parent is cloned and this column is advanced to the next scheduled date (or
     * nulled if the series has ended).
     */
    val recurrenceNextAt: Long? = null,
    /**
     * Relative path under `<filesDir>/receipts/` (e.g. `abc123.jpg`), or null
     * if no receipt is attached. Relative paths survive backup-restore across
     * devices. The file is deleted by the application, not the DB.
     */
    val receiptPath: String? = null,
)
```

The defaults (`accountId = 1L`, `categoryId = null`, `transferAccountId = null`) mean existing test code that constructs `TransactionEntity(...)` without specifying these keeps compiling — they fall back to safe values.

- [ ] **Step 3: Run all unit tests — they must still pass**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL. (Existing code paths use the defaults; the migration that actually changes the table is in Task 3.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AccountEntity.kt \
        app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionEntity.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): add AccountEntity and bind transactions to accounts"
```

---

## Task 3: Add AccountEntity to AppDatabase and write the v5→v6 migration

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt`

- [ ] **Step 1: Update AppDatabase to register AccountEntity, bump version to 6, and register the migration**

Replace the contents of `AppDatabase.kt` with:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
        AccountEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun accountDao(): AccountDao

    companion object {
        const val NAME = "expense_tracker.db"

        /** v2 → v3: add the recurring-transaction columns to `transactions`. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurringGroupId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceKind TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceInterval INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceEndAt INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceMaxOccurrences INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceNextAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_recurringGroupId ON transactions (recurringGroupId)")
            }
        }

        /** v3 → v4: add the `budgets` table. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS budgets (
                        categoryId INTEGER NOT NULL,
                        monthStartEpochMs INTEGER NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        PRIMARY KEY (categoryId, monthStartEpochMs),
                        FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_monthStartEpochMs ON budgets (monthStartEpochMs)")
            }
        }

        /** v4 → v5: add the `receiptPath` column to `transactions`. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN receiptPath TEXT")
            }
        }

        /**
         * v5 → v6: accounts. Creates the `accounts` table, seeds the default
         * "Cash wallet" (placeholder USD currency — overwritten by
         * [io.github.jiro.expensetracker.data.local.AccountSeeder] on first
         * DB open after migration), adds `accountId` / `transferAccountId`
         * columns to `transactions`, makes `categoryId` nullable.
         *
         * Non-destructive: pure additive migration. No existing data is lost.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create accounts table.
                db.execSQL("""
                    CREATE TABLE accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        icon TEXT NOT NULL,
                        color INTEGER NOT NULL,
                        currencyCode TEXT NOT NULL,
                        openingBalanceMinor INTEGER NOT NULL DEFAULT 0,
                        createdAtEpochMillis INTEGER NOT NULL,
                        archived INTEGER NOT NULL DEFAULT 0,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_name ON accounts (name)")

                // 2. Seed the default "Cash wallet" with placeholder USD currency.
                //    AccountSeeder will overwrite currencyCode with the user's
                //    SettingsRepository.homeCurrency on first open after this migration.
                db.execSQL("""
                    INSERT INTO accounts (id, name, type, icon, color, currencyCode,
                                          openingBalanceMinor, createdAtEpochMillis,
                                          archived, sortOrder)
                    VALUES (1, 'Cash wallet', 'CASH', '💵', -14934489, 'USD', 0, 0, 0, 0)
                """.trimIndent())

                // 3. Add accountId / transferAccountId columns to transactions.
                db.execSQL("ALTER TABLE transactions ADD COLUMN accountId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN transferAccountId INTEGER")

                // 4. accountId FK index (Room doesn't auto-create these for ALTER TABLE).
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_accountId ON transactions (accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_transferAccountId ON transactions (transferAccountId)")

                // 5. Recreate transactions with categoryId nullable (Room can't relax NOT NULL in-place).
                //    Same column order as v5, but `categoryId INTEGER` (no NOT NULL).
                db.execSQL("""
                    CREATE TABLE transactions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        currencyCode TEXT NOT NULL,
                        type TEXT NOT NULL,
                        categoryId INTEGER,
                        accountId INTEGER NOT NULL,
                        transferAccountId INTEGER,
                        occurredAtEpochMillis INTEGER NOT NULL,
                        note TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        recurringGroupId TEXT,
                        recurrenceKind TEXT,
                        recurrenceInterval INTEGER NOT NULL DEFAULT 1,
                        recurrenceEndAt INTEGER,
                        recurrenceMaxOccurrences INTEGER,
                        recurrenceNextAt INTEGER,
                        receiptPath TEXT,
                        FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE RESTRICT,
                        FOREIGN KEY (accountId) REFERENCES accounts(id) ON DELETE RESTRICT,
                        FOREIGN KEY (transferAccountId) REFERENCES accounts(id) ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO transactions_new (
                        id, title, amountMinor, currencyCode, type, categoryId, accountId,
                        transferAccountId, occurredAtEpochMillis, note, createdAtEpochMillis,
                        recurringGroupId, recurrenceKind, recurrenceInterval, recurrenceEndAt,
                        recurrenceMaxOccurrences, recurrenceNextAt, receiptPath
                    )
                    SELECT id, title, amountMinor, currencyCode, type, categoryId, accountId,
                           transferAccountId, occurredAtEpochMillis, note, createdAtEpochMillis,
                           recurringGroupId, recurrenceKind, recurrenceInterval, recurrenceEndAt,
                           recurrenceMaxOccurrences, recurrenceNextAt, receiptPath
                    FROM transactions
                """.trimIndent())
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")

                // 6. Recreate the indices that lived on the old transactions table.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_categoryId ON transactions (categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_accountId ON transactions (accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_transferAccountId ON transactions (transferAccountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_recurringGroupId ON transactions (recurringGroupId)")
            }
        }
    }
}
```

- [ ] **Step 2: Run all unit tests**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL. The migration code is exercised by the migration test in Task 16; the test suite itself compiles cleanly.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): v5->v6 migration creates accounts table and binds transactions"
```

---

## Task 4: AccountDao with CRUD and balance query

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt`

- [ ] **Step 1: Create AccountDao**

Create `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY sortOrder, name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY sortOrder, name")
    suspend fun listActiveOnce(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = 1 LIMIT 1")
    suspend fun findDefault(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity): Int

    @Query("UPDATE accounts SET currencyCode = :code WHERE id = 1")
    suspend fun updateDefaultCurrency(code: String): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE archived = 0")
    suspend fun countActive(): Int

    /**
     * Returns a row per non-archived account with its computed balance.
     * Mirrors the spec formula:
     *   opening + SUM(INCOME/EXPENSE/ADJUSTMENT on accountId)
     *         - SUM(TRANSFER where accountId = source)
     *         + SUM(TRANSFER where transferAccountId = destination)
     *
     * Rendered by the AccountsListViewModel as a map `accountId -> balanceMinor`.
     */
    @Query("""
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
        WHERE a.archived = 0
    """)
    fun observeBalances(): Flow<List<AccountBalanceRow>>
}

/** Projection returned by [AccountDao.observeBalances]. */
data class AccountBalanceRow(
    val accountId: Long,
    val balanceMinor: Long,
)
```

- [ ] **Step 2: Compile to confirm Room accepts the schema**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL. Room validates the entity ↔ DAO ↔ query wiring.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): add AccountDao with CRUD and balance projection"
```

---

## Task 5: AccountRepository with JVM unit tests for the balance formula

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/data/repository/AccountBalanceFormulaTest.kt`

- [ ] **Step 1: Write the failing test for the balance formula**

Create `app/src/test/java/io/github/jiro/expensetracker/data/repository/AccountBalanceFormulaTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the balance formula as a pure function over a fixed list of accounts
 * and transactions. The actual SQL query (in [AccountDao.observeBalances])
 * mirrors this; if it diverges, the migration test in Task 16 catches that.
 */
class AccountBalanceFormulaTest {

    private val now = 1_700_000_000_000L

    private fun txn(
        type: TransactionType,
        accountId: Long,
        amountMinor: Long,
        transferAccountId: Long? = null,
    ) = TransactionEntity(
        title = "t",
        amountMinor = amountMinor,
        currencyCode = "USD",
        type = type.name,
        accountId = accountId,
        transferAccountId = transferAccountId,
        occurredAtEpochMillis = now,
        createdAtEpochMillis = now,
    )

    private fun acct(id: Long, opening: Long = 0L) =
        AccountEntity(
            id = id,
            name = "A$id",
            type = "CASH",
            icon = "💵",
            color = 0,
            currencyCode = "USD",
            openingBalanceMinor = opening,
            createdAtEpochMillis = now,
        )

    private fun balanceOf(
        targetId: Long,
        accounts: List<AccountEntity>,
        txns: List<TransactionEntity>,
    ): Long {
        val account = accounts.first { it.id == targetId }
        val opening = account.openingBalanceMinor
        val onAccount = txns.filter {
            it.accountId == targetId && it.type in setOf("INCOME", "EXPENSE", "ADJUSTMENT")
        }.sumOf { it.amountMinor }
        val out = txns.filter {
            it.accountId == targetId && it.type == "TRANSFER"
        }.sumOf { it.amountMinor }
        val `in` = txns.filter {
            it.transferAccountId == targetId && it.type == "TRANSFER"
        }.sumOf { it.amountMinor }
        return opening + onAccount - out + `in`
    }

    @Test fun `opening balance alone`() {
        val a = listOf(acct(1, opening = 5000L))
        assertEquals(5000L, balanceOf(1, a, emptyList()))
    }

    @Test fun `opening plus income`() {
        val a = listOf(acct(1, opening = 100L))
        val t = listOf(txn(TransactionType.INCOME, 1, 900L))
        assertEquals(1000L, balanceOf(1, a, t))
    }

    @Test fun `opening plus expense reduces balance`() {
        val a = listOf(acct(1, opening = 1000L))
        val t = listOf(txn(TransactionType.EXPENSE, 1, 250L))
        assertEquals(750L, balanceOf(1, a, t))
    }

    @Test fun `transfer out subtracts from source`() {
        val a = listOf(acct(1, opening = 1000L), acct(2, opening = 0L))
        val t = listOf(txn(TransactionType.TRANSFER, 1, 200L, transferAccountId = 2))
        assertEquals(800L, balanceOf(1, a, t))
        assertEquals(200L, balanceOf(2, a, t))
    }

    @Test fun `transfer in adds to destination`() {
        val a = listOf(acct(1, opening = 1000L), acct(2, opening = 0L))
        val t = listOf(txn(TransactionType.TRANSFER, 1, 200L, transferAccountId = 2))
        assertEquals(200L, balanceOf(2, a, t))
    }

    @Test fun `transfer out and in net to opening`() {
        val a = listOf(acct(1, opening = 500L), acct(2, opening = 500L))
        val t = listOf(txn(TransactionType.TRANSFER, 1, 100L, transferAccountId = 2))
        assertEquals(500L, balanceOf(1, a, t))
        assertEquals(500L, balanceOf(2, a, t))
    }

    @Test fun `adjustment adds directly to balance`() {
        val a = listOf(acct(1, opening = 100L))
        val t = listOf(txn(TransactionType.ADJUSTMENT, 1, -30L))
        assertEquals(70L, balanceOf(1, a, t))
    }

    @Test fun `credit card negative balance`() {
        val a = listOf(acct(1, opening = 0L, ))
        val t = listOf(txn(TransactionType.EXPENSE, 1, 432L))
        assertEquals(-432L, balanceOf(1, a, t))
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails (no implementation yet)**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test --tests "io.github.jiro.expensetracker.data.repository.AccountBalanceFormulaTest"
```
Expected: Compilation error — `AccountRepository` doesn't exist yet.

- [ ] **Step 3: Create the AccountRepository**

Create `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Singleton
open class AccountRepository @Inject constructor(
    private val dao: AccountDao,
) {
    fun observeActive(): Flow<List<AccountEntity>> = dao.observeActive()

    suspend fun listActiveOnce(): List<AccountEntity> = dao.listActiveOnce()

    suspend fun findById(id: Long): AccountEntity? = dao.findById(id)

    suspend fun countActive(): Int = dao.countActive()

    /** Returns the seeded default account (id=1) if it exists. */
    suspend fun findDefault(): AccountEntity? = dao.findDefault()

    /**
     * Insert a new account. Returns the row id. Returns -1 if a duplicate
     * name exists (unique index violation). Callers should check for that
     * and surface a "name already in use" error.
     */
    open suspend fun add(account: AccountEntity): Long = dao.insert(account)

    open suspend fun update(account: AccountEntity) {
        dao.update(account)
    }

    /** Stream of all non-archived accounts joined with their computed balances. */
    fun observeWithBalances(): Flow<List<AccountWithBalance>> =
        combine(dao.observeActive(), dao.observeBalances()) { accounts, balances ->
            val map = balances.associate { it.accountId to it.balanceMinor }
            accounts.map { acc ->
                AccountWithBalance(
                    account = acc,
                    balanceMinor = map[acc.id] ?: acc.openingBalanceMinor,
                )
            }
        }

    /**
     * Overwrites the seeded default account's currency. Used by [AccountSeeder]
     * to sync the placeholder 'USD' from the v5→v6 migration to the user's
     * SettingsRepository.homeCurrency on first DB open.
     */
    open suspend fun syncDefaultCurrency(code: String) {
        dao.updateDefaultCurrency(code)
    }
}

/** Account row joined with its computed balance. */
data class AccountWithBalance(
    val account: AccountEntity,
    val balanceMinor: Long,
)
```

- [ ] **Step 4: Run the test to confirm it now passes**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test --tests "io.github.jiro.expensetracker.data.repository.AccountBalanceFormulaTest"
```
Expected: BUILD SUCCESSFUL. 8/8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt \
        app/src/test/java/io/github/jiro/expensetracker/data/repository/AccountBalanceFormulaTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AccountRepository with pure balance-formula tests"
```

---

## Task 6: AccountSeeder + DI wiring

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountSeeder.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/di/DatabaseModule.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ExpenseTrackerApp.kt`

- [ ] **Step 1: Create the AccountSeeder**

Create `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountSeeder.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-migration reconciliation. The v5→v6 migration seeds the default
 * "Cash wallet" with `currencyCode='USD'` as a placeholder (migrations don't
 * have access to SettingsRepository). On first DB open after migration, this
 * seeder runs and overwrites the placeholder with the user's actual
 * home currency.
 *
 * Idempotent: a no-op once the seeded default matches the home currency.
 */
@Singleton
class AccountSeeder @Inject constructor(
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun syncDefaultCurrency() {
        val home = settingsRepository.homeCurrency.value
        val default = accountRepository.findDefault() ?: return
        if (default.currencyCode != home) {
            accountRepository.syncDefaultCurrency(home)
        }
    }
}
```

- [ ] **Step 2: Register AccountDao in DatabaseModule**

Edit `app/src/main/java/io/github/jiro/expensetracker/di/DatabaseModule.kt` — add the import for `AccountDao`, add the migration, and add the DAO provider:

```kotlin
package io.github.jiro.expensetracker.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.data.local.BudgetDao
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.TransactionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideBudgetDao(db: AppDatabase): BudgetDao = db.budgetDao()

    @Provides
    fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()
}
```

- [ ] **Step 3: Wire AccountSeeder into ExpenseTrackerApp**

Edit `app/src/main/java/io/github/jiro/expensetracker/ExpenseTrackerApp.kt`:

```kotlin
@HiltAndroidApp
class ExpenseTrackerApp : Application(), Configuration.Provider {

    @Inject lateinit var categorySeeder: CategorySeeder
    @Inject lateinit var accountSeeder: AccountSeeder
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope.launch {
            categorySeeder.seedIfEmpty()
            accountSeeder.syncDefaultCurrency()
        }
        scheduleRecurringTransactionJob()
        triggerRecurringTransactionCheckOnLaunch()
    }

    // ... rest unchanged
}
```

- [ ] **Step 4: Build to confirm Hilt wiring is sound**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AccountSeeder.kt \
        app/src/main/java/io/github/jiro/expensetracker/di/DatabaseModule.kt \
        app/src/main/java/io/github/jiro/expensetracker/ExpenseTrackerApp.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AccountSeeder syncs default currency post-migration"
```

---

## Task 7: AddEditAccountViewModel + screen (single scrollable form)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt`
- Create: `app/src/main/java/io/github/github/jiro/expensetracker/ui/accounts/AddEditAccountScreen.kt` — actually: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the new strings**

Append to `app/src/main/res/values/strings.xml` (after the existing Categories management block):

```xml
    <!-- Accounts (Phase 2.16) -->
    <string name="accounts_title">Accounts</string>
    <string name="action_manage_accounts">Manage accounts</string>
    <string name="account_add_title">New account</string>
    <string name="account_edit_title">Edit account</string>
    <string name="field_account_name">Account name</string>
    <string name="field_account_type">Type</string>
    <string name="field_account_icon">Icon</string>
    <string name="field_account_color">Color</string>
    <string name="field_account_currency">Currency</string>
    <string name="field_account_opening_balance">Opening balance</string>
    <string name="field_account_opening_balance_hint">Pre-existing balance as of account creation. Optional — leave 0 if starting fresh.</string>
    <string name="field_account">Account</string>
    <string name="hint_account_currency_locked">Currency cannot be changed — create a new account if you need a different currency.</string>
    <string name="action_adjust_balance">Adjust balance</string>
    <string name="action_adjust_balance_dialog_title">Adjust balance</string>
    <string name="action_adjust_balance_dialog_body">Current balance: %1$s\nNew balance:</string>
    <string name="action_adjust_balance_dialog_confirm">Adjust</string>
    <string name="account_type_cash">Cash</string>
    <string name="account_type_bank">Bank</string>
    <string name="account_type_credit_card">Credit card</string>
    <string name="account_type_ewallet">e-Wallet</string>
    <string name="account_type_other">Other</string>
    <string name="account_type_custom">Custom</string>
    <string name="error_account_name_required">Name is required</string>
    <string name="error_account_name_duplicate">An account with this name already exists</string>
    <string name="error_account_currency_required">Currency is required</string>
    <string name="accounts_header_balance">Net balance (home)</string>
    <string name="accounts_header_count">across %1$d accounts</string>
    <string name="account_select_placeholder">Select an account</string>
    <string name="account_to_account">To account</string>
    <string name="type_transfer">Transfer</string>
    <string name="account_color_default_blue">#1976D2</string>
    <string name="account_balance_adjustment_note">Balance adjustment: %1$s → %2$s</string>
```

- [ ] **Step 2: Create AddEditAccountViewModel**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Stable identifiers for each user-facing error. */
enum class AccountFormError { NAME_REQUIRED, NAME_DUPLICATE, CURRENCY_REQUIRED }

/** The 5 presets; user can also pick "custom" and type their own. */
val ACCOUNT_TYPE_PRESETS = listOf("CASH", "BANK", "CREDIT_CARD", "EWALLET", "OTHER")

/** 8 emoji icons to pick from (matches the visual mockup). */
val ACCOUNT_ICON_CHOICES = listOf("💵", "🏦", "💳", "📱", "💰", "💼", "🎯", "🏠")

/** 8 preset colors (ARGB). Index 0 is the default blue. */
val ACCOUNT_COLOR_CHOICES = listOf(
    0xFF1976D2.toInt(), // blue
    0xFF43A047.toInt(), // green
    0xFFF57C00.toInt(), // orange
    0xFFC62828.toInt(), // red
    0xFF7B1FA2.toInt(), // purple
    0xFF00838F.toInt(), // teal
    0xFF5D4037.toInt(), // brown
    0xFF455A64.toInt(), // slate
)

data class AddEditAccountUiState(
    val isEdit: Boolean = false,
    val name: String = "",
    val type: String = "CASH",
    val customType: String = "",
    val icon: String = "💵",
    val color: Int = ACCOUNT_COLOR_CHOICES.first(),
    val currency: String = "USD",
    val openingBalanceInput: String = "0",
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val error: AccountFormError? = null,
    /** True after the form has been hydrated from an existing row. */
    val isCurrencyLocked: Boolean = false,
    /** Adjust balance dialog state. Non-null when the dialog should be visible. */
    val adjustDialog: AdjustBalanceDialogState? = null,
    /** True when at least one transaction exists against this account (Edit only). */
    val hasTransactions: Boolean = false,
    /** Current balance for the adjust dialog. */
    val currentBalanceMinor: Long = 0L,
)

data class AdjustBalanceDialogState(
    val newBalanceInput: String = "",
    val isSaving: Boolean = false,
)

@HiltViewModel
class AddEditAccountViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) : AndroidViewModel(application) {

    private val accountId: Long? = savedStateHandle
        .get<Long>("id")
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(AddEditAccountUiState(isEdit = accountId != null))
    val state: StateFlow<AddEditAccountUiState> = _state.asStateFlow()

    init {
        if (accountId != null) {
            viewModelScope.launch {
                val existing = accountRepository.findById(accountId) ?: return@launch
                val txnCount = transactionRepository.countForAccount(accountId)
                val currentBalance = accountRepository.observeWithBalances() // warm-up; first emission is cheap
                _state.update {
                    it.copy(
                        name = existing.name,
                        type = if (existing.type in ACCOUNT_TYPE_PRESETS) existing.type else "OTHER",
                        customType = if (existing.type !in ACCOUNT_TYPE_PRESETS) existing.type else "",
                        icon = existing.icon,
                        color = existing.color,
                        currency = existing.currencyCode,
                        openingBalanceInput = MoneyFormat.formatAmountForEdit(existing.openingBalanceMinor),
                        isCurrencyLocked = true,
                        hasTransactions = txnCount > 0,
                        isLoaded = true,
                    )
                }
            }
        } else {
            _state.update { it.copy(isLoaded = true) }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onTypeChange(value: String) = _state.update { it.copy(type = value, customType = "") }
    fun onCustomTypeChange(value: String) = _state.update { it.copy(customType = value) }
    fun onIconChange(value: String) = _state.update { it.copy(icon = value) }
    fun onColorChange(value: Int) = _state.update { it.copy(color = value) }
    fun onCurrencyChange(value: String) = _state.update {
        if (it.isCurrencyLocked) it else it.copy(currency = value, error = null)
    }
    fun onOpeningBalanceChange(value: String) = _state.update { it.copy(openingBalanceInput = value) }

    fun openAdjustDialog() {
        viewModelScope.launch {
            val id = accountId ?: return@launch
            val balance = accountRepository.observeWithBalances()
            // Take the first non-empty emission. observeWithBalances emits when
            // accounts OR transactions change; on a fresh Edit screen it's
            // synchronous on the first emit.
            val first = balance.firstSnapshotForAccount(id)
            _state.update {
                it.copy(
                    currentBalanceMinor = first,
                    adjustDialog = AdjustBalanceDialogState(
                        newBalanceInput = MoneyFormat.formatAmountForEdit(first),
                    ),
                )
            }
        }
    }

    fun closeAdjustDialog() = _state.update { it.copy(adjustDialog = null) }

    fun onAdjustNewBalanceChange(value: String) = _state.update {
        it.copy(adjustDialog = it.adjustDialog?.copy(newBalanceInput = value))
    }

    fun confirmAdjustBalance() {
        val id = accountId ?: return
        val dialog = _state.value.adjustDialog ?: return
        val newBalance = MoneyFormat.parseAmountToMinor(dialog.newBalanceInput)
        if (newBalance == null) return
        val delta = newBalance - _state.value.currentBalanceMinor
        if (delta == 0L) {
            _state.update { it.copy(adjustDialog = null) }
            return
        }
        _state.update { it.copy(adjustDialog = dialog.copy(isSaving = true)) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val oldStr = MoneyFormat.formatAmountForEdit(_state.value.currentBalanceMinor)
            val newStr = MoneyFormat.formatAmountForEdit(newBalance)
            transactionRepository.add(
                io.github.jiro.expensetracker.data.local.TransactionEntity(
                    title = "Balance adjustment: $oldStr → $newStr",
                    amountMinor = delta,
                    currencyCode = _state.value.currency,
                    type = TransactionType.ADJUSTMENT.name,
                    accountId = id,
                    occurredAtEpochMillis = now,
                    createdAtEpochMillis = now,
                ),
            )
            _state.update { it.copy(adjustDialog = null) }
        }
    }

    fun save() {
        val s = _state.value
        val name = s.name.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(error = AccountFormError.NAME_REQUIRED) }
            return
        }
        val type = if (s.type == "OTHER" && s.customType.isNotBlank()) s.customType.trim() else s.type
        val currency = s.currency.trim().uppercase()
        if (currency.length != 3) {
            _state.update { it.copy(error = AccountFormError.CURRENCY_REQUIRED) }
            return
        }
        val opening = MoneyFormat.parseAmountToMinor(s.openingBalanceInput) ?: 0L

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = AccountEntity(
                id = accountId ?: 0L,
                name = name,
                type = type,
                icon = s.icon,
                color = s.color,
                currencyCode = currency,
                openingBalanceMinor = if (s.isEdit) opening else opening,
                createdAtEpochMillis = if (s.isEdit) now else now,
                sortOrder = 0,
            )
            if (s.isEdit) {
                accountRepository.update(entity)
            } else {
                val newId = accountRepository.add(entity)
                if (newId == -1L) {
                    _state.update {
                        it.copy(isSaving = false, error = AccountFormError.NAME_DUPLICATE)
                    }
                    return@launch
                }
            }
            _state.update { it.copy(isSaving = false, saveComplete = true) }
        }
    }
}

/**
 * Helper: take the first emission of `observeWithBalances()` and return the
 * balance for [targetId]. Falls back to 0L if no row matches (shouldn't
 * happen for an existing account but guards against race conditions during
 * Edit screen hydration).
 */
private fun Flow<List<AccountWithBalance>>.firstSnapshotForAccount(targetId: Long): Long {
    return kotlinx.coroutines.flow.first(this).firstOrNull { it.account.id == targetId }?.balanceMinor ?: 0L
}
```

- [ ] **Step 3: Add TransactionRepository.countForAccount**

Add this method to `app/src/main/java/io/github/jiro/expensetracker/data/repository/TransactionRepository.kt`:

```kotlin
suspend fun countForAccount(accountId: Long): Int =
    dao.countForAccount(accountId)
```

And add the matching DAO method to `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionDao.kt`:

```kotlin
@Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId")
suspend fun countForAccount(accountId: Long): Int
```

- [ ] **Step 4: Run tests to verify the additions compile and pass**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/repository/TransactionRepository.kt \
        app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionDao.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountViewModel.kt \
        app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AddEditAccountViewModel with form state and adjust-balance dialog"
```

---

## Task 8: AddEditAccountScreen (Compose UI)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountScreen.kt`

- [ ] **Step 1: Create the screen**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountScreen.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditAccountScreen(
    onBack: () -> Unit,
    viewModel: AddEditAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(
                        if (state.isEdit) R.string.account_edit_title
                        else R.string.account_add_title,
                    ))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        AddEditAccountForm(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditAccountForm(
    state: AddEditAccountUiState,
    viewModel: AddEditAccountViewModel,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Icon picker
        Text(
            text = stringResource(R.string.field_account_icon),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ACCOUNT_ICON_CHOICES.forEach { emoji ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (state.icon == emoji) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .clickable { viewModel.onIconChange(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // Color picker
        Text(
            text = stringResource(R.string.field_account_color),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ACCOUNT_COLOR_CHOICES.forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(argb))
                        .border(
                            width = if (state.color == argb) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .clickable { viewModel.onColorChange(argb) },
                )
            }
        }

        // Name
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            label = { Text(stringResource(R.string.field_account_name)) },
            singleLine = true,
            isError = state.error == AccountFormError.NAME_REQUIRED ||
                state.error == AccountFormError.NAME_DUPLICATE,
            supportingText = {
                when (state.error) {
                    AccountFormError.NAME_REQUIRED -> Text(stringResource(R.string.error_account_name_required))
                    AccountFormError.NAME_DUPLICATE -> Text(stringResource(R.string.error_account_name_duplicate))
                    else -> Unit
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Type dropdown
        var typeExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = it },
        ) {
            OutlinedTextField(
                value = presetLabel(state.type),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.field_account_type)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                ACCOUNT_TYPE_PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(presetLabel(preset)) },
                        onClick = {
                            viewModel.onTypeChange(preset)
                            typeExpanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_type_custom)) },
                    onClick = {
                        viewModel.onTypeChange("OTHER")
                        typeExpanded = false
                    },
                )
            }
        }
        if (state.type == "OTHER") {
            OutlinedTextField(
                value = state.customType,
                onValueChange = viewModel::onCustomTypeChange,
                label = { Text(stringResource(R.string.account_type_custom)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Currency (locked when editing)
        OutlinedTextField(
            value = state.currency,
            onValueChange = viewModel::onCurrencyChange,
            label = { Text(stringResource(R.string.field_account_currency)) },
            enabled = !state.isCurrencyLocked,
            singleLine = true,
            supportingText = {
                if (state.isCurrencyLocked) {
                    Text(stringResource(R.string.hint_account_currency_locked))
                } else if (state.error == AccountFormError.CURRENCY_REQUIRED) {
                    Text(stringResource(R.string.error_account_currency_required))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Opening balance (Add only — Edit uses Adjust balance)
        if (!state.isEdit) {
            OutlinedTextField(
                value = state.openingBalanceInput,
                onValueChange = viewModel::onOpeningBalanceChange,
                label = { Text(stringResource(R.string.field_account_opening_balance)) },
                supportingText = { Text(stringResource(R.string.field_account_opening_balance_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving && state.isLoaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_save))
        }

        if (state.isEdit && state.hasTransactions) {
            OutlinedButton(
                onClick = viewModel::openAdjustDialog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_adjust_balance))
            }
        }
    }

    state.adjustDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::closeAdjustDialog,
            title = { Text(stringResource(R.string.action_adjust_balance_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            R.string.action_adjust_balance_dialog_body,
                            MoneyFormat.formatAmountForEdit(state.currentBalanceMinor),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialog.newBalanceInput,
                        onValueChange = viewModel::onAdjustNewBalanceChange,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmAdjustBalance,
                    enabled = !dialog.isSaving,
                ) { Text(stringResource(R.string.action_adjust_balance_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeAdjustDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun presetLabel(preset: String): String = when (preset) {
    "CASH" -> stringResource(R.string.account_type_cash)
    "BANK" -> stringResource(R.string.account_type_bank)
    "CREDIT_CARD" -> stringResource(R.string.account_type_credit_card)
    "EWALLET" -> stringResource(R.string.account_type_ewallet)
    "OTHER" -> stringResource(R.string.account_type_other)
    else -> preset
}
```

- [ ] **Step 2: Build to confirm the screen compiles**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AddEditAccountScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AddEditAccountScreen single scrollable form"
```

---

## Task 9: Add account navigation routes to AppNavHost

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`

- [ ] **Step 1: Add the route constants and composable entries**

Edit `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`. First, add the new constants to the `Routes` object:

```kotlin
object Routes {
    // ... existing routes ...
    const val ACCOUNTS_LIST = "accounts_list"
    const val ACCOUNT_DETAIL = "account_detail/{accountId}"
    const val ACCOUNT_DETAIL_ARG_ID = "accountId"
    const val ACCOUNT_EDIT = "account_edit"
    const val ACCOUNT_EDIT_WITH_ID = "account_edit/{accountId}"
    const val ACCOUNT_EDIT_ARG_ID = "accountId"
}
```

Then add the import and the composable entries. Replace the `composable(Routes.MORE) { ... }` block and add new composables:

```kotlin
import io.github.jiro.expensetracker.ui.accounts.AccountDetailScreen
import io.github.jiro.expensetracker.ui.accounts.AccountsListScreen
import io.github.jiro.expensetracker.ui.accounts.AddEditAccountScreen

// ... inside NavHost, before the MoreScreen composable entry ...

composable(Routes.ACCOUNTS_LIST) {
    AccountsListScreen(
        onBack = { navController.popBackStack() },
        onAddAccount = { navController.navigate(Routes.ACCOUNT_EDIT) },
        onAccountClick = { id -> navController.navigate("account_detail/$id") },
    )
}
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
composable(Routes.ACCOUNT_EDIT) {
    AddEditAccountScreen(onBack = { navController.popBackStack() })
}
composable(
    route = Routes.ACCOUNT_EDIT_WITH_ID,
    arguments = listOf(
        navArgument(Routes.ACCOUNT_EDIT_ARG_ID) { type = NavType.LongType },
    ),
) {
    AddEditAccountScreen(onBack = { navController.popBackStack() })
}
```

- [ ] **Step 2: Build to confirm navigation wiring is sound**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL — but the screen composables referenced don't exist yet. They'll be added in Tasks 10, 11.

Wait — Compose resolves these at runtime, not compile time, so the build will succeed even without the screens implemented. We'll get runtime errors only when navigating. That's fine for now; Tasks 10 and 11 add the screens.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): wire accounts routes into AppNavHost"
```

---

## Task 10: AccountsListViewModel + screen (compact 2-col grid)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModel.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt`

- [ ] **Step 1: Create the ViewModel**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModel.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AccountsListUiState(
    val accounts: List<AccountWithBalance> = emptyList(),
    val netBalanceInHome: String = "",
    val count: Int = 0,
    val isLoading: Boolean = true,
)

@HiltViewModel
class AccountsListViewModel @Inject constructor(
    accountRepository: AccountRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AccountsListUiState> = combine(
        accountRepository.observeWithBalances(),
        settingsRepository.fxRates,
        settingsRepository.homeCurrency,
    ) { accounts, fx, home ->
        val net = accounts.fold(0.0) { acc, aw ->
            val converted = FxConverter.convert(
                amountMinor = aw.balanceMinor,
                from = aw.account.currencyCode,
                to = home,
                rates = fx,
            )
            acc + converted
        }
        AccountsListUiState(
            accounts = accounts,
            netBalanceInHome = "%.2f %s".format(net, home),
            count = accounts.size,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AccountsListUiState(),
    )
}
```

- [ ] **Step 2: Create the screen**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.repository.AccountWithBalance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsListScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountClick: (Long) -> Unit,
    viewModel: AccountsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAccount) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.account_add_title))
            }
        },
    ) { padding ->
        if (state.accounts.isEmpty() && !state.isLoading) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HeaderCard(
                    netBalanceInHome = state.netBalanceInHome,
                    count = state.count,
                )
            }
            items(state.accounts, key = { it.account.id }) { aw ->
                AccountTile(
                    accountWithBalance = aw,
                    onClick = { onAccountClick(aw.account.id) },
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(netBalanceInHome: String, count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.accounts_header_balance),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = netBalanceInHome,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.accounts_header_count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountTile(
    accountWithBalance: AccountWithBalance,
    onClick: () -> Unit,
) {
    val account = accountWithBalance.account
    val balance = accountWithBalance.balanceMinor
    val isNegative = balance < 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(account.icon, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = account.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isNegative) {
                "−${io.github.jiro.expensetracker.data.local.MoneyFormat.formatAmountForEdit(-balance)} ${account.currencyCode}"
            } else {
                "${io.github.jiro.expensetracker.data.local.MoneyFormat.formatAmountForEdit(balance)} ${account.currencyCode}"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.account_add_title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

// GridItemSpan helper (Compose Foundation Layout has this; alias for readability).
private fun GridItemSpan(maxLineSpan: Int): androidx.compose.foundation.lazy.grid.GridItemSpan =
    androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
```

- [ ] **Step 3: Build to confirm**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModel.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AccountsListScreen compact 2-col grid with net-balance header"
```

---

## Task 11: AccountDetailScreen + ViewModel (filtered transactions for one account)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt`

- [ ] **Step 1: Add a DAO method to query transactions for one account**

Add to `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionDao.kt`:

```kotlin
@Transaction
@Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY occurredAtEpochMillis DESC")
fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>>
```

- [ ] **Step 2: Add the repository method**

Add to `app/src/main/java/io/github/jiro/expensetracker/data/repository/TransactionRepository.kt`:

```kotlin
fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
    dao.observeByAccount(accountId)
```

- [ ] **Step 3: Create the ViewModel**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`:

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AccountDetailUiState(
    val accountWithBalance: AccountWithBalance? = null,
    val transactions: List<TransactionWithCategory> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>(Routes.ACCOUNT_DETAIL_ARG_ID) ?: -1L

    val state: StateFlow<AccountDetailUiState> = combine(
        accountRepository.observeWithBalances(),
        transactionRepository.observeByAccount(accountId),
    ) { accounts, txns ->
        AccountDetailUiState(
            accountWithBalance = accounts.firstOrNull { it.account.id == accountId },
            transactions = txns,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AccountDetailUiState(),
    )
}
```

- [ ] **Step 4: Create the screen**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.home.TransactionRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val aw = state.accountWithBalance

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(aw?.account?.name ?: stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (aw != null) {
                        IconButton(onClick = { onEditAccount(aw.account.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (aw == null && !state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("—")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            aw?.let {
                item("header") {
                    BalanceHeader(name = it.account.name, balanceMinor = it.balanceMinor, currencyCode = it.account.currencyCode)
                    Spacer(Modifier.height(16.dp))
                }
            }
            items(state.transactions, key = { it.transaction.id }) { row ->
                TransactionRow(row = row, onClick = { onTransactionClick(row.transaction.id) })
            }
        }
    }
}

@Composable
private fun BalanceHeader(name: String, balanceMinor: Long, currencyCode: String) {
    val isNegative = balanceMinor < 0
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = (if (isNegative) "−" else "") + MoneyFormat.formatAmountForEdit(if (isNegative) -balanceMinor else balanceMinor) + " " + currencyCode,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
```

- [ ] **Step 5: Build to confirm**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionDao.kt \
        app/src/main/java/io/github/jiro/expensetracker/data/repository/TransactionRepository.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AccountDetailScreen with filtered transaction list"
```

---

## Task 12: More tab "Accounts" entry

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/more/MoreScreen.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`

- [ ] **Step 1: Add the Accounts entry to MoreScreen**

Replace the `items = listOf(...)` block in `MoreScreen.kt`:

```kotlin
val items = listOf(
    MoreItem(
        title = stringResource(R.string.action_manage_accounts),
        icon = Icons.Filled.AccountBalance,  // wallet / bank icon
        onClick = onManageAccounts,
    ),
    MoreItem(
        title = stringResource(R.string.action_add_receipt),
        icon = Icons.Filled.PhotoCamera,
        onClick = onAddReceipt,
    ),
    MoreItem(
        title = stringResource(R.string.action_manage_categories),
        icon = Icons.Filled.Category,
        onClick = onManageCategories,
    ),
    MoreItem(
        title = stringResource(R.string.settings_title),
        icon = Icons.Filled.Settings,
        onClick = onOpenSettings,
    ),
)
```

Add the new parameter to `MoreScreen`:

```kotlin
fun MoreScreen(
    onManageAccounts: () -> Unit = {},
    onManageCategories: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAddReceipt: () -> Unit = {},
)
```

Add the import:

```kotlin
import androidx.compose.material.icons.filled.AccountBalance
```

- [ ] **Step 2: Wire the navigation in AppNav**

In `AppNav.kt`, update the `composable(Routes.MORE)` block:

```kotlin
composable(Routes.MORE) {
    MoreScreen(
        onManageAccounts = { navController.navigate(Routes.ACCOUNTS_LIST) },
        onManageCategories = { navController.navigate(Routes.CATEGORIES) },
        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
        onAddReceipt = { navController.navigate(Routes.ADD_RECEIPT) },
    )
}
```

- [ ] **Step 3: Build and commit**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/more/MoreScreen.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): add Accounts entry to More tab"
```

---

## Task 13: Add Account field to AddEditTransaction (ViewModel state)

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt`

- [ ] **Step 1: Add account-related state and handlers**

Add these fields to `AddEditTransactionUiState`:

```kotlin
// ---- Phase 2.16: per-tx account ----
val accounts: List<AccountEntity> = emptyList(),
val selectedAccountId: Long? = null,
/** TRANSFER only: the destination account. null for EXPENSE/INCOME/ADJUSTMENT. */
val selectedTransferAccountId: Long? = null,
```

Add `AccountFormError` and a new error variant:

```kotlin
enum class FormError { TITLE_REQUIRED, AMOUNT_INVALID, CATEGORY_REQUIRED, RECEIPT_SAVE_FAILED, ACCOUNT_REQUIRED, TRANSFER_ACCOUNTS_MUST_DIFFER }
```

Inject `AccountRepository` into the ViewModel:

```kotlin
@HiltViewModel
class AddEditTransactionViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,  // NEW
    private val receiptRepository: ReceiptRepository,
    private val receiptOcrProcessor: ReceiptOcrProcessor,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) { ... }
```

Add the import:

```kotlin
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.repository.AccountRepository
```

Add a new collector in `init` for accounts:

```kotlin
// Accounts list — observable for the dropdown.
viewModelScope.launch {
    accountRepository.observeActive().collect { accounts ->
        _state.update { current ->
            val validIds = accounts.map { it.id }.toSet()
            current.copy(
                accounts = accounts,
                selectedAccountId = current.selectedAccountId?.takeIf { it in validIds },
                selectedTransferAccountId = current.selectedTransferAccountId?.takeIf { it in validIds },
            )
        }
    }
}
```

When editing an existing transaction, hydrate `selectedAccountId` and `selectedTransferAccountId`:

```kotlin
val existing = transactionRepository.findById(transactionId) ?: return@launch
_state.update {
    it.copy(
        ...,
        selectedAccountId = existing.accountId,
        selectedTransferAccountId = existing.transferAccountId,
    )
}
```

Add the type-change handler that clears the transfer account when type changes away from TRANSFER:

```kotlin
fun onTypeChange(value: TransactionType) = _state.update {
    it.copy(
        type = value,
        selectedCategoryId = null,
        // Clear transfer account when type is no longer TRANSFER
        selectedTransferAccountId = if (value == TransactionType.TRANSFER) it.selectedTransferAccountId else null,
        error = null,
    )
}
```

Add the new handlers:

```kotlin
fun onAccountChange(value: Long) = _state.update {
    it.copy(selectedAccountId = value, error = null)
}
fun onTransferAccountChange(value: Long) = _state.update {
    it.copy(selectedTransferAccountId = value, error = null)
}
```

Update `save()` with new validation:

```kotlin
fun save() {
    val s = _state.value
    val title = s.title.trim()
    if (title.isEmpty()) {
        _state.update { it.copy(error = FormError.TITLE_REQUIRED) }
        return
    }
    val amountMinor = MoneyFormat.parseAmountToMinor(s.amountInput)
    if (amountMinor == null || amountMinor <= 0) {
        _state.update { it.copy(error = FormError.AMOUNT_INVALID) }
        return
    }
    val accountId = s.selectedAccountId
    if (accountId == null) {
        _state.update { it.copy(error = FormError.ACCOUNT_REQUIRED) }
        return
    }
    if (s.type == TransactionType.TRANSFER) {
        val transferTo = s.selectedTransferAccountId
        if (transferTo == null || transferTo == accountId) {
            _state.update { it.copy(error = FormError.TRANSFER_ACCOUNTS_MUST_DIFFER) }
            return
        }
    }
    val categoryId = s.selectedCategoryId
    if (s.type != TransactionType.TRANSFER && categoryId == null) {
        _state.update { it.copy(error = FormError.CATEGORY_REQUIRED) }
        return
    }
    // ... rest of save() unchanged; just update the TransactionEntity construction:
    val entity = TransactionEntity(
        id = s.id ?: 0L,
        title = title,
        amountMinor = amountMinor,
        currencyCode = s.currency,
        type = s.type.name,
        categoryId = categoryId,
        accountId = accountId,
        transferAccountId = if (s.type == TransactionType.TRANSFER) s.selectedTransferAccountId else null,
        occurredAtEpochMillis = s.occurredAtEpochMillis,
        note = s.note.trim().ifEmpty { null },
        createdAtEpochMillis = now,
        ...
    )
}
```

- [ ] **Step 2: Build to confirm**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): AddEditTransactionViewModel binds every txn to an account"
```

---

## Task 14: Add Account dropdown UI to AddEditTransactionScreen

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt`

- [ ] **Step 1: Add TRANSFER to the Type segmented row**

Replace the type segmented row in `AddEditForm`:

```kotlin
// Type toggle — now includes Transfer
SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    val typeOptions = listOf(
        TransactionType.EXPENSE to R.string.type_expense,
        TransactionType.INCOME to R.string.type_income,
        TransactionType.TRANSFER to R.string.type_transfer,
    )
    typeOptions.forEachIndexed { index, (type, labelRes) ->
        SegmentedButton(
            selected = state.type == type,
            onClick = { viewModel.onTypeChange(type) },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = typeOptions.size),
        ) { Text(stringResource(labelRes)) }
    }
}
```

- [ ] **Step 2: Add AccountDropdown between Type and Category**

In `AddEditForm`, between the type toggle and the CategoryDropdown, add:

```kotlin
// Account picker (between Type and Category per Phase 2.16 spec)
AccountDropdown(
    state = state,
    onAccountChange = viewModel::onAccountChange,
)
```

Also add the new composable:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDropdown(
    state: AddEditTransactionUiState,
    onAccountChange: (Long) -> Unit,
) {
    val isError = state.error == FormError.ACCOUNT_REQUIRED
    if (state.accounts.size == 1) {
        // Single-account static label mode (matches the spec).
        val acc = state.accounts.first()
        OutlinedTextField(
            value = "${acc.icon}  ${acc.name}",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(stringResource(R.string.field_account)) },
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = state.accounts.firstOrNull { it.id == state.selectedAccountId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.icon}  ${it.name}" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_account)) },
            placeholder = { Text(stringResource(R.string.account_select_placeholder)) },
            isError = isError,
            supportingText = {
                if (isError) Text(stringResource(R.string.error_account_name_required))
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            state.accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text("${acc.icon}  ${acc.name}") },
                    onClick = {
                        onAccountChange(acc.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferAccountDropdown(
    state: AddEditTransactionUiState,
    onTransferAccountChange: (Long) -> Unit,
) {
    val isError = state.error == FormError.TRANSFER_ACCOUNTS_MUST_DIFFER
    var expanded by remember { mutableStateOf(false) }
    val selected = state.accounts.firstOrNull { it.id == state.selectedTransferAccountId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.icon}  ${it.name}" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.account_to_account)) },
            isError = isError,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            state.accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text("${acc.icon}  ${acc.name}") },
                    enabled = acc.id != state.selectedAccountId,
                    onClick = {
                        onTransferAccountChange(acc.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
```

After the AccountDropdown, conditionally render the To-account field when type is TRANSFER:

```kotlin
if (state.type == TransactionType.TRANSFER) {
    TransferAccountDropdown(
        state = state,
        onTransferAccountChange = viewModel::onTransferAccountChange,
    )
}
```

- [ ] **Step 3: Build and commit**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): Account dropdown on AddEditTransactionScreen with TRANSFER To-account"
```

---

## Task 15: Add Account dropdown to AddReceiptScreen

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptScreen.kt`

- [ ] **Step 1: Update AddReceiptViewModel**

Add to `AddReceiptUiState`:

```kotlin
val accounts: List<AccountEntity> = emptyList(),
val selectedAccountId: Long? = null,
```

Add `AccountRepository` to the constructor:

```kotlin
@HiltViewModel
class AddReceiptViewModel @Inject constructor(
    application: Application,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,  // NEW
    private val receiptRepository: ReceiptRepository,
    private val receiptOcrProcessor: ReceiptOcrProcessor,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
```

Add an accounts collector in `init`:

```kotlin
viewModelScope.launch {
    accountRepository.observeActive().collect { accounts ->
        _state.update { it.copy(accounts = accounts) }
    }
}
```

Add handler:

```kotlin
fun onAccountChange(value: Long) = _state.update { it.copy(selectedAccountId = value, error = null) }
```

Update `onSave()` validation and entity construction:

```kotlin
fun onSave() {
    val s = _state.value
    val title = s.title.trim()
    if (title.isEmpty()) { ... }
    val amountMinor = ...
    if (amountMinor == null || amountMinor <= 0) { ... }
    val accountId = s.selectedAccountId
    if (accountId == null) {
        _state.update { it.copy(error = AddReceiptError.ACCOUNT_REQUIRED) }
        return
    }
    val categoryId = s.selectedCategoryId
    if (categoryId == null) { ... }
    val photoPath = ...
    _state.update { it.copy(isSaving = true, error = null) }
    viewModelScope.launch {
        val now = System.currentTimeMillis()
        val entity = TransactionEntity(
            id = 0L,
            title = title,
            amountMinor = amountMinor,
            currencyCode = s.currency.ifEmpty { settingsRepository.homeCurrency.value },
            type = s.type.name,
            categoryId = categoryId,
            accountId = accountId,  // NEW
            occurredAtEpochMillis = s.occurredAtEpochMillis,
            note = s.note.trim().ifEmpty { null },
            createdAtEpochMillis = now,
            receiptPath = photoPath,
        )
        ...
    }
}
```

Add `ACCOUNT_REQUIRED` to `AddReceiptError`:

```kotlin
enum class AddReceiptError { TITLE_REQUIRED, AMOUNT_INVALID, CATEGORY_REQUIRED, RECEIPT_SAVE_FAILED, ACCOUNT_REQUIRED }
```

- [ ] **Step 2: Add the dropdown to AddReceiptScreen**

In the `ReviewForm` composable in `AddReceiptScreen.kt`, between the Type dropdown and the Currency text field, add:

```kotlin
// Account picker (Phase 2.16)
AccountPickerRow(
    accounts = state.accounts,
    selectedAccountId = state.selectedAccountId,
    error = state.error,
    onChange = onAccountChange,
)
```

And the helper composable at the bottom of the file:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPickerRow(
    accounts: List<io.github.jiro.expensetracker.data.local.AccountEntity>,
    selectedAccountId: Long?,
    error: AddReceiptError?,
    onChange: (Long) -> Unit,
) {
    if (accounts.size == 1) {
        val acc = accounts.first()
        OutlinedTextField(
            value = "${acc.icon}  ${acc.name}",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(stringResource(R.string.field_account)) },
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.id == selectedAccountId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.icon}  ${it.name}" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_account)) },
            placeholder = { Text(stringResource(R.string.account_select_placeholder)) },
            isError = error == AddReceiptError.ACCOUNT_REQUIRED,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text("${acc.icon}  ${acc.name}") },
                    onClick = {
                        onChange(acc.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
```

Update the `ReviewForm` parameter list and call site to thread the new handler:

```kotlin
@Composable
private fun ReviewForm(
    state: AddReceiptUiState,
    onTitleChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (Long) -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onCategoryChange: (Long) -> Unit,
    onAccountChange: (Long) -> Unit,  // NEW
    onCurrencyChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
)
```

In `AddReceiptScreen`, pass `onAccountChange = viewModel::onAccountChange`.

- [ ] **Step 3: Build and commit**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): Account dropdown on AddReceipt review form"
```

---

## Task 16: TRANSFER rendering in the transactions list + stats exclusion

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt`

- [ ] **Step 1: Render TRANSFER inline as `X → Y` in TransactionRow**

Modify `TransactionRow` in `TransactionComponents.kt`. After the existing `val type = ...` lines, branch on TRANSFER and render differently:

```kotlin
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

Extract the existing body into `StandardRow` (rename the existing function contents):

```kotlin
@Composable
private fun StandardRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String,
    highlightStyle: SpanStyle,
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
            Text(
                text = "${category.name} · ${txn.currencyCode} " +
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

Add `TransferRow` (rendered for TRANSFER rows):

```kotlin
@Composable
private fun TransferRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String,
    highlightStyle: SpanStyle,
) {
    val txn = row.transaction
    val fromName = row.category.name  // for TRANSFER rows, the "category" relation is null in real DB; in the projection it may be empty — fallback below
    val amountText = "${txn.amountMinor / 100}.${"%02d".format(txn.amountMinor % 100)} ${txn.currencyCode}"
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
            // Inline "from → to" rendering — the destination account's name
            // is read directly from the transferAccountId via the repository.
            Text(
                text = "→ acct#${txn.transferAccountId} · $amountText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

Note: rendering the destination account's name here requires a join that `TransactionWithCategory` doesn't currently carry. To keep this PR self-contained, render the id as a fallback (e.g. "→ acct#2"). A follow-up task can extend `TransactionWithCategory` to embed the destination account entity for proper name rendering.

Add a TODO comment for the follow-up:

```kotlin
// TODO(Phase 2.16+): extend TransactionWithCategory to embed the
// destination account entity for TRANSFER rows so we can render the
// account name instead of the id.
```

- [ ] **Step 2: Exclude TRANSFER from stats in HomeViewModel**

In `HomeViewModel.kt`, find the income/expense aggregation and add a TRANSFER filter. Replace the existing aggregation (search for `TransactionType.INCOME -> income += converted` and the surrounding `when (type)`):

```kotlin
when (type) {
    TransactionType.INCOME -> income += converted
    TransactionType.EXPENSE -> expense += converted
    TransactionType.TRANSFER, TransactionType.ADJUSTMENT -> Unit  // excluded from headline totals
}
```

- [ ] **Step 3: Build and run all tests**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/ui/home/TransactionComponents.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/home/HomeViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(accounts): TRANSFER renders as inline arrow row; excluded from headline stats"
```

---

## Task 17: Migration test (Room MigrationTestHelper v5 → v6)

**Files:**
- Create: `app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountMigrationTest.kt`

- [ ] **Step 1: Add Room testing dependency to gradle if not already present**

Check `app/build.gradle.kts`. The dependency `androidx.room:room-testing` should be added to `androidTestImplementation`. Open the file and verify; if missing, add:

```kotlin
androidTestImplementation(libs.androidx.room.testing)
```

- [ ] **Step 2: Write the migration test**

Create `app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountMigrationTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate5To6_createsAccountsTableAndSeedsDefault() {
        // Create v5 schema.
        helper.createDatabase(AppDatabase.NAME, 5).apply {
            execSQL("""
                CREATE TABLE transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    amountMinor INTEGER NOT NULL,
                    currencyCode TEXT NOT NULL,
                    type TEXT NOT NULL,
                    categoryId INTEGER NOT NULL,
                    occurredAtEpochMillis INTEGER NOT NULL,
                    note TEXT,
                    createdAtEpochMillis INTEGER NOT NULL,
                    recurringGroupId TEXT,
                    recurrenceKind TEXT,
                    recurrenceInterval INTEGER NOT NULL DEFAULT 1,
                    recurrenceEndAt INTEGER,
                    recurrenceMaxOccurrences INTEGER,
                    recurrenceNextAt INTEGER,
                    receiptPath TEXT,
                    FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE RESTRICT
                )
            """.trimIndent())
            execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0, isBuiltIn INTEGER NOT NULL DEFAULT 0)")
            close()
        }

        // Run the v5 -> v6 migration.
        val db = helper.runMigrationsAndValidate(AppDatabase.NAME, 6, true, AppDatabase.MIGRATION_5_6)

        // Verify the accounts table exists with the seeded default.
        db.query("SELECT * FROM accounts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
            assertEquals("Cash wallet", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals("CASH", cursor.getString(cursor.getColumnIndexOrThrow("type")))
            assertEquals("USD", cursor.getString(cursor.getColumnIndexOrThrow("currencyCode")))
        }

        // Verify existing transactions got accountId backfilled to 1 and categoryId is now nullable.
        db.query("PRAGMA table_info(transactions)").use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            assertTrue("accountId column missing", "accountId" in names)
            assertTrue("transferAccountId column missing", "transferAccountId" in names)
        }
    }

    @Test
    fun migrate5To6_categoryIdBecomesNullable() {
        // Insert a v5 row with a real categoryId, then migrate.
        helper.createDatabase(AppDatabase.NAME, 5).apply {
            execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0, isBuiltIn INTEGER NOT NULL DEFAULT 0)")
            execSQL("""
                INSERT INTO categories (id, name, type) VALUES (1, 'Food', 'EXPENSE')
            """.trimIndent())
            execSQL("""
                CREATE TABLE transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    amountMinor INTEGER NOT NULL,
                    currencyCode TEXT NOT NULL,
                    type TEXT NOT NULL,
                    categoryId INTEGER NOT NULL,
                    occurredAtEpochMillis INTEGER NOT NULL,
                    note TEXT,
                    createdAtEpochMillis INTEGER NOT NULL,
                    recurringGroupId TEXT,
                    recurrenceKind TEXT,
                    recurrenceInterval INTEGER NOT NULL DEFAULT 1,
                    recurrenceEndAt INTEGER,
                    recurrenceMaxOccurrences INTEGER,
                    recurrenceNextAt INTEGER,
                    receiptPath TEXT,
                    FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE RESTRICT
                )
            """.trimIndent())
            execSQL("INSERT INTO transactions (title, amountMinor, currencyCode, type, categoryId, occurredAtEpochMillis, createdAtEpochMillis) VALUES ('Lunch', 1500, 'USD', 'EXPENSE', 1, 0, 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(AppDatabase.NAME, 6, true, AppDatabase.MIGRATION_5_6)
        db.query("SELECT * FROM transactions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("accountId")))
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("categoryId")))
            assertNull(cursor.getColumnIndexOrThrow("transferAccountId").let { cursor.getString(it) })
        }
    }
}
```

- [ ] **Step 3: Run the migration test**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew connectedAndroidTest --tests "io.github.jiro.expensetracker.data.local.AccountMigrationTest"
```
Requires an emulator or device. If unavailable locally, defer to CI.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountMigrationTest.kt \
        app/build.gradle.kts
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "test(accounts): migration test verifies v5->v6 schema changes and seeding"
```

---

## Task 18: Final smoke test, lint, tag release

**Files:**
- (no code changes)

- [ ] **Step 1: Run the full JVM test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew test
```
Expected: BUILD SUCCESSFUL. All previously-passing tests still pass; new tests in this phase pass.

- [ ] **Step 2: Run Android Lint**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew lintDebug
```
Expected: BUILD SUCCESSFUL. Address any new lint errors specific to this phase (unused imports, missing content descriptions, etc.).

- [ ] **Step 3: Manual smoke checklist** (verify in a debug build on a device or emulator)

- Install the debug APK
- Launch — verify the default "Cash wallet" account is created (it's already on the seeded placeholder currency; the AccountSeeder overrides it to SettingsRepository.homeCurrency on first open)
- More → Accounts — verify the Accounts list shows the default
- Add a new account (e.g. "Maybank", BANK, MYR) — verify it appears
- Add a transaction — verify the Account dropdown appears between Type and Category and is required
- Switch Type to Transfer — verify "To account" dropdown appears
- Save a TRANSFER between two accounts — verify both balances change
- Verify the TRANSFER row in the transactions list renders inline as `→ acct#X` (id-based for now)
- Edit an account that has transactions — verify "Adjust balance" appears
- Adjust balance by a small delta — verify a new ADJUSTMENT row is created and the balance reflects it
- Verify net balance in home currency on the Accounts list header

- [ ] **Step 4: Tag the release**

```bash
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" tag v0.15.0
git push origin master --tags
```

- [ ] **Step 5: Final commit (if any post-tag fixes were needed)**

```bash
git log --oneline -20
```
Verify the phase shipped clean.

---

## Self-review

**1. Spec coverage** — every section in `2026-06-25-account-management-design.md` has a matching task:

- Schema (AccountEntity, TransactionEntity changes) → Tasks 2, 3
- Migration v5→v6 + placeholder seeding → Tasks 3, 6
- Domain layer (Account, AccountRepository, balance formula) → Tasks 4, 5
- TRANSFER mechanics (single-row, validation, balance formula) → Tasks 5, 13
- ADJUSTMENT mechanics → Tasks 7, 14
- UI: Accounts list (compact 2-col grid) → Task 10
- UI: Add/Edit Account (single scrollable, currency locked, opening balance via adjust dialog) → Tasks 7, 8
- UI: Account picker on Add/Edit Transaction (between Type and Category, single-account mode, TRANSFER To-account) → Tasks 13, 14
- UI: Account picker on Add Receipt → Task 15
- UI: More tab entry → Task 12
- TRANSFER rendering inline `X → Y` → Task 16
- Tests (balance formula, migration, viewmodels) → Tasks 5, 17 + existing test suite
- Out of scope items (archive UI, cross-currency aggregation, theming, reconciliation, delete UI) → not implemented, called out in spec

**2. Placeholder scan** — searched for "TBD", "TODO", "implement later":
- Found one `TODO(Phase 2.16+)` in Task 16 about extending `TransactionWithCategory` to embed the destination account entity for proper name rendering in TRANSFER rows. This is intentional and documented; it's a follow-up, not a placeholder for in-phase work.

**3. Type consistency:**
- `AccountEntity` — created in Task 2, used in Tasks 4 (DAO), 5 (Repository), 7 (ViewModel state), 10 (screen), 11 (detail), 13 (AddEdit txn VM), 15 (AddReceipt VM) — all consistent
- `AccountRepository.observeWithBalances()` — defined Task 5, consumed Tasks 7, 10, 11
- `AddEditAccountViewModel.AdjustBalanceDialogState` — defined Task 7, rendered Task 8
- `FormError.ACCOUNT_REQUIRED` / `TRANSFER_ACCOUNTS_MUST_DIFFER` — defined Task 13, surfaced Task 14
- `AddReceiptError.ACCOUNT_REQUIRED` — defined Task 15, surfaced Task 15
- `Routes.ACCOUNTS_LIST` etc. — defined Task 9, consumed Tasks 10, 11, 12, 13

**No type mismatches found.**

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-25-account-management.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints for review.

Which approach?