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
class CachedQuoteDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CachedQuoteDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.cachedQuoteDao()
    }

    @After fun teardown() { db.close() }

    @Test fun upsert_writesNewRow() = runTest {
        dao.upsert(CachedQuoteEntity("AAPL", 12345L, "USD", 1_000L))
        assertEquals(12345L, dao.findBySymbol("AAPL")?.priceMinor)
    }

    @Test fun upsert_overwritesExistingRowBySymbol() = runTest {
        dao.upsert(CachedQuoteEntity("AAPL", 12345L, "USD", 1_000L))
        dao.upsert(CachedQuoteEntity("AAPL", 13000L, "USD", 2_000L))
        val row = dao.findBySymbol("AAPL")!!
        assertEquals(13000L, row.priceMinor)
        assertEquals(2_000L, row.fetchedAtEpochMillis)
    }

    @Test fun findBySymbol_missingReturnsNull() = runTest {
        assertNull(dao.findBySymbol("MISSING"))
    }

    @Test fun observeBySymbols_emitsRowsForRequestedSymbols() = runTest {
        dao.upsert(CachedQuoteEntity("AAPL", 12345L, "USD", 1L))
        dao.upsert(CachedQuoteEntity("GOOG", 50000L, "USD", 1L))
        val rows = dao.observeBySymbols(listOf("AAPL", "GOOG", "MISSING")).first()
        assertEquals(2, rows.size)
        val aapl = rows.single { it.symbol == "AAPL" }
        val goog = rows.single { it.symbol == "GOOG" }
        assertEquals(12345L, aapl.priceMinor)
        assertEquals(50000L, goog.priceMinor)
    }

    @Test fun findBySymbols_returnsRowsForProvidedSymbols() = runTest {
        dao.upsert(CachedQuoteEntity("AAPL", 12345L, "USD", 1L))
        dao.upsert(CachedQuoteEntity("GOOG", 50000L, "USD", 1L))
        val rows = dao.findBySymbols(listOf("AAPL", "MISSING"))
        assertEquals(1, rows.size)
        assertEquals("AAPL", rows[0].symbol)
    }
}
