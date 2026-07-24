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
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","regularMarketPrice":123.45,"regularMarketTime":1}}],"error":null}}
        """.trimIndent()).setResponseCode(200))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL", "ZZZZ"))
        assertEquals(2, result.size)
        assertNotNull(result[0])
        assertNull(result[1])
    }

    @Test fun allSymbolsUnknown_throwsMarketDataException() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val ex = runCatching { client.fetchQuotes(listOf("AAPL", "ZZZZ")) }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(MarketDataException::class.java, ex!!.javaClass)
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
                {"chart":{"result":[{"meta":{"currency":"USD","symbol":"X","regularMarketPrice":1.0,"regularMarketTime":1}}],"error":null}}
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

    @Test fun allSymbolsFailed_throwsMarketDataException() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server boom"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("server boom"))

        val ex = runCatching { client.fetchQuotes(listOf("AAPL", "MSFT")) }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(MarketDataException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("All 2 symbols failed"))
        assertTrue(ex.message!!.contains("HTTP 500"))
    }

    @Test fun allSymbolsFailed_messageIncludesPerSymbolReasons() = runTest {
        // Mix of failure modes: 500 + chart.error.
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":null,"error":{"code":"Not Found","description":"symbol invalid"}}}
        """.trimIndent()).setResponseCode(200))

        val ex = runCatching { client.fetchQuotes(listOf("AAPL", "ZZZZ")) }.exceptionOrNull()
        assertNotNull(ex)
        val msg = ex!!.message!!
        assertTrue("expected AAPL reason in: $msg", msg.contains("AAPL: HTTP 500"))
        assertTrue("expected ZZZZ reason in: $msg", msg.contains("ZZZZ: chart.error: symbol invalid"))
    }

    @Test fun realisticYahooResponse_parsesCorrectly() = runTest {
        // Captured from query1.finance.yahoo.com on 2026-07-24 for AAPL.
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","exchangeName":"NMS","fullExchangeName":"NasdaqGS","instrumentType":"EQUITY","firstTradeDate":345479400,"regularMarketTime":1784903522,"hasPrePostMarketData":true,"gmtoffset":-14400,"timezone":"EDT","exchangeTimezoneName":"America/New_York","regularMarketPrice":328.879,"fiftyTwoWeekHigh":334.99,"fiftyTwoWeekLow":201.5,"regularMarketDayHigh":329.439,"regularMarketDayLow":321.62,"regularMarketVolume":8731307,"longName":"Apple Inc.","shortName":"Apple Inc.","chartPreviousClose":333.74,"priceHint":2,"currentTradingPeriod":{"pre":{"timezone":"EDT","end":1784899800,"start":1784880000,"gmtoffset":-14400},"regular":{"timezone":"EDT","end":1784923200,"start":1784899800,"gmtoffset":-14400},"post":{"timezone":"EDT","end":1784937600,"start":1784923200,"gmtoffset":-14400}},"dataGranularity":"1d","range":"5d","validRanges":["1d","5d","1mo","3mo","6mo","1y","2y","5y","10y","ytd","max"]},"timestamp":[1784554200,1784640600,1784727000,1784813400,1784899800],"indicators":{"quote":[{"open":[333.510009765625,323.1300048828125,327.8699951171875,321.7300109863281,322.0400085449219],"volume":[53468000,41338900,38755900,40812200,8731307],"close":[326.5899963378906,327.739990234375,325.8900146484375,321.6600036621094,328.87921142578125],"high":[333.7099914550781,329.6000061035156,329.0,323.29998779296875,329.4389953613281],"low":[323.67999267578125,322.2200012207031,323.3399963378906,319.3500061950683,321.6199951171875]}],"adjclose":[{"adjclose":[326.5899963378906,327.739990234375,325.8900146484375,321.6600036621094,328.87921142578125]}]}}],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("AAPL"))
        assertEquals(1, result.size)
        val q = result[0]!!
        assertEquals("AAPL", q.symbol)
        assertEquals("USD", q.currencyCode)
        // 328.879 → 32888 minor (rounded). Yahoo's full-precision price is
        // 328.87921142578125 which rounds to 32888.
        assertEquals(32_888L, q.priceMinor)
        assertEquals(1_784_903_522_000L, q.asOfEpochMillis)
    }

    @Test fun partialFailure_succeedsAndReturnsNulls_noThrow() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server boom"))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL","regularMarketPrice":123.45,"regularMarketTime":1}}],"error":null}}
        """.trimIndent()).setResponseCode(200))

        val result = client.fetchQuotes(listOf("ZZZZ", "AAPL"))
        assertEquals(2, result.size)
        assertNull(result[0])
        assertNotNull(result[1])
    }
}
