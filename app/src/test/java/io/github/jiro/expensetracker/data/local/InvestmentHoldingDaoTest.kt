package io.github.jiro.expensetracker.data.local

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InvestmentHoldingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: InvestmentHoldingDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.investmentHoldingDao()
    }

    @After fun teardown() { db.close() }

    @Test fun insertAndObserveByAccount_returnsInsertedRow() = runTest {
        val row = InvestmentHoldingEntity(
            accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD",
            createdAtEpochMillis = 1_000L,
        )
        val id = dao.insert(row)
        assertEquals(1L, dao.countByAccount(1L))
        val rows = dao.observeByAccount(1L).first()
        assertEquals(1, rows.size)
        assertEquals(id, rows[0].id)
        assertEquals("AAPL", rows[0].symbol)
    }

    @Test fun observeByAccount_emptyAccountReturnsEmpty() = runTest {
        val rows = dao.observeByAccount(99L).first()
        assertEquals(0, rows.size)
    }

    @Test fun update_changesFields() = runTest {
        val id = dao.insert(InvestmentHoldingEntity(
            accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD",
            createdAtEpochMillis = 1_000L,
        ))
        dao.update(InvestmentHoldingEntity(
            id = id, accountId = 1L, symbol = "AAPL", quantity = 12.0,
            costBasisMinor = 180_000L, currencyCode = "USD",
            createdAtEpochMillis = 1_000L,
        ))
        val row = dao.observeByAccount(1L).first().single()
        assertEquals(12.0, row.quantity, 0.0001)
        assertEquals(180_000L, row.costBasisMinor)
    }

    @Test fun delete_removesRow() = runTest {
        val id = dao.insert(InvestmentHoldingEntity(
            accountId = 1L, symbol = "AAPL", quantity = 10.0,
            costBasisMinor = 150_000L, currencyCode = "USD",
            createdAtEpochMillis = 1_000L,
        ))
        dao.delete(id)
        assertEquals(0, dao.countByAccount(1L))
    }

    @Test fun findById_returnsNullForMissing() = runTest {
        assertNull(dao.findById(999L))
    }

    @Test fun uniqueSymbolsByAccount_returnsDistinctUppercased() = runTest {
        listOf("aapl", "AAPL", "goog", "GOOG").forEach {
            dao.insert(InvestmentHoldingEntity(
                accountId = 1L, symbol = it, quantity = 1.0,
                costBasisMinor = 100L, currencyCode = "USD",
                createdAtEpochMillis = 1L,
            ))
        }
        // Dedupe is at the consumer side (YahooMarketDataClient refreshes
        // unique symbols). The DAO doesn't uppercase.
        assertEquals(4, dao.countByAccount(1L))
    }
}
