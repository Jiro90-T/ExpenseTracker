package io.github.jiro.expensetracker.data.market

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class YahooMarketDataClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: YahooMarketDataClient

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = YahooMarketDataClient(
            httpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().removeSuffix("/") },
        )
    }

    @After fun teardown() { server.shutdown() }

    @Test fun happyPath_returnsAllRequestedQuotes() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "quoteResponse": {
                "result": [
                  {"symbol":"AAPL","regularMarketPrice":123.45,"currency":"USD","regularMarketTime":1700000000,"marketState":"REGULAR"},
                  {"symbol":"7203.T","regularMarketPrice":2800.0,"currency":"JPY","regularMarketTime":1700000000,"marketState":"CLOSED"}
                ],
                "error": null
              }
            }
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "7203.T"))
        assertEquals(2, result.size)
        val aapl = result[0]!!
        assertEquals("AAPL", aapl.symbol)
        assertEquals(12_345L, aapl.priceMinor)   // 123.45 USD × 100
        assertEquals("USD", aapl.currencyCode)
        assertEquals(1_700_000_000_000L, aapl.asOfEpochMillis)
        val toyota = result[1]!!
        assertEquals("7203.T", toyota.symbol)
        assertEquals(2800L, toyota.priceMinor)    // 2800.0 JPY × 1 (0dp)
        assertEquals("JPY", toyota.currencyCode)
    }

    @Test fun unknownSymbol_returnsNullInPosition() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "quoteResponse": {
                "result": [
                  {"symbol":"AAPL","regularMarketPrice":123.45,"currency":"USD","regularMarketTime":1,"marketState":"REGULAR"}
                ],
                "error": null
              }
            }
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "ZZZZ"))
        assertEquals(2, result.size)
        assertNotNull(result[0])
        assertNull(result[1])
    }

    @Test fun emptyResultArray_allSymbolsReturnNull() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"quoteResponse":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "GOOG"))
        assertEquals(2, result.size)
        assertNull(result[0])
        assertNull(result[1])
    }

    @Test fun serverError_throwsMarketDataException() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server boom"))
        try {
            client.fetchQuotes(listOf("AAPL"))
            fail("expected MarketDataException")
        } catch (e: MarketDataException) {
            // ok
        }
    }

    @Test fun malformedJson_throwsMarketDataException() = runTest {
        server.enqueue(MockResponse().setBody("not json at all").setResponseCode(200))
        try {
            client.fetchQuotes(listOf("AAPL"))
            fail("expected MarketDataException")
        } catch (e: MarketDataException) {
            // ok
        }
    }

    @Test fun validJsonWrongShape_throwsMarketDataException() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"finance":{"error":{"code":"Unauthorized","description":"Invalid CrumbStore"}}}
        """.trimIndent()).setResponseCode(200))
        try {
            client.fetchQuotes(listOf("AAPL"))
            fail("expected MarketDataException")
        } catch (e: MarketDataException) {
            // ok
        }
    }

    @Test fun requestUrl_includesAllSymbols() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"quoteResponse":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))
        client.fetchQuotes(listOf("AAPL", "GOOG", "MSFT"))
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("symbols=AAPL%2CGOOG%2CMSFT") ||
                   request.path!!.contains("symbols=AAPL,GOOG,MSFT"))
    }
}