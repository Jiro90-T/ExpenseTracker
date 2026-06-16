package io.github.jiro.expensetracker.preferences

/**
 * Currencies surfaced in the home currency dropdown. Sorted by likelihood
 * of use (regional default first, then major global currencies).
 */
internal val SUPPORTED_CURRENCIES: List<String> = listOf(
    "MYR", "SGD", "USD", "EUR", "GBP", "JPY", "CNY", "CAD", "AUD", "TWD",
)
