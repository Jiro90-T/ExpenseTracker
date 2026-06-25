package io.github.jiro.expensetracker.domain.model

/**
 * Whether a transaction moves money in, out, or between accounts. Stored as
 * a String column in Room (via [name]) for forward-compatibility.
 *
 * - EXPENSE / INCOME — the historical kinds, both reference a category.
 * - TRANSFER — moves money between two accounts in a single row. Uses
 *   `accountId` (source) + `transferAccountId` (destination), no category.
 * - ADJUSTMENT — a manual balance correction created only via the
 *   "Adjust balance" dialog on Edit Account. No category, no transfer partner.
 */
enum class TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER,
    ADJUSTMENT;

    companion object {
        fun fromStorage(raw: String): TransactionType =
            entries.firstOrNull { it.name == raw } ?: EXPENSE
    }
}
