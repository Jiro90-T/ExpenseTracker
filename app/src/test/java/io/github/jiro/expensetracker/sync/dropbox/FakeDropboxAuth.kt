package io.github.jiro.expensetracker.sync.dropbox

import android.content.Intent

/**
 * Test fake for [DropboxAuth]. Tests set [extractResult] to control the
 * outcome of [handleAuthResult]. [signInIntentValue] is the Intent that
 * [buildAuthIntent] will return.
 */
internal class FakeDropboxAuth : DropboxAuth {
    var extractResult: DropboxAccountSnapshot? = null
    var signInIntentValue: Intent = Intent()
    var lastAuthState: DropboxAccountSnapshot? = null

    override fun buildAuthIntent(): Intent = signInIntentValue

    override suspend fun handleAuthResult(data: Intent?): DropboxAccountSnapshot? = extractResult

    override suspend fun getLastAuthState(): DropboxAccountSnapshot? = lastAuthState
}