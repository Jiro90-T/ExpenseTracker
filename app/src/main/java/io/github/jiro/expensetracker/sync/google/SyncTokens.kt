package io.github.jiro.expensetracker.sync.google

internal data class SyncTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val accountEmail: String,
    val snapshotFileId: String?,
)