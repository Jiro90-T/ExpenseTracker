# Phase 4b — Google Drive Provider — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `NoOpCloudSyncRepository` with `GoogleDriveCloudSyncRepository`, wire OAuth (Play Services Auth), persist tokens in DataStore + Android Keystore AES-GCM, and drive the user's Google Drive via OkHttp + REST v3 — flipping a single `@Binds` line in `SyncModule`.

**Architecture:** New `sync/google/` sub-package. The existing `CloudSyncRepository` interface from 4a is widened by two members (`signInIntent`, `handleSignInResult`); `PushResult` becomes a sealed class with a `Failed` variant. `DriveApiClient` (OkHttp) and `SyncTokensRepository` (DataStore + Keystore) are isolated behind small interfaces so the orchestrator is testable with fakes. All I/O runs on `Dispatchers.IO`; `state` is a single `MutableStateFlow<SyncState>` guarded by a `Mutex` to serialize concurrent pushes/pulls.

**Tech Stack:** Kotlin, Hilt, kotlinx-coroutines, OkHttp 4.12, Play Services Auth 21.2, DataStore Preferences 1.1, Android Keystore (`KeyGenParameterSpec` + AES/GCM/NoPadding), `org.json` (existing testImplementation), Robolectric (existing testImplementation), OkHttp `MockWebServer` (testImplementation), JUnit 4.

---

## File Structure

