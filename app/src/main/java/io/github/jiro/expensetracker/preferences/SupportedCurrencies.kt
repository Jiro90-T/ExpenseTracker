package io.github.jiro.expensetracker.preferences

/**
 * Currencies surfaced in the home currency dropdown. Sorted by likelihood
 * of use (regional default first, then major global currencies).
 */
internal val SUPPORTED_CURRENCIES: List<String> = listOf(
    "MYR", "SGD", "USD", "EUR", "GBP", "JPY", "CNY", "CAD", "AUD", "TWD",
)

/**
 * Common pairs surfaced as hints in the "Add rate" dialog. Used to
 * pre-populate a starter set; the user can still add any pair.
 */
internal val COMMON_CURRENCY_PAIRS: List<Pair<String, String>> = listOf(
    "USD" to "MYR",
    "USD" to "SGD",
    "USD" to "TWD",
    "USD" to "EUR",
    "USD" to "GBP",
    "USD" to "JPY",
    "USD" to "CNY",
)
