package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A financial account (cash, bank, credit card, e-wallet, custom). Every
 * [TransactionEntity] is bound to one via `accountId`; TRANSFER rows also
 * reference a destination via `transferAccountId`.
 *
 * `currencyCode` is locked at creation (changing it would silently invalidate
 * every transaction's native currency assignment).
 *
 * The unique name index is enforced at the application layer (the repository
 * rejects duplicates on insert); SQLite's "unique only among non-archived"
 * partial index isn't expressible via Room's `@Index`, so duplicates can
 * technically exist in the table — we never create them.
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["name"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** "CASH" | "BANK" | "CREDIT_CARD" | "EWALLET" | "OTHER" | user custom. */
    val type: String,
    /** Emoji or short code (e.g. "💵"). */
    val icon: String,
    /** ARGB color integer. */
    val color: Int,
    /** 3-letter ISO 4217 code, locked at creation. */
    val currencyCode: String,
    val openingBalanceMinor: Long = 0L,
    val createdAtEpochMillis: Long,
    val archived: Boolean = false,
    /**
     * Timestamp when the account was closed (archived = true). Null means
     * the account has never been closed. Set whenever the user closes an
     * account from AccountDetailScreen; cleared on reopen. Distinct from
     * `archived` so reopen doesn't need to inspect a sentinel.
     */
    val archivedAtEpochMillis: Long? = null,
    val sortOrder: Int = 0,
)