| File | Action | Responsibility |
| --- | --- | --- |
| `gradle/libs.versions.toml` | modify | Add OkHttp, Play Services Auth, Play Services Base, MockWebServer. |
| `app/build.gradle.kts` | modify | Add deps + `buildConfigField` for `DEFAULT_WEB_CLIENT_ID` (read from `local.properties`). |
| `app/src/main/AndroidManifest.xml` | modify | Add `<uses-permission android:name="android.permission.INTERNET"/>`. |
| `app/src/main/res/values/strings.xml` | modify | Add `default_web_client_id` + `google_drive_provider_id`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt` | modify | Add `val signInIntent: Intent` + `suspend fun handleSignInResult(data: Intent?): SignInResult`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt` | modify | Convert `PushResult` from data class to sealed class with `Pushed` + `Failed`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt` | modify | Implement `signInIntent` (returns `Intent()`) + `handleSignInResult` (returns `Success`). |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAuth.kt` | new | Interface: `getLastSignedInAccount`, `buildSignInIntent`, `extractAccountFromResult`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAccountSnapshot.kt` | new | Data class: `email`, `serverAuthCode`, `idToken`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleSignInAuthImpl.kt` | new | Play Services wrapper. Holds `GoogleSignInClient`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClient.kt` | new | Interface: `upload(fileId, body, mimeType): String`, `download(fileId): String?`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiException.kt` | new | Sealed `RuntimeException` with `AuthRevoked`, `QuotaExceeded`, `RateLimited`, `ServerError`, `NotFound`, `Generic`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt` | new | OkHttp impl: multipart upload, GET `?alt=media` download, 429/5xx retry logic. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokens.kt` | new | Data class: `accessToken`, `refreshToken`, `expiresAtEpochMillis`, `accountEmail`, `snapshotFileId`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/TokenCrypto.kt` | new | Keystore-backed AES-GCM wrapper. `encrypt(plaintext)`, `decrypt(ciphertext)`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepository.kt` | new | Interface + impl. DataStore + TokenCrypto. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt` | new | Orchestrator. Implements widened `CloudSyncRepository`. Owns `Mutex` + `MutableStateFlow`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/google/di/GoogleDriveModule.kt` | new | Hilt `@Module` providing `OkHttpClient`, `DriveApiClient`, `GoogleAuth`, and rebinding `CloudSyncRepository` to `GoogleDriveCloudSyncRepository` (replaces SyncModule's `@Binds`). |
| `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt` | modify | Remove the `CloudSyncRepository` `@Binds` (moved to `GoogleDriveModule`). |
| `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeGoogleAuth.kt` | new | Test fake for `GoogleAuth`. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeDriveApiClient.kt` | new | Test fake for `DriveApiClient`. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientTest.kt` | new | 8 MockWebServer tests. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepositoryTest.kt` | new | 3 Robolectric tests. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepositoryTest.kt` | new | 12 Robolectric tests. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt` | modify | Add 2 tests for new `signInIntent` + `handleSignInResult` members. |
| `docs/superpowers/testdata/phase-4b-google-drive.md` | new | Smoke test doc. |

---

### Task 1: Add dependencies (OkHttp + Play Services Auth + MockWebServer)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add versions + libraries to libs.versions.toml**

Append to the `[versions]` block (keep existing entries; add only these):

```toml
okhttp = "4.12.0"
playServicesAuth = "21.2.0"
playServicesBase = "18.5.0"
```

Append to the `[libraries]` block:

```toml
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
play-services-auth = { group = "com.google.android.gms", name = "play-services-auth", version.ref = "playServicesAuth" }
play-services-base = { group = "com.google.android.gms", name = "play-services-base", version.ref = "playServicesBase" }
```

- [ ] **Step 2: Add dependencies to app/build.gradle.kts**

In the `dependencies { }` block, add:

```kotlin
implementation(libs.okhttp)
implementation(libs.play.services.auth)
implementation(libs.play.services.base)
testImplementation(libs.okhttp.mockwebserver)
```

Also add a `buildConfigField` so `BuildConfig.DEFAULT_WEB_CLIENT_ID` resolves at compile time. Place this inside the `defaultConfig { }` block, after `vectorDrawables`:

```kotlin
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
buildConfigField(
    "String",
    "DEFAULT_WEB_CLIENT_ID",
    "\"${localProps.getProperty("google.web.client.id", "")}\"",
)
```

Note: `buildConfig = true` is already enabled in `app/build.gradle.kts:72`.

- [ ] **Step 3: Verify Gradle resolves the new deps**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>&1 | grep -E "okhttp|play-services-auth" | head -10
```

Expected: at least one line for `okhttp` and `play-services-auth`. Build does not need to compile yet (no source uses these).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "build(sync): add OkHttp + Play Services Auth deps (Phase 4b)"
```

---

### Task 2: Widen CloudSyncRepository + PushResult + NoOp update + 2 tests

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt`

- [ ] **Step 1: Add the failing tests for NoOp's new members**

Append to `app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt` (after the last existing `@Test`):

```kotlin
@Test
fun signInIntent_isNonNullEmptyIntent() = runTest {
    assertNotNull(repo.signInIntent)
}

@Test
fun handleSignInResult_returnsSuccess() = runTest {
    assertEquals(SignInResult.Success, repo.handleSignInResult(null))
}
```

Add the `assertNotNull` import at the top of the file:
```kotlin
import org.junit.Assert.assertNotNull
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*NoOpCloudSyncRepositoryTest*" 2>&1 | tail -15
```

Expected: FAIL with `Unresolved reference: signInIntent` (and `handleSignInResult`).

- [ ] **Step 3: Widen CloudSyncRepository.kt**

Replace the entire contents of `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt` with:

```kotlin
package io.github.jiro.expensetracker.sync

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for any cloud-sync provider. 4a ships NoOpCloudSyncRepository;
 * 4b swaps in a Drive-backed implementation and 4c swaps in a Dropbox-
 * backed one. Callers depend only on this interface, so the swap is a
 * single Hilt binding.
 *
 * `internal` because its methods expose `internal` types ([SyncSnapshot],
 * [SignInResult], [PushResult], [PullResult], [SyncResult]) — widen
 * visibility if a future consumer outside `sync/` needs it.
 */
internal interface CloudSyncRepository {
    val state: StateFlow<SyncState>
    val lastSyncedAtEpochMillis: StateFlow<Long?>
    val isSignedIn: StateFlow<Boolean>

    /**
     * Pre-built OAuth sign-in Intent. The caller (typically a 4d Activity)
     * launches this via `ActivityResultLauncher<Intent>` and forwards the
     * result back via [handleSignInResult].
     */
    val signInIntent: Intent

    /**
     * Consumes the OAuth result Intent returned by [signInIntent]'s
     * launcher. Returns Success when tokens are persisted and the state
     * transitions to SignedIn, Failed otherwise.
     */
    suspend fun handleSignInResult(data: Intent?): SignInResult

    /**
     * Silent sign-in path: returns Success if a cached token is still
     * valid (or refreshed), Failed otherwise. Does NOT launch UI.
     */
    suspend fun signIn(): SignInResult

    suspend fun signOut()
    suspend fun push(snapshot: SyncSnapshot): PushResult
    suspend fun pull(): PullResult<SyncSnapshot>
    suspend fun syncOnce(): SyncResult
}
```

- [ ] **Step 4: Convert PushResult to a sealed class**

Replace the entire contents of `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt` with:

```kotlin
package io.github.jiro.expensetracker.sync

internal sealed class PushResult {
    internal data class Pushed(val pushedAtEpochMillis: Long) : PushResult()
    internal data class Failed(val message: String, val cause: Throwable? = null) : PushResult()
}

internal sealed class PullResult<out T> {
    internal data class Success<T>(val snapshot: T, val pulledAtEpochMillis: Long) : PullResult<T>()
    internal object NoRemoteSnapshot : PullResult<Nothing>()
    internal data class Conflict(val remote: SyncSnapshot, val local: SyncSnapshot) : PullResult<Nothing>()
    internal data class Failed(val message: String, val cause: Throwable? = null) : PullResult<Nothing>()
}

internal sealed class SyncResult {
    internal data class Pushed(val pushedAtEpochMillis: Long) : SyncResult()
    internal data class Pulled(val snapshot: SyncSnapshot, val pulledAtEpochMillis: Long) : SyncResult()
    internal object NoRemoteSnapshot : SyncResult()
    internal data class Failed(val message: String, val cause: Throwable? = null) : SyncResult()
}

internal sealed class SignInResult {
    internal object Success : SignInResult()
    internal data class Failed(val message: String, val cause: Throwable? = null) : SignInResult()
}
```

- [ ] **Step 5: Update NoOpCloudSyncRepository to satisfy the widened interface**

Replace the entire contents of `app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt` with:

```kotlin
package io.github.jiro.expensetracker.sync

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Structural placeholder for 4a. Every method exists so the contract
 * compiles, but no I/O happens. 4b/4c replace this with a real provider
 * via a single Hilt binding swap.
 *
 * - signIn() / handleSignInResult(): return Success without I/O — useful
 *   as a test stub and a future fallback if Drive wiring is disabled.
 * - signOut()   : flips state back to SignedOut.
 * - push(...)   : throws — the contract exists, no real backend yet.
 * - pull()      : returns NoRemoteSnapshot — there is no remote.
 * - syncOnce()  : returns NoRemoteSnapshot — pull is the no-op result.
 */
@Singleton
internal class NoOpCloudSyncRepository @Inject constructor() : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val signInIntent: Intent = Intent()

    override suspend fun signIn(): SignInResult {
        _state.value = SyncState.SignedIn("noop")
        return SignInResult.Success
    }

    override suspend fun handleSignInResult(data: Intent?): SignInResult {
        _state.value = SyncState.SignedIn("noop")
        return SignInResult.Success
    }

    override suspend fun signOut() {
        _state.value = SyncState.SignedOut
    }

    override suspend fun push(snapshot: SyncSnapshot): PushResult {
        throw NotImplementedError("push not available in NoOpCloudSyncRepository")
    }

    override suspend fun pull(): PullResult<SyncSnapshot> = PullResult.NoRemoteSnapshot

    override suspend fun syncOnce(): SyncResult = SyncResult.NoRemoteSnapshot
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*NoOpCloudSyncRepositoryTest*" 2>&1 | tail -15
```

Expected: 10 tests (8 original + 2 new), 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): widen CloudSyncRepository + PushResult sealed (Phase 4b)"
```

---

### Task 3: DriveApiClient — interface, exception, OkHttp impl, 8 MockWebServer tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiException.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClient.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientTest.kt`:

```kotlin
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
            baseUrl = baseUrl,
            tokenProvider = { token },
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
        assertEquals("/upload/drive/v3/files/abc123", req.path)
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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DriveApiClientTest*" 2>&1 | tail -15
```

Expected: FAIL with `Unresolved reference: DriveApiClientImpl`.

- [ ] **Step 3: Create DriveApiException.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiException.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

