package io.github.jiro.expensetracker.sync.dropbox

import kotlinx.coroutines.runBlocking
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

class DropboxApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DropboxApiClientImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        client = DropboxApiClientImpl(
            httpClient = OkHttpClient(),
            tokensProvider = { FIXED_TOKENS },
            contentHost = baseUrl,
            apiHost = baseUrl,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun upload_createsFile_returnsNewRev() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"rev": "abc123"}"""),
        )
        val rev = client.upload(existingRev = null, body = "{}")
        assertEquals("abc123", rev)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(
            "Expected /2/files/upload path, got ${req.path}",
            req.path!!.startsWith("/2/files/upload"),
        )
        // The Dropbox-API-Arg header is a JSON string with mode=overwrite when
        // existingRev is null.
        val arg = req.getHeader("Dropbox-API-Arg")
        assertNotNull(arg)
        assertTrue(arg!!.contains("\"mode\":\"overwrite\""))
        assertTrue(arg.contains("\"/ExpenseTracker-sync.json\""))
        assertEquals("Bearer test-access", req.getHeader("Authorization"))
        assertEquals("{}", req.body.readUtf8())
    }

    @Test
    fun upload_updatesFile_returnsUpdatedRev() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"rev": "def456"}"""),
        )
        val rev = client.upload(existingRev = "abc123", body = "{}")
        assertEquals("def456", rev)

        val req = server.takeRequest()
        val arg = req.getHeader("Dropbox-API-Arg")
        assertTrue(
            "Expected mode=update with rev abc123, got $arg",
            arg!!.contains("\"update\":\"abc123\""),
        )
    }

    @Test
    fun download_returnsBody_onSuccess() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"hello":"world"}"""),
        )
        val body = client.download()
        assertEquals("""{"hello":"world"}""", body)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!.startsWith("/2/files/download"))
        val arg = req.getHeader("Dropbox-API-Arg")
        assertTrue(arg!!.contains("\"/ExpenseTracker-sync.json\""))
    }

    @Test
    fun download_returnsNull_on404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.download())
    }

    @Test
    fun download_returnsNull_on409_pathNotFound() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody(
                    """{"error_summary": "path/not_found/...", "error": {".tag": "path", "path": {".tag": "not_found"}}}""",
                ),
        )
        assertNull(client.download())
    }

    @Test
    fun download_throwsAuthRevoked_on401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client.download()
            fail("Expected AuthRevoked")
        } catch (e: DropboxApiException.AuthRevoked) {
            // expected
        }
    }

    @Test
    fun download_retriesWithBackoff_on429_thenSucceeds() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0"), // 0 → no real sleep
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}"""),
        )
        val body = client.download()
        assertEquals("""{"ok":true}""", body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun download_throwsServerError_afterRetries_exhausted_on500() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        try {
            client.download()
            fail("Expected ServerError")
        } catch (e: DropboxApiException.ServerError) {
            assertTrue("Expected at least 3 attempts, got ${server.requestCount}", server.requestCount >= 3)
        }
    }

    private companion object {
        val FIXED_TOKENS = DropboxSyncTokens(
            accessToken = "test-access",
            refreshToken = null,
            expiresAtEpochMillis = 0L,
            accountEmail = "test@example.com",
            snapshotRev = null,
        )
    }
}