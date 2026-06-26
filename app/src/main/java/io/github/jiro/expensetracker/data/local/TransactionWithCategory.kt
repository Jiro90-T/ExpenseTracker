package io.github.jiro.expensetracker.data.local

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Joined view of a transaction with its category. Returned by `@Transaction`
 * DAO methods so the UI can render transaction + category name in one pass.
 */
data class TransactionWithCategory(
    @Embedded val transaction: TransactionEntity,
    @Relation(
        parentColumn = "categoryId",
        entityColumn = "id",
    )
    val category: CategoryEntity?,
)