internal sealed class DriveApiException(message: String, cause: Throwable? = null)
    : RuntimeException(message, cause) {
    object AuthRevoked : DriveApiException("Auth revoked (401)")
    object QuotaExceeded : DriveApiException("Drive quota exceeded (403)")
    object RateLimited : DriveApiException("Drive rate limit exceeded (429)")
    data class ServerError(val httpCode: Int) : DriveApiException("Drive server error ($httpCode)")
    object NotFound : DriveApiException("Drive file not found (404)")
    data class Generic(val httpCode: Int, val reason: String) :
        DriveApiException("Drive rejected request ($httpCode): $reason")
}
```

- [ ] **Step 4: Create DriveApiClient.kt (interface)**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClient.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

/**
 * Minimal interface to Drive REST v3 — only what sync needs (upload + download
 * by file ID). Implementations must throw [DriveApiException] subtypes on
 * failure; never return null except for [download] when the remote file is
 * missing (HTTP 404).
 */
internal interface DriveApiClient {
    /**
     * Upload [body] as [mimeType]. When [fileId] is null, creates a new file
     * and returns its ID. When [fileId] is non-null, replaces the file's
     * contents and returns the same ID.
     */
    suspend fun upload(fileId: String?, body: String, mimeType: String): String

    /**
     * Download the file with [fileId]. Returns null if the file does not
     * exist (HTTP 404). Throws [DriveApiException] subtypes on other errors.
     */
    suspend fun download(fileId: String): String?
}
```

