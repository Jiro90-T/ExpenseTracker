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
        MemberCardEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun accountDao(): AccountDao
    abstract fun memberCardDao(): MemberCardDao

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

        /**
         * v6 → v7: member cards. Creates the `member_cards` table with
         * columns matching [MemberCardEntity]. No existing data is touched.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `member_cards` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `name` TEXT NOT NULL,
                      `imagePath` TEXT NOT NULL,
                      `memberIdText` TEXT,
                      `colorHex` INTEGER,
                      `icon` TEXT,
                      `expiresAtEpochMillis` INTEGER,
                      `notes` TEXT,
                      `createdAtEpochMillis` INTEGER NOT NULL,
                      `sortOrder` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
