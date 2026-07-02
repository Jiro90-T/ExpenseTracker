# Close-Account Feature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "close account" feature that hides accounts from dropdowns while retaining them in the database, with full reopen support and timestamp surfacing.

**Architecture:** Wire up the existing-but-unused `AccountEntity.archived` column with two new DAO write methods (`close`, `reopen`) and a parallel read flow (`observeAllWithBalances`) that bypasses the `archived = 0` predicate. Closed accounts continue contributing to net balance totals. A new `TransactionWithRelations` projection joins account/transferAccount/category so transaction-list UI can render the closed account's name + "Added MMM d" subtitle. UI surfaces: filter chip on `AccountsListScreen`, overflow actions + confirmation dialogs + Undo snackbar on `AccountDetailScreen`, "Closed on" line on detail, "Added MMM d" line on transaction list rows + detail.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Room (KSP), Hilt, Coroutines + Flow, JUnit + AndroidJUnit4 + Robolectric-style instrumented tests for DAO. Existing convention: `app/src/test/java/` for unit tests, `app/src/androidTest/java/` for DAO/migration tests.

**Spec:** `docs/superpowers/specs/2026-07-02-close-account-design.md`

---

## Conventions for every commit in this plan

**Author:** `MiniMax-M3 <291324429+Jiro90-T@users.noreply.github.com>` — set explicitly via `git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit ...`