- [ ] **Step 5: Create DriveApiClientImpl.kt (OkHttp implementation)**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Singleton
internal class DriveApiClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://www.googleapis.com",
    private val tokenProvider: () -> String?,
) : DriveApiClient {

    override suspend fun upload(fileId: String?, body: String, mimeType: String): String =
        withContext(Dispatchers.IO) {
            val metadata = if (fileId == null) {
                JSONObject().apply {
                    put("name", FILE_NAME)
                    put("mimeType", mimeType)
                }
            } else {
                JSONObject().apply {
                    put("name", FILE_NAME)
                    put("mimeType", mimeType)
                }
            }

            val multipart = MultipartBody.Builder()
                .setType("multipart/related; boundary=expense_tracker_sync")
                .addPart(
                    headersOf("Content-Type", "application/json; charset=UTF-8"),
                    metadata.toString().toRequestBody(JSON),
                )
                .addPart(
                    headersOf("Content-Type", mimeType),
                    body.toRequestBody(mimeType.toMediaType()),
                )
                .build()

            val url = if (fileId == null) {
                "$baseUrl/upload/drive/v3/files?uploadType=multipart"
            } else {
                "$baseUrl/upload/drive/v3/files/$fileId?uploadType=multipart"
            }

            val request = Request.Builder()
                .url(url)
                .apply {
                    val token = tokenProvider() ?: throw DriveApiException.AuthRevoked
                    header("Authorization", "Bearer $token")
                }
                .apply {
                    if (fileId == null) post(multipart) else patch(multipart)
                }
                .build()

            executeWithRetry(request) { resp ->
                val text = resp.body?.string().orEmpty()
                val id = JSONObject(text).optString("id", "")
                require(id.isNotEmpty()) { "Drive upload returned no id: $text" }
                id
            }
        }

    override suspend fun download(fileId: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/drive/v3/files/$fileId?alt=media")
                .apply {
                    val token = tokenProvider() ?: throw DriveApiException.AuthRevoked
                    header("Authorization", "Bearer $token")
                }
                .get()
                .build()

            try {
                executeWithRetry(request, retryOn404 = false) { resp -> resp.body?.string().orEmpty() }
            } catch (e: DriveApiException.NotFound) {
                null
            }
        }

    private suspend fun <T> executeWithRetry(
        request: Request,
        retryOn404: Boolean = true,
        parse: (okhttp3.Response) -> T,
    ): T {
        var attempt = 0
        var response: okhttp3.Response? = null
        try {
            while (true) {
                response = httpClient.newCall(request).execute()
                val code = response.code
                when {
                    code in 200..299 -> return parse(response).also { response.close() }
                    code == 401 -> throw DriveApiException.AuthRevoked
                    code == 403 -> throw DriveApiException.QuotaExceeded
                    code == 404 && retryOn404 -> throw DriveApiException.NotFound
                    code == 404 -> throw DriveApiException.NotFound
                    code == 429 -> {
                        response.close()
                        val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 0L
                        attempt++
                        if (attempt >= MAX_RETRIES_429) throw DriveApiException.RateLimited
                        delay(retryAfter * 1000L)
                    }
                    code in 500..599 -> {
                        response.close()
                        attempt++
                        if (attempt >= MAX_RETRIES_5XX) throw DriveApiException.ServerError(code)
                        delay(RETRY_BACKOFF_5XX_MS)
                    }
                    else -> {
                        val body = response.body?.string().orEmpty()
                        response.close()
                        throw DriveApiException.Generic(code, body)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: IOException) {
            response?.close()
            throw DriveApiException.Generic(0, e.message ?: "I/O error")
        }
    }

    private companion object {
        const val FILE_NAME = "ExpenseTracker-sync.json"
        const val MAX_RETRIES_429 = 3
        const val MAX_RETRIES_5XX = 2
        const val RETRY_BACKOFF_5XX_MS = 1000L
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DriveApiClientTest*" 2>&1 | tail -20
```

Expected: 8 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiException.kt app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClient.kt app/src/main/java/io/github/jiro/expensetracker/sync/google/DriveApiClientImpl.kt app/src/test/java/io/github/jiro/expensetracker/sync/google/DriveApiClientTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): DriveApiClient with OkHttp multipart upload (Phase 4b)"
```

---

### Task 4: SyncTokensRepository + TokenCrypto + 3 Robolectric tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/TokenCrypto.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokens.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepositoryTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncTokensRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: SyncTokensRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe any persisted tokens between tests
        context.getSharedPreferences("sync_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        repo = DefaultSyncTokensRepository(context)
    }

    @Test
    fun save_thenLoad_returnsSameTokens() = runBlocking {
        val original = SyncTokens(
            accessToken = "access-abc",
            refreshToken = "refresh-xyz",
            expiresAtEpochMillis = 1_700_000_000_000L,
            accountEmail = "user@example.com",
            snapshotFileId = "drive-file-id-1",
        )
        repo.save(original)
        val loaded = repo.load()
        assertEquals(original, loaded)
    }

    @Test
    fun clear_removesAllTokens() = runBlocking {
        repo.save(
            SyncTokens(
                accessToken = "a",
                refreshToken = "r",
                expiresAtEpochMillis = 1L,
                accountEmail = "u@e.com",
                snapshotFileId = null,
            ),
        )
        repo.clear()
        assertNull(repo.load())
    }

    @Test
    fun load_returnsNull_afterClearWithoutPriorSave() = runBlocking {
        assertNull(repo.load())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*SyncTokensRepositoryTest*" 2>&1 | tail -15
```

Expected: FAIL with `Unresolved reference: DefaultSyncTokensRepository`.

- [ ] **Step 3: Create SyncTokens.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokens.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

internal data class SyncTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val accountEmail: String,
    val snapshotFileId: String?,
)
```

- [ ] **Step 4: Create TokenCrypto.kt (Keystore AES-GCM wrapper)**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/TokenCrypto.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * AES-256-GCM encryption with a key held in Android Keystore. The key
 * never leaves the TEE/StrongBox (when available). Per-value random IVs
 * (12 bytes) are prepended to ciphertext; base64 is used for storage.
 *
 * User authentication is NOT required (setUserAuthenticationRequired(false))
 * so the sync flow stays non-interactive. The Keystore-backed key is
 * strong enough without a biometric gate; on lock-screen change the key
 * is invalidated and TokenCrypto throws — caller wipes DataStore.
 */
internal class TokenCrypto {

    fun encrypt(plaintext: String): String {
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

    fun decrypt(ciphertextB64: String): String {
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
        const val KEY_ALIAS = "expensetracker_sync_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
```

- [ ] **Step 5: Create SyncTokensRepository.kt (interface + impl)**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface SyncTokensRepository {
    suspend fun load(): SyncTokens?
    suspend fun save(tokens: SyncTokens)
    suspend fun clear()
}

@Singleton
internal class DefaultSyncTokensRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: TokenCrypto = TokenCrypto(),
) : SyncTokensRepository {

    // SharedPreferences (not DataStore) — small, infrequent writes, no Flow
    // observers needed. Crypto handles the security boundary; the prefs file
    // holds ciphertext only.
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): SyncTokens? = withContext(Dispatchers.IO) {
        val access = prefs.getString(K_ACCESS, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext null
        val refresh = prefs.getString(K_REFRESH, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        val expires = prefs.getString(K_EXPIRES, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        val email = prefs.getString(K_EMAIL, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        val fileId = prefs.getString(K_FILE_ID, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }

        SyncTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMillis = expires.toLong(),
            accountEmail = email,
            snapshotFileId = fileId,
        )
    }

    override suspend fun save(tokens: SyncTokens) = withContext(Dispatchers.IO) {
        prefs.edit {
            putString(K_ACCESS, crypto.encrypt(tokens.accessToken))
            putString(K_REFRESH, crypto.encrypt(tokens.refreshToken))
            putString(K_EXPIRES, crypto.encrypt(tokens.expiresAtEpochMillis.toString()))
            putString(K_EMAIL, crypto.encrypt(tokens.accountEmail))
            if (tokens.snapshotFileId != null) {
                putString(K_FILE_ID, crypto.encrypt(tokens.snapshotFileId))
            } else {
                remove(K_FILE_ID)
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }

    private fun wipeAndNull(): SyncTokens? {
        prefs.edit().clear().apply()
        return null
    }

    private companion object {
        const val PREFS_NAME = "sync_tokens"
        const val K_ACCESS = "access_token_b64"
        const val K_REFRESH = "refresh_token_b64"
        const val K_EXPIRES = "expires_at_b64"
        const val K_EMAIL = "account_email_b64"
        const val K_FILE_ID = "snapshot_file_id_b64"
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*SyncTokensRepositoryTest*" 2>&1 | tail -20
```

Expected: 3 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/google/TokenCrypto.kt app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokens.kt app/src/main/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepository.kt app/src/test/java/io/github/jiro/expensetracker/sync/google/SyncTokensRepositoryTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): SyncTokensRepository with Keystore AES-GCM (Phase 4b)"
```

---

### Task 5: GoogleAuth + GoogleSignInAuthImpl + FakeGoogleAuth

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAccountSnapshot.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAuth.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleSignInAuthImpl.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeGoogleAuth.kt`

- [ ] **Step 1: Create GoogleAccountSnapshot.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAccountSnapshot.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

internal data class GoogleAccountSnapshot(
    val email: String,
    val serverAuthCode: String?,
    val idToken: String?,
)
```

- [ ] **Step 2: Create GoogleAuth.kt (interface)**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAuth.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

import android.content.Intent

/**
 * Thin wrapper around Play Services Auth. Production impl uses
 * [com.google.android.gms.auth.api.signin.GoogleSignIn]; tests use a fake.
 */
internal interface GoogleAuth {
    /** Returns the most recently signed-in account, or null. */
    suspend fun getLastSignedInAccount(): GoogleAccountSnapshot?

    /** Builds the OAuth sign-in Intent for [handleSignInResult]. */
    fun buildSignInIntent(): Intent

    /** Extracts the signed-in account from the OAuth result Intent. */
    suspend fun extractAccountFromResult(data: Intent?): GoogleAccountSnapshot?
}
```

- [ ] **Step 3: Create GoogleSignInAuthImpl.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleSignInAuthImpl.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@Singleton
internal class GoogleSignInAuthImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : GoogleAuth {

    private val client by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(webClientId.takeIf { it.isNotEmpty() })
            .requestServerAuthCode(webClientId.takeIf { it.isNotEmpty() })
            .build()
        GoogleSignIn.getClient(context, options)
    }

    override suspend fun getLastSignedInAccount(): GoogleAccountSnapshot? =
        withContext(Dispatchers.IO) {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            account.toSnapshot()
        }

    override fun buildSignInIntent(): Intent = client.signInIntent

    override suspend fun extractAccountFromResult(data: Intent?): GoogleAccountSnapshot? =
        withContext(Dispatchers.IO) {
            if (data == null) return@withContext null
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                task.await().toSnapshot()
            } catch (e: Exception) {
                null
            }
        }

    private fun GoogleSignInAccount.toSnapshot(): GoogleAccountSnapshot =
        GoogleAccountSnapshot(
            email = email.orEmpty(),
            serverAuthCode = serverAuthCode,
            idToken = idToken,
        )

    private companion object {
        val webClientId: String get() = BuildConfig.DEFAULT_WEB_CLIENT_ID
    }
}
```

- [ ] **Step 4: Create FakeGoogleAuth.kt**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeGoogleAuth.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

import android.content.Intent

internal class FakeGoogleAuth : GoogleAuth {
    var lastAccount: GoogleAccountSnapshot? = null
    var extractResult: GoogleAccountSnapshot? = null
    var signInIntentValue: Intent = Intent()

    override suspend fun getLastSignedInAccount(): GoogleAccountSnapshot? = lastAccount
    override fun buildSignInIntent(): Intent = signInIntentValue
    override suspend fun extractAccountFromResult(data: Intent?): GoogleAccountSnapshot? = extractResult
}
```

- [ ] **Step 5: Verify the project compiles (no tests for this task)**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. (GoogleSignInAuthImpl uses Play Services Auth + Tasks API which the dependency added in Task 1.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAccountSnapshot.kt app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleAuth.kt app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleSignInAuthImpl.kt app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeGoogleAuth.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): GoogleAuth + Play Services wrapper (Phase 4b)"
```

---

### Task 6: FakeDriveApiClient

**Files:**
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeDriveApiClient.kt`

- [ ] **Step 1: Create FakeDriveApiClient.kt**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeDriveApiClient.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

internal class FakeDriveApiClient : DriveApiClient {
    /** When non-null, every upload throws this. */
    var uploadError: DriveApiException? = null

    /** When non-null, every download throws this. */
    var downloadError: DriveApiException? = null

    /** Body returned by the next download call. */
    var downloadBody: String? = null

    /** Recorded uploads: (fileId, body, mimeType). */
    val uploads = mutableListOf<Triple<String?, String, String>>()

    /** Recorded downloads. */
    val downloads = mutableListOf<String>()

    /** ID returned by the next upload call. Default "fake-file-id". */
    var nextUploadId: String = "fake-file-id"

    override suspend fun upload(fileId: String?, body: String, mimeType: String): String {
        uploads.add(Triple(fileId, body, mimeType))
        uploadError?.let { throw it }
        return nextUploadId
    }

    override suspend fun download(fileId: String): String? {
        downloads.add(fileId)
        downloadError?.let { throw it }
        return downloadBody
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugUnitTestKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/io/github/jiro/expensetracker/sync/google/FakeDriveApiClient.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "test(sync): FakeDriveApiClient for orchestrator tests (Phase 4b)"
```

---

### Task 7: GoogleDriveCloudSyncRepository + 12 Robolectric tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepositoryTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

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
class GoogleDriveCloudSyncRepositoryTest {

    private lateinit var auth: FakeGoogleAuth
    private lateinit var api: FakeDriveApiClient
    private lateinit var tokens: SyncTokensRepository
    private lateinit var repo: GoogleDriveCloudSyncRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Wipe persisted tokens between tests
        context.getSharedPreferences("sync_tokens", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        auth = FakeGoogleAuth()
        api = FakeDriveApiClient()
        tokens = DefaultSyncTokensRepository(context)
        repo = GoogleDriveCloudSyncRepository(
            context = context,
            googleAuth = auth,
            api = api,
            tokens = tokens,
            tokenExchangeClient = FakeTokenExchangeClient(),
            nowProvider = { 1_700_000_000_000L },
        )
    }

    @Test
    fun signInIntent_isNotNull() = runBlocking {
        assertNotNull(repo.signInIntent)
    }

    @Test
    fun handleSignInResult_persistsTokens_onSuccess() = runBlocking {
        auth.extractResult = GoogleAccountSnapshot(
            email = "user@example.com",
            serverAuthCode = "code-abc",
            idToken = null,
        )
        val result = repo.handleSignInResult(android.content.Intent())
        assertEquals(SignInResult.Success, result)
        val saved = tokens.load()
        assertEquals("user@example.com", saved?.accountEmail)
        assertTrue(saved?.accessToken?.isNotEmpty() == true)
        assertEquals(SyncState.SignedIn("google_drive"), repo.state.first())
    }

    @Test
    fun handleSignInResult_returnsFailed_whenCancelled() = runBlocking {
        auth.extractResult = null
        val result = repo.handleSignInResult(null)
        assertTrue(result is SignInResult.Failed)
        assertEquals(SyncState.SignedOut, repo.state.first())
    }

    @Test
    fun handleSignInResult_returnsFailed_whenServerAuthCodeMissing() = runBlocking {
        auth.extractResult = GoogleAccountSnapshot(email = "u@e.com", serverAuthCode = null, idToken = null)
        val result = repo.handleSignInResult(android.content.Intent())
        assertTrue(result is SignInResult.Failed)
    }

    @Test
    fun push_createsFile_whenNoSnapshotFileId() = runBlocking {
        // First sign in so push can run
        auth.extractResult = GoogleAccountSnapshot(email = "u@e.com", serverAuthCode = "code", idToken = null)
        repo.handleSignInResult(android.content.Intent())
        api.uploads.clear()

        val snapshot = sampleSnapshot()
        val result = repo.push(snapshot)
        assertTrue("Expected PushResult.Pushed, got $result", result is PushResult.Pushed)
        assertEquals(1, api.uploads.size)
        assertNull("upload must be a CREATE (fileId=null) when no file id is stored", api.uploads.first().first)
        // After first push, tokens should now have a file id
        val saved = tokens.load()
        assertEquals("fake-file-id", saved?.snapshotFileId)
    }

    @Test
    fun push_patchesFile_whenSnapshotFileIdExists() = runBlocking {
        // Pre-seed tokens with a known file id
        tokens.save(
            SyncTokens(
                accessToken = "tok",
                refreshToken = "ref",
                expiresAtEpochMillis = 1_700_000_000_000L + 3_600_000L,
                accountEmail = "u@e.com",
                snapshotFileId = "existing-id",
            ),
        )
        // Sign in via the silent path so state goes to SignedIn
        repo.signIn()

        api.uploads.clear()
        val snapshot = sampleSnapshot()
        val result = repo.push(snapshot)
        assertTrue(result is PushResult.Pushed)
        assertEquals(1, api.uploads.size)
        assertEquals("existing-id", api.uploads.first().first) // PATCH path
    }

    @Test
    fun pull_returnsSuccess_whenRemoteSnapshotDecodes() = runBlocking {
        tokens.save(
            SyncTokens(
                accessToken = "tok", refreshToken = "ref",
                expiresAtEpochMillis = 1_700_000_000_000L + 3_600_000L,
                accountEmail = "u@e.com", snapshotFileId = "remote-id",
            ),
        )
        repo.signIn()
        val snapshot = sampleSnapshot()
        api.downloadBody = SyncSnapshotCodec.encode(snapshot)

        val result = repo.pull()
        assertTrue(result is PullResult.Success<*>)
        assertEquals(1, api.downloads.size)
        assertEquals("remote-id", api.downloads.first())
    }

    @Test
    fun pull_returnsNoRemoteSnapshot_whenFileIdNull() = runBlocking {
        // No tokens, no signed-in state
        assertEquals(PullResult.NoRemoteSnapshot, repo.pull())
    }

    @Test
    fun pull_returnsNoRemoteSnapshot_whenHttp404() = runBlocking {
        tokens.save(
            SyncTokens(
                accessToken = "tok", refreshToken = "ref",
                expiresAtEpochMillis = 1_700_000_000_000L + 3_600_000L,
                accountEmail = "u@e.com", snapshotFileId = "missing",
            ),
        )
        repo.signIn()
        api.downloadError = DriveApiException.NotFound
        assertEquals(PullResult.NoRemoteSnapshot, repo.pull())
    }

    @Test
    fun pull_returnsFailed_whenChecksumMismatch() = runBlocking {
        tokens.save(
            SyncTokens(
                accessToken = "tok", refreshToken = "ref",
                expiresAtEpochMillis = 1_700_000_000_000L + 3_600_000L,
                accountEmail = "u@e.com", snapshotFileId = "remote-id",
            ),
        )
        repo.signIn()
        api.downloadBody = "this-is-not-valid-json-at-all"
        val result = repo.pull()
        assertTrue(result is PullResult.Failed)
    }

    @Test
    fun syncOnce_returnsPulled_onSuccess() = runBlocking {
        tokens.save(
            SyncTokens(
                accessToken = "tok", refreshToken = "ref",
                expiresAtEpochMillis = 1_700_000_000_000L + 3_600_000L,
                accountEmail = "u@e.com", snapshotFileId = "remote-id",
            ),
        )
        repo.signIn()
        val snapshot = sampleSnapshot()
        api.downloadBody = SyncSnapshotCodec.encode(snapshot)

        val result = repo.syncOnce()
        assertTrue(result is io.github.jiro.expensetracker.sync.SyncResult.Pulled)
    }

    @Test
    fun push_returnsFailed_whenStateNotSignedIn() = runBlocking {
        // Never signed in — state is SignedOut
        val result = repo.push(sampleSnapshot())
        assertTrue(result is PushResult.Failed)
    }

    @Test
    fun signOut_clearsTokens_andFlipsState() = runBlocking {
        tokens.save(
            SyncTokens(
                accessToken = "tok", refreshToken = "ref",
                expiresAtEpochMillis = 1_700_000_000_000L + 3_600_000L,
                accountEmail = "u@e.com", snapshotFileId = "x",
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

/** Test fake for the OAuth code-exchange HTTP call. Returns a fixed token shape. */
internal class FakeTokenExchangeClient : TokenExchangeClient {
    override suspend fun exchangeCode(code: String, email: String): ExchangeResult =
        ExchangeResult(
            accessToken = "exchanged-access-for-$code",
            refreshToken = "exchanged-refresh-for-$code",
            expiresInSeconds = 3600L,
        )
}
```

Note: `TokenExchangeClient` is referenced but not yet defined. It will be added in the implementation step.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*GoogleDriveCloudSyncRepositoryTest*" 2>&1 | tail -15
```

Expected: FAIL with `Unresolved reference: GoogleDriveCloudSyncRepository`.

- [ ] **Step 3: Create GoogleDriveCloudSyncRepository.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.BuildConfig
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.PullResult
import io.github.jiro.expensetracker.sync.PushResult
import io.github.jiro.expensetracker.sync.SignInResult
import io.github.jiro.expensetracker.sync.SyncResult
import io.github.jiro.expensetracker.sync.SyncSnapshot
import io.github.jiro.expensetracker.sync.SyncSnapshotCodec
import io.github.jiro.expensetracker.sync.SyncState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
internal class GoogleDriveCloudSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googleAuth: GoogleAuth,
    private val api: DriveApiClient,
    private val tokens: SyncTokensRepository,
    private val tokenExchangeClient: TokenExchangeClient = DefaultTokenExchangeClient(OkHttpClient()),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : CloudSyncRepository {

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined,
    )
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)
    private val mutex = Mutex()

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val signInIntent: Intent = googleAuth.buildSignInIntent()

    override suspend fun signIn(): SignInResult = withContext(Dispatchers.IO) {
        val cached = tokens.load()
        if (cached != null && cached.expiresAtEpochMillis > nowProvider() + 60_000L) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            return@withContext SignInResult.Success
        }
        // Try silent re-auth via Play Services
        val account = googleAuth.getLastSignedInAccount()
            ?: return@withContext SignInResult.Failed("Not signed in")
        return@withContext refreshTokens(account)
    }

    override suspend fun handleSignInResult(data: Intent?): SignInResult = withContext(Dispatchers.IO) {
        val account = googleAuth.extractAccountFromResult(data)
            ?: return@withContext SignInResult.Failed("Sign-in cancelled")
        val code = account.serverAuthCode
            ?: return@withContext SignInResult.Failed("Could not get auth code")
        try {
            val exchange = tokenExchangeClient.exchangeCode(code, account.email)
            tokens.save(
                SyncTokens(
                    accessToken = exchange.accessToken,
                    refreshToken = exchange.refreshToken,
                    expiresAtEpochMillis = nowProvider() + exchange.expiresInSeconds * 1000L,
                    accountEmail = account.email,
                    snapshotFileId = null,
                ),
            )
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            SignInResult.Success
        } catch (e: Exception) {
            SignInResult.Failed("Token exchange failed: ${e.message}", e)
        }
    }

    private suspend fun refreshTokens(account: GoogleAccountSnapshot): SignInResult {
        // For 4b, we don't yet implement actual refresh — if cached token is
        // expired, prompt re-auth via signInIntent. 4c may add refresh.
        _state.value = SyncState.SignedOut
        return SignInResult.Failed("Session expired — please sign in again")
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
        _state.value = SyncState.Syncing(io.github.jiro.expensetracker.sync.Operation.PUSH)
        try {
            val body = SyncSnapshotCodec.encode(snapshot)
            val cached = tokens.load()
            val existingId = cached?.snapshotFileId
            val newId = api.upload(existingId, body, MIME_TYPE)
            if (existingId == null && cached != null) {
                tokens.save(cached.copy(snapshotFileId = newId))
            }
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PushResult.Pushed(pushedAtEpochMillis = snapshot.lastModifiedEpochMillis)
        } catch (e: DriveApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PushResult.Failed("Session expired — please sign in again", e)
        } catch (e: DriveApiException) {
            PushResult.Failed(e.message ?: "Drive error", e)
        } catch (e: Exception) {
            PushResult.Failed("Push failed: ${e.message}", e)
        }
    }

    override suspend fun pull(): PullResult<SyncSnapshot> = mutex.withLock {
        val current = _state.value
        if (current !is SyncState.SignedIn) {
            return@withLock PullResult.Failed("Not signed in", null)
        }
        _state.value = SyncState.Syncing(io.github.jiro.expensetracker.sync.Operation.PULL)
        try {
            val cached = tokens.load()
            val fileId = cached?.snapshotFileId
                ?: return@withLock PullResult.NoRemoteSnapshot.also { _state.value = SyncState.SignedIn(PROVIDER_ID) }
            val body = api.download(fileId)
                ?: return@withLock PullResult.NoRemoteSnapshot.also { _state.value = SyncState.SignedIn(PROVIDER_ID) }
            val snapshot = SyncSnapshotCodec.decode(body)
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PullResult.Success(snapshot, pulledAtEpochMillis = nowProvider())
        } catch (e: DriveApiException.NotFound) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.NoRemoteSnapshot
        } catch (e: DriveApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PullResult.Failed("Session expired", e)
        } catch (e: DriveApiException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(e.message ?: "Drive error", e)
        } catch (e: io.github.jiro.expensetracker.sync.SyncException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(when (e.code) {
                io.github.jiro.expensetracker.sync.SyncErrorCode.CHECKSUM_MISMATCH ->
                    "Remote snapshot failed integrity check"
                io.github.jiro.expensetracker.sync.SyncErrorCode.SCHEMA_INCOMPATIBLE ->
                    "Remote snapshot was written by a newer app version"
                io.github.jiro.expensetracker.sync.SyncErrorCode.MALFORMED ->
                    "Remote snapshot is corrupted"
            }, e)
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
            is PullResult.Conflict<*> -> SyncResult.Failed("Unexpected conflict", null)
        }
    }

    private companion object {
        const val PROVIDER_ID = "google_drive"
        const val MIME_TYPE = "application/json"
    }
}

/**
 * Wraps the OAuth code-exchange HTTP call. Separate from the repo for
 * testability — tests inject a fake that returns canned tokens.
 */
internal interface TokenExchangeClient {
    suspend fun exchangeCode(code: String, email: String): ExchangeResult
}

internal data class ExchangeResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

internal class DefaultTokenExchangeClient(
    private val httpClient: OkHttpClient,
    private val clientId: String = BuildConfig.DEFAULT_WEB_CLIENT_ID,
    private val clientSecret: String = "", // empty for installed apps; Google handles PKCE via Play Services
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
) : TokenExchangeClient {

    override suspend fun exchangeCode(code: String, email: String): ExchangeResult {
        val form = FormBody.Builder()
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", "")
            .build()
        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(form)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "Token exchange failed ($resp): $body" }
            val json = org.json.JSONObject(body)
            return ExchangeResult(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token"),
                expiresInSeconds = json.optLong("expires_in", 3600L),
            )
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*GoogleDriveCloudSyncRepositoryTest*" 2>&1 | tail -25
```

Expected: 12 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepository.kt app/src/test/java/io/github/jiro/expensetracker/sync/google/GoogleDriveCloudSyncRepositoryTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): GoogleDriveCloudSyncRepository orchestrator (Phase 4b)"
```

---

### Task 8: Hilt module + manifest + strings + SyncModule @Binds flip

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/google/di/GoogleDriveModule.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt`

- [ ] **Step 1: Add INTERNET permission**

In `app/src/main/AndroidManifest.xml`, after the `<uses-feature>` line for the camera, add:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: Add strings to res/values/strings.xml**

Append to the end of `app/src/main/res/values/strings.xml` (inside `<resources>`):

```xml
<string name="default_web_client_id" translatable="false"></string>
<string name="google_drive_provider_id" translatable="false">google_drive</string>
```

The `default_web_client_id` is empty in v1 — the developer pastes the OAuth Web client ID from Google Cloud Console here (or overrides via `local.properties` + `buildConfigField` if they prefer code-only injection).

- [ ] **Step 3: Create GoogleDriveModule.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/google/di/GoogleDriveModule.kt`:

```kotlin
package io.github.jiro.expensetracker.sync.google.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.google.DriveApiClient
import io.github.jiro.expensetracker.sync.google.DriveApiClientImpl
import io.github.jiro.expensetracker.sync.google.GoogleAuth
import io.github.jiro.expensetracker.sync.google.GoogleDriveCloudSyncRepository
import io.github.jiro.expensetracker.sync.google.GoogleSignInAuthImpl
import io.github.jiro.expensetracker.sync.google.SyncTokensRepository
import io.github.jiro.expensetracker.sync.google.DefaultSyncTokensRepository
import io.github.jiro.expensetracker.sync.google.TokenCrypto
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GoogleDriveModule {

    @Binds
    @Singleton
    abstract fun bindCloudSyncRepository(
        impl: GoogleDriveCloudSyncRepository,
    ): CloudSyncRepository

    @Binds
    @Singleton
    abstract fun bindGoogleAuth(
        impl: GoogleSignInAuthImpl,
    ): GoogleAuth

    @Binds
    @Singleton
    abstract fun bindDriveApiClient(
        impl: DriveApiClientImpl,
    ): DriveApiClient

    @Binds
    @Singleton
    abstract fun bindSyncTokensRepository(
        impl: DefaultSyncTokensRepository,
    ): SyncTokensRepository

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        @Provides
        @Singleton
        fun provideTokenCrypto(): TokenCrypto = TokenCrypto()
    }
}
```

`internal abstract class` because all sync types are internal (visibility cascade from 4a).

- [ ] **Step 4: Remove the conflicting @Binds from SyncModule.kt**

Replace the entire contents of `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt` with:

```kotlin
package io.github.jiro.expensetracker.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.sync.DeviceIdProvider
import io.github.jiro.expensetracker.sync.DefaultDeviceIdProvider
import dagger.Binds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindDeviceIdProvider(
        impl: DefaultDeviceIdProvider,
    ): DeviceIdProvider
}
```

`CloudSyncRepository` is no longer bound here — `GoogleDriveModule` owns that binding. This means the `@Binds` swap from 4a (NoOp → Drive) is implemented exactly as planned.

- [ ] **Step 5: Verify the Hilt graph builds**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL. Any Hilt graph error (e.g., duplicate `CloudSyncRepository` binding) would surface here.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/java/io/github/jiro/expensetracker/sync/google/di/GoogleDriveModule.kt app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): Hilt GoogleDriveModule + manifest INTERNET (Phase 4b)"
```

---

### Task 9: Smoke test doc + full test suite + tag v0.18.13 + push

**Files:**
- Create: `docs/superpowers/testdata/phase-4b-google-drive.md`

- [ ] **Step 1: Write the smoke test document**

Create `docs/superpowers/testdata/phase-4b-google-drive.md`:

```markdown
# Phase 4b — Google Drive Provider — Smoke Test

## Scope

4b adds the first concrete cloud-sync provider. The OAuth flow, token
storage, and Drive REST v3 I/O are all wired up; the only thing missing
is a UI entry point (which 4d adds).

## Automated verification

\`\`\`bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest    # 25 new tests, 0 regressions
./gradlew :app:assembleDebug   # debug APK builds, Hilt graph resolves
\`\`\`

Expected: `BUILD SUCCESSFUL` from both, with `testDebugUnitTest` reporting
`X/Y passing` where `Y = (previous count) + 25`.

The 25 new tests break down as:
- 8 `DriveApiClientTest` (MockWebServer)
- 3 `SyncTokensRepositoryTest` (Robolectric + Keystore)
- 12 `GoogleDriveCloudSyncRepositoryTest` (Robolectric + fakes)
- 2 `NoOpCloudSyncRepositoryTest` (new `signInIntent` + `handleSignInResult`)

## Manual verification

### Prerequisites

Before manual testing, the developer must:

1. Create an OAuth 2.0 Web Client ID in Google Cloud Console:
   - Application type: **Web application**
   - Authorized redirect URIs: leave empty (Play Services handles PKCE for
     installed apps via the `requestServerAuthCode` flow)
   - Note the Client ID string.
2. Either:
   - Paste the Client ID into `app/src/main/res/values/strings.xml` as the
     value of `default_web_client_id`, OR
   - Add `google.web.client.id=<your-client-id>` to `local.properties` (the
     `buildConfigField` reads it).

### Steps

- [ ] Build + install: `./gradlew :app:installDebug`
- [ ] Use a debug-only entry point (added in a future debug variant; for now,
      call `repo.signInIntent` directly from a test `Activity` or use
      `adb shell am start` to fire the OAuth intent).
- [ ] Complete Google consent. Verify `state` transitions to `SignedIn`.
- [ ] Push a snapshot. Verify `ExpenseTracker-sync.json` appears in the
      user's Drive root.
- [ ] Pull. Verify the snapshot decodes and `PullResult.Success` returns.
- [ ] Sign out. Verify tokens are wiped from DataStore (inspect
      `/data/data/io.github.jiro.expensetracker.debug/shared_prefs/sync_tokens.xml`
      — should be empty or absent).

## What this phase did NOT add

- No Settings UI for sign-in (4d).
- No sync status indicator (4d).
- No automatic push/pull triggers (4d).
- No WorkManager sync job (4d).
- No Dropbox provider (4c).
- No receipt binaries in cloud backup (later).
- No multi-account support (later).
```

- [ ] **Step 2: Run the full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL with all tests green. Count = (previous count) + 25. **STOP and investigate if any existing test fails** — 4b must not regress prior phases.

- [ ] **Step 3: Verify the debug APK still builds**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit + tag + push**

```bash
git add docs/superpowers/testdata/phase-4b-google-drive.md
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "docs(sync): Phase 4b Google Drive smoke test (Phase 4b)"
git tag v0.18.13
git push origin master v0.18.13
```

Expected:
- Commit author `MiniMax-M3 <291324429+Jiro90-T@users.noreply.github.com>`, no Co-Authored-By trailer.
- Tag `v0.18.13` exists locally.
- `git push` reports both `master` and `v0.18.13` pushed.

Verify:
```bash
git log -1 --format="%H %s"
git tag -l "v0.18.13"
git log v0.18.12..v0.18.13 --oneline   # should show ~9 new commits
git ls-remote --tags origin "v0.18.13"
```

---

## Self-Review

**1. Spec coverage:**
- Architecture & components ✓ (Task 7 + Task 8)
- OAuth entry-point pattern ✓ (Tasks 2 + 5 + 7)
- GoogleAuth interface ✓ (Task 5)
- DriveApiClient ✓ (Task 3)
- SyncTokensRepository ✓ (Task 4)
- GoogleDriveCloudSyncRepository orchestrator ✓ (Task 7)
- `PushResult.Failed` sealed widening ✓ (Task 2)
- Dependencies added ✓ (Task 1)
- Manifest `INTERNET` ✓ (Task 8)
- Strings ✓ (Task 8)
- Hilt module + @Binds swap ✓ (Task 8)
- Tests ✓ (Tasks 3, 4, 5 fakes, 7)
- Smoke test doc + tag ✓ (Task 9)

**2. Placeholder scan:** No "TBD"/"TODO"/"fill in" — all code blocks are complete.

**3. Type consistency:**
- `DriveApiClient.upload(fileId: String?, body: String, mimeType: String): String` — same signature in Task 3 (impl) and Task 6 (fake) and Task 7 (consumer).
- `SyncTokens` data class fields — same in Task 4 (definition), Task 7 (consumer in test), Task 8 (no change).
- `TokenExchangeClient.exchangeCode(code, email): ExchangeResult` — defined in Task 7, faked in Task 7 test.
- `GoogleAccountSnapshot(email, serverAuthCode, idToken)` — defined in Task 5, used in Tasks 5 (fake), 7 (consumer).

All types match. No drift.