package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single source of truth for a personal-finance transaction.
 *
 * `amountMinor` is stored in the currency's minor unit (e.g. cents) to avoid
 * floating-point drift. `type` is stored as a String column (matching
 * [io.github.jiro.expensetracker.domain.model.TransactionType.name]) so adding
 * a new type (TRANSFER, ADJUSTMENT) doesn't require a migration.
 *
 * **Accounts (Phase 2.16):** every row has `accountId`. TRANSFER rows also
 * reference `transferAccountId` (the destination); all other types leave it
 * null. `categoryId` is now nullable because TRANSFER and ADJUSTMENT have no
 * category.
 *
 * **Recurring transactions:** a row is part of a recurring series when
 * [recurringGroupId] is non-null. All rows in the same series share that id.
 * The "parent" — the row that drives the schedule — is the one with
 * [recurrenceNextAt] set; materialised instances have `recurrenceNextAt = null`.
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
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["transferAccountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index("accountId"),
        Index("transferAccountId"),
        Index("recurringGroupId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val type: String,
    val categoryId: Long? = null,
    /** Every transaction belongs to one account (Phase 2.16). Default = seeded "Cash wallet" (id=1). */
    val accountId: Long = 1L,
    /** TRANSFER only: the destination account. null for all other types. */
    val transferAccountId: Long? = null,
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
    /**
     * Relative path under `<filesDir>/receipts/` (e.g. `abc123.jpg`), or null
     * if no receipt is attached. Relative paths survive backup-restore across
     * devices. The file is deleted by the application, not the DB.
     */
    val receiptPath: String? = null,
)
