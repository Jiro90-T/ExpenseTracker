package io.github.jiro.expensetracker.local

/**
 * Foreground service that hosts the Ktor engine for the local PC browser
 * server. The full implementation lands in Task 5 — this stub exists so
 * the notification channel can reference its CHANNEL_ID constant now.
 */
class LocalServerService {
    companion object {
        const val CHANNEL_ID = "local_server_channel"
    }
}
