# Phase 4c — Dropbox Provider — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `GoogleDriveCloudSyncRepository` (4b) with `DropboxCloudSyncRepository`, wire OAuth via AppAuth-Android (PKCE), persist tokens in SharedPreferences + Android Keystore AES-GCM, and drive the user's Dropbox App folder via OkHttp + Dropbox HTTP API v2 — flipping a single `@Binds` line in `SyncModule`.

**Architecture:** New `sync/dropbox/` sub-package mirroring `sync/google/`. Reuses the `CloudSyncRepository` interface (widened in 4b with `signInIntent` + `handleSignInResult`). `DropboxApiClient` (OkHttp, RPC-style with `Dropbox-API-Arg` header) and `DropboxSyncTokensRepository` (SharedPreferences + Keystore) are isolated behind small interfaces. AppAuth-Android 0.11.1 handles the OAuth flow via PKCE; tokens are bridged into our Keystore-protected store (AppAuth uses `NoopTokenStore` so it never persists plaintext). All I/O runs on `Dispatchers.IO`; `state` is a single `MutableStateFlow<SyncState>` guarded by a `Mutex`.

**Tech Stack:** Kotlin, Hilt, kotlinx-coroutines, OkHttp 4.12 (added in 4b), AppAuth-Android 0.11.1, Android Keystore (`KeyGenParameterSpec` + AES/GCM/NoPadding), `org.json`, Robolectric, OkHttp `MockWebServer`, JUnit 4. JDK 21 at `C:/tools/jdk-21.0.5+11`. Author commits as `MiniMax-M3 <291324429+Jiro90-T@users.noreply.github.com>` via `-c user.name=` / `-c user.email=` flags (never amend, no `Co-Authored-By:` trailer).

---

## File Structure

| File | Action | Responsibility |
| --- | --- | --- |
| `gradle/libs.versions.toml` | modify | Add `appauth` version + library entry. |
| `app/build.gradle.kts` | modify | Add `appauth` dep + `buildConfigField` for `DROPBOX_CLIENT_ID` (read from `local.properties`). |
| `app/src/main/AndroidManifest.xml` | modify | Add deep-link intent-filter for `io.github.jiro.expensetracker` scheme, host `oauth2redirect`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxAuth.kt` | new | Interface: `buildAuthIntent`, `handleAuthResult`, `getLastAuthState`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxAccountSnapshot.kt` | new | Data class: `email`, `accessToken`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/AppAuthDropboxAuth.kt` | new | AppAuth-Android wrapper. PKCE flow, `NoopTokenStore`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClient.kt` | new | Interface: `upload(existingRev, body): String`, `download(): String?`, `getRev(): String?`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiException.kt` | new | Sealed `RuntimeException` with `AuthRevoked`, `NotFound`, `Conflict`, `RateLimited`, `ServerError`, `Generic`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt` | new | OkHttp impl: POST `/2/files/upload` with `Dropbox-API-Arg` header, GET `/2/files/download`, POST `/2/files/get_metadata`. 429/5xx retry via `Retry-After` header. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokens.kt` | new | Data class: `accessToken`, `refreshToken`, `expiresAtEpochMillis`, `accountEmail`, `snapshotRev`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/TokenCrypto.kt` | new | Interface (extracted because Robolectric 4.11.1 doesn't implement AndroidKeyStore — see 4b Task 4). |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/KeystoreTokenCrypto.kt` | new | AES-256-GCM Keystore impl. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokensRepository.kt` | new | Interface + impl. SharedPreferences + TokenCrypto. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepository.kt` | new | Orchestrator. Implements `CloudSyncRepository`. Owns `Mutex` + `MutableStateFlow`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/di/DropboxModule.kt` | new | Hilt `@Module` providing `OkHttpClient` (separate scope from Drive's), `DropboxAuth`, `DropboxApiClient`, `DropboxSyncTokensRepository`, `TokenCrypto`. |
| `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt` | modify | Replace `bindCloudSyncRepository(impl: GoogleDriveCloudSyncRepository)` with `bindCloudSyncRepository(impl: DropboxCloudSyncRepository)`. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxAuth.kt` | new | Test fake for `DropboxAuth`. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxApiClient.kt` | new | Test fake for `DropboxApiClient`. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientTest.kt` | new | 8 MockWebServer tests. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokensRepositoryTest.kt` | new | 4 Robolectric tests. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepositoryTest.kt` | new | 10 Robolectric tests. |
| `docs/superpowers/testdata/phase-4c-dropbox.md` | new | Smoke test doc. |

**NOT modified** (already done in 4b): `CloudSyncRepository.kt`, `PullResult.kt`, `NoOpCloudSyncRepository.kt`, OkHttp dep entries in `libs.versions.toml` + `app/build.gradle.kts`, INTERNET permission in manifest.

---

### Task 1: Add AppAuth dependency + DROPBOX_CLIENT_ID buildConfigField

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version + library entry to libs.versions.toml**

Append to the `[versions]` block (after existing entries):

```toml
appauth = "0.11.1"
```

Append to the `[libraries]` block:

```toml
appauth = { group = "net.openid", name = "appauth", version.ref = "appauth" }
```

- [ ] **Step 2: Add dependency + buildConfigField to app/build.gradle.kts**

In the `dependencies { }` block, add:

```kotlin
implementation(libs.appauth)
```

Add a `buildConfigField` so `BuildConfig.DROPBOX_CLIENT_ID` resolves at compile time. Place this inside the `defaultConfig { }` block, after the existing `buildConfigField("String", "DEFAULT_WEB_CLIENT_ID", ...)` line:

```kotlin
buildConfigField(
    "String",
    "DROPBOX_CLIENT_ID",
    "\"${localProps.getProperty("dropbox.client.id", "")}\"",
)
```

Note: the existing `val localProps = java.util.Properties().apply { ... }` is already present from 4b; reuse it.

- [ ] **Step 3: Verify Gradle resolves the new dep**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>&1 | grep -E "appauth" | head -5
```

Expected: at least one line containing `net.openid:appauth`. (OkHttp lines are already present from 4b; no need to verify again.)

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "build(sync): add AppAuth-Android dep (Phase 4c)"
```

---

### Task 2: DropboxApiException + DropboxApiClient + DropboxApiClientImpl + 8 MockWebServer tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiException.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClient.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientTest.kt`

- [ ] **Step 1: Create DropboxApiException.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiException.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

/**
 * Sealed hierarchy for HTTP-layer failures. Maps directly onto Dropbox
 * v2 status codes; orchestrator translates each into a [PullResult]/[PushResult]
 * variant. Mirror of [io.github.jiro.expensetracker.sync.google.DriveApiException]
 * so error handling stays symmetric across providers.
 */
