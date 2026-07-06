package io.github.jiro.expensetracker.sync.dropbox

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.Operation
import io.github.jiro.expensetracker.sync.PullResult
import io.github.jiro.expensetracker.sync.PushResult
import io.github.jiro.expensetracker.sync.SignInResult
import io.github.jiro.expensetracker.sync.SyncErrorCode
import io.github.jiro.expensetracker.sync.SyncException
import io.github.jiro.expensetracker.sync.SyncResult
import io.github.jiro.expensetracker.sync.SyncSnapshot
import io.github.jiro.expensetracker.sync.SyncSnapshotCodec
import io.github.jiro.expensetracker.sync.SyncState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
internal class DropboxCloudSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dropboxAuth: DropboxAuth,
    private val api: DropboxApiClient,
    private val tokens: DropboxSyncTokensRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)
    private val mutex = Mutex()

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val signInIntent: Intent = dropboxAuth.buildAuthIntent()

    override suspend fun signIn(): SignInResult = withContext(Dispatchers.IO) {
        val cached = tokens.load()
        if (cached != null && cached.expiresAtEpochMillis > nowProvider() + 60_000L) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            return@withContext SignInResult.Success
        }
        // Caller launches signInIntent when this returns Failed. Silent re-auth
        // isn't wired in 4c; a future refresh flow can replace this branch.
        SignInResult.Failed("Not signed in")
    }

    override suspend fun handleSignInResult(data: Intent?): SignInResult = withContext(Dispatchers.IO) {
        try {
            val account = dropboxAuth.handleAuthResult(data)
                ?: return@withContext SignInResult.Failed("Sign-in cancelled")
            tokens.save(
                DropboxSyncTokens(
                    accessToken = account.accessToken,
                    refreshToken = null,
                    expiresAtEpochMillis = nowProvider() + ACCESS_TOKEN_LIFESPAN_MS,
                    accountEmail = account.email,
                    snapshotRev = null,
                ),
            )
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            SignInResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SignInResult.Failed("Sign-in failed: ${e.message}", e)
        }
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        tokens.clear()
        _state.value = SyncState.SignedOut
    }

    override suspend fun push(snapshot: SyncSnapshot): PushResult = mutex.withLock {
        val current = _state.value
        if (current !is SyncState.SignedIn) {
            return@withLock PushResult.Failed("Not signed in", null)
        }
        _state.value = SyncState.Syncing(Operation.PUSH)
        try {
            val body = SyncSnapshotCodec.encode(snapshot)
            val cached = tokens.load()
            val existingRev = cached?.snapshotRev
            val newRev = api.upload(existingRev, body)
            if (existingRev == null) {
                // SignedIn state implies tokens exist (saved by handleSignInResult);
                // reload to be safe against signOut races rather than trust the cached ref.
                val current2 = tokens.load()
                if (current2 != null) {
                    tokens.save(current2.copy(snapshotRev = newRev))
                }
            } else {
                tokens.save(cached!!.copy(snapshotRev = newRev))
            }
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PushResult.Pushed(pushedAtEpochMillis = snapshot.lastModifiedEpochMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DropboxApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PushResult.Failed("Session expired — please sign in again", e)
        } catch (e: DropboxApiException) {
            PushResult.Failed(e.message ?: "Dropbox error", e)
        } catch (e: Exception) {
            PushResult.Failed("Push failed: ${e.message}", e)
        }
    }

    override suspend fun pull(): PullResult<SyncSnapshot> = mutex.withLock {
        val cached = tokens.load()
        val fileRev = cached?.snapshotRev
        if (fileRev == null) {
            return@withLock PullResult.NoRemoteSnapshot
        }
        _state.value = SyncState.Syncing(Operation.PULL)
        try {
            val body = api.download()
                ?: return@withLock PullResult.NoRemoteSnapshot.also { _state.value = SyncState.SignedIn(PROVIDER_ID) }
            val snapshot = SyncSnapshotCodec.decode(body)
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PullResult.Success(snapshot, pulledAtEpochMillis = nowProvider())
        } catch (e: CancellationException) {
            throw e
        } catch (e: DropboxApiException.NotFound) {
            // HTTP 404 from a stale rev (server-side cleanup). Treat as no
            // remote snapshot rather than a failure — user can re-push to
            // create a fresh file.
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.NoRemoteSnapshot
        } catch (e: DropboxApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PullResult.Failed("Session expired", e)
        } catch (e: DropboxApiException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(e.message ?: "Dropbox error", e)
        } catch (e: SyncException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(
                when (e.code) {
                    SyncErrorCode.CHECKSUM_MISMATCH -> "Remote snapshot failed integrity check"
                    SyncErrorCode.SCHEMA_INCOMPATIBLE -> "Remote snapshot was written by a newer app version"
                    SyncErrorCode.MALFORMED -> "Remote snapshot is corrupted"
                },
                e,
            )
        } catch (e: Exception) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed("Pull failed: ${e.message}", e)
        }
    }

    override suspend fun syncOnce(): SyncResult = withContext(Dispatchers.IO) {
        when (val result = pull()) {
            is PullResult.Success<*> -> SyncResult.Pulled(
                snapshot = result.snapshot as SyncSnapshot,
                pulledAtEpochMillis = result.pulledAtEpochMillis,
            )
            PullResult.NoRemoteSnapshot -> SyncResult.NoRemoteSnapshot
            is PullResult.Failed -> SyncResult.Failed(result.message, result.cause)
            is PullResult.Conflict -> SyncResult.ConflictPending(
                remote = result.remote,
                local = result.local,
            )
        }
    }

    private companion object {
        const val PROVIDER_ID = "dropbox"

        // AppAuth PKCE Dropbox flows issue 4-hour access tokens without a
        // refresh token. 4d may add refresh via /2/auth/token/refresh.
        const val ACCESS_TOKEN_LIFESPAN_MS = 4L * 60L * 60L * 1000L
    }
}
