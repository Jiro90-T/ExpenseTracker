package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A per-category monthly spending limit. Stored in the home currency's minor
 * units. `monthStartEpochMs` is the local-timezone midnight on the 1st of the
 * month — produced by `BudgetRepository.currentMonthStart()` so all call sites
 * agree on the bucket key.
 *
 * One row per (category, month). Missing row = no budget set for that
 * (category, month). Composite primary key naturally enforces that.
 *
 * `ON DELETE RESTRICT` on categoryId matches the existing pattern for
 * `transactions.categoryId`: dropping a category that has budgets (current or
 * historical) is rejected by the FK. The existing catch in
 * `CategoryManagementViewModel.delete` will surface the same
 * `SQLiteConstraintException` — its message is updated in Task 6.
 */
@Entity(
    tableName = "budgets",
    primaryKeys = ["categoryId", "monthStartEpochMs"],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("monthStartEpochMs")],
)
data class BudgetEntity(
    val categoryId: Long,
    val monthStartEpochMs: Long,
    val amountMinor: Long,
)
