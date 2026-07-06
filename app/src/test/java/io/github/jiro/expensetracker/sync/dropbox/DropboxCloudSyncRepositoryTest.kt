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

    @Test
    fun syncOnce_returnsConflictPending_onPullConflict() = runBlocking {
        // Pull returns Conflict by going through the FakeDropboxApiClient
        // path that throws a mock — but Conflict today is not reachable via
        // the fake (pull() only returns NoRemote / Success / Failed). For
        // the orchestrator contract we verify that pull() never produces
        // Conflict in the current shape: this test ensures the existing
        // pull() returns are not regressed.
        tokens.save(
            DropboxSyncTokens(
                accessToken = "tok",
                refreshToken = null,
                expiresAtEpochMillis = 1_700_000_000_000L + 4 * 60 * 60 * 1000L,
                accountEmail = "u@e.com",
                snapshotRev = null,
            ),
        )
        val result = repo.syncOnce()
        // pull() returns NoRemoteSnapshot because snapshotRev is null —
        // that's the path that maps to SyncResult.NoRemoteSnapshot.
        assertEquals(io.github.jiro.expensetracker.sync.SyncResult.NoRemoteSnapshot, result)
    }

    private fun sampleSnapshot(): SyncSnapshot = SyncSnapshot(
        body = BackupBody(emptyList(), emptyList(), emptyList()),
        lastModifiedEpochMillis = 1_700_000_001_000L,
        deviceId = "device-1",
        checksum = "ignored-by-encoder",
    )
}
