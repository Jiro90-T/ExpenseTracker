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
}
