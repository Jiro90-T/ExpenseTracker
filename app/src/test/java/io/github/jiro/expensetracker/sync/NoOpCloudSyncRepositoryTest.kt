package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NoOpCloudSyncRepositoryTest {

    private lateinit var repo: NoOpCloudSyncRepository

    @Before
    fun setUp() {
        repo = NoOpCloudSyncRepository()
    }

    @Test
    fun state_startsAsSignedOut() = runTest {
        assertEquals(SyncState.SignedOut, repo.state.first())
    }

    @Test
    fun isSignedIn_isFalse_initially() = runTest {
        assertFalse(repo.isSignedIn.first())
    }

    @Test
    fun lastSyncedAtEpochMillis_remainsNull() = runTest {
        assertNull(repo.lastSyncedAtEpochMillis.first())
    }

    @Test
    fun signIn_transitionsStateToSignedIn() = runTest {
        val result = repo.signIn()
        assertEquals(SignInResult.Success, result)
        assertEquals(SyncState.SignedIn("noop"), repo.state.first())
        assertTrue(repo.isSignedIn.first())
    }

    @Test
    fun signOut_transitionsStateToSignedOut() = runTest {
        repo.signIn()
        repo.signOut()
        assertEquals(SyncState.SignedOut, repo.state.first())
        assertFalse(repo.isSignedIn.first())
    }

    @Test
    fun push_throwsNotImplementedError() = runTest {
        val snapshot = SyncSnapshot(
            body = BackupBody(emptyList(), emptyList(), emptyList()),
            lastModifiedEpochMillis = 0L,
            deviceId = "test",
            checksum = "00",
        )
        try {
            repo.push(snapshot)
            fail("Expected NotImplementedError")
        } catch (e: NotImplementedError) {
            // expected
        }
    }

    @Test
    fun pull_returnsNoRemoteSnapshot() = runTest {
        assertEquals(PullResult.NoRemoteSnapshot, repo.pull())
    }

    @Test
    fun syncOnce_returnsNoRemoteSnapshot() = runTest {
        assertEquals(SyncResult.NoRemoteSnapshot, repo.syncOnce())
    }
}