package io.github.jiro.expensetracker.data.market

interface MarketDataClient {
    /**
     * Fetches latest quotes for [symbols]. Returns one Quote? per requested
     * symbol, in input order; null entries for symbols the feed didn't
     * recognize. Throws [MarketDataException] on transport / parse failure.
     */
    suspend fun fetchQuotes(symbols: List<String>): List<Quote?>
}
