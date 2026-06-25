package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the balance formula as a pure function over a fixed list of accounts
 * and transactions. The actual SQL query (in [AccountDao.observeBalances])
 * mirrors this; if it diverges, the migration test in Task 16 catches that.
 */
class AccountBalanceFormulaTest {

    private val now = 1_700_000_000_000L

    private fun txn(
        type: TransactionType,
        accountId: Long,
        amountMinor: Long,
        transferAccountId: Long? = null,
    ) = TransactionEntity(
        title = "t",
        amountMinor = amountMinor,
        currencyCode = "USD",
        type = type.name,
        accountId = accountId,
        transferAccountId = transferAccountId,
        occurredAtEpochMillis = now,
        createdAtEpochMillis = now,
    )

    private fun acct(id: Long, opening: Long = 0L) =
        AccountEntity(
            id = id,
            name = "A$id",
            type = "CASH",
            icon = "💵",
            color = 0,
            currencyCode = "USD",
            openingBalanceMinor = opening,
            createdAtEpochMillis = now,
        )

    private fun balanceOf(
        targetId: Long,
        accounts: List<AccountEntity>,
        txns: List<TransactionEntity>,
    ): Long {
        val account = accounts.first { it.id == targetId }
        val opening = account.openingBalanceMinor
        val income = txns.filter {
            it.accountId == targetId && it.type == "INCOME"
        }.sumOf { it.amountMinor }
        val expense = txns.filter {
            it.accountId == targetId && it.type == "EXPENSE"
        }.sumOf { it.amountMinor }
        val adjustment = txns.filter {
            it.accountId == targetId && it.type == "ADJUSTMENT"
        }.sumOf { it.amountMinor }
        val onAccount = income - expense + adjustment
        val out = txns.filter {
            it.accountId == targetId && it.type == "TRANSFER"
        }.sumOf { it.amountMinor }
        val `in` = txns.filter {
            it.transferAccountId == targetId && it.type == "TRANSFER"
        }.sumOf { it.amountMinor }
        return opening + onAccount - out + `in`
    }

    @Test fun `opening balance alone`() {
        val a = listOf(acct(1, opening = 5000L))
        assertEquals(5000L, balanceOf(1, a, emptyList()))
    }

    @Test fun `opening plus income`() {
        val a = listOf(acct(1, opening = 100L))
        val t = listOf(txn(TransactionType.INCOME, 1, 900L))
        assertEquals(1000L, balanceOf(1, a, t))
    }

    @Test fun `opening plus expense reduces balance`() {
        val a = listOf(acct(1, opening = 1000L))
        val t = listOf(txn(TransactionType.EXPENSE, 1, 250L))
        assertEquals(750L, balanceOf(1, a, t))
    }

    @Test fun `transfer out subtracts from source`() {
        val a = listOf(acct(1, opening = 1000L), acct(2, opening = 0L))
        val t = listOf(txn(TransactionType.TRANSFER, 1, 200L, transferAccountId = 2))
        assertEquals(800L, balanceOf(1, a, t))
        assertEquals(200L, balanceOf(2, a, t))
    }

    @Test fun `transfer in adds to destination`() {
        val a = listOf(acct(1, opening = 1000L), acct(2, opening = 0L))
        val t = listOf(txn(TransactionType.TRANSFER, 1, 200L, transferAccountId = 2))
        assertEquals(200L, balanceOf(2, a, t))
    }

    @Test fun `transfer out and in net to opening`() {
        val a = listOf(acct(1, opening = 500L), acct(2, opening = 500L))
        val t = listOf(txn(TransactionType.TRANSFER, 1, 100L, transferAccountId = 2))
        assertEquals(500L, balanceOf(1, a, t))
        assertEquals(500L, balanceOf(2, a, t))
    }

    @Test fun `adjustment adds directly to balance`() {
        val a = listOf(acct(1, opening = 100L))
        val t = listOf(txn(TransactionType.ADJUSTMENT, 1, -30L))
        assertEquals(70L, balanceOf(1, a, t))
    }

    @Test fun `credit card negative balance`() {
        val a = listOf(acct(1, opening = 0L, ))
        val t = listOf(txn(TransactionType.EXPENSE, 1, 432L))
        assertEquals(-432L, balanceOf(1, a, t))
    }
}