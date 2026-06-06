package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single source of truth for a personal-finance transaction.
 *
 * `amountMinor` is stored in the currency's minor unit (e.g. cents) to avoid floating-point
 * drift. `type` is stored as a String column (matching [io.github.jiro.expensetracker.domain.model.TransactionType.name])
 * for forward-compatibility if a third type (TRANSFER) is added later.
 *
 * `categoryId` is a foreign key into [CategoryEntity] with RESTRICT on delete: you can't
 * drop a category while transactions still reference it.
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
    ],
    indices = [Index("categoryId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val type: String,
    val categoryId: Long,
    val occurredAtEpochMillis: Long,
    val note: String? = null,
    val createdAtEpochMillis: Long,
)
