package io.github.jiro.expensetracker.sync.google

/**
 * Encrypt/decrypt individual strings. The repo encrypts each
 * SharedPreferences field independently, so this is a per-value cipher,
 * not a stream. Production uses [KeystoreTokenCrypto] (Android Keystore,
 * AES-256-GCM); tests use [FakeTokenCrypto] (plaintext pass-through).
 */
internal interface TokenCrypto {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertextB64: String): String
}