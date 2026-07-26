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
            {"result":"success","base_code":"USD","rates":{"MYR":4.45,"JPY":156.7,"EUR":0.91}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(3, map.size)
        assertEquals(4.45, map["USD_to_MYR"]!!, 0.0001)
        assertEquals(156.7, map["USD_to_JPY"]!!, 0.0001)
        assertEquals(0.91, map["USD_to_EUR"]!!, 0.0001)
    }

    @Test fun usdItself_isImplicit_notIncluded() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"result":"success","base_code":"USD","rates":{"USD":1.0,"MYR":4.45}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(1, map.size)
        assertNull("USD_to_USD should not be in map", map["USD_to_USD"])
        assertEquals(4.45, map["USD_to_MYR"]!!, 0.0001)
    }

    @Test fun nonPositiveRate_isSkipped() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"result":"success","base_code":"USD","rates":{"MYR":4.45,"BAD":-1.0,"ZERO":0.0}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(1, map.size)
        assertEquals(4.45, map["USD_to_MYR"]!!, 0.0001)
    }

    @Test fun nonThreeLetterCode_isSkipped() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"result":"success","base_code":"USD","rates":{"MYR":4.45,"ABCD":1.0,"EU":0.91}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(1, map.size)
        assertEquals(4.45, map["USD_to_MYR"]!!, 0.0001)
    }

    @Test fun noUsableRates_throws() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"result":"success","base_code":"USD","rates":{"USD":1.0}}
        """.trimIndent()).setResponseCode(200))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("no usable rates"))
    }

    @Test fun wrongBaseCode_throws() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"result":"success","base_code":"EUR","rates":{"MYR":4.45}}
        """.trimIndent()).setResponseCode(200))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("unexpected base_code"))
        assertTrue(ex.message!!.contains("EUR"))
    }

    @Test fun resultNotSuccess_throws() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"result":"failure","base_code":"USD"}
        """.trimIndent()).setResponseCode(200))

        val ex = runCatching { client.fetchLatestUsdRates() }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(FxRateFetchException::class.java, ex!!.javaClass)
        assertTrue(ex.message!!.contains("API result"))
        assertTrue(ex.message!!.contains("failure"))
    }

    @Test fun missingRatesObject_throws() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"result":"success","base_code":"USD"}
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

    @Test fun requestUrl_isLatestUsdPath() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"result":"success","base_code":"USD","rates":{"MYR":4.45}}
        """.trimIndent()).setResponseCode(200))

        client.fetchLatestUsdRates()

        val request = server.takeRequest()
        assertEquals("/v6/latest/USD", request.path)
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
            {"result":"success","base_code":"USD","rates":{"MYR":4.45}}
        """.trimIndent()).setResponseCode(200))

        ioClient.fetchLatestUsdRates()

        assertNotNull(callThreadName[0])
        assertTrue(
            "expected HTTP call to run off the test dispatcher; " +
                "test=$testThreadName client=${callThreadName[0]}",
            callThreadName[0] != testThreadName,
        )
    }

    @Test fun realisticOpenErApiResponse_parsesCorrectly() = runTest {
        // Captured from open.er-api.com on 2026-07-25 — representative sample
        // subset of the full payload to confirm shape compatibility.
        server.enqueue(MockResponse().setBody("""
            {"result":"success","provider":"https://www.exchangerate-api.com","documentation":"https://www.exchangerate-api.com/docs/free","terms_of_use":"https://www.exchangerate-api.com/terms","time_last_update_unix":1721846401,"time_last_update_utc":"Tue, 23 Jul 2024 00:00:01 +0000","time_next_update_unix":1721932801,"time_next_update_utc":"Wed, 24 Jul 2024 00:00:01 +0000","time_eol_unix":0,"base_code":"USD","rates":{"USD":1,"AED":3.673,"AFN":71.81,"MYR":4.448,"JPY":156.78,"EUR":0.916,"GBP":0.773}}
        """.trimIndent()).setResponseCode(200))

        val map = client.fetchLatestUsdRates()

        assertEquals(6, map.size)
        assertEquals(4.448, map["USD_to_MYR"]!!, 0.0001)
        assertEquals(156.78, map["USD_to_JPY"]!!, 0.0001)
        assertEquals(0.916, map["USD_to_EUR"]!!, 0.0001)
        assertEquals(0.773, map["USD_to_GBP"]!!, 0.0001)
    }
}
