package io.github.jiro.expensetracker.sync.google

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DriveApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DriveApiClientImpl
    private val token = "fake-access-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        client = DriveApiClientImpl(
            httpClient = OkHttpClient(),
            tokens = FakeDriveSyncTokensRepository(initial = SyncTokens(
                accessToken = token,
                refreshToken = "fake-refresh",
                expiresAtEpochMillis = 0L,
                accountEmail = "test@example.com",
                snapshotFileId = null,
            )),
            baseUrl = baseUrl,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun upload_createsFile_whenFileIdNull() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"abc123"}"""),
        )
        val id = client.upload(fileId = null, body = "snapshot-json", mimeType = "application/json")
        assertEquals("abc123", id)
        val req: RecordedRequest = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.startsWith("/upload/drive/v3/files"))
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        val ct = req.getHeader("Content-Type")!!
        assertTrue("Content-Type was $ct", ct.startsWith("multipart/related"))
    }

    @Test
    fun upload_patchesFile_whenFileIdSet() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"abc123"}"""),
        )
        val id = client.upload(fileId = "abc123", body = "snapshot-json", mimeType = "application/json")
        assertEquals("abc123", id)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertTrue(req.path!!.startsWith("/upload/drive/v3/files/abc123"))
    }

    @Test
    fun download_returnsBody_whenHttp200() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"hello":"world"}"""),
        )
        val body = client.download("abc123")
        assertEquals("""{"hello":"world"}""", body)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/drive/v3/files/abc123?alt=media", req.path)
    }

    @Test
    fun download_returnsNull_whenHttp404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val body = client.download("missing")
        assertNull(body)
    }

    @Test
    fun upload_throwsAuthRevoked_onHttp401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client.upload(fileId = null, body = "x", mimeType = "application/json")
            fail("Expected DriveApiException.AuthRevoked")
        } catch (e: DriveApiException.AuthRevoked) {
            // expected
        }
    }

    @Test
    fun upload_retriesOnHttp429_thenSucceeds() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"after-retry"}"""),
        )
        val id = client.upload(fileId = null, body = "x", mimeType = "application/json")
        assertEquals("after-retry", id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun upload_givesUpAfter3Retries_onHttp429() = runBlocking {
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "0"),
            )
        }
        try {
            client.upload(fileId = null, body = "x", mimeType = "application/json")
            fail("Expected DriveApiException.RateLimited")
        } catch (e: DriveApiException.RateLimited) {
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun upload_givesUpAfter1Retry_onHttp500() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            client.upload(fileId = null, body = "x", mimeType = "application/json")
            fail("Expected DriveApiException.ServerError")
        } catch (e: DriveApiException.ServerError) {
            assertEquals(2, server.requestCount) // original + 1 retry
        }
    }

    @Test
    fun upload_throwsAuthRevoked_whenTokenRepoReturnsNull() = runBlocking {
        client = DriveApiClientImpl(
            httpClient = OkHttpClient(),
            tokens = FakeDriveSyncTokensRepository(initial = null),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        try {
            client.upload(fileId = null, body = "x", mimeType = "application/json")
            fail("Expected DriveApiException.AuthRevoked")
        } catch (e: DriveApiException.AuthRevoked) {
            // expected
        }
    }
}
