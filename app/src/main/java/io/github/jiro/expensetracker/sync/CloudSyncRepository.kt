package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for any cloud-sync provider. 4a ships NoOpCloudSyncRepository;
 * 4b swaps in a Drive-backed implementation and 4c swaps in a Dropbox-
 * backed one. Callers depend only on this interface, so the swap is a
 * single Hilt binding.
 *
 * `internal` because its methods expose `internal` types ([SyncSnapshot],
 * [SignInResult], [PushResult], [PullResult], [SyncResult]) — widen
 * visibility if a future consumer outside `sync/` needs it.
 */
internal interface CloudSyncRepository {
    val state: StateFlow<SyncState>
    val lastSyncedAtEpochMillis: StateFlow<Long?>
    val isSignedIn: StateFlow<Boolean>

    suspend fun signIn(): SignInResult
    suspend fun signOut()
    suspend fun push(snapshot: SyncSnapshot): PushResult
    suspend fun pull(): PullResult<SyncSnapshot>
    suspend fun syncOnce(): SyncResult
}