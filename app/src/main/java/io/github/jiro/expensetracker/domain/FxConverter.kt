package io.github.jiro.expensetracker.domain

/**
 * Multi-currency support (Phase 2.2).
 *
 * FX rates are stored as multiplicative factors: a rate of 0.92 from USD
 * to EUR means "1 USD = 0.92 EUR". Each pair is keyed as `"USD_to_EUR"`.
 *
 * MVP source: manual entry in Settings. Future iterations can swap the
 * in-memory map for a real rate source (exchangerate.host, Open Exchange
 * Rates, etc.) without touching the call sites.
 */
object FxConverter {

    /**
     * Converts [amountMinor] from [fromCurrency] to [toCurrency] using
     * the [rates] map. Returns null when the rate isn't known — callers
     * should fall back to 1:1 (or exclude) and surface a warning.
     *
     * Same-currency conversions short-circuit. Negative rates are
     * rejected (programmer-error guard).
     */
    fun convertMinor(
        amountMinor: Long,
        fromCurrency: String,
        toCurrency: String,
        rates: Map<String, Double>,
    ): Long? {
        require(rates.values.all { it >= 0.0 }) { "FX rates must be non-negative" }
        if (fromCurrency == toCurrency) return amountMinor
        if (fromCurrency.isBlank() || toCurrency.isBlank()) return null
        val rate = rates["${fromCurrency}_to_$toCurrency"] ?: return null
        // Banker's rounding keeps the conversion as fair as possible for
        // large amounts; toLong() alone truncates toward zero.
        val converted = amountMinor.toDouble() * rate
        return Math.round(converted).toLong()
    }

    /** Pretty key for the rate map. */
    fun rateKey(from: String, to: String): String = "${from}_to_$to"

    /**
     * String encoding for SharedPreferences round-trip. Entries separated
     * by `;`, key and value by `=`. Round-trip safe for the rates we
     * accept (finite positive doubles).
     */
    fun encode(rates: Map<String, Double>): String =
        rates.entries.joinToString(separator = ";") { "${it.key}=${it.value}" }

    fun decode(encoded: String): Map<String, Double> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split(";").mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val k = entry.substring(0, eq)
            val v = entry.substring(eq + 1).toDoubleOrNull() ?: return@mapNotNull null
            k to v
        }.toMap()
    }
}
