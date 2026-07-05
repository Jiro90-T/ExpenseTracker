package io.github.jiro.expensetracker.sync.google

import android.content.Intent

internal class FakeGoogleAuth : GoogleAuth {
    var lastAccount: GoogleAccountSnapshot? = null
    var extractResult: GoogleAccountSnapshot? = null
    var signInIntentValue: Intent = Intent()

    override suspend fun getLastSignedInAccount(): GoogleAccountSnapshot? = lastAccount
    override fun buildSignInIntent(): Intent = signInIntentValue
    override suspend fun extractAccountFromResult(data: Intent?): GoogleAccountSnapshot? = extractResult
}