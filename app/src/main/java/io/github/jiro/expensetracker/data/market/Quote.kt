package io.github.jiro.expensetracker.data.market

/** Latest price for a single ticker, already scaled to minor units. */
data class Quote(
    val symbol: String,
    val priceMinor: Long,
    val currencyCode: String,
    val asOfEpochMillis: Long,
)
