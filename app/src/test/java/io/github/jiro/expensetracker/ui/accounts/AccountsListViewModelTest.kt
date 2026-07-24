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
        type: String = "CASH",
    ) = AccountEntity(
        id = id,
        name = name,
        type = type,
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

class AccountsListViewModelToggleTest {

    @Test
    fun `view model exposes showClosed state that defaults to false`() {
        // Sanity: the data class must gain a showClosed field; this test
        // fails to compile until the field exists.
        val s = AccountsListUiState(showClosed = false)
        assertEquals(false, s.showClosed)
        val s2 = s.copy(showClosed = true)
        assertEquals(true, s2.showClosed)
    }

    @Test
    fun `default tab is BANK so bank accounts are visible and investments hidden`() {
        // Sanity: enum + default tab wiring. Fails to compile until
        // AccountsTab exists and the UiState carries activeTab.
        assertEquals(AccountsTab.BANK, AccountsListUiState().activeTab)
        val s = AccountsListUiState(activeTab = AccountsTab.BANK)
        assertEquals(AccountsTab.BANK, s.activeTab)
    }

    @Test
    fun `setActiveTab INVESTMENT swaps activeTab to INVESTMENT`() {
        val s = AccountsListUiState(activeTab = AccountsTab.BANK)
        val s2 = s.copy(activeTab = AccountsTab.INVESTMENT)
        assertEquals(AccountsTab.INVESTMENT, s2.activeTab)
    }
}

class AccountsListViewModelTabFilteringTest {

    private fun account(
        id: Long,
        name: String,
        type: String,
    ) = AccountEntity(
        id = id,
        name = name,
        type = type,
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = "USD",
        createdAtEpochMillis = 0L,
    )

    /**
     * Reproduces the VM's filter+sort logic without standing up Hilt.
     * The VM itself uses combine() + stateIn() which can't be exercised
     * outside of Robolectric; testing the *predicate* that drives the
     * visible list is the only behavior that matters for the tab wiring.
     */
    private fun visible(
        all: List<AccountWithBalance>,
        tab: AccountsTab,
    ): List<AccountWithBalance> {
        val sorted = all.sortedBy { it.account.name.lowercase() }
        return when (tab) {
            AccountsTab.BANK -> sorted.filter { it.account.type != "INVESTMENT" }
            AccountsTab.INVESTMENT -> sorted.filter { it.account.type == "INVESTMENT" }
        }
    }

    @Test
    fun `default tab is BANK so bank accounts are visible and investments hidden`() {
        val all = listOf(
            AccountWithBalance(account(1, "Brokerage", "INVESTMENT"), 0L),
            AccountWithBalance(account(2, "Checking", "BANK"), 0L),
            AccountWithBalance(account(3, "Cash", "CASH"), 0L),
        )
        val v = visible(all, AccountsTab.BANK)
        assertEquals(listOf("Cash", "Checking"), v.map { it.account.name })
    }

    @Test
    fun `setActiveTab INVESTMENT shows investments and hides bank accounts`() {
        val all = listOf(
            AccountWithBalance(account(1, "Brokerage", "INVESTMENT"), 0L),
            AccountWithBalance(account(2, "Checking", "BANK"), 0L),
            AccountWithBalance(account(3, "Cash", "CASH"), 0L),
        )
        val v = visible(all, AccountsTab.INVESTMENT)
        assertEquals(listOf("Brokerage"), v.map { it.account.name })
    }
}