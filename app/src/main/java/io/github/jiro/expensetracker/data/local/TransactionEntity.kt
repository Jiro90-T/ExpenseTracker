package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single source of truth for a personal-finance transaction.
 *
 * `amountMinor` is stored in the currency's minor unit (e.g. cents) to avoid floating-point
 * drift. `type` is a sealed enum (EXPENSE / INCOME); represent as a String column for
 * forward-compatibility if a third type (TRANSFER) is added later.
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val type: String,
    val category: String,
    val occurredAtEpochMillis: Long,
    val note: String? = null,
    val createdAtEpochMillis: Long,
)
