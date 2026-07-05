package io.github.jiro.expensetracker.sync.dropbox

/**
 * Wire-level surface for the Dropbox App folder. The path to the snapshot
 * is fixed (`/ExpenseTracker-sync.json`) and lives inside this class — the
 * orchestrator never names the file itself.
 *
 * Implementations must throw [DropboxApiException] subclasses on failure;
 * callers translate those into [PullResult]/[PushResult] variants.
 */
internal interface DropboxApiClient {

    /**
     * Upload [body] to `/ExpenseTracker-sync.json`. If [existingRev] is null,
     * creates the file; otherwise uses `mode: {".tag": "update", "update": existingRev}`
     * to enforce optimistic concurrency.
     *
     * Returns the new `rev` returned by Dropbox (a content-addressed server
     * identifier). Throws [DropboxApiException.NotFound] only if the parent
     * folder is missing — should never happen for the App folder.
     */
    suspend fun upload(existingRev: String?, body: String): String

    /**
     * Download the snapshot body. Returns null if the file does not exist
     * (HTTP 404, or HTTP 409 with `path/not_found/`). Throws
     * [DropboxApiException.AuthRevoked] on 401/403.
     */
    suspend fun download(): String?

    /**
     * Return the current `rev` of the snapshot file, or null if it does
     * not exist. Used by the orchestrator to refresh its cached rev
     * (deferred to a later phase).
     */
    suspend fun getRev(): String?
}