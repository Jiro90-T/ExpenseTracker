package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Latest known price for a ticker, shared across all accounts that hold it.
 * One row per symbol. Updated by [io.github.jiro.expensetracker.data.market.QuoteRepository].
 */
@Entity(tableName = "cached_quotes")
data class CachedQuoteEntity(
    /** Uppercased ticker. */
    @PrimaryKey val symbol: String,
    /** Latest known price in `currencyCode` minor units (use MoneyFormat.priceToMinor). */
    val priceMinor: Long,
    val currencyCode: String,
    val fetchedAtEpochMillis: Long,
)
