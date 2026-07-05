package io.github.jiro.expensetracker.sync

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Structural placeholder for 4a. Every method exists so the contract
 * compiles, but no I/O happens. 4b/4c replace this with a real provider
 * via a single Hilt binding swap.
 *
 * - signIn() / handleSignInResult(): return Success without I/O — useful
 *   as a test stub and a future fallback if Drive wiring is disabled.
 * - signOut()   : flips state back to SignedOut.
 * - push(...)   : throws — the contract exists, no real backend yet.
 * - pull()      : returns NoRemoteSnapshot — there is no remote.
 * - syncOnce()  : returns NoRemoteSnapshot — pull is the no-op result.
 */
@Singleton
internal class NoOpCloudSyncRepository @Inject constructor() : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val signInIntent: Intent = Intent()

    override suspend fun signIn(): SignInResult {
        _state.value = SyncState.SignedIn("noop")
        return SignInResult.Success
    }

    override suspend fun handleSignInResult(data: Intent?): SignInResult {
        _state.value = SyncState.SignedIn("noop")
        return SignInResult.Success
    }

    override suspend fun signOut() {
        _state.value = SyncState.SignedOut
    }

    override suspend fun push(snapshot: SyncSnapshot): PushResult {
        throw NotImplementedError("push not available in NoOpCloudSyncRepository")
    }

    override suspend fun pull(): PullResult<SyncSnapshot> = PullResult.NoRemoteSnapshot

    override suspend fun syncOnce(): SyncResult = SyncResult.NoRemoteSnapshot
}