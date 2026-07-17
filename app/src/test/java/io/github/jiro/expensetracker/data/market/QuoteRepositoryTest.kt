package io.github.jiro.expensetracker.data.market

import androidx.room.Room
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.data.local.CachedQuoteDao
import io.github.jiro.expensetracker.data.local.CachedQuoteEntity
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
class QuoteRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var cacheDao: CachedQuoteDao
    private lateinit var fakeClient: FakeMarketDataClient
    private lateinit var repo: QuoteRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        cacheDao = db.cachedQuoteDao()
        fakeClient = FakeMarketDataClient()
        repo = QuoteRepository(fakeClient, cacheDao)
    }

    @After fun teardown() { db.close() }

    @Test fun refresh_writesFreshQuotesToCache() = runTest {
        fakeClient.respondWith(listOf(
            quote("AAPL", 12_345L, "USD"),
            quote("GOOG", 50_000L, "USD"),
        ))
        val outcome = repo.refresh(listOf("AAPL", "GOOG"))
        assertEquals(SymbolOutcome.Fresh, outcome.perSymbol["AAPL"])
        assertEquals(SymbolOutcome.Fresh, outcome.perSymbol["GOOG"])
        assertEquals(12_345L, cacheDao.findBySymbol("AAPL")?.priceMinor)
        assertEquals(50_000L, cacheDao.findBySymbol("GOOG")?.priceMinor)
    }

    @Test fun refresh_unknownSymbol_doesNotTouchCache() = runTest {
        cacheDao.upsert(CachedQuoteEntity("AAPL", 99_999L, "USD", 1L))
        fakeClient.respondWith(listOf(null))
        val outcome = repo.refresh(listOf("AAPL"))
        assertEquals(SymbolOutcome.Unknown, outcome.perSymbol["AAPL"])
        // Cache preserved with old timestamp.
        val row = cacheDao.findBySymbol("AAPL")!!
        assertEquals(99_999L, row.priceMinor)
        assertEquals(1L, row.fetchedAtEpochMillis)
    }

    @Test fun refresh_transportFailure_doesNotTouchCache() = runTest {
        cacheDao.upsert(CachedQuoteEntity("AAPL", 99_999L, "USD", 1L))
        fakeClient.failNext = true
        try { repo.refresh(listOf("AAPL")) } catch (_: MarketDataException) {}
        val row = cacheDao.findBySymbol("AAPL")!!
        assertEquals(99_999L, row.priceMinor)
    }

    @Test fun refresh_partialResponse_marksUnknownOnly() = runTest {
        fakeClient.respondWith(listOf(quote("AAPL", 12_345L, "USD"), null))
        val outcome = repo.refresh(listOf("AAPL", "ZZZZ"))
        assertEquals(SymbolOutcome.Fresh, outcome.perSymbol["AAPL"])
        assertEquals(SymbolOutcome.Unknown, outcome.perSymbol["ZZZZ"])
        assertEquals(12_345L, cacheDao.findBySymbol("AAPL")?.priceMinor)
        assertNull(cacheDao.findBySymbol("ZZZZ"))
    }

    @Test fun refresh_overwritesPreviousCacheEntry() = runTest {
        cacheDao.upsert(CachedQuoteEntity("AAPL", 99_999L, "USD", 1L))
        fakeClient.respondWith(listOf(quote("AAPL", 12_345L, "USD")))
        repo.refresh(listOf("AAPL"))
        assertEquals(12_345L, cacheDao.findBySymbol("AAPL")?.priceMinor)
    }

    @Test fun observeAllCached_emitsForRequestedSymbols() = runTest {
        cacheDao.upsert(CachedQuoteEntity("AAPL", 12_345L, "USD", 1L))
        cacheDao.upsert(CachedQuoteEntity("GOOG", 50_000L, "USD", 1L))
        val map = repo.observeAllCached(listOf("AAPL", "MISSING")).first()
        assertEquals(1, map.size)
        assertEquals(12_345L, map["AAPL"]?.priceMinor)
    }
}

private fun quote(symbol: String, priceMinor: Long, currency: String) =
    Quote(symbol, priceMinor, currency, asOfEpochMillis = 0L)

private class FakeMarketDataClient : MarketDataClient {
    var nextResponse: List<Quote?> = emptyList()
    var failNext: Boolean = false

    fun respondWith(q: List<Quote?>) { nextResponse = q; failNext = false }

    override suspend fun fetchQuotes(symbols: List<String>): List<Quote?> {
        if (failNext) throw MarketDataException("fake failure")
        return nextResponse
    }
}