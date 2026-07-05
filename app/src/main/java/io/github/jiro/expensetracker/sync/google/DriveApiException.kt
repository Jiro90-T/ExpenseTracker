package io.github.jiro.expensetracker.sync.google

internal sealed class DriveApiException(message: String, cause: Throwable? = null)
    : RuntimeException(message, cause) {
    object AuthRevoked : DriveApiException("Auth revoked (401)")
    object QuotaExceeded : DriveApiException("Drive quota exceeded (403)")
    object RateLimited : DriveApiException("Drive rate limit exceeded (429)")
    data class ServerError(val httpCode: Int) : DriveApiException("Drive server error ($httpCode)")
    object NotFound : DriveApiException("Drive file not found (404)")
    data class Generic(val httpCode: Int, val reason: String) :
        DriveApiException("Drive rejected request ($httpCode): $reason")
}