internal sealed class DropboxApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** HTTP 401/403, or a 400 with `invalid_access_token` body. */
    class AuthRevoked : DropboxApiException("Auth revoked")

    /** HTTP 404 — file does not exist at the given path. */
    class NotFound : DropboxApiException("Not found")

    /** HTTP 409 — `path/conflict/file` or `path/conflict/folder`. */
    class Conflict(val serverRev: String?) :
        DropboxApiException("Conflict (serverRev=$serverRev)")

    /** HTTP 429 — `Retry-After` header is honored by the impl. */
    class RateLimited : DropboxApiException("Rate limited")

    /** HTTP 5xx. Retried up to 3x with exponential backoff. */
    class ServerError : DropboxApiException("Server error")

    /** Anything else. Includes the response body for debugging. */
    class Generic(message: String, cause: Throwable? = null) :
        DropboxApiException(message, cause)
}
```

- [ ] **Step 2: Create DropboxApiClient.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClient.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

/**
 * Wire-level surface for the Dropbox App folder. The path to the snapshot
 * is fixed (`/ExpenseTracker-sync.json`) and lives inside this class — the
 * orchestrator never names the file itself.
 *
 * Implementations must throw [DropboxApiException] subclasses on failure;
 * callers translate those into [PullResult]/[PushResult] variants.
 */
internal interface DropboxApiClient {

    /**
     * Upload [body] to `/ExpenseTracker-sync.json`. If [existingRev] is null,
     * creates the file; otherwise uses `mode: {".tag": "update", "update": existingRev}`
     * to enforce optimistic concurrency.
     *
     * Returns the new `rev` returned by Dropbox (a content-addressed server
     * identifier). Throws [DropboxApiException.NotFound] only if the parent
     * folder is missing — should never happen for the App folder.
     */
    suspend fun upload(existingRev: String?, body: String): String

    /**
     * Download the snapshot body. Returns null if the file does not exist
     * (HTTP 404, or HTTP 409 with `path/not_found/`). Throws
     * [DropboxApiException.AuthRevoked] on 401/403.
     */
    suspend fun download(): String?

    /**
     * Return the current `rev` of the snapshot file, or null if it does
     * not exist. Used by the orchestrator to refresh its cached rev
     * (deferred to a later phase).
     */
    suspend fun getRev(): String?
}
```

