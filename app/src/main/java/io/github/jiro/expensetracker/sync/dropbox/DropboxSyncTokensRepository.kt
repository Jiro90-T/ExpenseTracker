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
        // refreshToken is nullable by design (AppAuth PKCE issues none). Treat
        // a missing key as valid null; only wipe on decrypt failure.
        val refresh = prefs.getString(K_REFRESH, null)
            ?.let { runCatching { crypto.decrypt(it) }.getOrNull() ?: return@withContext wipeAndNull() }
        val expires = prefs.getString(K_EXPIRES, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        val email = prefs.getString(K_EMAIL, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
            ?: return@withContext wipeAndNull()
        // snapshotRev is nullable (no snapshot pushed yet). Treat a missing key
        // as valid null; only wipe on decrypt failure.
        val rev = prefs.getString(K_REV, null)
            ?.let { runCatching { crypto.decrypt(it) }.getOrNull() ?: return@withContext wipeAndNull() }

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