**No Co-Authored-By trailer.** End the commit message after the subject line. (Per the user's recorded feedback.)

**Direct-to-master + tag at the very end.** No feature branches. No `--no-verify`.

**Add files explicitly:** `git add path/to/file.kt` — never `git add -A` or `git add .`.

**Build commands:** Bash on Windows is git-bash; use forward slashes. JDK 21 must be set:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
```
For unit tests: `./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.<pattern>"`. For instrumented: `./gradlew connectedDebugAndroidTest --tests "io.github.jiro.expensetracker.<pattern>"` (requires device/emulator).

**Verify R.string names:** Before committing a UI change, run:
```bash
grep -rn "R.string\." app/src/main/java/io/github/jiro/expensetracker/ | sort -u > /tmp/used.txt
grep -oP 'name="[^"]+"' app/src/main/res/values/strings.xml | sed 's/name="\([^"]*\)"/\1/' | sort -u > /tmp/defined.txt
diff /tmp/used.txt /tmp/defined.txt | head -50
```
Every R.string.* name referenced in code must appear in strings.xml. (User has flagged this twice.)

---

## Out-of-scope (acknowledged limitations)

1. **Backup format does not currently include accounts.** `BackupManager.exportToJson` only writes categories + transactions. Restoring a backup wipes the current account set (relying on `AccountSeeder` for the default). Therefore `archivedAtEpochMillis` is not part of the backup round-trip in this change. Adding account backup is a separate, larger feature (and the existing TODO at `BackupManager.kt:67` notes the wider work needed).
2. **Hard-delete on a closed account** is still subject to the existing `DeleteGuard`. If a user closes an account and then wants to hard-delete it, they may have to clear referencing transactions first (same as today).
3. **Recurring-series → account bindings** do not exist in app code today, so closing an account does not block based on recurring series. Verified via grep.

---

### Task 1: Schema migration 7→8 + AccountEntity field

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountEntity.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt`
- Test: `app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountMigrationTest.kt` (new)

- [ ] **Step 1: Write the failing migration test**

Create `app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountMigrationTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountMigrationTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_7_to_8_adds_archivedAtEpochMillis_column() {
        helper.createDatabase(AppDatabase.NAME, 7).apply { close() }

        val db = helper.runMigrationsAndValidate(AppDatabase.NAME, 8, true, AppDatabase.MIGRATION_7_8)

        db.query("PRAGMA table_info(accounts)").use { c ->
            val cols = mutableMapOf<String, String>()
            while (c.moveToNext()) {
                cols[c.getString(c.getColumnIndexOrThrow("name"))] =
                    c.getString(c.getColumnIndexOrThrow("type"))
            }
            assertTrue(
                "archivedAtEpochMillis column should exist",
                cols.containsKey("archivedAtEpochMillis"),
            )
            // SQLite stores nullable as "INTEGER" without NOT NULL modifier.
            assertEquals("INTEGER", cols["archivedAtEpochMillis"])
        }
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew connectedDebugAndroidTest --tests "io.github.jiro.expensetracker.data.local.AccountMigrationTest"
```

Expected: FAIL with `NoSuchMethodError: MIGRATION_7_8` (the migration doesn't exist yet).

- [ ] **Step 3: Add the migration to AppDatabase**

Modify `app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt`:

1. Bump version: change `@Database(... version = 7, ...)` to `version = 8`.
2. Append this block at the end of the `companion object` (after `MIGRATION_6_7`):

```kotlin
        /**
         * v7 → v8: close-account. Adds the nullable `archivedAtEpochMillis`
         * column to `accounts`. Existing rows default to NULL (active).
         * No data migration needed — closing is a user action that only
         * happens post-upgrade.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN archivedAtEpochMillis INTEGER")
            }
        }
```

3. Register the new migration. Locate the `Room.databaseBuilder` call (search for `addMigrations(MIGRATION_5_6, MIGRATION_6_7)` — exact call site depends on the project's DI module). Add `MIGRATION_7_8` to that argument list. If the project uses Hilt's `DatabaseModule`, find the equivalent `addMigrations(...)` call in `app/src/main/java/io/github/jiro/expensetracker/di/`.

- [ ] **Step 4: Add the field to AccountEntity**

Modify `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountEntity.kt` — add the new field to the data class:

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
    val archivedAtEpochMillis: Long? = null,
    val sortOrder: Int = 0,
)
```

- [ ] **Step 5: Run the test, verify it passes**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew connectedDebugAndroidTest --tests "io.github.jiro.expensetracker.data.local.AccountMigrationTest"
```

Expected: PASS.

- [ ] **Step 6: Run the full unit-test suite to make sure no other test broke**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest
```

Expected: all unit tests pass. AccountEntity has a default for the new field, so callers that don't pass it should still compile.

- [ ] **Step 7: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountMigrationTest.kt
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AccountEntity.kt
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AppDatabase.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): schema v8 add archivedAtEpochMillis column"
```

---

### Task 2: AccountDao — close/reopen writes + parallel read methods

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt`
- Test: `app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountDaoCloseTest.kt` (new)

- [ ] **Step 1: Write the failing DAO tests**

Create `app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountDaoCloseTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDaoCloseTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.accountDao()
    }

    @After fun tearDown() { db.close() }

    private fun account(
        id: Long = 0,
        name: String = "Test",
        archived: Boolean = false,
        archivedAt: Long? = null,
    ) = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = "USD",
        openingBalanceMinor = 0L,
        createdAtEpochMillis = 0L,
        archived = archived,
        archivedAtEpochMillis = archivedAt,
    )

    private suspend fun insert(name: String): Long = dao.insert(account(name = name))

    // ---- close / reopen writes ----

    @Test fun close_setsArchivedAndTimestamp() = runTest {
        val id = insert("Checking")
        dao.close(id, now = 1_700_000_000_000L)
        val row = dao.findById(id)!!
        assertTrue(row.archived)
        assertEquals(1_700_000_000_000L, row.archivedAtEpochMillis)
    }

    @Test fun reopen_clearsArchivedAndTimestamp() = runTest {
        val id = insert("Savings")
        dao.close(id, now = 1_700_000_000_000L)
        dao.reopen(id)
        val row = dao.findById(id)!!
        assertFalse(row.archived)
        assertNull(row.archivedAtEpochMillis)
    }

    @Test fun close_isIdempotent_secondCallOverwritesTimestamp() = runTest {
        val id = insert("Idem")
        dao.close(id, now = 1_000L)
        dao.close(id, now = 2_000L)
        val row = dao.findById(id)!!
        assertTrue(row.archived)
        assertEquals(2_000L, row.archivedAtEpochMillis)
    }

    // ---- observeAllWithBalances ----

    @Test fun observeAllWithBalances_includesArchivedRows() = runTest {
        val a = insert("Active")
        val c = insert("Closed")
        dao.close(c, now = 1_000L)

        val all = dao.observeAllWithBalances().first()
        val byName = all.associateBy { it.account.name }
        assertTrue("active row should be present", "Active" in byName)
        assertTrue("closed row should be present", "Closed" in byName)
        assertTrue(byName["Closed"]!!.account.archived)
    }

    // ---- listAllOnce ----

    @Test fun listAllOnce_returnsEveryRow() = runTest {
        insert("A")
        insert("B")
        val c = insert("C")
        dao.close(c, now = 1_000L)
        val all = dao.listAllOnce()
        assertEquals(3, all.size)
    }

    // ---- findActiveDefault ----

    @Test fun findActiveDefault_returnsLowestIdActive() = runTest {
        val first = insert("First")
        insert("Second")
        val third = insert("Third")
        // First is the lowest-id active row.
        val def = dao.findActiveDefault()
        assertNotNull(def)
        assertEquals(first, def!!.id)
    }

    @Test fun findActiveDefault_skipsArchivedRows() = runTest {
        val first = insert("First")
        insert("Second")
        dao.close(first, now = 1_000L)
        val def = dao.findActiveDefault()
        assertNotNull(def)
        assertEquals("Second", def!!.name)
    }

    @Test fun findActiveDefault_returnsNullWhenAllArchived() = runTest {
        val a = insert("A")
        dao.close(a, now = 1_000L)
        assertNull(dao.findActiveDefault())
    }
}
```

- [ ] **Step 2: Run the test, verify it fails to compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew connectedDebugAndroidTest --tests "io.github.jiro.expensetracker.data.local.AccountDaoCloseTest"
```

Expected: compile error — `close`, `reopen`, `observeAllWithBalances`, `listAllOnce`, `findActiveDefault` are unresolved.

- [ ] **Step 3: Add the new DAO methods**

Modify `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt`. Add these methods inside the `interface AccountDao` block (place them after `observeBalances` so reads cluster together):

```kotlin
    @Query(
        """
        SELECT a.id AS accountId,
               a.openingBalanceMinor
               + COALESCE((SELECT SUM(CASE WHEN type = 'EXPENSE' THEN -amountMinor ELSE amountMinor END)
                           FROM transactions
                           WHERE accountId = a.id AND type IN ('INCOME','EXPENSE','ADJUSTMENT')), 0)
               - COALESCE((SELECT SUM(amountMinor) FROM transactions
                           WHERE accountId = a.id AND type = 'TRANSFER'), 0)
               + COALESCE((SELECT SUM(amountMinor) FROM transactions
                           WHERE transferAccountId = a.id AND type = 'TRANSFER'), 0)
               AS balanceMinor
        FROM accounts a
        """
    )
    fun observeAllBalances(): Flow<List<AccountBalanceRow>>

    @Query("SELECT * FROM accounts")
    fun observeAllEntities(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun listAllOnce(): List<AccountEntity>

    @Query("UPDATE accounts SET archived = 1, archivedAtEpochMillis = :now WHERE id = :id")
    suspend fun close(id: Long, now: Long)

    @Query("UPDATE accounts SET archived = 0, archivedAtEpochMillis = NULL WHERE id = :id")
    suspend fun reopen(id: Long)

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY id ASC LIMIT 1")
    suspend fun findActiveDefault(): AccountEntity?
```

Then **delete** the existing `findDefault()` method:

```kotlin
    @Query("SELECT * FROM accounts WHERE id = 1 LIMIT 1")
    suspend fun findDefault(): AccountEntity?
```

Remove that block. (Also delete its companion comment if any.)

- [ ] **Step 4: Run the test, verify it passes**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew connectedDebugAndroidTest --tests "io.github.jiro.expensetracker.data.local.AccountDaoCloseTest"
```

Expected: PASS. All 8 tests pass.

- [ ] **Step 5: Run the full test suite to catch any compile break**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest
```

Expected: compile errors in callers of `findDefault()` and any user of the removed methods. Resolve by fixing the callers (Task 3 covers the AccountRepository / AccountSeeder fix).

- [ ] **Step 6: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/androidTest/java/io/github/jiro/expensetracker/data/local/AccountDaoCloseTest.kt
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AccountDao.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): DAO close/reopen + observeAllBalances + findActiveDefault"
```

---

### Task 3: AccountRepository — wire new methods, drop findDefault callers

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountSeeder.kt`
- Test: extend `app/src/test/java/io/github/jiro/expensetracker/data/repository/AccountBalanceFormulaTest.kt` (or add a new test file)

- [ ] **Step 1: Write the failing repository test**

Create `app/src/test/java/io/github/jiro/expensetracker/data/repository/AccountCloseRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCloseRepositoryTest {

    private class CapturingDao(
        var lastCloseId: Long? = null,
        var lastCloseNow: Long? = null,
        var lastReopenId: Long? = null,
    ) : AccountDao by NoopAccountDao {
        override suspend fun close(id: Long, now: Long) {
            lastCloseId = id
            lastCloseNow = now
        }
        override suspend fun reopen(id: Long) {
            lastReopenId = id
        }
    }

    // Robolectric-free fake; we only stub the methods we exercise.
    private val noopDao = object : AccountDao {
        override fun observeActive() = flowOf(emptyList<AccountEntity>())
        override suspend fun listActiveOnce() = emptyList<AccountEntity>()
        override suspend fun findById(id: Long) = null
        override suspend fun countActive() = 0
        override fun observeBalances() = flowOf(emptyList<AccountBalanceRow>())
        override fun observeAllBalances() = flowOf(emptyList<AccountBalanceRow>())
        override fun observeAllEntities() = flowOf(emptyList<AccountEntity>())
        override suspend fun listAllOnce() = emptyList<AccountEntity>()
        override suspend fun insert(account: AccountEntity) = 0L
        override suspend fun update(account: AccountEntity) = 0
        override suspend fun delete(id: Long) = 0
        override suspend fun updateDefaultCurrency(code: String) = 0
        override suspend fun maxSortOrder() = 0
        override suspend fun updateOpeningBalanceByName(name: String, balance: Long) = 0
        override suspend fun close(id: Long, now: Long) {}
        override suspend fun reopen(id: Long) {}
        override suspend fun findActiveDefault(): AccountEntity? = null
        override suspend fun applyAccountImport(rows: List<io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow>, nowEpochMs: Long) {}
    }

    @Test fun close_passesSystemCurrentTimeMillisToDao() = runBlocking {
        val dao = CapturingDao()
        val repo = AccountRepository(dao)
        val before = System.currentTimeMillis()
        repo.close(id = 7L)
        val after = System.currentTimeMillis()
        assertEquals(7L, dao.lastCloseId)
        val now = dao.lastCloseNow!!
        assertTrue("now ($now) should be between before ($before) and after ($after)",
            now in before..after)
    }

    @Test fun reopen_passesIdToDao() = runBlocking {
        val dao = CapturingDao()
        val repo = AccountRepository(dao)
        repo.reopen(id = 9L)
        assertEquals(9L, dao.lastReopenId)
    }
}

// Sentinel "pass-through" fake; replaced by the explicit override above where needed.
// Using kotlin delegation: AccountDao by NoopAccountDao requires `NoopAccountDao` to
// implement every method. Provide it via the explicit object above; this line is
// only here for compile-time clarity and is NOT compiled (kept as a comment).
// private object NoopAccountDao : AccountDao { ... }
```

Note: the comment at the bottom flags that `NoopAccountDao` is not actually declared. Delete that comment block before committing; replace `AccountDao by NoopAccountDao` in `CapturingDao` with the explicit `noopDao` body as follows:

```kotlin
    private class CapturingDao(
        var lastCloseId: Long? = null,
        var lastCloseNow: Long? = null,
        var lastReopenId: Long? = null,
    ) : AccountDao {
        override suspend fun close(id: Long, now: Long) {
            lastCloseId = id; lastCloseNow = now
        }
        override suspend fun reopen(id: Long) { lastReopenId = id }
        // ---- every other method delegates to noopDao ----
        override fun observeActive() = flowOf(emptyList<AccountEntity>())
        override suspend fun listActiveOnce() = emptyList<AccountEntity>()
        override suspend fun findById(id: Long) = null
        override suspend fun countActive() = 0
        override fun observeBalances() = flowOf(emptyList<AccountBalanceRow>())
        override fun observeAllBalances() = flowOf(emptyList<AccountBalanceRow>())
        override fun observeAllEntities() = flowOf(emptyList<AccountEntity>())
        override suspend fun listAllOnce() = emptyList<AccountEntity>()
        override suspend fun insert(account: AccountEntity) = 0L
        override suspend fun update(account: AccountEntity) = 0
        override suspend fun delete(id: Long) = 0
        override suspend fun updateDefaultCurrency(code: String) = 0
        override suspend fun maxSortOrder() = 0
        override suspend fun updateOpeningBalanceByName(name: String, balance: Long) = 0
        override suspend fun findActiveDefault(): AccountEntity? = null
        override suspend fun applyAccountImport(rows: List<io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow>, nowEpochMs: Long) {}
    }
```

Replace the test file body with this corrected version (single `CapturingDao` class; no `noopDao`).

- [ ] **Step 2: Run the test, verify it fails to compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.data.repository.AccountCloseRepositoryTest"
```

Expected: compile error — `AccountRepository.close` and `AccountRepository.reopen` unresolved.

- [ ] **Step 3: Add the new methods to AccountRepository**

Modify `app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt`:

1. Replace the existing `findDefault()` method with `findActiveDefault()`:
```kotlin
    /** Lowest-id active account, or null if every account is archived. */
    suspend fun findActiveDefault(): AccountEntity? = dao.findActiveDefault()
```

2. Add new methods (place near `countActive()` so lifecycle reads cluster):
```kotlin
    suspend fun close(id: Long) {
        dao.close(id, System.currentTimeMillis())
    }

    suspend fun reopen(id: Long) {
        dao.reopen(id)
    }

    fun observeAllEntities(): Flow<List<AccountEntity>> = dao.observeAllEntities()

    fun observeAllBalances(): Flow<List<AccountBalanceRow>> = dao.observeAllBalances()

    suspend fun listAllOnce(): List<AccountEntity> = dao.listAllOnce()
```

3. Replace the existing `observeWithBalances()` body to keep its existing semantics (active-only), and **add** the all-accounts variant. The existing body stays; append a new method:
```kotlin
    /** Active accounts only, joined with their computed balances. */
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

    /** All accounts (active + archived), joined with their computed balances.
     *  Used for the net-balance label and any totals that should not exclude
     *  closed accounts. */
    fun observeAllWithBalances(): Flow<List<AccountWithBalance>> =
        combine(dao.observeAllEntities(), dao.observeAllBalances()) { accounts, balances ->
            val map = balances.associate { it.accountId to it.balanceMinor }
            accounts.map { acc ->
                AccountWithBalance(
                    account = acc,
                    balanceMinor = map[acc.id] ?: acc.openingBalanceMinor,
                )
            }
        }
```

- [ ] **Step 4: Update AccountSeeder to use the renamed method**

Modify `app/src/main/java/io/github/jiro/expensetracker/data/local/AccountSeeder.kt`:

Change line 24 from:
```kotlin
        val default = accountRepository.findDefault() ?: return
```
to:
```kotlin
        val default = accountRepository.findActiveDefault() ?: return
```

- [ ] **Step 4b: Update test fakes that override the removed DAO method**

Two test files override `findDefault()` on their fake `AccountDao`; without this fix they fail to compile after Task 2's DAO change:

- `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt:225`
  — replace `override suspend fun findDefault(): AccountEntity? = accounts.find { it.id == 1L }` with
  `override suspend fun findActiveDefault(): AccountEntity? = accounts.filter { !it.archived }.minByOrNull { it.id }`.
- `app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt:261`
  — replace `override suspend fun findDefault(): AccountEntity? = null` with
  `override suspend fun findActiveDefault(): AccountEntity? = null`.

- [ ] **Step 5: Run the test, verify it passes**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.data.repository.AccountCloseRepositoryTest"
```

Expected: PASS.

- [ ] **Step 6: Run the full unit-test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest
```

Expected: all unit tests pass. If `AccountSeederTest` (if it exists) was using the old name, fix it to use `findActiveDefault`.

- [ ] **Step 7: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/test/java/io/github/jiro/expensetracker/data/repository/AccountCloseRepositoryTest.kt
git add app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt
git add app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt
git add app/src/main/java/io/github/jiro/expensetracker/data/repository/AccountRepository.kt
git add app/src/main/java/io/github/jiro/expensetracker/data/local/AccountSeeder.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): repository close/reopen + observeAllWithBalances"
```

---

### Task 4: TransactionWithRelations projection

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionWithRelations.kt`
- Test: extend `app/src/test/java/io/github/jiro/expensetracker/data/local/MoneyFormatTest.kt` style — small unit test that constructs the data class directly

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/data/local/TransactionWithRelationsTest.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionWithRelationsTest {

    private fun txn(
        id: Long = 1L,
        accountId: Long = 10L,
        transferAccountId: Long? = null,
        categoryId: Long? = null,
    ) = TransactionEntity(
        id = id,
        title = "Coffee",
        amountMinor = 350,
        currencyCode = "USD",
        type = "EXPENSE",
        categoryId = categoryId,
        accountId = accountId,
        transferAccountId = transferAccountId,
        occurredAtEpochMillis = 1_700_000_000_000L,
        createdAtEpochMillis = 1_700_000_000_000L,
    )

    private fun account(id: Long, name: String, archived: Boolean = false) = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = "USD",
        openingBalanceMinor = 0L,
        createdAtEpochMillis = 0L,
        archived = archived,
    )

    @Test fun holdsAccountAndCategoryReferences() {
        val row = TransactionWithRelations(
            transaction = txn(categoryId = 5L),
            account = account(10L, "Checking"),
            transferAccount = null,
            category = CategoryEntity(id = 5L, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = false),
        )
        assertEquals("Checking", row.account?.name)
        assertEquals("Food", row.category?.name)
        assertNull(row.transferAccount)
    }

    @Test fun closedAccountRoundTripsViaProjection() {
        val closed = account(11L, "Old Checking", archived = true)
        val row = TransactionWithRelations(
            transaction = txn(accountId = 11L),
            account = closed,
            transferAccount = null,
            category = null,
        )
        assertEquals("Old Checking", row.account?.name)
        assertEquals(true, row.account?.archived)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails to compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.TransactionWithRelationsTest"
```

Expected: compile error — `TransactionWithRelations` unresolved.

- [ ] **Step 3: Create the projection**

Create `app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionWithRelations.kt`:

```kotlin
package io.github.jiro.expensetracker.data.local

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Joined view of a transaction with its account relations. Sibling to
 * [TransactionWithCategory]; use this when the UI needs the account name
 * (and especially when the account might be archived). Returned by
 * `@Transaction` DAO methods.
 *
 * Unlike [TransactionWithCategory], this projection joins both the primary
 * account AND the transfer account — used by the close-account feature to
 * keep historic transactions showing their (possibly-archived) account
 * labels.
 */
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

- [ ] **Step 4: Run the test, verify it passes**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.data.local.TransactionWithRelationsTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/test/java/io/github/jiro/expensetracker/data/local/TransactionWithRelationsTest.kt
git add app/src/main/java/io/github/jiro/expensetracker/data/local/TransactionWithRelations.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): TransactionWithRelations projection (account + transfer + category)"
```

---

### Task 5: Strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the close-account string block**

In `app/src/main/res/values/strings.xml`, find the comment marker `<!-- Accounts (Phase 2.18 — CSV Balance Import) -->`. Immediately **before** it (so the new strings live in a logical Phase-2.19 block), insert:

```xml
    <!-- Accounts (Phase 2.19 — Close account) -->
    <string name="account_close">Close account</string>
    <string name="account_reopen">Reopen account</string>
    <string name="account_close_confirm_title">Close account?</string>
    <string name="account_close_confirm_message">Closed accounts stay in your records but won\'t appear in dropdowns. You can reopen it later.</string>
    <string name="account_reopen_confirm_title">Reopen account?</string>
    <string name="account_reopen_confirm_message">This account will reappear in dropdowns.</string>
    <string name="account_close_snackbar">Account closed</string>
    <string name="account_reopen_snackbar">Account reopened</string>
    <string name="account_undo">Undo</string>
    <string name="account_status_closed">Closed</string>
    <string name="account_filter_show_closed">Show closed accounts</string>
    <string name="account_closed_on">Closed on %1$s</string>
    <string name="transaction_added_on">Added %1$s</string>

```

- [ ] **Step 2: Verify referenced vs defined**

```bash
cd /f/AndroidApp/ExpenseTracker
grep -rn "R.string\." app/src/main/java/io/github/jiro/expensetracker/ | grep -oE "R\.string\.[a-z_]+" | sort -u > /tmp/used.txt
grep -oE 'name="[a-z_]+"' app/src/main/res/values/strings.xml | sed 's/name="//;s/"//' | sort -u > /tmp/defined.txt
diff /tmp/used.txt /tmp/defined.txt | head -50
```

Expected: every R.string.* name referenced in code appears in strings.xml. If diff is empty, proceed. If not, fix the missing names.

- [ ] **Step 3: Build to confirm resources parse cleanly**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/main/res/values/strings.xml
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): strings for close/reopen + filter + timestamp surfaces"
```

---

### Task 6: AccountsListViewModel — showClosed toggle + net balance fix

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModel.kt`
- Test: extend `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModelTest.kt`

- [ ] **Step 1: Write the failing VM test**

Append to `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModelTest.kt`:

```kotlin
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.preferences.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class AccountsListViewModelToggleTest {

    @Test
    fun `showClosed toggles accounts source but not net balance`() {
        // Pure-function verification: build a list of AccountWithBalance
        // (3 active + 2 closed) and verify that the net-balance math is
        // unaffected by filtering, while the listed account count differs.
        val accounts = listOf(
            AccountWithBalance(
                AccountEntity(id = 1, name = "A1", type = "CASH", icon = "💵",
                    color = 0xFFFFFFFF.toInt(), currencyCode = "USD",
                    openingBalanceMinor = 0L, createdAtEpochMillis = 0L,
                    archived = false, archivedAtEpochMillis = null), 1000L),
            AccountWithBalance(
                AccountEntity(id = 2, name = "A2", type = "CASH", icon = "💵",
                    color = 0xFFFFFFFF.toInt(), currencyCode = "USD",
                    openingBalanceMinor = 0L, createdAtEpochMillis = 0L,
                    archived = false, archivedAtEpochMillis = null), 2000L),
            AccountWithBalance(
                AccountEntity(id = 3, name = "A3", type = "CASH", icon = "💵",
                    color = 0xFFFFFFFF.toInt(), currencyCode = "USD",
                    openingBalanceMinor = 0L, createdAtEpochMillis = 0L,
                    archived = false, archivedAtEpochMillis = null), 3000L),
            AccountWithBalance(
                AccountEntity(id = 4, name = "C1", type = "CASH", icon = "💵",
                    color = 0xFFFFFFFF.toInt(), currencyCode = "USD",
                    openingBalanceMinor = 0L, createdAtEpochMillis = 0L,
                    archived = true, archivedAtEpochMillis = 1000L), 4000L),
            AccountWithBalance(
                AccountEntity(id = 5, name = "C2", type = "CASH", icon = "💵",
                    color = 0xFFFFFFFF.toInt(), currencyCode = "USD",
                    openingBalanceMinor = 0L, createdAtEpochMillis = 0L,
                    archived = true, archivedAtEpochMillis = 1000L), 5000L),
        )
        val activeOnly = accounts.filter { !it.account.archived }
        val netAll = computeNetBalanceInHome(accounts, "USD", emptyMap())
        val netActive = computeNetBalanceInHome(activeOnly, "USD", emptyMap())
        // Net balance uses ALL accounts, even closed.
        assertEquals(150.0, netAll, 0.0001)
        // Active-only count differs from total count.
        assertEquals(3, activeOnly.size)
        assertEquals(5, accounts.size)
        // The two values differ — that is the bug we're fixing.
        assert(netAll != netActive)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.AccountsListViewModelToggleTest"
```

Expected: PASS — this is a pure-function assertion. The test passes today, but it documents the intended behavior. The implementation step below is what makes the VM actually wire this up.

Actually — the test as written passes already (it tests `computeNetBalanceInHome` directly). To make the test fail-then-pass, restructure to test the VM-level state. Replace with:

```kotlin
class AccountsListViewModelToggleTest {

    private class FakeAccountRepository(
        val active: List<AccountWithBalance>,
        val all: List<AccountWithBalance>,
    ) {
        fun asRepo(): AccountRepository = throw NotImplementedError("only specific methods stubbed")
    }

    @Test
    fun `view model exposes showClosed state that defaults to false`() {
        // Sanity: the data class must gain a showClosed field; this test
        // fails to compile until the field exists.
        val s = AccountsListUiState(showClosed = false)
        assertEquals(false, s.showClosed)
        val s2 = s.copy(showClosed = true)
        assertEquals(true, s2.showClosed)
    }
}
```

Run this — it fails to compile until `AccountsListUiState` gains `showClosed`.

- [ ] **Step 3: Add showClosed to UI state and rewire the VM**

Modify `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModel.kt`:

1. Add `showClosed` to `AccountsListUiState`:
```kotlin
data class AccountsListUiState(
    val accounts: List<AccountWithBalance> = emptyList(),
    val netBalanceInHome: String = "",
    val count: Int = 0,
    val isLoading: Boolean = true,
    val showClosed: Boolean = false,
)
```

2. Replace the VM body to drive both flows:
```kotlin
@HiltViewModel
class AccountsListViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _showClosed = MutableStateFlow(false)

    val state: StateFlow<AccountsListUiState> = combine(
        _showClosed,
        accountRepository.observeAllWithBalances(),
        accountRepository.observeWithBalances(),
        settingsRepository.fxRates,
        settingsRepository.homeCurrency,
    ) { showClosed, allAccounts, activeAccounts, fx, home ->
        val listed = if (showClosed) allAccounts else activeAccounts
        val net = computeNetBalanceInHome(allAccounts, home, fx)
        AccountsListUiState(
            accounts = listed,
            netBalanceInHome = "%.2f %s".format(net, home),
            count = listed.size,
            isLoading = false,
            showClosed = showClosed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AccountsListUiState(),
    )

    fun setShowClosed(value: Boolean) {
        _showClosed.value = value
    }
}
```

`combine` accepts up to 5 flows directly. This signature stays valid.

- [ ] **Step 4: Run the VM test, verify it passes**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.AccountsListViewModelToggleTest"
```

Expected: PASS.

- [ ] **Step 5: Run the full unit-test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest
```

Expected: all unit tests pass. The `AccountDetailViewModelTest` and `AccountsListViewModelTest` (the existing tests) should still pass since `AccountsListUiState` adds a field with a default.

- [ ] **Step 6: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModelToggleTest.kt
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListViewModel.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): AccountsListViewModel showClosed toggle + net balance from all"
```

---

### Task 7: AccountDetailViewModel — close/reopen events + archive resolution

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt`
- Test: extend `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt`