- [ ] **Step 3: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientTest.kt`:

```kotlin
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
        client = DropboxApiClientImpl(
            httpClient = OkHttpClient(),
            tokensProvider = { FIXED_TOKENS },
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
            arg!!.contains("\"mode\":{\"\\".tag\\":\\"update\\",\\"update\\":\\"abc123\\"}") ||
                arg.contains("\"update\":\"abc123\""),
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
```

Note on the cross-package dependency (CORRECTION): DropboxApiClientImpl takes a closure that returns `DropboxSyncTokens?` (defined in Task 3) — NOT Drive's `SyncTokens?`. The original plan incorrectly typed the closure against the Drive type; the corrected version uses DropboxSyncTokens so the two providers' token shapes stay decoupled. No structural sharing between providers.

- [ ] **Step 4: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxApiClientTest*" 2>&1 | tail -15
```

Expected: FAIL with `Unresolved reference: DropboxApiClientImpl`.

- [ ] **Step 5: Create DropboxApiClientImpl.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val HOST_CONTENT = "https://content.dropboxapi.com"
private const val HOST_API = "https://api.dropboxapi.com"
private const val PATH_UPLOAD = "/2/files/upload"
private const val PATH_DOWNLOAD = "/2/files/download"
private const val PATH_GET_METADATA = "/2/files/get_metadata"
private const val SNAPSHOT_PATH = "/ExpenseTracker-sync.json"

private const val OCTET_STREAM = "application/octet-stream"
private const val JSON = "application/json"
private val OCTET_STREAM_BODY: okhttp3.RequestBody = "".toRequestBody(JSON.toMediaType())

/**
 * OkHttp-backed [DropboxApiClient]. Reads tokens via a closure (NOT a stored
 * reference) so the orchestrator can rotate tokens without rebuilding the
 * client. Retries 429 (via `Retry-After`) and 5xx up to 3 times with
 * exponential backoff; 401/403/404/409 do NOT retry.
 */
@Singleton
internal class DropboxApiClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokensProvider: () -> DropboxSyncTokens?,
) : DropboxApiClient {

    override suspend fun upload(existingRev: String?, body: String): String =
        withContext(Dispatchers.IO) {
            val arg = JSONObject().apply {
                put("path", SNAPSHOT_PATH)
                if (existingRev == null) {
                    put("mode", "overwrite")
                } else {
                    put(
                        "mode",
                        JSONObject().apply {
                            put(".tag", "update")
                            put("update", existingRev)
                        },
                    )
                }
                put("autorename", false)
                put("mute", true)
            }
            val req = Request.Builder()
                .url("$HOST_CONTENT$PATH_UPLOAD")
                .header("Authorization", "Bearer ${requireToken()}")
                .header("Dropbox-API-Arg", arg.toString())
                .header("Content-Type", OCTET_STREAM)
                .post(body.toRequestBody(OCTET_STREAM.toMediaType()))
                .build()
            executeWithRetry(req).use { resp ->
                when (resp.code) {
                    200 -> JSONObject(resp.body?.string() ?: "{}").getString("rev")
                    401, 403 -> throw DropboxApiException.AuthRevoked()
                    409 -> throw DropboxApiException.Conflict(serverRev = parseServerRev(resp))
                    429 -> throw DropboxApiException.RateLimited()
                    in 500..599 -> throw DropboxApiException.ServerError()
                    else -> throw DropboxApiException.Generic("HTTP ${resp.code}: ${resp.body?.string()}")
                }
            }
        }

    override suspend fun download(): String? = withContext(Dispatchers.IO) {
        val arg = JSONObject().put("path", SNAPSHOT_PATH)
        val req = Request.Builder()
            .url("$HOST_CONTENT$PATH_DOWNLOAD")
            .header("Authorization", "Bearer ${requireToken()}")
            .header("Dropbox-API-Arg", arg.toString())
            .get()
            .build()
        executeWithRetry(req).use { resp ->
            when (resp.code) {
                200 -> resp.body?.string()
                401, 403 -> throw DropboxApiException.AuthRevoked()
                404 -> null
                409 -> null // path/not_found/ — treat same as 404
                429 -> throw DropboxApiException.RateLimited()
                in 500..599 -> throw DropboxApiException.ServerError()
                else -> throw DropboxApiException.Generic("HTTP ${resp.code}: ${resp.body?.string()}")
            }
        }
    }

    override suspend fun getRev(): String? = withContext(Dispatchers.IO) {
        val arg = JSONObject().put("path", SNAPSHOT_PATH)
        val req = Request.Builder()
            .url("$HOST_API$PATH_GET_METADATA")
            .header("Authorization", "Bearer ${requireToken()}")
            .header("Dropbox-API-Arg", arg.toString())
            .post(OCTET_STREAM_BODY)
            .build()
        executeWithRetry(req).use { resp ->
            when (resp.code) {
                200 -> JSONObject(resp.body?.string() ?: "{}").optString("rev").takeIf { it.isNotEmpty() }
                401, 403 -> throw DropboxApiException.AuthRevoked()
                404, 409 -> null
                429 -> throw DropboxApiException.RateLimited()
                in 500..599 -> throw DropboxApiException.ServerError()
                else -> throw DropboxApiException.Generic("HTTP ${resp.code}: ${resp.body?.string()}")
            }
        }
    }

    private fun requireToken(): String =
        tokensProvider()?.accessToken ?: error("No Dropbox token available")

    private suspend fun executeWithRetry(
        request: Request,
        maxAttempts: Int = 3,
    ): okhttp3.Response {
        var attempt = 0
        while (true) {
            attempt++
            val resp = httpClient.newCall(request).execute()
            val shouldRetry = (resp.code == 429 || resp.code in 500..599) && attempt < maxAttempts
            if (!shouldRetry) return resp
            val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: 0L
            resp.close()
            // Exponential backoff: 1s, 2s, 4s. Capped at Retry-After if larger.
            val backoffMs = maxOf(retryAfterSec * 1000L, 1000L shl (attempt - 1))
            delay(backoffMs)
        }
    }

    private fun parseServerRev(resp: okhttp3.Response): String? = try {
        JSONObject(resp.body?.string() ?: "{}")
            .optJSONObject("error")
            ?.optJSONObject("path_conflict")
            ?.optString("rev")
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxApiClientTest*" 2>&1 | tail -25
```

Expected: 8 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiException.kt app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClient.kt app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientImpl.kt app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxApiClientTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): DropboxApiClient with OkHttp RPC-style upload (Phase 4c)"
```

---

### Task 3: DropboxSyncTokens + TokenCrypto + KeystoreTokenCrypto + DropboxSyncTokensRepository + 4 Robolectric tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokens.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/TokenCrypto.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/KeystoreTokenCrypto.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokensRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokensRepositoryTest.kt`

- [ ] **Step 1: Create DropboxSyncTokens.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokens.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

/**
 * All persisted state needed to talk to Dropbox on behalf of the signed-in
 * user. `snapshotRev` is Dropbox's optimistic-concurrency token — analogous
 * to Drive's `snapshotFileId`. AppAuth PKCE flows return a 4-hour access
 * token and NO refresh token, so [refreshToken] is nullable.
 */
internal data class DropboxSyncTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
    val accountEmail: String,
    val snapshotRev: String?,
)
```

- [ ] **Step 2: Create TokenCrypto.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/TokenCrypto.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

/**
 * Encrypt/decrypt individual strings. The repo encrypts each
 * SharedPreferences field independently, so this is a per-value cipher,
 * not a stream. Production uses [KeystoreTokenCrypto] (Android Keystore,
 * AES-256-GCM); tests use a plaintext pass-through.
 *
 * Extracted as an interface (same reason as 4b's TokenCrypto): Robolectric
 * 4.11.1 does not implement the AndroidKeyStore JCA provider, so we cannot
 * exercise KeystoreTokenCrypto in unit tests.
 */
internal interface TokenCrypto {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertextB64: String): String
}
```

- [ ] **Step 3: Create KeystoreTokenCrypto.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/KeystoreTokenCrypto.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a key held in Android Keystore. Per-value
 * random IVs (12 bytes) are prepended to ciphertext; base64 is used for
 * storage. User authentication is NOT required so the sync flow stays
 * non-interactive. If the Keystore key is ever destroyed (factory reset,
 * app uninstall, hardware rollback), decryption throws and the caller
 * wipes the SharedPreferences-backed token store.
 *
 * NOTE: separate Keystore alias from Drive's `expensetracker_sync_key` —
 * `expensetracker_dropbox_sync_key` — so the two providers cannot read
 * each other's tokens.
 */
internal class KeystoreTokenCrypto : TokenCrypto {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ciphertext, 0, it, iv.size, ciphertext.size)
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertextB64: String): String {
        val combined = Base64.decode(ciphertextB64, Base64.NO_WRAP)
        require(combined.size > IV_BYTES) { "Ciphertext too short" }
        val iv = combined.copyOfRange(0, IV_BYTES)
        val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "expensetracker_dropbox_sync_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
```

- [ ] **Step 4: Create DropboxSyncTokensRepository.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokensRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface DropboxSyncTokensRepository {
    suspend fun load(): DropboxSyncTokens?
    suspend fun save(tokens: DropboxSyncTokens)
    suspend fun clear()
}

@Singleton
internal class DefaultDropboxSyncTokensRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: TokenCrypto = KeystoreTokenCrypto(),
) : DropboxSyncTokensRepository {

    // SharedPreferences (not DataStore) — small, infrequent writes, no Flow
    // observers needed. Crypto handles the security boundary; the prefs file
    // holds ciphertext only.
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): DropboxSyncTokens? = withContext(Dispatchers.IO) {
        val access = prefs.getString(K_ACCESS, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        val refresh = prefs.getString(K_REFRESH, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        val expires = prefs.getString(K_EXPIRES, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        val email = prefs.getString(K_EMAIL, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        val rev = prefs.getString(K_REV, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }

        DropboxSyncTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMillis = expires.toLong(),
            accountEmail = email,
            snapshotRev = rev,
        )
    }

    override suspend fun save(tokens: DropboxSyncTokens) = withContext(Dispatchers.IO) {
        prefs.edit {
            putString(K_ACCESS, crypto.encrypt(tokens.accessToken))
            tokens.refreshToken?.let { putString(K_REFRESH, crypto.encrypt(it)) }
                ?: remove(K_REFRESH)
            putString(K_EXPIRES, crypto.encrypt(tokens.expiresAtEpochMillis.toString()))
            putString(K_EMAIL, crypto.encrypt(tokens.accountEmail))
            if (tokens.snapshotRev != null) {
                putString(K_REV, crypto.encrypt(tokens.snapshotRev))
            } else {
                remove(K_REV)
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }

    private fun wipeAndNull(): DropboxSyncTokens? {
        prefs.edit().clear().apply()
        return null
    }

    private companion object {
        const val PREFS_NAME = "dropbox_sync_tokens"
        const val K_ACCESS = "access_token_b64"
        const val K_REFRESH = "refresh_token_b64"
        const val K_EXPIRES = "expires_at_b64"
        const val K_EMAIL = "account_email_b64"
        const val K_REV = "snapshot_rev_b64"
    }
}
```

- [ ] **Step 5: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokensRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DropboxSyncTokensRepositoryTest {

    /** Plaintext pass-through. Robolectric 4.11 doesn't implement AndroidKeyStore. */
    private class FakeTokenCrypto : TokenCrypto {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertextB64: String): String = ciphertextB64
    }

    @Before
    fun clearPrefs() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("dropbox_sync_tokens", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun newRepo(): DropboxSyncTokensRepository =
        DefaultDropboxSyncTokensRepository(
            ApplicationProvider.getApplicationContext(),
            FakeTokenCrypto(),
        )

    @Test
    fun load_returnsNull_whenPrefsEmpty() = kotlinx.coroutines.runBlocking {
        assertNull(newRepo().load())
    }

    @Test
    fun save_thenLoad_roundTrips() = kotlinx.coroutines.runBlocking {
        val repo = newRepo()
        val tokens = DropboxSyncTokens(
            accessToken = "access-xyz",
            refreshToken = null,
            expiresAtEpochMillis = 1_700_000_000_000L,
            accountEmail = "u@e.com",
            snapshotRev = "rev-1",
        )
        repo.save(tokens)
        val loaded = repo.load()
        assertNotNull(loaded)
        assertEquals("access-xyz", loaded!!.accessToken)
        assertEquals(null, loaded.refreshToken)
        assertEquals(1_700_000_000_000L, loaded.expiresAtEpochMillis)
        assertEquals("u@e.com", loaded.accountEmail)
        assertEquals("rev-1", loaded.snapshotRev)
    }

    @Test
    fun load_wipesPrefs_whenAccessTokenDecryptFails() = kotlinx.coroutines.runBlocking {
        // Seed prefs with a corrupted access-token value
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("dropbox_sync_tokens", Context.MODE_PRIVATE)
            .edit()
            .putString("access_token_b64", "garbage-not-base64-valid-encrypted-blob")
            .commit()

        // Use a real KeystoreTokenCrypto-style behavior: any decode that doesn't
        // round-trip through encrypt() is unreadable. Here we use FakeTokenCrypto
        // which is lossless, so we manually plant a mismatch: encrypt with one
        // instance, load with another that has a different "key" — simulated by
        // a FailingTokenCrypto.
        class FailingTokenCrypto : TokenCrypto {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(ciphertextB64: String): String =
                throw java.security.GeneralSecurityException("simulated decrypt failure")
        }
        val repo = DefaultDropboxSyncTokensRepository(
            ApplicationProvider.getApplicationContext(),
            FailingTokenCrypto(),
        )
        assertNull(repo.load())
        // Prefs should be wiped after the failed load
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("dropbox_sync_tokens", Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun clear_removesAllEntries() = kotlinx.coroutines.runBlocking {
        val repo = newRepo()
        repo.save(
            DropboxSyncTokens(
                accessToken = "x",
                refreshToken = null,
                expiresAtEpochMillis = 0L,
                accountEmail = "u@e.com",
                snapshotRev = null,
            ),
        )
        assertNotNull(repo.load())
        repo.clear()
        assertNull(repo.load())
    }
}
```

- [ ] **Step 6: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxSyncTokensRepositoryTest*" 2>&1 | tail -15
```

Expected: FAIL with `Unresolved reference: DropboxSyncTokensRepository`.

- [ ] **Step 7: Run tests to verify they pass**

(The implementation is in Step 4 — `DropboxSyncTokensRepository.kt` already compiles.)

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxSyncTokensRepositoryTest*" 2>&1 | tail -15
```

Expected: 4 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokens.kt app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/TokenCrypto.kt app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/KeystoreTokenCrypto.kt app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokensRepository.kt app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxSyncTokensRepositoryTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): DropboxSyncTokensRepository with Keystore AES-GCM (Phase 4c)"
```

---

### Task 4: DropboxAuth + DropboxAccountSnapshot + AppAuthDropboxAuth + FakeDropboxAuth

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxAccountSnapshot.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxAuth.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/AppAuthDropboxAuth.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxAuth.kt`

- [ ] **Step 1: Create DropboxAccountSnapshot.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxAccountSnapshot.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

/**
 * Minimal account info the orchestrator needs to persist tokens and show
 * the user which Dropbox account is signed in. We do NOT cache the access
 * token here — the orchestrator persists it via [DropboxSyncTokensRepository].
 */
internal data class DropboxAccountSnapshot(
    val email: String,
    val accessToken: String,
)
```

- [ ] **Step 2: Create DropboxAuth.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxAuth.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import android.content.Intent

/**
 * Contract for any Dropbox OAuth-flow implementation. The production impl
 * ([AppAuthDropboxAuth]) wraps AppAuth-Android; tests use [FakeDropboxAuth].
 *
 * `buildAuthIntent` is sync — it just constructs an Intent that launches
 * Chrome Custom Tabs. The redirect Intent returned by the launcher is
 * parsed by [handleAuthResult].
 */
internal interface DropboxAuth {
    /** Build a CustomTabs-backed OAuth Intent. Caller launches it. */
    fun buildAuthIntent(): Intent

    /** Parse the OAuth redirect Intent returned by the launcher. */
    suspend fun handleAuthResult(data: Intent?): DropboxAccountSnapshot?

    /** Return cached account if a valid token is available, else null. */
    suspend fun getLastAuthState(): DropboxAccountSnapshot?
}
```

- [ ] **Step 3: Create AppAuthDropboxAuth.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/AppAuthDropboxAuth.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.net.Uri

/**
 * AppAuth-Android PKCE wrapper for Dropbox OAuth 2.0. AppAuth is configured
 * with [net.openid.appauth.NoopTokenStore] so it never persists tokens to
 * disk — instead the orchestrator copies tokens into
 * [DefaultDropboxSyncTokensRepository] where they are protected by Android
 * Keystore AES-GCM.
 *
 * AppAuth's own [AuthorizationService] is lazy-instantiated because it
 * requires a Context at construction.
 */
@Singleton
internal class AppAuthDropboxAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val clientId: String = io.github.jiro.expensetracker.BuildConfig.DROPBOX_CLIENT_ID,
    private val redirectUri: String = "io.github.jiro.expensetracker:/oauth2redirect",
) : DropboxAuth {

    private val authService: AuthorizationService by lazy {
        AuthorizationService(context)
    }

    override fun buildAuthIntent(): Intent {
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://api.dropboxapi.com/oauth2/authorize"),
            Uri.parse("https://api.dropboxapi.com/oauth2/token"),
        )
        val request = AuthorizationRequest.Builder(
            config,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri),
        )
            .setScope("account_info.read files.content.read files.content.write")
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    override suspend fun handleAuthResult(data: Intent?): DropboxAccountSnapshot? =
        withContext(Dispatchers.IO) {
            val resp = net.openid.appauth.AuthorizationResponse.fromIntent(data ?: return@withContext null)
                ?: return@withContext null
            val tokenReq = resp.createTokenExchangeRequest()
            val tokenResp = try {
                performTokenRequest(tokenReq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: net.openid.appauth.AuthorizationException) {
                return@withContext null
            }
            val accessToken = tokenResp.accessToken ?: return@withContext null
            val email = fetchAccountEmail(accessToken) ?: return@withContext null
            DropboxAccountSnapshot(email = email, accessToken = accessToken)
        }

    override suspend fun getLastAuthState(): DropboxAccountSnapshot? = null

    private suspend fun performTokenRequest(
        req: net.openid.appauth.TokenRequest,
    ): net.openid.appauth.TokenResponse = suspendCancellableCoroutine { cont ->
        authService.performTokenRequest(req, net.openid.appauth.NoopTokenStore()) { resp, ex ->
            if (ex != null) {
                cont.resumeWith(Result.failure(ex))
            } else {
                cont.resumeWith(Result.success(resp))
            }
        }
    }

    private suspend fun fetchAccountEmail(accessToken: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/2/users/get_current_account")
                .header("Authorization", "Bearer $accessToken")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                JSONObject(body).optString("email").takeIf { it.isNotEmpty() }
            }
        }
}
```

- [ ] **Step 4: Create FakeDropboxAuth.kt**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxAuth.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import android.content.Intent

/**
 * Test fake for [DropboxAuth]. Tests set [extractResult] to control the
 * outcome of [handleAuthResult]. [signInIntentValue] is the Intent that
 * [buildAuthIntent] will return.
 */
internal class FakeDropboxAuth : DropboxAuth {
    var extractResult: DropboxAccountSnapshot? = null
    var signInIntentValue: Intent = Intent()
    var lastAuthState: DropboxAccountSnapshot? = null

    override fun buildAuthIntent(): Intent = signInIntentValue

    override suspend fun handleAuthResult(data: Intent?): DropboxAccountSnapshot? = extractResult

    override suspend fun getLastAuthState(): DropboxAccountSnapshot? = lastAuthState
}
```

- [ ] **Step 5: Verify the project compiles**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. AppAuth classes are resolvable.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxAccountSnapshot.kt app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxAuth.kt app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/AppAuthDropboxAuth.kt app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxAuth.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): DropboxAuth + AppAuth-Android wrapper (Phase 4c)"
```

---

### Task 5: FakeDropboxApiClient

**Files:**
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxApiClient.kt`

- [ ] **Step 1: Create FakeDropboxApiClient.kt**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxApiClient.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

/**
 * Test fake for [DropboxApiClient]. Mirrors 4b's [io.github.jiro.expensetracker.sync.google.FakeDriveApiClient].
 *
 * Configure [uploadError]/[downloadError] to make the next call throw;
 * otherwise [nextUploadId] is returned from upload and [downloadBody] from
 * download. Use [uploads]/[downloads]/[revLookups] to assert on what was
 * called and with what arguments.
 */
internal class FakeDropboxApiClient : DropboxApiClient {
    var uploadError: DropboxApiException? = null
    var downloadError: DropboxApiException? = null
    var revLookupError: DropboxApiException? = null

    var downloadBody: String? = null
    var nextUploadId: String = "fake-rev"
    var nextRevLookupResult: String? = null

    val uploads: MutableList<Pair<String?, String>> = mutableListOf()
    val downloads: MutableList<Unit> = mutableListOf()
    val revLookups: MutableList<Unit> = mutableListOf()

    override suspend fun upload(existingRev: String?, body: String): String {
        uploads.add(existingRev to body)
        uploadError?.let { throw it }
        return nextUploadId
    }

    override suspend fun download(): String? {
        downloads.add(Unit)
        downloadError?.let { throw it }
        return downloadBody
    }

    override suspend fun getRev(): String? {
        revLookups.add(Unit)
        revLookupError?.let { throw it }
        return nextRevLookupResult
    }
}
```

- [ ] **Step 2: Verify the test source compiles**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugUnitTestKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/FakeDropboxApiClient.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "test(sync): FakeDropboxApiClient for orchestrator tests (Phase 4c)"
```

---

### Task 6: DropboxCloudSyncRepository + 10 Robolectric tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepositoryTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.jiro.expensetracker.sync.BackupBody
import io.github.jiro.expensetracker.sync.PullResult
import io.github.jiro.expensetracker.sync.PushResult
import io.github.jiro.expensetracker.sync.SignInResult
import io.github.jiro.expensetracker.sync.SyncSnapshot
import io.github.jiro.expensetracker.sync.SyncSnapshotCodec
import io.github.jiro.expensetracker.sync.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DropboxCloudSyncRepositoryTest {

    /** Plaintext pass-through. Robolectric 4.11 doesn't implement AndroidKeyStore. */
    private class FakeTokenCrypto : TokenCrypto {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertextB64: String): String = ciphertextB64
    }

    private lateinit var auth: FakeDropboxAuth
    private lateinit var api: FakeDropboxApiClient
    private lateinit var tokens: DropboxSyncTokensRepository
    private lateinit var repo: DropboxCloudSyncRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("dropbox_sync_tokens", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        auth = FakeDropboxAuth()
        api = FakeDropboxApiClient()
        tokens = DefaultDropboxSyncTokensRepository(context, FakeTokenCrypto())
        repo = DropboxCloudSyncRepository(
            context = context,
            dropboxAuth = auth,
            api = api,
            tokens = tokens,
            nowProvider = { 1_700_000_000_000L },
        )
    }

    @Test
    fun signInIntent_isNotNull() {
        assertNotNull(repo.signInIntent)
    }

    @Test
    fun handleSignInResult_persistsTokens_onSuccess() = runBlocking {
        auth.extractResult = DropboxAccountSnapshot(
            email = "user@example.com",
            accessToken = "token-abc",
        )
        val result = repo.handleSignInResult(Intent())
        assertEquals(SignInResult.Success, result)
        val saved = tokens.load()
        assertEquals("user@example.com", saved?.accountEmail)
        assertEquals("token-abc", saved?.accessToken)
        assertEquals(SyncState.SignedIn("dropbox"), repo.state.first())
    }

    @Test
    fun handleSignInResult_returnsFailed_whenCancelled() = runBlocking {
        auth.extractResult = null
        val result = repo.handleSignInResult(null)
        assertTrue(result is SignInResult.Failed)
        assertEquals(SyncState.SignedOut, repo.state.first())
    }

    @Test
    fun push_createsFile_whenNoSnapshotRev() = runBlocking {
        // Sign in first
        auth.extractResult = DropboxAccountSnapshot(email = "u@e.com", accessToken = "code")
        repo.handleSignInResult(Intent())
        api.uploads.clear()

        val snapshot = sampleSnapshot()
        val result = repo.push(snapshot)
        assertTrue("Expected PushResult.Pushed, got $result", result is PushResult.Pushed)
        assertEquals(1, api.uploads.size)
        assertNull("upload must be CREATE (existingRev=null) when no rev stored", api.uploads.first().first)
        // After first push, tokens should now have a rev
        val saved = tokens.load()
        assertEquals("fake-rev", saved?.snapshotRev)
    }

    @Test
    fun push_updatesFile_whenSnapshotRevExists() = runBlocking {
        // Pre-seed tokens with a known rev
        tokens.save(
            DropboxSyncTokens(
                accessToken = "tok",
                refreshToken = null,
                expiresAtEpochMillis = 1_700_000_000_000L + 4 * 60 * 60 * 1000L,
                accountEmail = "u@e.com",
                snapshotRev = "existing-rev",
            ),
        )
        repo.signIn()

        api.uploads.clear()
        val snapshot = sampleSnapshot()
        val result = repo.push(snapshot)
        assertTrue(result is PushResult.Pushed)
        assertEquals(1, api.uploads.size)
        assertEquals("existing-rev", api.uploads.first().first) // UPDATE path
    }

    @Test
    fun pull_returnsSuccess_whenRemoteSnapshotDecodes() = runBlocking {
        tokens.save(
            DropboxSyncTokens(
                accessToken = "tok",
                refreshToken = null,
                expiresAtEpochMillis = 1_700_000_000_000L + 4 * 60 * 60 * 1000L,
                accountEmail = "u@e.com",
                snapshotRev = "remote-rev",
            ),
        )
        repo.signIn()
        val snapshot = sampleSnapshot()
        api.downloadBody = SyncSnapshotCodec.encode(snapshot)

        val result = repo.pull()
        assertTrue(result is PullResult.Success<*>)
        assertEquals(1, api.downloads.size)
    }

    @Test
    fun pull_returnsNoRemoteSnapshot_whenRevNull() = runBlocking {
        // No tokens, no signed-in state
        assertEquals(PullResult.NoRemoteSnapshot, repo.pull())
    }

    @Test
    fun pull_returnsNoRemoteSnapshot_whenHttp404() = runBlocking {
        tokens.save(
            DropboxSyncTokens(
                accessToken = "tok",
                refreshToken = null,
                expiresAtEpochMillis = 1_700_000_000_000L + 4 * 60 * 60 * 1000L,
                accountEmail = "u@e.com",
                snapshotRev = "missing",
            ),
        )
        repo.signIn()
        api.downloadError = DropboxApiException.NotFound()
        assertEquals(PullResult.NoRemoteSnapshot, repo.pull())
    }

    @Test
    fun pull_returnsFailed_whenChecksumMismatch() = runBlocking {
        tokens.save(
            DropboxSyncTokens(
                accessToken = "tok",
                refreshToken = null,
                expiresAtEpochMillis = 1_700_000_000_000L + 4 * 60 * 60 * 1000L,
                accountEmail = "u@e.com",
                snapshotRev = "remote-rev",
            ),
        )
        repo.signIn()
        api.downloadBody = "this-is-not-valid-json-at-all"
        val result = repo.pull()
        assertTrue(result is PullResult.Failed)
    }

    @Test
    fun signOut_clearsTokens_andFlipsState() = runBlocking {
        tokens.save(
            DropboxSyncTokens(
                accessToken = "tok",
                refreshToken = null,
                expiresAtEpochMillis = 1_700_000_000_000L + 4 * 60 * 60 * 1000L,
                accountEmail = "u@e.com",
                snapshotRev = "x",
            ),
        )
        repo.signIn()
        assertTrue(repo.isSignedIn.first())

        repo.signOut()
        assertFalse(repo.isSignedIn.first())
        assertEquals(SyncState.SignedOut, repo.state.first())
        assertNull(tokens.load())
    }

    private fun sampleSnapshot(): SyncSnapshot = SyncSnapshot(
        body = BackupBody(emptyList(), emptyList(), emptyList()),
        lastModifiedEpochMillis = 1_700_000_001_000L,
        deviceId = "device-1",
        checksum = "ignored-by-encoder",
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxCloudSyncRepositoryTest*" 2>&1 | tail -15
```

Expected: FAIL with `Unresolved reference: DropboxCloudSyncRepository`.

- [ ] **Step 3: Create DropboxCloudSyncRepository.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.PullResult
import io.github.jiro.expensetracker.sync.PushResult
import io.github.jiro.expensetracker.sync.SignInResult
import io.github.jiro.expensetracker.sync.SyncErrorCode
import io.github.jiro.expensetracker.sync.SyncException
import io.github.jiro.expensetracker.sync.SyncResult
import io.github.jiro.expensetracker.sync.SyncSnapshot
import io.github.jiro.expensetracker.sync.SyncSnapshotCodec
import io.github.jiro.expensetracker.sync.SyncState
import io.github.jiro.expensetracker.sync.Operation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
internal class DropboxCloudSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dropboxAuth: DropboxAuth,
    private val api: DropboxApiClient,
    private val tokens: DropboxSyncTokensRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)
    private val mutex = Mutex()

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val signInIntent: Intent = dropboxAuth.buildAuthIntent()

    override suspend fun signIn(): SignInResult = withContext(Dispatchers.IO) {
        val cached = tokens.load()
        if (cached != null && cached.expiresAtEpochMillis > nowProvider() + 60_000L) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            return@withContext SignInResult.Success
        }
        SignInResult.Failed("Not signed in")
    }

    override suspend fun handleSignInResult(data: Intent?): SignInResult = withContext(Dispatchers.IO) {
        try {
            val account = dropboxAuth.handleAuthResult(data)
                ?: return@withContext SignInResult.Failed("Sign-in cancelled")
            tokens.save(
                DropboxSyncTokens(
                    accessToken = account.accessToken,
                    refreshToken = null,
                    expiresAtEpochMillis = nowProvider() + ACCESS_TOKEN_LIFESPAN_MS,
                    accountEmail = account.email,
                    snapshotRev = null,
                ),
            )
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            SignInResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SignInResult.Failed("Sign-in failed: ${e.message}", e)
        }
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        tokens.clear()
        _state.value = SyncState.SignedOut
    }

    override suspend fun push(snapshot: SyncSnapshot): PushResult = mutex.withLock {
        val current = _state.value
        if (current !is SyncState.SignedIn) {
            return@withLock PushResult.Failed("Not signed in", null)
        }
        _state.value = SyncState.Syncing(Operation.PUSH)
        try {
            val body = SyncSnapshotCodec.encode(snapshot)
            val cached = tokens.load()
            val existingRev = cached?.snapshotRev
            val newRev = api.upload(existingRev, body)
            if (existingRev == null) {
                val current2 = tokens.load()
                if (current2 != null) {
                    tokens.save(current2.copy(snapshotRev = newRev))
                }
            } else {
                tokens.save(cached!!.copy(snapshotRev = newRev))
            }
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PushResult.Pushed(pushedAtEpochMillis = snapshot.lastModifiedEpochMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DropboxApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PushResult.Failed("Session expired — please sign in again", e)
        } catch (e: DropboxApiException) {
            PushResult.Failed(e.message ?: "Dropbox error", e)
        } catch (e: Exception) {
            PushResult.Failed("Push failed: ${e.message}", e)
        }
    }

    override suspend fun pull(): PullResult<SyncSnapshot> = mutex.withLock {
        val cached = tokens.load()
        val fileRev = cached?.snapshotRev
        if (fileRev == null) {
            return@withLock PullResult.NoRemoteSnapshot
        }
        _state.value = SyncState.Syncing(Operation.PULL)
        try {
            val body = api.download()
                ?: return@withLock PullResult.NoRemoteSnapshot.also { _state.value = SyncState.SignedIn(PROVIDER_ID) }
            val snapshot = SyncSnapshotCodec.decode(body)
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PullResult.Success(snapshot, pulledAtEpochMillis = nowProvider())
        } catch (e: CancellationException) {
            throw e
        } catch (e: DropboxApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PullResult.Failed("Session expired", e)
        } catch (e: DropboxApiException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(e.message ?: "Dropbox error", e)
        } catch (e: SyncException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(
                when (e.code) {
                    SyncErrorCode.CHECKSUM_MISMATCH -> "Remote snapshot failed integrity check"
                    SyncErrorCode.SCHEMA_INCOMPATIBLE -> "Remote snapshot was written by a newer app version"
                    SyncErrorCode.MALFORMED -> "Remote snapshot is corrupted"
                },
                e,
            )
        } catch (e: Exception) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed("Pull failed: ${e.message}", e)
        }
    }

    override suspend fun syncOnce(): SyncResult = withContext(Dispatchers.IO) {
        when (val result = pull()) {
            is PullResult.Success<*> -> SyncResult.Pulled(
                snapshot = result.snapshot as SyncSnapshot,
                pulledAtEpochMillis = result.pulledAtEpochMillis,
            )
            PullResult.NoRemoteSnapshot -> SyncResult.NoRemoteSnapshot
            is PullResult.Failed -> SyncResult.Failed(result.message, result.cause)
            is PullResult.Conflict -> SyncResult.Failed("Conflict on remote", null)
        }
    }

    private companion object {
        const val PROVIDER_ID = "dropbox"

        // AppAuth PKCE Dropbox flows issue 4-hour access tokens without a
        // refresh token. 4d may add refresh via /2/auth/token/refresh.
        const val ACCESS_TOKEN_LIFESPAN_MS = 4L * 60L * 60L * 1000L
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DropboxCloudSyncRepositoryTest*" 2>&1 | tail -25
```

Expected: 10 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepository.kt app/src/test/java/io/github/jiro/expensetracker/sync/dropbox/DropboxCloudSyncRepositoryTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): DropboxCloudSyncRepository orchestrator (Phase 4c)"
```

---

### Task 7: Manifest deep-link + DropboxModule + SyncModule @Binds flip

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/di/DropboxModule.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt`

- [ ] **Step 1: Add deep-link intent-filter to AndroidManifest.xml**

In `app/src/main/AndroidManifest.xml`, inside `<activity android:name=".MainActivity" ...>`, AFTER the existing `<intent-filter>` block (which has MAIN + LAUNCHER), add a second `<intent-filter>`:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="io.github.jiro.expensetracker" android:host="oauth2redirect" />
</intent-filter>
```

- [ ] **Step 2: Create DropboxModule.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/di/DropboxModule.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.dropbox.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.sync.dropbox.AppAuthDropboxAuth
import io.github.jiro.expensetracker.sync.dropbox.DefaultDropboxSyncTokensRepository
import io.github.jiro.expensetracker.sync.dropbox.DropboxApiClient
import io.github.jiro.expensetracker.sync.dropbox.DropboxApiClientImpl
import io.github.jiro.expensetracker.sync.dropbox.DropboxAuth
import io.github.jiro.expensetracker.sync.dropbox.DropboxSyncTokensRepository
import io.github.jiro.expensetracker.sync.dropbox.KeystoreTokenCrypto
import io.github.jiro.expensetracker.sync.dropbox.TokenCrypto
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DropboxModule {

    @Binds
    @Singleton
    abstract fun bindDropboxAuth(impl: AppAuthDropboxAuth): DropboxAuth

    @Binds
    @Singleton
    abstract fun bindDropboxApiClient(impl: DropboxApiClientImpl): DropboxApiClient

    @Binds
    @Singleton
    abstract fun bindDropboxSyncTokensRepository(
        impl: DefaultDropboxSyncTokensRepository,
    ): DropboxSyncTokensRepository

    companion object {
        @Provides
        @Singleton
        fun provideTokenCrypto(): TokenCrypto = KeystoreTokenCrypto()
    }
}
```

- [ ] **Step 3: Flip SyncModule @Binds to DropboxCloudSyncRepository**

Replace the entire contents of `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt` with:

```kotlin
package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.DefaultDeviceIdProvider
import io.github.jiro.expensetracker.sync.DeviceIdProvider
import io.github.jiro.expensetracker.sync.dropbox.DropboxCloudSyncRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindCloudSyncRepository(
        impl: DropboxCloudSyncRepository,
    ): CloudSyncRepository

    @Binds
    @Singleton
    abstract fun bindDeviceIdProvider(
        impl: DefaultDeviceIdProvider,
    ): DeviceIdProvider
}
```

Note: `GoogleDriveModule.kt` is left intact — its `@Binds` are still useful for direct injection (e.g., tests or feature flags in 4d). The runtime `CloudSyncRepository` injection now resolves to Dropbox. 4d will add a `selectedProviderId` preference that switches at runtime by replacing this binding or using a wrapper.

- [ ] **Step 4: Verify the Hilt graph builds**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. Any Hilt graph error (e.g., duplicate `CloudSyncRepository` binding) would surface here. The Drive `CloudSyncRepository` binding in `GoogleDriveModule` is harmless because Drive's module does NOT bind `CloudSyncRepository` — that binding lives only in `SyncModule`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/io/github/jiro/expensetracker/sync/dropbox/di/DropboxModule.kt app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): Hilt DropboxModule + manifest deep-link (Phase 4c)"
```

---

### Task 8: Smoke test doc + full test suite + tag v0.18.14 + push

**Files:**
- Create: `docs/superpowers/testdata/phase-4c-dropbox.md`

- [ ] **Step 1: Write the smoke test document**

Create `docs/superpowers/testdata/phase-4c-dropbox.md`:

```markdown
# Phase 4c — Dropbox Provider — Smoke Test

## Scope

4c adds the Dropbox provider as the second concrete cloud-sync implementation.
OAuth uses AppAuth-Android (PKCE); tokens are bridged into a Keystore-protected
SharedPreferences store; HTTP I/O uses Dropbox API v2 with the `Dropbox-API-Arg`
header convention. 4c ships Dropbox-bound by default; 4d adds the provider
selector.

## Automated verification

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest    # 22 new tests, 0 regressions
./gradlew :app:assembleDebug   # debug APK builds, Hilt graph resolves
```

Expected: `BUILD SUCCESSFUL` from both, with `testDebugUnitTest` reporting
`X/Y passing` where `Y = (v0.18.13 count) + 22`.

The 22 new tests break down as:
- 8 `DropboxApiClientTest` (MockWebServer)
- 4 `DropboxSyncTokensRepositoryTest` (Robolectric + FakeTokenCrypto)
- 10 `DropboxCloudSyncRepositoryTest` (Robolectric + fakes)

## Manual verification

### Prerequisites

Before manual testing, the developer must:

1. Create a Dropbox app at https://www.dropbox.com/developers/apps:
   - **API:** Scoped access
   - **Type of access:** App folder
   - **Name:** Expense Tracker (or similar)
   - **Permissions:** `account_info.read`, `files.content.read`, `files.content.write`
   - **Redirect URIs:** `io.github.jiro.expensetracker:/oauth2redirect`
2. Note the App key (this is the OAuth client_id).
3. Add `dropbox.client.id=<your-app-key>` to `local.properties`. The
   `buildConfigField` reads it into `BuildConfig.DROPBOX_CLIENT_ID`.

### Steps

- [ ] Build + install: `./gradlew :app:installDebug`
- [ ] Use a debug-only entry point (added in 4d; for now, call `repo.signInIntent`
      directly from a test Activity or fire the OAuth intent via
      `adb shell am start -a android.intent.action.VIEW -d "io.github.jiro.expensetracker:/oauth2redirect?..."`).
- [ ] Complete Dropbox consent. Verify `state` transitions to `SignedIn`.
- [ ] Push a snapshot. Verify `ExpenseTracker-sync.json` appears in the
      user's App folder (`/Apps/ExpenseTracker/ExpenseTracker-sync.json`).
- [ ] Pull. Verify the snapshot decodes and `PullResult.Success` returns.
- [ ] Sign out. Verify tokens are wiped from SharedPreferences (inspect
      `/data/data/io.github.jiro.expensetracker.debug/shared_prefs/dropbox_sync_tokens.xml`
      — should be empty or absent).

## What this phase did NOT add

- No Settings UI for provider selection or sign-in (4d).
- No sync status indicator (4d).
- No automatic push/pull triggers (4d).
- No WorkManager sync job (4d).
- No Google Drive provider as the active binding (4c ships with Dropbox bound
  by default; 4d adds the selector that flips between Drive and Dropbox).
- No Dropbox refresh-token flow (AppAuth's 4-hour access tokens are used as-is).
- No Dropbox folder picker (4c uses fixed path `/ExpenseTracker-sync.json`).
- No multi-account support (later).
- No receipt binaries in cloud backup (later).
```

- [ ] **Step 2: Run the full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL with all tests green. Count = (v0.18.13 count) + 22.
**STOP and investigate if any existing test fails** — 4c must not regress prior phases.

- [ ] **Step 3: Verify the debug APK still builds**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit + tag + push**

```bash
git add docs/superpowers/testdata/phase-4c-dropbox.md
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "docs(sync): Phase 4c Dropbox smoke test (Phase 4c)"
git tag v0.18.14
git push origin master v0.18.14
```

Expected:
- Commit author `MiniMax-M3 <291324429+Jiro90-T@users.noreply.github.com>`, no Co-Authored-By trailer.
- Tag `v0.18.14` exists locally.
- `git push` reports both `master` and `v0.18.14` pushed.

Verify:
```bash
git log -1 --format="%H %s"
git tag -l "v0.18.14"
git log v0.18.13..v0.18.14 --oneline   # should show ~8 new commits
git ls-remote --tags origin "v0.18.14"
```

---

## Self-Review

**1. Spec coverage:**
- AppAuth-Android OAuth flow ✓ (Task 4)
- `NoopTokenStore` to avoid plaintext persistence ✓ (Task 4)
- Bridge tokens to Keystore AES-GCM ✓ (Task 3)
- App folder + fixed `/ExpenseTracker-sync.json` path ✓ (Task 2)
- `Dropbox-API-Arg` header convention ✓ (Task 2)
- 429/5xx retry with `Retry-After` + exponential backoff ✓ (Task 2)
- AuthRevoked/NotFound/Conflict/RateLimited/ServerError/Generic mapping ✓ (Task 2 + Task 6)
- SyncException → SyncErrorCode mapping ✓ (Task 6)
- CancellationException rethrow in all 3 catches ✓ (Task 6)
- Token crypto interface + Keystore impl + FakeCrypto for tests ✓ (Task 3)
- Orchestrator with Mutex-serialized push/pull ✓ (Task 6)
- Manifest deep-link intent-filter ✓ (Task 7)
- Hilt module + @Binds flip ✓ (Task 7)
- ~22 new tests ✓ (Tasks 2, 3, 6)
- Smoke test doc + tag v0.18.14 ✓ (Task 8)

**2. Placeholder scan:** No "TBD"/"TODO"/"fill in" — all code blocks complete.

**3. Type consistency:**
- `DropboxApiClient.upload(existingRev, body): String` — same in Task 2 (interface, impl) and Task 5 (fake) and Task 6 (consumer).
- `DropboxSyncTokens` fields (accessToken, refreshToken?, expiresAtEpochMillis, accountEmail, snapshotRev?) — consistent across Tasks 3, 4, 6.
- `DropboxAccountSnapshot(email, accessToken)` — defined in Task 4, used in Tasks 4 (fake) and 6 (consumer).
- `DropboxApiException` sealed class — defined in Task 2, referenced in Tasks 5 (fake), 6 (orchestrator catch sites).
- `PROVIDER_ID = "dropbox"` — matches the orchestrator and the test's `SyncState.SignedIn("dropbox")` assertion.

**Note on cross-package coupling (Task 2):** The `DropboxApiClientImpl` constructor takes a `tokensProvider: () -> DropboxSyncTokens?` lambda (Task 3's data class). This decouples the wire-level surface from the OAuth implementation — the impl only knows how to ask for an access token, never how the token was obtained. The original draft incorrectly typed the closure against Drive's `SyncTokens?`; the fix was applied inline above (Step 3 test uses `DropboxSyncTokens(...)`, Step 5 impl signature uses `() -> DropboxSyncTokens?`).