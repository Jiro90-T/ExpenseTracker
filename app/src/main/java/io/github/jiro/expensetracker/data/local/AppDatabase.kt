package io.github.jiro.expensetracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, BudgetEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        const val NAME = "expense_tracker.db"

        /**
         * v2 → v3: add the recurring-transaction columns to the `transactions`
         * table. All new columns are nullable or have a default, so existing
         * rows survive without modification.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurringGroupId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceKind TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceInterval INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceEndAt INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceMaxOccurrences INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceNextAt INTEGER")
                // Index for the worker's "due recurrences" query.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_recurringGroupId ON transactions (recurringGroupId)")
            }
        }

        /**
         * v3 → v4: add the `budgets` table. Non-destructive — the new table
         * starts empty; existing transactions/categories are untouched.
         */
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
    }
}