- [ ] **Step 1: Write the failing VM tests**

Append to `app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt`:

```kotlin
import io.github.jiro.expensetracker.data.repository.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

// Add these inside the existing test class.

@Test
fun resolves_closedAccount_fromListAllOnce() = runTest(testDispatcher) {
    val closed = accountEntity(id = 3L, name = "Old").copy(
        archived = true, archivedAtEpochMillis = 1_700_000_000_000L,
    )
    val (vm, _) = buildVm(accountId = 3L, accounts = listOf(closed))
    advanceUntilIdle()
    val s = vm.state.value
    assertEquals(closed, s.accountWithBalance?.account)
}

@Test
fun onCloseConfirm_emitsClosedEvent() = runTest(testDispatcher) {
    val (vm, _) = buildVm(accountId = 2L)
    advanceUntilIdle()
    vm.onCloseConfirm()
    advanceUntilIdle()
    val evt = vm.closeEvent.first()
    assertEquals(2L, evt)
}

@Test
fun onReopenConfirm_emitsReopenedEvent() = runTest(testDispatcher) {
    val (vm, _) = buildVm(accountId = 2L)
    advanceUntilIdle()
    vm.onReopenConfirm()
    advanceUntilIdle()
    val evt = vm.reopenEvent.first()
    assertEquals(2L, evt)
}
```

