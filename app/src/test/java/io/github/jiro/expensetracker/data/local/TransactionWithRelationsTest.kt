package io.github.jiro.expensetracker.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionWithRelationsTest {

    private fun txn(
        id: Long = 1L,
        accountId: Long = 10L,
        transferAccountId: Long? = null,
        categoryId: Long? = null,
    ) = TransactionEntity(
        id = id,
        title = "Coffee",
        amountMinor = 350,
        currencyCode = "USD",
        type = "EXPENSE",
        categoryId = categoryId,
        accountId = accountId,
        transferAccountId = transferAccountId,
        occurredAtEpochMillis = 1_700_000_000_000L,
        createdAtEpochMillis = 1_700_000_000_000L,
    )

    private fun account(id: Long, name: String, archived: Boolean = false) = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = "USD",
        openingBalanceMinor = 0L,
        createdAtEpochMillis = 0L,
        archived = archived,
    )

    @Test fun holdsAccountAndCategoryReferences() {
        val row = TransactionWithRelations(
            transaction = txn(categoryId = 5L),
            account = account(10L, "Checking"),
            transferAccount = null,
            category = CategoryEntity(id = 5L, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = false),
        )
        assertEquals("Checking", row.account?.name)
        assertEquals("Food", row.category?.name)
        assertNull(row.transferAccount)
    }

    @Test fun closedAccountRoundTripsViaProjection() {
        val closed = account(11L, "Old Checking", archived = true)
        val row = TransactionWithRelations(
            transaction = txn(accountId = 11L),
            account = closed,
            transferAccount = null,
            category = null,
        )
        assertEquals("Old Checking", row.account?.name)
        assertTrue(row.account!!.archived)
    }

    @Test fun transferAccountIsResolvedIndependently() {
        val row = TransactionWithRelations(
            transaction = txn(accountId = 1L, transferAccountId = 2L),
            account = account(1L, "From"),
            transferAccount = account(2L, "To", archived = true),
            category = null,
        )
        assertEquals("From", row.account?.name)
        assertEquals("To", row.transferAccount?.name)
        assertTrue(row.transferAccount!!.archived)
    }
}