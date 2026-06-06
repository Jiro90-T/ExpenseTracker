package io.github.jiro.expensetracker.domain.model

/**
 * Whether a transaction moves money in or out. Stored as a String column in
 * Room (via [name]) so a future third variant (e.g. TRANSFER) doesn't require
 * a schema migration.
 */
enum class TransactionType {
    EXPENSE,
    INCOME;

    companion object {
        fun fromStorage(raw: String): TransactionType =
            entries.firstOrNull { it.name == raw } ?: EXPENSE
    }
}