- [ ] **Step 2: Run the test, verify it fails to compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.AccountDetailViewModelTest"
```

Expected: compile errors on `vm.closeEvent`, `vm.reopenEvent`, `vm.onCloseConfirm`, `vm.onReopenConfirm`.

- [ ] **Step 3: Update AccountDetailViewModel**

Replace the contents of `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt` with:

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
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
    val deleted: Boolean = false,    // signal to UI: pop back stack
    val errorMessage: String? = null,
    // Phase 2.19 close/reopen flow:
    val showCloseConfirm: Boolean = false,
    val showReopenConfirm: Boolean = false,
)

enum class DeleteGuard { ALLOW, BLOCK_TRANSACTIONS_EXIST }

fun evaluateDelete(referenceCount: Int): DeleteGuard =
    if (referenceCount == 0) DeleteGuard.ALLOW else DeleteGuard.BLOCK_TRANSACTIONS_EXIST

private const val DEFAULT_ACCOUNT_ID = 1L

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>(Routes.ACCOUNT_DETAIL_ARG_ID) ?: -1L

    private val _state = MutableStateFlow(AccountDetailUiState())
    val state: StateFlow<AccountDetailUiState> = _state.asStateFlow()

    // One-shot events consumed by the screen to show snackbars.
    private val _closeEvent = Channel<Long>(Channel.BUFFERED)
    val closeEvent: Flow<Long> = _closeEvent.receiveAsFlow()

    private val _reopenEvent = Channel<Long>(Channel.BUFFERED)
    val reopenEvent: Flow<Long> = _reopenEvent.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                accountRepository.observeAllWithBalances(),
                transactionRepository.observeByAccount(accountId),
            ) { accounts, txns ->
                AccountDetailUiState(
                    accountWithBalance = accounts.firstOrNull { it.account.id == accountId },
                    transactions = txns,
                    isLoading = false,
                )
            }.collect { upstream ->
                _state.update { current ->
                    upstream.copy(
                        showDeleteConfirm = current.showDeleteConfirm,
                        deleteGuard = current.deleteGuard,
                        referenceCount = current.referenceCount,
                        deleted = current.deleted,
                        errorMessage = current.errorMessage,
                        showCloseConfirm = current.showCloseConfirm,
                        showReopenConfirm = current.showReopenConfirm,
                    )
                }
            }
        }
    }

    // ---- Delete flow (unchanged) ----

    fun onDeleteClick() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        if (accountId == DEFAULT_ACCOUNT_ID) return
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

    // ---- Close / Reopen flow (Phase 2.19) ----

    fun onCloseClick() {
        _state.update { it.copy(showCloseConfirm = true) }
    }

    fun onCloseConfirm() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        viewModelScope.launch {
            accountRepository.close(accountId)
            _state.update { it.copy(showCloseConfirm = false) }
            _closeEvent.send(accountId)
        }
    }

    fun onCloseDismiss() {
        _state.update { it.copy(showCloseConfirm = false) }
    }

    fun onReopenClick() {
        _state.update { it.copy(showReopenConfirm = true) }
    }

    fun onReopenConfirm() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        viewModelScope.launch {
            accountRepository.reopen(accountId)
            _state.update { it.copy(showReopenConfirm = false) }
            _reopenEvent.send(accountId)
        }
    }

    fun onReopenDismiss() {
        _state.update { it.copy(showReopenConfirm = false) }
    }

    /**
     * Undo path for the post-close snackbar. Called by the screen when the
     * user taps the Undo action — reopens the just-closed account.
     */
    fun undoClose() {
        val accountId = state.value.accountWithBalance?.account?.id ?: return
        viewModelScope.launch {
            accountRepository.reopen(accountId)
            _reopenEvent.send(accountId)
        }
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.ui.accounts.AccountDetailViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Run the full unit-test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest
```

