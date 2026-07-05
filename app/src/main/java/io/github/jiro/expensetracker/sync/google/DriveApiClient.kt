package io.github.jiro.expensetracker.sync.google

/**
 * Minimal interface to Drive REST v3 — only what sync needs (upload + download
 * by file ID). Implementations must throw [DriveApiException] subtypes on
 * failure; never return null except for [download] when the remote file is
 * missing (HTTP 404).
 */
internal interface DriveApiClient {
    /**
     * Upload [body] as [mimeType]. When [fileId] is null, creates a new file
     * and returns its ID. When [fileId] is non-null, replaces the file's
     * contents and returns the same ID.
     */
    suspend fun upload(fileId: String?, body: String, mimeType: String): String

    /**
     * Download the file with [fileId]. Returns null if the file does not
     * exist (HTTP 404). Throws [DriveApiException] subtypes on other errors.
     */
    suspend fun download(fileId: String): String?
}
