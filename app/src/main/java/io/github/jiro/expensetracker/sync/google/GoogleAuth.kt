package io.github.jiro.expensetracker.sync.google

import android.content.Intent

/**
 * Thin wrapper around Play Services Auth. Production impl uses
 * [com.google.android.gms.auth.api.signin.GoogleSignIn]; tests use a fake.
 */
internal interface GoogleAuth {
    /** Returns the most recently signed-in account, or null. */
    suspend fun getLastSignedInAccount(): GoogleAccountSnapshot?

    /** Builds the OAuth sign-in Intent for [handleSignInResult]. */
    fun buildSignInIntent(): Intent

    /** Extracts the signed-in account from the OAuth result Intent. */
    suspend fun extractAccountFromResult(data: Intent?): GoogleAccountSnapshot?
}