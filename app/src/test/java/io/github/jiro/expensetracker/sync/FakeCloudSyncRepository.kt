package io.github.jiro.expensetracker.sync

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test double for [CloudSyncRepository]. Every method is a no-op or returns a
 * benign default so ViewModels that depend on the interface can be unit-tested
 * without touching a real provider. The CloudSyncRepository interface is
 * `internal`, so this fake lives in the same module.
 */
internal class FakeCloudSyncRepository : CloudSyncRepository {
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    override val state: StateFlow<SyncState> = _state
    override val isSignedIn: StateFlow<Boolean> = MutableStateFlow(false)
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = MutableStateFlow(null)
    override val signInIntent: Intent = Intent()

    override suspend fun handleSignInResult(data: Intent?): SignInResult = SignInResult.Success

    override suspend fun signIn(): SignInResult {
        _state.value = SyncState.SignedIn("fake")
        return SignInResult.Success
    }

    override suspend fun signOut() {
        _state.value = SyncState.SignedOut
    }

    override suspend fun push(snapshot: SyncSnapshot): PushResult = PushResult.Pushed(0L)

    override suspend fun pull(): PullResult<SyncSnapshot> = PullResult.NoRemoteSnapshot

    override suspend fun syncOnce(): SyncResult = SyncResult.NoRemoteSnapshot
}
