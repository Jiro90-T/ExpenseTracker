package io.github.jiro.expensetracker.sync.google

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.BuildConfig
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.Operation
import io.github.jiro.expensetracker.sync.PullResult
import io.github.jiro.expensetracker.sync.PushResult
import io.github.jiro.expensetracker.sync.SignInResult
import io.github.jiro.expensetracker.sync.SyncErrorCode
import io.github.jiro.expensetracker.sync.SyncResult
import io.github.jiro.expensetracker.sync.SyncSnapshot
import io.github.jiro.expensetracker.sync.SyncSnapshotCodec
import io.github.jiro.expensetracker.sync.SyncState
import javax.inject.Inject
import javax.inject.Named
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
internal class GoogleDriveCloudSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googleAuth: GoogleAuth,
    private val api: DriveApiClient,
    private val tokens: SyncTokensRepository,
    private val tokenExchangeClient: TokenExchangeClient,
    @Named("googleDriveNowProvider") private val nowProvider: () -> Long,
) : CloudSyncRepository {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Unconfined,
    )
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)
    private val mutex = Mutex()

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val signInIntent: Intent = googleAuth.buildSignInIntent()

    override suspend fun signIn(): SignInResult = withContext(Dispatchers.IO) {
        val cached = tokens.load()
        if (cached != null && cached.expiresAtEpochMillis > nowProvider() + 60_000L) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            return@withContext SignInResult.Success
        }
        // Caller launches signInIntent when this returns Failed. Silent re-auth
        // isn't wired in 4b; a future refresh flow can replace this branch.
        SignInResult.Failed("Not signed in")
    }

    override suspend fun handleSignInResult(data: Intent?): SignInResult = withContext(Dispatchers.IO) {
        val account = googleAuth.extractAccountFromResult(data)
            ?: return@withContext SignInResult.Failed("Sign-in cancelled")
        val code = account.serverAuthCode
            ?: return@withContext SignInResult.Failed("Could not get auth code")
        try {
            val exchange = tokenExchangeClient.exchangeCode(code, account.email)
            tokens.save(
                SyncTokens(
                    accessToken = exchange.accessToken,
                    refreshToken = exchange.refreshToken,
                    expiresAtEpochMillis = nowProvider() + exchange.expiresInSeconds * 1000L,
                    accountEmail = account.email,
                    snapshotFileId = null,
                ),
            )
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            SignInResult.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SignInResult.Failed("Token exchange failed: ${e.message}", e)
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
            val existingId = cached?.snapshotFileId
            val newId = api.upload(existingId, body, MIME_TYPE)
            if (existingId == null) {
                // SignedIn state implies tokens exist (saved by handleSignInResult);
                // reload to be safe against signOut races rather than trust the cached ref.
                val current2 = tokens.load()
                if (current2 != null) {
                    tokens.save(current2.copy(snapshotFileId = newId))
                }
            }
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PushResult.Pushed(pushedAtEpochMillis = snapshot.lastModifiedEpochMillis)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DriveApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PushResult.Failed("Session expired — please sign in again", e)
        } catch (e: DriveApiException) {
            PushResult.Failed(e.message ?: "Drive error", e)
        } catch (e: Exception) {
            PushResult.Failed("Push failed: ${e.message}", e)
        }
    }

    override suspend fun pull(): PullResult<SyncSnapshot> = mutex.withLock {
        val cached = tokens.load()
        val fileId = cached?.snapshotFileId
        if (fileId == null) {
            return@withLock PullResult.NoRemoteSnapshot
        }
        _state.value = SyncState.Syncing(Operation.PULL)
        try {
            val body = api.download(fileId)
                ?: return@withLock PullResult.NoRemoteSnapshot.also { _state.value = SyncState.SignedIn(PROVIDER_ID) }
            val snapshot = SyncSnapshotCodec.decode(body)
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            _lastSyncedAtEpochMillis.value = snapshot.lastModifiedEpochMillis
            PullResult.Success(snapshot, pulledAtEpochMillis = nowProvider())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DriveApiException.NotFound) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.NoRemoteSnapshot
        } catch (e: DriveApiException.AuthRevoked) {
            tokens.clear()
            _state.value = SyncState.SignedOut
            PullResult.Failed("Session expired", e)
        } catch (e: DriveApiException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(e.message ?: "Drive error", e)
        } catch (e: io.github.jiro.expensetracker.sync.SyncException) {
            _state.value = SyncState.SignedIn(PROVIDER_ID)
            PullResult.Failed(when (e.code) {
                SyncErrorCode.CHECKSUM_MISMATCH ->
                    "Remote snapshot failed integrity check"
                SyncErrorCode.SCHEMA_INCOMPATIBLE ->
                    "Remote snapshot was written by a newer app version"
                SyncErrorCode.MALFORMED ->
                    "Remote snapshot is corrupted"
            }, e)
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
        const val PROVIDER_ID = "google_drive"
        const val MIME_TYPE = "application/json"
    }
}

/**
 * Wraps the OAuth code-exchange HTTP call. Separate from the repo for
 * testability — tests inject a fake that returns canned tokens.
 */
internal interface TokenExchangeClient {
    suspend fun exchangeCode(code: String, email: String): ExchangeResult
}

internal data class ExchangeResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

internal class DefaultTokenExchangeClient(
    private val httpClient: OkHttpClient,
    private val clientId: String = BuildConfig.DEFAULT_WEB_CLIENT_ID,
    private val clientSecret: String = "", // empty for installed apps; Google handles PKCE via Play Services
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
) : TokenExchangeClient {

    override suspend fun exchangeCode(code: String, email: String): ExchangeResult {
        val form = FormBody.Builder()
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", "")
            .build()
        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(form)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "Token exchange failed ($resp): $body" }
            val json = org.json.JSONObject(body)
            return ExchangeResult(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token"),
                expiresInSeconds = json.optLong("expires_in", 3600L),
            )
        }
    }
}