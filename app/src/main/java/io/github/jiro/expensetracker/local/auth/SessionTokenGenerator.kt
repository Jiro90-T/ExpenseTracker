package io.github.jiro.expensetracker.local.auth

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates URL-safe session tokens (32 bytes = 256 bits of entropy,
 * base64url-encoded without padding so they fit cleanly in a ?t= param).
 */
@Singleton
class SessionTokenGenerator @Inject constructor() {

    fun generate(): String {
        val bytes = ByteArray(BYTE_COUNT)
        secureRandom.nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private val secureRandom: SecureRandom = SecureRandom()

    companion object {
        const val BYTE_COUNT = 32

        fun generate(): String = SessionTokenGenerator().generate()
    }
}
