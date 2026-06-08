package io.github.jiro.expensetracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao

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
    }
}
