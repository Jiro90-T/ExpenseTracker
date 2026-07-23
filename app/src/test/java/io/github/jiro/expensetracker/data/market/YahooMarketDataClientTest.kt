package io.github.jiro.expensetracker.data.market

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","regularMarketPrice":123.45,"regularMarketTime":1700000000}}],"error":null}}
        """.trimIndent()).setResponseCode(200))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[{"meta":{"currency":"JPY","symbol":"7203.T","regularMarketPrice":2800.0,"regularMarketTime":1700000000}}],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "7203.T"))
        assertEquals(2, result.size)
        val aapl = result[0]!!
        assertEquals("AAPL", aapl.symbol)
        assertEquals(12_345L, aapl.priceMinor)
        assertEquals("USD", aapl.currencyCode)
        assertEquals(1_700_000_000_000L, aapl.asOfEpochMillis)
        val toyota = result[1]!!
        assertEquals("7203.T", toyota.symbol)
        assertEquals(2_800L, toyota.priceMinor)
        assertEquals("JPY", toyota.currencyCode)
    }

    @Test fun unknownSymbol_returnsNullInPosition() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "ZZZZ"))
        assertEquals(2, result.size)
        assertNull(result[0])
        assertNull(result[1])
    }

    @Test fun serverError_returnsNullForThatSymbol() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server boom"))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","regularMarketPrice":123.45,"regularMarketTime":1}}],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("ZZZZ", "AAPL"))
        assertEquals(2, result.size)
        assertNull(result[0])
        assertNotNull(result[1])
    }

    @Test fun malformedJson_returnsNullForThatSymbol() = runTest {
        server.enqueue(MockResponse().setBody("not json at all").setResponseCode(200))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","regularMarketPrice":123.45,"regularMarketTime":1}}],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("BAD", "AAPL"))
        assertEquals(2, result.size)
        assertNull(result[0])
        assertNotNull(result[1])
    }

    @Test fun chartErrorObject_returnsNullForThatSymbol() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":null,"error":{"code":"Not Found","description":"symbol invalid"}}}
        """.trimIndent()).setResponseCode(200))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","regularMarketPrice":123.45,"regularMarketTime":1}}],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("ZZZZ", "AAPL"))
        assertEquals(2, result.size)
        assertNull(result[0])
        assertNotNull(result[1])
    }

    @Test fun requestUrl_isChartEndpoint_perSymbol() = runTest {
        repeat(2) {
            server.enqueue(MockResponse().setBody("""
                {"chart":{"result":[],"error":null}}
            """.trimIndent()).setResponseCode(200))
        }

        client.fetchQuotes(listOf("AAPL", "7203.T"))

        val first = server.takeRequest()
        assertEquals("/v8/finance/chart/AAPL?interval=1d&range=5d", first.path)
        val second = server.takeRequest()
        assertEquals("/v8/finance/chart/7203.T?interval=1d&range=5d", second.path)
    }

    @Test fun emptySymbols_returnsEmptyList() = runTest {
        val result = client.fetchQuotes(emptyList())
        assertEquals(0, result.size)
        assertEquals(0, server.requestCount)
    }
}
