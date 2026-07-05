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
}