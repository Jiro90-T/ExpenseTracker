package io.github.jiro.expensetracker.sync

sealed class SyncState {
    object SignedOut : SyncState()
    data class SignedIn(val providerId: String) : SyncState()
    data class Syncing(val operation: Operation) : SyncState()
    data class Error(val message: String, val cause: Throwable? = null) : SyncState()
}

enum class Operation { PUSH, PULL }