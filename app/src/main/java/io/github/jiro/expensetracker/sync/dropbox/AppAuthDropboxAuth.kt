package io.github.jiro.expensetracker.sync.dropbox

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.net.Uri

/**
 * AppAuth-Android PKCE wrapper for Dropbox OAuth 2.0. AppAuth handles the
 * browser redirect via Chrome Custom Tabs but does NOT persist tokens to
 * disk for us here — we use the callback-only [performTokenRequest] overload
 * (no [net.openid.appauth.AuthState], no token store) so we can bridge the
 * resulting access token straight into [DefaultDropboxSyncTokensRepository]
 * where it is protected by Android Keystore AES-GCM.
 *
 * AppAuth's own [AuthorizationService] is lazy-instantiated because it
 * requires a Context at construction.
 */
@Singleton
internal class AppAuthDropboxAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val clientId: String = io.github.jiro.expensetracker.BuildConfig.DROPBOX_CLIENT_ID,
    private val redirectUri: String = "io.github.jiro.expensetracker:/oauth2redirect",
) : DropboxAuth {

    private val authService: AuthorizationService by lazy {
        AuthorizationService(context)
    }

    override fun buildAuthIntent(): Intent {
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://api.dropboxapi.com/oauth2/authorize"),
            Uri.parse("https://api.dropboxapi.com/oauth2/token"),
        )
        val request = AuthorizationRequest.Builder(
            config,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri),
        )
            .setScope("account_info.read files.content.read files.content.write")
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    override suspend fun handleAuthResult(data: Intent?): DropboxAccountSnapshot? =
        withContext(Dispatchers.IO) {
            val resp = net.openid.appauth.AuthorizationResponse.fromIntent(data ?: return@withContext null)
                ?: return@withContext null
            val tokenReq = resp.createTokenExchangeRequest()
            val tokenResp = try {
                performTokenRequest(tokenReq)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: net.openid.appauth.AuthorizationException) {
                return@withContext null
            } catch (e: IllegalArgumentException) {
                return@withContext null
            }
            val accessToken = tokenResp.accessToken ?: return@withContext null
            val email = fetchAccountEmail(accessToken) ?: return@withContext null
            DropboxAccountSnapshot(email = email, accessToken = accessToken)
        }

    /**
     * Always returns null. AppAuth is configured without a [net.openid.appauth.TokenStore]
     * (so we can bridge tokens into our Keystore-protected
     * [DefaultDropboxSyncTokensRepository] instead). The orchestrator must consult
     * [DropboxSyncTokensRepository.load] directly to determine sign-in state.
     */
    override suspend fun getLastAuthState(): DropboxAccountSnapshot? = null

    private suspend fun performTokenRequest(
        req: net.openid.appauth.TokenRequest,
    ): net.openid.appauth.TokenResponse = suspendCancellableCoroutine { cont ->
        // No ClientAuthentication for PKCE public clients (Dropbox PKCE flow
        // needs no client secret). No AuthState/TokenStore wiring here —
        // tokens are bridged straight into our Keystore-protected repo.
        authService.performTokenRequest(req) { resp, ex ->
            if (ex != null) {
                cont.resumeWith(Result.failure(ex))
            } else {
                cont.resumeWith(Result.success(resp!!))
            }
        }
    }

    private suspend fun fetchAccountEmail(accessToken: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.dropboxapi.com/2/users/get_current_account")
                .header("Authorization", "Bearer $accessToken")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                JSONObject(body).optString("email").takeIf { it.isNotEmpty() }
            }
        }
}