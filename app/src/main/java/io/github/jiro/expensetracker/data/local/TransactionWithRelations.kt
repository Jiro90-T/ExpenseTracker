package io.github.jiro.expensetracker.data.local

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Joined view of a transaction with its account relations. Sibling to
 * [TransactionWithCategory]; use this when the UI needs the account name
 * (and especially when the account might be archived). Returned by
 * `@Transaction` DAO methods.
 *
 * Unlike [TransactionWithCategory], this projection joins both the primary
 * account AND the transfer account — used by the close-account feature to
 * keep historic transactions showing their (possibly-archived) account
 * labels.
 */
data class TransactionWithRelations(
    @Embedded val transaction: TransactionEntity,
    @Relation(
        parentColumn = "accountId",
        entityColumn = "id",
    )
    val account: AccountEntity?,
    @Relation(
        parentColumn = "transferAccountId",
        entityColumn = "id",
    )
    val transferAccount: AccountEntity?,
    @Relation(
        parentColumn = "categoryId",
        entityColumn = "id",
    )
    val category: CategoryEntity?,
)