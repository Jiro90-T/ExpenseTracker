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

    /**
     * Mimics the behavior of KeystoreTokenCrypto when the underlying
     * key has been destroyed (factory reset, app uninstall, hardware
     * rollback): the AndroidKeyStore provider surfaces this as
     * KeyPermanentlyInvalidatedException on every decrypt() call.
     * The repo's load() must catch it on the access-token field and
     * wipe the prefs before returning null.
     */
    private class FailingTokenCrypto : TokenCrypto {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertextB64: String): String {
            throw android.security.keystore.KeyPermanentlyInvalidatedException()
        }
    }

    private lateinit var context: Context
    private lateinit var repo: SyncTokensRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe any persisted tokens between tests
        context.getSharedPreferences("sync_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        repo = DefaultSyncTokensRepository(context, FakeTokenCrypto())
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

    @Test
    fun load_returnsNull_andWipesPrefs_whenDecryptThrowsKeyPermanentlyInvalidated() = runBlocking {
        // Pre-populate the prefs file with FakeTokenCrypto ciphertext so
        // the repo has something to attempt to decrypt.
        repo.save(
            SyncTokens(
                accessToken = "stale-access",
                refreshToken = "stale-refresh",
                expiresAtEpochMillis = 1_700_000_000_000L,
                accountEmail = "user@example.com",
                snapshotFileId = "drive-file-id-1",
            ),
        )
        val prefs = context.getSharedPreferences("sync_tokens", Context.MODE_PRIVATE)
        assertEquals("prefs should be populated before invalidation",
            "stale-access", prefs.getString("access_token_b64", null))

        // Construct a new repo with FailingTokenCrypto so decrypt() throws
        // KeyPermanentlyInvalidatedException on every call.
        val failingRepo = DefaultSyncTokensRepository(context, FailingTokenCrypto())

        // First decrypt failure on accessToken should wipe and return null.
        assertNull(failingRepo.load())
        assertEquals("access should be wiped",
            null, prefs.getString("access_token_b64", null))
        assertEquals("refresh should be wiped",
            null, prefs.getString("refresh_token_b64", null))
        assertEquals("expires should be wiped",
            null, prefs.getString("expires_at_b64", null))
        assertEquals("email should be wiped",
            null, prefs.getString("account_email_b64", null))
        assertEquals("file id should be wiped",
            null, prefs.getString("snapshot_file_id_b64", null))
    }
}