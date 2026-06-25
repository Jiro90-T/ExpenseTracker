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
