package io.github.jiro.expensetracker.ui.investments

import androidx.lifecycle.SavedStateHandle
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditHoldingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dao: FakeHoldingDao

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun vm(accountId: Long, holdingId: Long? = null) =
        AddEditHoldingViewModel(
            savedStateHandle = SavedStateHandle().apply {
                set(Routes.INVESTMENT_HOLDING_EDIT_ARG_ACCOUNT_ID, accountId)
                if (holdingId != null) set(Routes.INVESTMENT_HOLDING_EDIT_ARG_HOLDING_ID, holdingId)
            },
            holdingDao = dao,
        )

    @Test fun symbol_isUppercasedOnSave() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("aapl")
        v.onQuantityChange("10")
        v.onCostBasisChange("1500.00")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertEquals("AAPL", dao.lastInserted?.symbol)
    }

    @Test fun blankSymbol_saveFailsWithError() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("")
        v.onQuantityChange("10")
        v.onCostBasisChange("1500")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertNull(dao.lastInserted)
        assertEquals(HoldingFormError.SYMBOL_REQUIRED, v.state.value.error)
    }

    @Test fun overlongSymbol_saveFailsWithError() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("A".repeat(13))
        v.onQuantityChange("1")
        v.onCostBasisChange("100")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertNull(dao.lastInserted)
        assertEquals(HoldingFormError.SYMBOL_TOO_LONG, v.state.value.error)
    }

    @Test fun zeroQuantity_saveFails() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("AAPL")
        v.onQuantityChange("0")
        v.onCostBasisChange("100")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertNull(dao.lastInserted)
        assertEquals(HoldingFormError.QUANTITY_INVALID, v.state.value.error)
    }

    @Test fun dotTSuffix_infersJpy() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("7203.T")
        v.onQuantityChange("100")
        v.onCostBasisChange("200000")
        // Don't touch currency; the VM should default to JPY for .T suffix.
        v.save()
        advanceUntilIdle()
        assertEquals("JPY", dao.lastInserted?.currencyCode)
    }

    @Test fun nonDotTSuffix_defaultsToUsd() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("AAPL")
        v.onQuantityChange("10")
        v.onCostBasisChange("1500")
        v.save()
        advanceUntilIdle()
        assertEquals("USD", dao.lastInserted?.currencyCode)
    }

    @Test fun editMode_updatesExistingRow() = runTest(dispatcher) {
        dao = FakeHoldingDao(initial = InvestmentHoldingEntity(
            id = 7L, accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD", createdAtEpochMillis = 1L,
        ))
        val v = vm(accountId = 1L, holdingId = 7L)
        advanceUntilIdle()  // let init's load-existing-row coroutine finish
        v.onQuantityChange("12")
        v.save()
        advanceUntilIdle()
        assertEquals(12.0, dao.lastUpdated?.quantity!!, 0.0001)
        assertEquals(7L, dao.lastUpdated?.id)
    }

    @Test fun save_emitsSaveCompleteOnSuccess() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.onSymbolChange("AAPL")
        v.onQuantityChange("10")
        v.onCostBasisChange("1500")
        v.onCurrencyChange("USD")
        v.save()
        advanceUntilIdle()
        assertTrue(v.state.value.saveComplete)
    }

    @Test fun delete_removesRowAndSetsSaveComplete() = runTest(dispatcher) {
        dao = FakeHoldingDao(initial = InvestmentHoldingEntity(
            id = 42L, accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD", createdAtEpochMillis = 1L,
        ))
        val v = vm(accountId = 1L, holdingId = 42L)
        advanceUntilIdle()
        v.delete()
        advanceUntilIdle()
        assertNull(dao.findById(42L))
        assertTrue(v.state.value.saveComplete)
    }

    @Test fun delete_inAddMode_isNoOp() = runTest(dispatcher) {
        dao = FakeHoldingDao()
        val v = vm(accountId = 1L)
        v.delete()
        advanceUntilIdle()
        assertEquals(false, v.state.value.saveComplete)
    }
}

private class FakeHoldingDao(initial: InvestmentHoldingEntity? = null) : InvestmentHoldingDao {
    var lastInserted: InvestmentHoldingEntity? = null
    var lastUpdated: InvestmentHoldingEntity? = null
    private val rows = mutableMapOf<Long, InvestmentHoldingEntity>()
    init { if (initial != null) rows[initial.id] = initial }

    override suspend fun insert(row: InvestmentHoldingEntity): Long {
        val withId = row.copy(id = (rows.keys.maxOrNull() ?: 0L) + 1L)
        rows[withId.id] = withId
        lastInserted = withId
        return withId.id
    }
    override suspend fun update(row: InvestmentHoldingEntity) {
        rows[row.id] = row
        lastUpdated = row
    }
    override suspend fun delete(id: Long) { rows.remove(id) }
    override fun observeByAccount(accountId: Long) = kotlinx.coroutines.flow.flowOf(rows.values.filter { it.accountId == accountId })
    override suspend fun findById(id: Long) = rows[id]
    override suspend fun countByAccount(accountId: Long) = rows.values.count { it.accountId == accountId }
}
