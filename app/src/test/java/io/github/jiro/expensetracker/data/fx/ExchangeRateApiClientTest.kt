package io.github.jiro.expensetracker.data.fx

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

class ExchangeRateApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ExchangeRateApiClient

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = ExchangeRateApiClient(
            httpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().removeSuffix("/") },
        )
    }

    @After fun teardown() { server.shutdown() }

    @Test fun happyPath_returnsUsdToXxxMap() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-08-02","rates":{"MYR":4.0865,"JPY":160.24,"EUR":0.8707}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(3, map.size)
        assertEquals(4.0865, map["USD_to_MYR"]!!, 0.0001)
        assertEquals(160.24, map["USD_to_JPY"]!!, 0.0001)
        assertEquals(0.8707, map["USD_to_EUR"]!!, 0.0001)
    }

    @Test fun usdItself_isImplicit_notIncluded() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-08-02","rates":{"USD":1.0,"MYR":4.0865}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(1, map.size)
        assertNull("USD_to_USD should not be in map", map["USD_to_USD"])
        assertEquals(4.0865, map["USD_to_MYR"]!!, 0.0001)
    }

    @Test fun nonPositiveRate_isSkipped() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-08-02","rates":{"MYR":4.0865,"BAD":-1.0,"ZERO":0.0}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(1, map.size)
        assertEquals(4.0865, map["USD_to_MYR"]!!, 0.0001)
    }

    @Test fun nonThreeLetterCode_isSkipped() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-08-02","rates":{"MYR":4.0865,"ABCD":1.0,"EU":0.91}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(1, map.size)
        assertEquals(4.0865, map["USD_to_MYR"]!!, 0.0001)
    }

    @Test fun noUsableRates_throws() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-08-02","rates":{"USD":1.0}}
        """.trimIndent()).setResponseCode(200))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("no usable rates"))
    }

    @Test fun wrongBaseCode_throws() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"EUR","date":"2026-08-02","rates":{"MYR":4.0865}}
        """.trimIndent()).setResponseCode(200))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("unexpected base"))
        assertTrue(ex.message!!.contains("EUR"))
    }

    @Test fun missingRatesObject_throws() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-08-02"}
        """.trimIndent()).setResponseCode(200))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("missing rates"))
    }

    @Test fun httpError_throwsWithStatusCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream unavailable"))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("503"))
    }

    @Test fun emptyBody_throws() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("empty body"))
    }

    @Test fun malformedJson_throws() = runTest {
        server.enqueue(MockResponse().setBody("not json at all").setResponseCode(200))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("parse"))
    }

    @Test fun requestUrl_isFrankfurterLatestUsdPath() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-08-02","rates":{"MYR":4.0865}}
        """.trimIndent()).setResponseCode(200))

        client.fetchLatestUsdRates()

        val request = server.takeRequest()
        assertEquals("/v1/latest?base=USD", request.path)
        assertEquals("GET", request.method)
    }

    @Test fun fetchLatestUsdRates_runsHttpOffTheTestDispatcherThread() = runTest {
        // Same rationale as YahooMarketDataClient: must not hit OkHttp's
        // blocking execute() from the Main dispatcher or Android throws
        // NetworkOnMainThreadException.
        val testThreadName = Thread.currentThread().name
        val callThreadName = arrayOfNulls<String>(1)
        val capturingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callThreadName[0] = Thread.currentThread().name
                chain.proceed(chain.request())
            }
            .build()
        val ioClient = ExchangeRateApiClient(
            httpClient = capturingClient,
            baseUrlProvider = { server.url("/").toString().removeSuffix("/") },
        )
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-08-02","rates":{"MYR":4.0865}}
        """.trimIndent()).setResponseCode(200))

        ioClient.fetchLatestUsdRates()

        assertNotNull(callThreadName[0])
        assertTrue(
            "expected HTTP call to run off the test dispatcher; " +
                "test=$testThreadName client=${callThreadName[0]}",
            callThreadName[0] != testThreadName,
        )
    }

    @Test fun realisticFrankfurterResponse_parsesCorrectly() = runTest {
        // Captured from api.frankfurter.dev on 2026-08-02 — representative
        // sample to confirm shape compatibility and that the user's needed
        // currencies (MYR, JPY, EUR, GBP, SGD, HKD) are present.
        server.enqueue(MockResponse().setBody("""
            {"amount":1.0,"base":"USD","date":"2026-07-31","rates":{"AUD":1.4249,"BRL":5.0583,"CAD":1.4041,"CHF":0.8101,"CNY":6.7513,"CZK":21.081,"DKK":6.5087,"EUR":0.8707,"GBP":0.74508,"HKD":7.8432,"HUF":317.15,"IDR":18052,"ILS":3.0574,"INR":95.39,"ISK":124.16,"JPY":160.24,"KRW":1443.61,"MXN":17.3715,"MYR":4.0865,"NOK":9.5272,"NZD":1.7056,"PHP":61.269,"PLN":3.7558,"RON":4.5683,"SEK":9.5651,"SGD":1.2849,"THB":33.465,"TRY":47.525,"ZAR":16.575}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(29, map.size)
        assertEquals(4.0865, map["USD_to_MYR"]!!, 0.0001)
        assertEquals(160.24, map["USD_to_JPY"]!!, 0.0001)
        assertEquals(0.8707, map["USD_to_EUR"]!!, 0.0001)
        assertEquals(0.74508, map["USD_to_GBP"]!!, 0.0001)
        assertEquals(1.2849, map["USD_to_SGD"]!!, 0.0001)
    }
}