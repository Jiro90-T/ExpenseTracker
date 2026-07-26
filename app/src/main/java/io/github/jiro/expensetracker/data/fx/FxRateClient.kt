package io.github.jiro.expensetracker.data.fx

interface FxRateClient {
    /**
     * Fetches the latest FX rates with USD as the base currency.
     * Returns a map keyed "USD_to_XXX" (e.g. "USD_to_MYR" = 4.7) to the
     * multiplicative rate (1 USD = rate XXX).
     *
     * Throws [FxRateFetchException] on transport, parse, or API-level
     * failure (result != "success"). Same shape as [io.github.jiro.expensetracker.data.market.MarketDataException]
     * — the SettingsViewModel surfaces the message verbatim.
     */
    suspend fun fetchLatestUsdRates(): Map<String, Double>
}

class FxRateFetchException(message: String) : Exception(message)
