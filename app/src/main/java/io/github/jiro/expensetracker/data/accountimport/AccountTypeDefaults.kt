package io.github.jiro.expensetracker.data.accountimport

/**
 * Type-specific icon + color used when auto-creating accounts from a CSV import.
 * Unknown types fall back to the same defaults a freshly created account gets
 * in AddEditAccountViewModel (💵, blue) — visually identical.
 */
object AccountTypeDefaults {

    private val ICON_BY_TYPE = mapOf(
        "CASH" to "💵",
        "BANK" to "🏦",
        "CREDIT_CARD" to "💳",
        "EWALLET" to "📱",
        "OTHER" to "💰",
    )

    private val COLOR_BY_TYPE = mapOf(
        "CASH" to 0xFF43A047.toInt(),         // green
        "BANK" to 0xFF1976D2.toInt(),         // blue
        "CREDIT_CARD" to 0xFFC62828.toInt(),  // red
        "EWALLET" to 0xFFF57C00.toInt(),      // orange
        "OTHER" to 0xFF455A64.toInt(),        // slate
    )

    private const val FALLBACK_ICON = "💵"
    private const val FALLBACK_COLOR = 0xFF1976D2.toInt()  // blue, matches AddEditAccountViewModel

    fun iconFor(type: String): String =
        ICON_BY_TYPE[type.uppercase()] ?: FALLBACK_ICON

    fun colorFor(type: String): Int =
        COLOR_BY_TYPE[type.uppercase()] ?: FALLBACK_COLOR
}