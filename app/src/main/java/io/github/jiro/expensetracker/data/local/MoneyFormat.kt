package io.github.jiro.expensetracker.data.local

/**
 * Shared money parsing/formatting used by AddEdit, Budget edit dialog, and any
 * other place the user types an amount. Stored values are minor units (Long).
 */
object MoneyFormat {

    /** Cap whole units at 9_999_999_999 to stay well clear of Long overflow when * 100. */
    const val MAX_AMOUNT_WHOLE = 9_999_999_999L

    /**
     * Parse a user-entered amount like "25", "25.5", "25.57", "25.123" → minor units.
     * Returns null on empty, negative, two decimal points, non-numeric, or over-max.
     * Excess decimals are truncated (not rounded) to two digits.
     */
    fun parseAmountToMinor(input: String): Long? {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.size > 2) return null
        val whole = parts[0].toLongOrNull() ?: return null
        if (whole < 0) return null
        val fractionStr = if (parts.size == 2) parts[1].padEnd(2, '0').take(2) else "00"
        if (fractionStr.length > 2) return null
        val fraction = fractionStr.toLongOrNull() ?: return null
        if (whole > MAX_AMOUNT_WHOLE) return null
        return whole * 100 + fraction
    }

    /**
     * Parse a signed amount (allows a leading `-`), for account opening balance
     * and adjust-balance dialogs. A credit card starting with $50 of debt becomes
     * `-50.00` here and `-5000` minor. Transactions/budgets/transaction-list
     * filters continue to use [parseAmountToMinor] which still rejects negatives.
     */
    fun parseSignedAmountToMinor(input: String): Long? {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return null
        val negative = cleaned.startsWith("-")
        val body = if (negative) cleaned.substring(1) else cleaned
        val parts = body.split('.')
        if (parts.size > 2) return null
        val absWhole = parts[0].toLongOrNull() ?: return null
        if (absWhole > MAX_AMOUNT_WHOLE) return null
        val fractionStr = if (parts.size == 2) parts[1].padEnd(2, '0').take(2) else "00"
        if (fractionStr.length > 2) return null
        val fraction = fractionStr.toLongOrNull() ?: return null
        val absMinor = absWhole * 100 + fraction
        return if (negative) -absMinor else absMinor
    }

    /** Format a minor-unit value back to a user-facing string with two-decimal fraction. */
    fun formatAmountForEdit(minor: Long): String {
        val whole = minor / 100
        val fraction = minor % 100
        return "%d.%02d".format(whole, fraction)
    }

    /**
     * Format a minor-unit value for display (lists, cards, balance headers).
     * Includes a thousands separator on the whole portion so 1_000_00 displays
     * as "1,000.00". Use [formatAmountForEdit] for text fields where the user
     * is typing — reformatting there would fight cursor position.
     */
    fun formatForDisplay(minor: Long): String {
        val isNegative = minor < 0
        val absMinor = if (isNegative) -minor else minor
        val whole = absMinor / 100
        val fraction = absMinor % 100
        val grouped = groupThousands(whole)
        val sign = if (isNegative) "-" else ""
        return "$sign$grouped.%02d".format(fraction)
    }

    private fun groupThousands(value: Long): String {
        if (value < 1000) return value.toString()
        val sb = StringBuilder()
        var v = value
        while (v >= 1000) {
            val rem = (v % 1000).toInt()
            sb.insert(0, ",%03d".format(rem))
            v /= 1000
        }
        sb.insert(0, v.toString())
        return sb.toString()
    }

    /**
     * Pure: strips thousands separators (`,`, ASCII space, U+202F narrow
     * no-break space, U+00A0 non-breaking space) from a user-typed search
     * string and lowercases it. "1,200", "1 200", "1200" all normalize to
     * "1200". "1,200.50" → "1200.50".
     */
    fun stripAmountSeparators(query: String): String {
        return query
            .replace(',', ' ')
            .replace(Char(0x202F), ' ')   // narrow no-break space
            .replace(Char(0x00A0), ' ')   // non-breaking space
            .replace(" ", "")
            .lowercase()
    }

    /**
     * Per-currency decimal-place map for prices that arrive as doubles
     * (Yahoo Finance, FX rates). Most fiat is 2dp; JPY/KRW are 0dp; BTC/ETH
     * use 2dp/5dp respectively. Unknown currencies default to 2dp.
     */
    private val CURRENCY_DECIMAL_PLACES: Map<String, Int> = mapOf(
        "USD" to 2, "EUR" to 2, "GBP" to 2, "AUD" to 2, "CAD" to 2,
        "CHF" to 2, "SGD" to 2, "HKD" to 2, "MYR" to 2, "CNY" to 2,
        "JPY" to 0, "KRW" to 0,
        "BTC" to 2, "ETH" to 5,
    )

    /** Convert a price expressed as a double (Yahoo precision) into minor units
     *  for the given currency. Uses banker's-ish rounding (Math.round = half-up). */
    fun priceToMinor(price: Double, currencyCode: String): Long {
        val dp = CURRENCY_DECIMAL_PLACES[currencyCode.uppercase()] ?: 2
        val multiplier = Math.pow(10.0, dp.toDouble())
        return Math.round(price * multiplier).toLong()
    }

    /** Format minor units back to a display string with the currency's natural
     *  decimal places (USD 2dp, JPY 0dp). Includes a thousands separator. */
    fun minorToDisplay(minor: Long, currencyCode: String): String {
        val dp = CURRENCY_DECIMAL_PLACES[currencyCode.uppercase()] ?: 2
        val divisor = Math.pow(10.0, dp.toDouble()).toLong()
        val isNegative = minor < 0
        val absMinor = if (isNegative) -minor else minor
        val whole = absMinor / divisor
        val fraction = absMinor % divisor
        val groupedWhole = groupThousands(whole)
        val sign = if (isNegative) "-" else ""
        return if (dp == 0) "$sign$groupedWhole"
        else "$sign$groupedWhole.%0${dp}d".format(fraction)
    }
}