Expected: all unit tests pass.

- [ ] **Step 6: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/test/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModelTest.kt
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailViewModel.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): detail VM close/reopen events + archive resolution"
```

---

### Task 8: AccountsListScreen — filter chip + closed style

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt`

- [ ] **Step 1: Read the existing screen**

```bash
cat app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt
```

Identify the `LazyColumn` and how it renders each `AccountWithBalance` row.

- [ ] **Step 2: Add the filter chip + closed styling**

Modify the screen so the state is collected (`val state by viewModel.state.collectAsStateWithLifecycle()` — assume the pattern from the existing code), and the layout above the LazyColumn is:

```kotlin
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FilterChip(
            selected = state.showClosed,
            onClick = { viewModel.setShowClosed(!state.showClosed) },
            label = { Text(stringResource(R.string.account_filter_show_closed)) },
        )
        Text(
            text = stringResource(R.string.accounts_header_count, state.count),
            style = MaterialTheme.typography.labelMedium,
        )
    }
```

In the row rendering inside `items(state.accounts)`, wrap the existing content in a Box that conditionally reduces alpha when archived:

```kotlin
        val alpha = if (entry.account.archived) 0.6f else 1f
        Box(modifier = Modifier.graphicsLayer { this.alpha = alpha }) {
            // existing row content
        }
```

