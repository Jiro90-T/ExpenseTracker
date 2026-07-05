package io.github.jiro.expensetracker.sync.dropbox

/**
 * Encrypt/decrypt individual strings. The repo encrypts each
 * SharedPreferences field independently, so this is a per-value cipher,
 * not a stream. Production uses [KeystoreTokenCrypto] (Android Keystore,
 * AES-256-GCM); tests use a plaintext pass-through.
 *
 * Extracted as an interface (same reason as 4b's TokenCrypto): Robolectric
 * 4.11.1 does not implement the AndroidKeyStore JCA provider, so we cannot
 * exercise KeystoreTokenCrypto in unit tests.
 */
internal interface TokenCrypto {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertextB64: String): String
}
