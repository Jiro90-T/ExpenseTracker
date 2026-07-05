package io.github.jiro.expensetracker.sync

import android.content.Intent
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

    /**
     * Pre-built OAuth sign-in Intent. The caller (typically a 4d Activity)
     * launches this via `ActivityResultLauncher<Intent>` and forwards the
     * result back via [handleSignInResult].
     */
    val signInIntent: Intent

    /**
     * Consumes the OAuth result Intent returned by [signInIntent]'s
     * launcher. Returns Success when tokens are persisted and the state
     * transitions to SignedIn, Failed otherwise.
     */
    suspend fun handleSignInResult(data: Intent?): SignInResult

    /**
     * Silent sign-in path: returns Success if a cached token is still
     * valid (or refreshed), Failed otherwise. Does NOT launch UI.
     */
    suspend fun signIn(): SignInResult

    suspend fun signOut()
    suspend fun push(snapshot: SyncSnapshot): PushResult
    suspend fun pull(): PullResult<SyncSnapshot>
    suspend fun syncOnce(): SyncResult
}