package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.AccountRow
import io.github.jiro.expensetracker.backup.CategoryRow
import io.github.jiro.expensetracker.backup.TransactionRow
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupBodyTest {

    @Test
    fun roundTrip_preservesAccountsCategoriesAndTransactions() {
        val original = BackupBody(
            accounts = listOf(
                AccountRow(
                    id = 1L,
                    name = "Cash wallet",
                    type = "CASH",
                    icon = "💵",
                    color = 0xFF00FF00.toInt(),
                    currencyCode = "USD",
                    openingBalanceMinor = 0L,
                    createdAtEpochMillis = 1_700_000_000_000L,
                    archived = false,
                    archivedAtEpochMillis = null,
                    sortOrder = 0,
                ),
            ),
            categories = listOf(
                CategoryRow(id = 1L, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = true),
                CategoryRow(id = 2L, name = "Salary", type = "INCOME", sortOrder = 1, isBuiltIn = true),
            ),
            transactions = listOf(
                TransactionRow(
                    id = 10L,
                    title = "Groceries",
                    amountMinor = 1500L,
                    currencyCode = "USD",
                    type = "EXPENSE",
                    categoryId = 1L,
                    occurredAtEpochMillis = 1_710_000_000_000L,
                    note = "weekly",
                    createdAtEpochMillis = 1_710_000_000_000L,
                    recurringGroupId = null,
                    recurrenceKind = null,
                    recurrenceInterval = 1,
                    recurrenceEndAt = null,
                    recurrenceMaxOccurrences = null,
                    recurrenceNextAt = null,
                    receiptPath = null,
                    accountId = 1L,
                    transferAccountId = null,
                ),
            ),
        )
        val json = original.serialize()
        val decoded = BackupBody.deserialize(json)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTrip_handlesEmptyArrays() {
        val empty = BackupBody(accounts = emptyList(), categories = emptyList(), transactions = emptyList())
        val decoded = BackupBody.deserialize(empty.serialize())
        assertEquals(empty, decoded)
    }
}
