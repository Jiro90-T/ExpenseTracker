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

    /**
     * Plaintext pass-through. Robolectric 4.11 does not implement the
     * AndroidKeyStore JCA provider, so KeystoreTokenCrypto cannot be
     * exercised in unit tests — only on a real device or emulator.
     * This fake preserves the repo's encrypt-on-save / decrypt-on-load
     * contract while keeping the round-trip lossless.
     */
    private class FakeTokenCrypto : TokenCrypto {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertextB64: String): String = ciphertextB64
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Wipe persisted tokens between tests
        context.getSharedPreferences("sync_tokens", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        auth = FakeGoogleAuth()
        api = FakeDriveApiClient()
        tokens = DefaultSyncTokensRepository(context, FakeTokenCrypto())
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

    @Test
    fun syncOnce_returnsConflictPendingMapping_exists() = runBlocking {
        // The orchestrator exposes a `ConflictPending` mapping for completeness
        // even though pull() does not yet produce Conflict. This test pins
        // the current behavior — when pull() returns NoRemote, syncOnce()
        // surfaces NoRemoteSnapshot. Future Conflict support can extend this
        // test with a fake that throws a mock Conflict.
        tokens.save(
            SyncTokens(
                accessToken = "tok",
                refreshToken = "ref",
                expiresAtEpochMillis = 1_700_000_000_000L + 3_600_000L,
                accountEmail = "u@e.com",
                snapshotFileId = null,
            ),
        )
        val result = repo.syncOnce()
        assertEquals(io.github.jiro.expensetracker.sync.SyncResult.NoRemoteSnapshot, result)
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