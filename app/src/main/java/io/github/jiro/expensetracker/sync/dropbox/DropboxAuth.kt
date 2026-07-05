package io.github.jiro.expensetracker.sync.dropbox

import android.content.Intent

/**
 * Contract for any Dropbox OAuth-flow implementation. The production impl
 * ([AppAuthDropboxAuth]) wraps AppAuth-Android; tests use [FakeDropboxAuth].
 *
 * `buildAuthIntent` is sync — it just constructs an Intent that launches
 * Chrome Custom Tabs. The redirect Intent returned by the launcher is
 * parsed by [handleAuthResult].
 */
internal interface DropboxAuth {
    /** Build a CustomTabs-backed OAuth Intent. Caller launches it. */
    fun buildAuthIntent(): Intent

    /** Parse the OAuth redirect Intent returned by the launcher. */
    suspend fun handleAuthResult(data: Intent?): DropboxAccountSnapshot?

    /** Return cached account if a valid token is available, else null. */
    suspend fun getLastAuthState(): DropboxAccountSnapshot?
}