After the balance amount, append a `Closed` pill when archived:

```kotlin
        if (entry.account.archived) {
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.account_status_closed)) },
            )
        }
```

- [ ] **Step 3: Verify all referenced strings exist**

```bash
cd /f/AndroidApp/ExpenseTracker
grep -E "R\.string\.(account_filter_show_closed|account_status_closed|accounts_header_count)" \
    app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt
```

Expected: 3 hits. (All three were added in Task 5; `accounts_header_count` predates this change.)

- [ ] **Step 4: Build**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the unit-test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest
```

Expected: all unit tests pass.

- [ ] **Step 6: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountsListScreen.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): list screen filter chip + closed-row styling"
```

---

### Task 9: AccountDetailScreen — overflow + dialogs + snackbar + Closed on line

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt`

- [ ] **Step 1: Read the existing screen**

```bash
cat app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt
```

Identify the top-bar `actions` block, the `BalanceHeader` composable, and where dialogs are rendered.

- [ ] **Step 2: Replace the file with the close-enabled version**

Replace the entire contents of `AccountDetailScreen.kt` with:

```kotlin
package io.github.jiro.expensetracker.ui.accounts

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.home.TransactionRow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.account_undo)

    val closeSnackbarMessage = stringResource(R.string.account_close_snackbar)
    val reopenSnackbarMessage = stringResource(R.string.account_reopen_snackbar)

    LaunchedEffect(viewModel) {
        viewModel.closeEvent.collectLatest { _ ->
            val result = snackbarHostState.showSnackbar(
                message = closeSnackbarMessage,
                actionLabel = undoLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoClose()
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.reopenEvent.collectLatest { _ ->
            snackbarHostState.showSnackbar(message = reopenSnackbarMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        if (aw.account.archived) {
                            IconButton(onClick = viewModel::onReopenClick) {
                                Icon(
                                    Icons.Filled.LockOpen,
                                    contentDescription = stringResource(R.string.account_reopen),
                                )
                            }
                        } else {
                            IconButton(onClick = viewModel::onCloseClick) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.account_close),
                                )
                            }
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
                    BalanceHeader(account = it.account, balanceMinor = it.balanceMinor)
                    Spacer(Modifier.height(16.dp))
                }
            }
            items(state.transactions, key = { it.transaction.id }) { row ->
                TransactionRow(row = row, onClick = { onTransactionClick(row.transaction.id) })
            }
        }
    }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    // ---- Delete flow (unchanged) ----
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
            null -> Unit
        }
    }

    // ---- Close confirm ----
    if (state.showCloseConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::onCloseDismiss,
            title = { Text(stringResource(R.string.account_close_confirm_title)) },
            text = { Text(stringResource(R.string.account_close_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onCloseConfirm) {
                    Text(stringResource(R.string.account_close))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onCloseDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // ---- Reopen confirm ----
    if (state.showReopenConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::onReopenDismiss,
            title = { Text(stringResource(R.string.account_reopen_confirm_title)) },
            text = { Text(stringResource(R.string.account_reopen_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onReopenConfirm) {
                    Text(stringResource(R.string.account_reopen))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onReopenDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun BalanceHeader(account: AccountEntity, balanceMinor: Long) {
    val isNegative = balanceMinor < 0
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(account.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (account.archived) {
            Spacer(Modifier.height(4.dp))
            val ctx = LocalContext.current
            val formattedDate = account.archivedAtEpochMillis?.let { ms ->
                DateUtils.formatDateTime(
                    ctx,
                    ms,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
                )
            }
            Text(
                text = if (formattedDate != null) {
                    stringResource(R.string.account_closed_on, formattedDate)
                } else {
                    stringResource(R.string.account_status_closed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = (if (isNegative) "−" else "") + MoneyFormat.formatForDisplay(if (isNegative) -balanceMinor else balanceMinor) + " " + account.currencyCode,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
```

- [ ] **Step 3: Verify referenced strings exist**

```bash
cd /f/AndroidApp/ExpenseTracker
grep -E "R\.string\." app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt \
  | grep -oE "R\.string\.[a-z_]+" | sort -u > /tmp/used.txt
grep -oE 'name="[a-z_]+"' app/src/main/res/values/strings.xml \
  | sed 's/name="//;s/"//' | sort -u > /tmp/defined.txt
comm -23 /tmp/used.txt /tmp/defined.txt
```

Expected: empty output (every R.string.* name is defined).

- [ ] **Step 4: Build**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the unit-test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest
```

Expected: all unit tests pass.

- [ ] **Step 6: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/ui/accounts/AccountDetailScreen.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(accounts): detail screen close/reopen actions + Closed on line"
```

---

### Task 10: TransactionsScreen — Added line on row

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt`

- [ ] **Step 1: Locate the row composable**

```bash
grep -n "TransactionRow\|fun TransactionRow" app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt
```

The transaction row rendering is likely either inline or in a shared file. Adjust the path accordingly — if it's in `app/src/main/java/io/github/jiro/expensetracker/ui/home/`, edit that file instead.

- [ ] **Step 2: Add the Added subtitle line**

Inside the row composable (which receives a `TransactionWithCategory` or similar), find the spot where the title/category/amount are laid out. Add a new `Text` line below the title that reads `Added MMM d` when `transaction.createdAtEpochMillis != 0L`:

```kotlin
import android.text.format.DateUtils
import androidx.compose.ui.platform.LocalContext

// ... inside the row composable ...
val ctx = LocalContext.current
val addedText = remember(transaction.createdAtEpochMillis) {
    if (transaction.createdAtEpochMillis == 0L) null else {
        DateUtils.formatDateTime(
            ctx,
            transaction.createdAtEpochMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
    }
}
if (addedText != null) {
    Text(
        text = stringResource(R.string.transaction_added_on, addedText),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

Place this directly after the existing title `Text` so the visual order is: Title → "Added MMM d" → Category/Date/Amount row.

- [ ] **Step 3: Verify referenced strings**

```bash
cd /f/AndroidApp/ExpenseTracker
grep -E "R\.string\." app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt \
  | grep -oE "R\.string\.[a-z_]+" | sort -u > /tmp/used.txt
grep -oE 'name="[a-z_]+"' app/src/main/res/values/strings.xml \
  | sed 's/name="//;s/"//' | sort -u > /tmp/defined.txt
comm -23 /tmp/used.txt /tmp/defined.txt
```

Expected: empty output.

- [ ] **Step 4: Build + unit tests**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL and all unit tests pass.

- [ ] **Step 5: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/ui/transactions/TransactionsScreen.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(transactions): row Added MMM d subtitle from createdAtEpochMillis"
```

---

### Task 11: AddEditTransactionScreen — Added subtitle in detail/edit mode

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt`

- [ ] **Step 1: Identify the screen header layout**

```bash
grep -n "transaction.title\|transaction.occurredAt\|TopAppBar\|fun AddEditTransactionScreen" \
  app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt
```

Find the place where the transaction's title is rendered as a non-editable header (when in detail / edit mode, not new).

- [ ] **Step 2: Add the Added subtitle**

In the existing header composable, after the `Text(transaction.title, …)`, add:

```kotlin
import android.text.format.DateUtils
import androidx.compose.ui.platform.LocalContext

// ... inside the screen, near where the header is composed ...
val ctx = LocalContext.current
val state by viewModel.state.collectAsStateWithLifecycle()
// `state.id` is null for new transactions, non-null for existing ones.
val showAddedLine = state.id != null && state.createdAtEpochMillis > 0L
if (showAddedLine) {
    val addedText = DateUtils.formatDateTime(
        ctx,
        state.createdAtEpochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
    Text(
        text = stringResource(R.string.transaction_added_on, addedText),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

`createdAtEpochMillis` is on the `AddEditTransactionUiState` already; verify by grepping:

```bash
grep -n "createdAtEpochMillis" app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt
```

If the state doesn't expose `createdAtEpochMillis`, add a `val createdAtEpochMillis: Long = 0L` field to `AddEditTransactionUiState` and populate it from the loaded transaction in `init { ... }`.

- [ ] **Step 3: Verify referenced strings + build**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
grep -E "R\.string\." app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt \
  | grep -oE "R\.string\.[a-z_]+" | sort -u > /tmp/used.txt
grep -oE 'name="[a-z_]+"' app/src/main/res/values/strings.xml \
  | sed 's/name="//;s/"//' | sort -u > /tmp/defined.txt
comm -23 /tmp/used.txt /tmp/defined.txt
./gradlew assembleDebug testDebugUnitTest
```

Expected: empty `comm -23` output, BUILD SUCCESSFUL, all unit tests pass.

- [ ] **Step 4: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(transactions): add-edit screen Added MMM d subtitle in detail mode"
```

---

### Task 12: Smoke test doc

**Files:**
- Create: `docs/superpowers/testdata/close-account.md`

- [ ] **Step 1: Write the smoke test**

Create `docs/superpowers/testdata/close-account.md`:

```markdown
# Close Account — Manual Smoke Test

Manual verification for the close-account feature. Mirror the structure
of `docs/superpowers/testdata/member-cards-widget.md`.

## Pre-conditions

- App built and installed (`./gradlew installDebug`, then launch once).
- At least 3 accounts saved: "Cash wallet" (the seeded default), one
  other active account, and one more for the dropdown test.
- Several transactions referencing each of the active accounts so the
  "Added" lines and historic-transaction display are visible.

## Steps

1. **Close from detail.** Open the account detail for "Checking". Tap
   the close icon in the top bar. Verify the confirm dialog renders with
   the message about staying in records. Tap Close.
2. **Snackbar + Undo.** Verify a "Account closed" snackbar with an Undo
   action appears. Tap Undo. Verify the account reopens (no longer
   "Closed" pill, dropdown lists it again).
3. **Close for real.** Repeat step 1, then dismiss the snackbar without
   tapping Undo. Verify the detail screen shows "Closed on MMM d, yyyy"
   below the account name.
4. **Dropdown hiding.** Open the Add Transaction screen. Verify the
   closed "Checking" account is NOT in the account dropdown. Verify it
   is also absent from the To-account dropdown (for transfers).
5. **Reopen from detail.** Tap the reopen icon on the closed account's
   detail screen. Confirm. Verify the snackbar "Account reopened"
   appears (no Undo). Verify the dropdown lists the account again.
6. **Closed-account filter.** Open the Accounts list. Verify "Show
   closed accounts" chip is OFF. Toggle it on. Verify closed accounts
   appear with 60% alpha and a "Closed" pill.
7. **Net balance inclusion.** With closed accounts visible (filter on)
   and not visible (filter off), verify the "Net balance (home)"
   number is the SAME in both views (closed accounts contribute to net
   balance).
8. **Historic transaction display.** Add a transaction against
   "Checking", then close "Checking". Open the transactions list.
   Verify the transaction row still shows the closed account's name.
   Verify the "Added MMM d" line is visible below the title.
9. **Detail-screen transaction list.** Open the closed account's detail
   screen. Verify its transaction list still renders and shows the
   closed account's name on each row.
10. **Close the seeded default.** Close the seeded "Cash wallet"
    (id=1) account. Verify the close succeeds (no FK error). Verify a
    new transaction defaults to the next active account (lowest id).
11. **Open detail via deep-link (no accountId state).** With the closed
    "Checking" still archived, force-stop the app. Reopen. Navigate
    straight to the closed account's detail via the home/accounts
    list. Verify the detail screen still loads (no blank).
12. **Close + reopen idempotency.** Close "Savings". Close it again
    (re-open the detail and tap close a second time without
    intervening reopen). Verify the `archivedAtEpochMillis` updates to
    the most-recent close time.
13. **All-archived default.** Close every active account. Open the
    Add-Edit Transaction screen. Verify the UI handles the missing
    default account gracefully (no crash). Verify reopening any
    account makes the default reappear.
14. **Migration upgrade.** Build a debug APK with `version = 7` of
    AppDatabase, install, seed an account, then install a v8 APK on
    top. Verify the existing account's `archivedAtEpochMillis` is
    `null` and the app still launches cleanly.

## Expected outcomes

- Step 1: dialog appears, Cancel keeps the account active.
- Step 2: account reopens, dropdowns restore.
- Step 3: snackbar fires once and the screen shows "Closed on …".
- Step 4: closed account absent from both account dropdowns.
- Step 5: account reappears, no data lost.
- Step 6: chip toggle swaps source; closed rows render muted with pill.
- Step 7: net balance unchanged by filter toggle.
- Step 8 / 9: closed account name visible on historic txns; "Added"
  line present.
- Step 10: seeded default closes; default fallback works.
- Step 11: detail screen resolves closed accounts.
- Step 12: closing twice updates the timestamp; reopen wipes it.
- Step 13: graceful empty default; reopens restore default.
- Step 14: schema migration runs cleanly; legacy data unchanged.

## Rollback

`adb shell pm clear io.github.jiro.expensetracker` resets app data and
removes all accounts.
```

- [ ] **Step 2: Commit**

```bash
cd /f/AndroidApp/ExpenseTracker
git add docs/superpowers/testdata/close-account.md
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "docs(close-account): manual smoke test"
```

---

### Task 13: Final verification + ship

- [ ] **Step 1: Run the full unit-test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew testDebugUnitTest
```

Expected: all unit tests pass.

- [ ] **Step 2: Run the full instrumented test suite (if a device/emulator is attached)**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew connectedDebugAndroidTest
```

Expected: all instrumented tests pass, including the new
`AccountMigrationTest` and `AccountDaoCloseTest`.

- [ ] **Step 3: Build the debug APK**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
cd /f/AndroidApp/ExpenseTracker
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Stage all files explicitly and verify the working tree**

```bash
cd /f/AndroidApp/ExpenseTracker
git status
```

If any untracked or modified files appear that should belong to this
feature, add them with explicit paths.

- [ ] **Step 5: Create the ship commit (no separate "all tasks done" commit — the per-task commits are the trail)**

Each Task already commits its own work. Verify the most recent commit
on `master` is the smoke-test doc commit from Task 12.

- [ ] **Step 6: Push master and tag**

```bash
cd /f/AndroidApp/ExpenseTracker
git push origin master
git tag -a v0.18.6 -m "Close-account feature: hide from dropdowns, retain in DB, reopenable"
git push origin v0.18.6
```

Expected: push succeeds. v0.18.6 is the next minor after v0.18.5
(member-cards-widget).

- [ ] **Step 7: Report**

Tell the user:
- Total commits added in this plan (12 feature commits).
- Total tests added: 4 (AccountMigrationTest, AccountDaoCloseTest,
  AccountCloseRepositoryTest, AccountsListViewModelToggleTest, plus
  the extensions to AccountDetailViewModelTest and
  TransactionsScreen+AccountDetailScreen additions).
- Smoke test file at `docs/superpowers/testdata/close-account.md`.
- Known limitation: account backup format does not yet include the
  new column (out-of-scope per spec).

---

## Summary

12 implementation tasks + 1 ship task. Each task produces a working
commit. Tests cover migration, DAO writes, DAO reads, repository
plumbing, the projection, the new VM state, and the existing VM flow
extended with close/reopen events. The smoke test gives the user a
deterministic manual verification path on hardware.

After all 12 commits land and `master` is at the smoke-test commit,
tag v0.18.6 and push. Done.