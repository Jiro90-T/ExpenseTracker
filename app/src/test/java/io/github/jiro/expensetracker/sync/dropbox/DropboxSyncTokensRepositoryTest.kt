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

        // Use a FailingTokenCrypto to simulate the case where Keystore is gone
        // (factory reset, app uninstall, hardware rollback).
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
