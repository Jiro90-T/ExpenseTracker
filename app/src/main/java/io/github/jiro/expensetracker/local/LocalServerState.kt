package io.github.jiro.expensetracker.local

data class LocalServerState(
    val isRunning: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val ipAddress: String? = null,
    val token: String? = null,
    val lastError: String? = null,
) {
    companion object {
        const val DEFAULT_PORT = 8080
    }
}