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
}
