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
    private val crypto: TokenCrypto = KeystoreTokenCrypto(),
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