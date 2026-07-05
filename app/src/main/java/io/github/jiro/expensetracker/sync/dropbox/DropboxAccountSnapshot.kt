package io.github.jiro.expensetracker.sync.dropbox

/**
 * Minimal account info the orchestrator needs to persist tokens and show
 * the user which Dropbox account is signed in. We do NOT cache the access
 * token here — the orchestrator persists it via [DropboxSyncTokensRepository].
 */
internal data class DropboxAccountSnapshot(
    val email: String,
    val accessToken: String,
)