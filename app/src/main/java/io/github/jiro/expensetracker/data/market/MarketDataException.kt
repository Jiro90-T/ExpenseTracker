package io.github.jiro.expensetracker.data.market

/** Thrown by MarketDataClient on transport / parse failure. Unknown symbols
 *  are NOT a failure — they return null in the result list instead. */
class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
