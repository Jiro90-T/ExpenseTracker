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
class MemberCardMigrationTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_6_to_7_createsMemberCardsTable() {
        helper.createDatabase(AppDatabase.NAME, 6).apply { close() }

        val db = helper.runMigrationsAndValidate(AppDatabase.NAME, 7, true, AppDatabase.MIGRATION_6_7)

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='member_cards'").use { c ->
            assertTrue("member_cards table should exist", c.moveToFirst())
        }

        db.query("PRAGMA table_info(member_cards)").use { c ->
            val cols = mutableMapOf<String, String>()
            while (c.moveToNext()) {
                cols[c.getString(c.getColumnIndexOrThrow("name"))] = c.getString(c.getColumnIndexOrThrow("type"))
            }
            assertEquals("INTEGER", cols["id"])
            assertEquals("TEXT", cols["name"])
            assertEquals("TEXT", cols["imagePath"])
            assertEquals("TEXT", cols["memberIdText"])
            assertEquals("INTEGER", cols["colorHex"])
            assertEquals("TEXT", cols["icon"])
            assertEquals("INTEGER", cols["expiresAtEpochMillis"])
            assertEquals("TEXT", cols["notes"])
            assertEquals("INTEGER", cols["createdAtEpochMillis"])
            assertEquals("INTEGER", cols["sortOrder"])
        }
    }
}
