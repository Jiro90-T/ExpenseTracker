package io.github.jiro.expensetracker.local.auth

import android.util.Base64
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates URL-safe session tokens (32 bytes = 256 bits of entropy,
 * base64url-encoded without padding so they fit cleanly in a ?t= param).
 * Uses [android.util.Base64] (not java.util) so it works on API 24+.
 */
@Singleton
class SessionTokenGenerator @Inject constructor() {

    private val secureRandom: SecureRandom = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(BYTE_COUNT)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, URL_SAFE_FLAGS)
    }

    companion object {
        const val BYTE_COUNT = 32
        private const val URL_SAFE_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    }
}
