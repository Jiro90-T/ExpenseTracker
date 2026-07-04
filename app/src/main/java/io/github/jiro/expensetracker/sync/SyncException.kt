package io.github.jiro.expensetracker.sync

enum class SyncErrorCode { MALFORMED, CHECKSUM_MISMATCH, SCHEMA_INCOMPATIBLE }

data class SyncException(
    val code: SyncErrorCode,
    override val message: String,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)