package io.github.jiro.expensetracker.ui.accounts

import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.repository.AccountWithBalance
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountsListViewModelTest {

    private fun account(
        id: Long,
        name: String,
        currencyCode: String,
    ) = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = currencyCode,
        createdAtEpochMillis = 0L,
    )

    @Test
    fun `net balance in major units, single account, no FX needed`() {
        val result = computeNetBalanceInHome(
            accounts = listOf(AccountWithBalance(account(1, "Cash", "USD"), balanceMinor = 3500L)),
            homeCurrency = "USD",
            fxRates = emptyMap(),
        )
        assertEquals(35.0, result, 0.0001)
    }

    @Test
    fun `net balance sums multiple accounts in major units`() {
        val accounts = listOf(
            AccountWithBalance(account(1, "Cash", "USD"), balanceMinor = 3500L),
            AccountWithBalance(account(2, "Bank", "USD"), balanceMinor = -10000L),
        )
        val result = computeNetBalanceInHome(accounts, "USD", emptyMap())
        assertEquals(-65.0, result, 0.0001)
    }

    @Test
    fun `negative balance converts to negative major units`() {
        val result = computeNetBalanceInHome(
            accounts = listOf(AccountWithBalance(account(1, "Cash", "USD"), balanceMinor = -3500L)),
            homeCurrency = "USD",
            fxRates = emptyMap(),
        )
        assertEquals(-35.0, result, 0.0001)
    }

    @Test
    fun `net balance applies FX rate when converting across currencies`() {
        val accounts = listOf(
            AccountWithBalance(account(1, "EUR Cash", "EUR"), balanceMinor = 1000L),
        )
        val rates = mapOf("EUR_to_USD" to 1.1)
        val result = computeNetBalanceInHome(accounts, "USD", rates)
        assertEquals(11.0, result, 0.0001)
    }

    @Test
    fun `missing FX rate contributes zero rather than crashing`() {
        val accounts = listOf(
            AccountWithBalance(account(1, "JPY Cash", "JPY"), balanceMinor = 500000L),
        )
        val result = computeNetBalanceInHome(accounts, "USD", emptyMap())
        assertEquals(0.0, result, 0.0001)
    }

    @Test
    fun `empty accounts list yields zero`() {
        val result = computeNetBalanceInHome(emptyList(), "USD", emptyMap())
        assertEquals(0.0, result, 0.0001)
    }

    @Test
    fun `zero balance accounts produce zero net`() {
        val accounts = listOf(
            AccountWithBalance(account(1, "Empty", "USD"), balanceMinor = 0L),
        )
        val result = computeNetBalanceInHome(accounts, "USD", emptyMap())
        assertEquals(0.0, result, 0.0001)
    }
}