package io.github.jiro.expensetracker.sync.dropbox

/**
 * All persisted state needed to talk to Dropbox on behalf of the signed-in
 * user. `snapshotRev` is Dropbox's optimistic-concurrency token — analogous
 * to Drive's `snapshotFileId`. AppAuth PKCE flows return a 4-hour access
 * token and NO refresh token, so [refreshToken] is nullable.
 */
internal data class DropboxSyncTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
    val accountEmail: String,
    val snapshotRev: String?,
)