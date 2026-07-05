package io.github.jiro.expensetracker.sync.google

internal data class GoogleAccountSnapshot(
    val email: String,
    val serverAuthCode: String?,
    val idToken: String?,
)