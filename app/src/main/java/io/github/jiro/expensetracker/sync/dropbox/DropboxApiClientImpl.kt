package io.github.jiro.expensetracker.sync.dropbox

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val HOST_CONTENT = "https://content.dropboxapi.com"
private const val HOST_API = "https://api.dropboxapi.com"
private const val PATH_UPLOAD = "/2/files/upload"
private const val PATH_DOWNLOAD = "/2/files/download"
private const val PATH_GET_METADATA = "/2/files/get_metadata"
private const val SNAPSHOT_PATH = "/ExpenseTracker-sync.json"

private const val OCTET_STREAM = "application/octet-stream"
private const val JSON = "application/json"
private val OCTET_STREAM_BODY: okhttp3.RequestBody = "".toRequestBody(JSON.toMediaType())

/**
 * OkHttp-backed [DropboxApiClient]. Reads tokens via a closure (NOT a stored
 * reference) so the orchestrator can rotate tokens without rebuilding the
 * client. Retries 429 (via `Retry-After`) and 5xx up to 3 times with
 * exponential backoff; 401/403/404/409 do NOT retry.
 */
@Singleton
internal class DropboxApiClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokensProvider: () -> DropboxSyncTokens?,
    private val contentHost: String = HOST_CONTENT,
    private val apiHost: String = HOST_API,
) : DropboxApiClient {

    override suspend fun upload(existingRev: String?, body: String): String =
        withContext(Dispatchers.IO) {
            val arg = JSONObject().apply {
                put("path", SNAPSHOT_PATH)
                if (existingRev == null) {
                    put("mode", "overwrite")
                } else {
                    put(
                        "mode",
                        JSONObject().apply {
                            put(".tag", "update")
                            put("update", existingRev)
                        },
                    )
                }
                put("autorename", false)
                put("mute", true)
            }
            val req = Request.Builder()
                .url("$contentHost$PATH_UPLOAD")
                .header("Authorization", "Bearer ${requireToken()}")
                .header("Dropbox-API-Arg", arg.toString())
                .header("Content-Type", OCTET_STREAM)
                .post(body.toRequestBody(OCTET_STREAM.toMediaType()))
                .build()
            executeWithRetry(req).use { resp ->
                when (resp.code) {
                    200 -> JSONObject(resp.body?.string() ?: "{}").getString("rev")
                    401, 403 -> throw DropboxApiException.AuthRevoked()
                    409 -> throw DropboxApiException.Conflict(serverRev = parseServerRev(resp))
                    429 -> throw DropboxApiException.RateLimited()
                    in 500..599 -> throw DropboxApiException.ServerError()
                    else -> throw DropboxApiException.Generic("HTTP ${resp.code}: ${resp.body?.string()}")
                }
            }
        }

    override suspend fun download(): String? = withContext(Dispatchers.IO) {
        val arg = JSONObject().put("path", SNAPSHOT_PATH)
        val req = Request.Builder()
            .url("$contentHost$PATH_DOWNLOAD")
            .header("Authorization", "Bearer ${requireToken()}")
            .header("Dropbox-API-Arg", arg.toString())
            .get()
            .build()
        executeWithRetry(req).use { resp ->
            when (resp.code) {
                200 -> resp.body?.string()
                401, 403 -> throw DropboxApiException.AuthRevoked()
                404 -> null
                409 -> null // path/not_found/ — treat same as 404
                429 -> throw DropboxApiException.RateLimited()
                in 500..599 -> throw DropboxApiException.ServerError()
                else -> throw DropboxApiException.Generic("HTTP ${resp.code}: ${resp.body?.string()}")
            }
        }
    }

    override suspend fun getRev(): String? = withContext(Dispatchers.IO) {
        val arg = JSONObject().put("path", SNAPSHOT_PATH)
        val req = Request.Builder()
            .url("$apiHost$PATH_GET_METADATA")
            .header("Authorization", "Bearer ${requireToken()}")
            .header("Dropbox-API-Arg", arg.toString())
            .post(OCTET_STREAM_BODY)
            .build()
        executeWithRetry(req).use { resp ->
            when (resp.code) {
                200 -> JSONObject(resp.body?.string() ?: "{}").optString("rev").takeIf { it.isNotEmpty() }
                401, 403 -> throw DropboxApiException.AuthRevoked()
                404, 409 -> null
                429 -> throw DropboxApiException.RateLimited()
                in 500..599 -> throw DropboxApiException.ServerError()
                else -> throw DropboxApiException.Generic("HTTP ${resp.code}: ${resp.body?.string()}")
            }
        }
    }

    private fun requireToken(): String =
        tokensProvider()?.accessToken ?: error("No Dropbox token available")

    private suspend fun executeWithRetry(
        request: Request,
        maxAttempts: Int = 3,
    ): okhttp3.Response {
        var attempt = 0
        while (true) {
            attempt++
            val resp = httpClient.newCall(request).execute()
            val shouldRetry = (resp.code == 429 || resp.code in 500..599) && attempt < maxAttempts
            if (!shouldRetry) return resp
            val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: 0L
            resp.close()
            // Exponential backoff: 1s, 2s, 4s. Capped at Retry-After if larger.
            val backoffMs = maxOf(retryAfterSec * 1000L, 1000L shl (attempt - 1))
            delay(backoffMs)
        }
    }

    private fun parseServerRev(resp: okhttp3.Response): String? = try {
        JSONObject(resp.body?.string() ?: "{}")
            .optJSONObject("error")
            ?.optJSONObject("path_conflict")
            ?.optString("rev")
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}