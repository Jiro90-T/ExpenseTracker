package io.github.jiro.expensetracker.sync.dropbox

/**
 * Sealed hierarchy for HTTP-layer failures. Maps directly onto Dropbox
 * v2 status codes; orchestrator translates each into a [PullResult]/[PushResult]
 * variant. Mirror of [io.github.jiro.expensetracker.sync.google.DriveApiException]
 * so error handling stays symmetric across providers.
 */
internal sealed class DropboxApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** HTTP 401/403, or a 400 with `invalid_access_token` body. */
    class AuthRevoked : DropboxApiException("Auth revoked")

    /** HTTP 404 — file does not exist at the given path. */
    class NotFound : DropboxApiException("Not found")

    /** HTTP 409 — `path/conflict/file` or `path/conflict/folder`. */
    class Conflict(val serverRev: String?) :
        DropboxApiException("Conflict (serverRev=$serverRev)")

    /** HTTP 429 — `Retry-After` header is honored by the impl. */
    class RateLimited : DropboxApiException("Rate limited")

    /** HTTP 5xx. Retried up to 3x with exponential backoff. */
    class ServerError : DropboxApiException("Server error")

    /** Anything else. Includes the response body for debugging. */
    class Generic(message: String, cause: Throwable? = null) :
        DropboxApiException(message, cause)
}