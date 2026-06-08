package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single source of truth for a personal-finance transaction.
 *
 * `amountMinor` is stored in the currency's minor unit (e.g. cents) to avoid floating-point
 * drift. `type` is stored as a String column (matching
 * [io.github.jiro.expensetracker.domain.model.TransactionType.name]) for forward-compatibility
 * if a third type (TRANSFER) is added later.
 *
 * `categoryId` is a foreign key into [CategoryEntity] with RESTRICT on delete: you can't drop
 * a category while transactions still reference it.
 *
 * **Recurring transactions**: a row is part of a recurring series when
 * [recurringGroupId] is non-null. All rows in the same series share that id. The
 * "parent" — the row that drives the schedule — is the one with
 * [recurrenceNextAt] set; materialised instances have `recurrenceNextAt = null`.
 * The materialisation worker (see `RecurringTransactionWorker`) finds parents whose
 * `recurrenceNextAt <= now`, clones them as a new instance, and advances the
 * parent's `recurrenceNextAt` (or nulls it when the end condition is met).
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
    indices = [
        Index("categoryId"),
        Index("recurringGroupId"),
    ],
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
    /** Non-null iff this row is part of a recurring series. */
    val recurringGroupId: String? = null,
    /** "DAILY" / "WEEKLY" / "MONTHLY" / "YEARLY". Non-null iff [recurringGroupId] is. */
    val recurrenceKind: String? = null,
    /** Every N periods (1 = every period, 2 = every other, etc.). Defaults to 1. */
    val recurrenceInterval: Int = 1,
    /** Stop the series at this wall-clock instant (or null = no end-by-date). */
    val recurrenceEndAt: Long? = null,
    /** Stop after this many materialised instances (or null = no occurrence cap). */
    val recurrenceMaxOccurrences: Int? = null,
    /**
     * Next time the worker should materialise a new instance. Null on materialised
     * instances. On the parent, this is what the worker checks; when it fires, the
     * parent is cloned and this column is advanced to the next scheduled date (or
     * nulled if the series has ended).
     */
    val recurrenceNextAt: Long? = null,
)
