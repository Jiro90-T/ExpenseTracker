package io.github.jiro.expensetracker.sync

internal sealed class PushResult {
    internal data class Pushed(val pushedAtEpochMillis: Long) : PushResult()
    internal data class Failed(val message: String, val cause: Throwable? = null) : PushResult()
}

internal sealed class PullResult<out T> {
    internal data class Success<T>(val snapshot: T, val pulledAtEpochMillis: Long) : PullResult<T>()
    internal object NoRemoteSnapshot : PullResult<Nothing>()
    internal data class Conflict(val remote: SyncSnapshot, val local: SyncSnapshot) : PullResult<Nothing>()
    internal data class Failed(val message: String, val cause: Throwable? = null) : PullResult<Nothing>()
}

internal sealed class SyncResult {
    internal data class Pushed(val pushedAtEpochMillis: Long) : SyncResult()
    internal data class Pulled(val snapshot: SyncSnapshot, val pulledAtEpochMillis: Long) : SyncResult()
    internal object NoRemoteSnapshot : SyncResult()
    internal data class Failed(val message: String, val cause: Throwable? = null) : SyncResult()
}

internal sealed class SignInResult {
    internal object Success : SignInResult()
    internal data class Failed(val message: String, val cause: Throwable? = null) : SignInResult()
}