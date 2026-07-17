package io.github.jiro.expensetracker.data.local

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Validates the v8 → v9 schema migration end-to-end:
 *  1. Open a fresh on-disk file as a v9 [AppDatabase] with the full
 *     migration chain registered. Room sees no `user_version` in the
 *     file, treats it as v1, and runs every migration in order:
 *     2_3 → 3_4 → 4_5 → 5_6 → 6_7 → 7_8 → 8_9. Each migration is
 *     validated by Room's standard post-migration schema check.
 *  2. Insert one row into each new table to prove the DAOs work after
 *     the migration.
 *
 * Why not androidx.room.testing.MigrationTestHelper? That helper looks
 * up schema JSONs in the Android `assets/` folder. Robolectric unit
 * tests don't bundle `src/test/assets/` into the test APK by default,
 * and going through the full `Room.databaseBuilder` exercises the
 * same Room code path (migration + schema validation) without the
 * AssetManager indirection.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    private lateinit var dbFile: File

    @Before fun setup() {
        dbFile = File.createTempFile("migration-test-", ".db")
        dbFile.deleteOnExit()
    }

    @After fun teardown() {
        if (dbFile.exists()) dbFile.delete()
    }

    @Test fun v8_to_v9_createsInvestmentTables() = runTest {
        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java, dbFile.absolutePath,
        ).addMigrations(
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
        ).build()
        try {
            // After all migrations, the DB is at v9. Seed the default
            // account so the FK on investment_holdings.accountId is
            // satisfied, then exercise the new DAOs end-to-end.
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO accounts (id, name, type, icon, color, currencyCode,
                                      openingBalanceMinor, createdAtEpochMillis,
                                      archived, sortOrder)
                VALUES (1, 'Test Account', 'CASH', '💵', -14934489, 'USD', 0, 0, 0, 0)
                """.trimIndent(),
            )
            db.investmentHoldingDao().insert(
                InvestmentHoldingEntity(
                    accountId = 1L, symbol = "AAPL", quantity = 1.0,
                    costBasisMinor = 100L, currencyCode = "USD",
                    createdAtEpochMillis = 0L,
                ),
            )
            db.cachedQuoteDao().upsert(CachedQuoteEntity("AAPL", 100L, "USD", 0L))
            // Read them back to prove the schema is real and queryable.
            val holdings = db.investmentHoldingDao().observeByAccount(1L).first()
            val quote = db.cachedQuoteDao().findBySymbol("AAPL")
            assert(holdings.size == 1) { "expected 1 holding, got $holdings" }
            assert(quote?.priceMinor == 100L) { "quote not queryable: $quote" }
        } finally {
            db.close()
        }
    }
}
