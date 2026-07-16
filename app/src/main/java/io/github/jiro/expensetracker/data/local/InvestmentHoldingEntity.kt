package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A position held inside an INVESTMENT account. One row per symbol per
 * account. Cost basis is the total amount paid (not per-share); the UI
 * shows per-share as `costBasisMinor / quantity`.
 *
 * The FK to accounts is RESTRICT (matches TransactionEntity/BudgetEntity)
 * so the database will reject any future code that deletes an account
 * row while holdings exist. The app enforces the same rule at the UI layer
 * via DeleteGuard.BLOCK_HOLDINGS_EXIST.
 */
@Entity(
    tableName = "investment_holdings",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["symbol"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class InvestmentHoldingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val accountId: Long,
    /** Uppercased ticker, e.g. "AAPL", "BTC-USD", "7203.T". */
    val symbol: String,
    /** Fractional shares allowed (crypto, DRIP). */
    val quantity: Double,
    /** Total cost in `currencyCode` minor units. */
    val costBasisMinor: Long,
    /** ISO 4217 code matching the symbol's native currency. */
    val currencyCode: String,
    val createdAtEpochMillis: Long,
)
