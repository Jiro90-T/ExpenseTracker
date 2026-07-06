package io.github.jiro.expensetracker.sync.google

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Singleton
internal class DriveApiClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokens: SyncTokensRepository,
    private val baseUrl: String = "https://www.googleapis.com",
) : DriveApiClient {

    private suspend fun accessToken(): String =
        tokens.load()?.accessToken ?: throw DriveApiException.AuthRevoked

    override suspend fun upload(fileId: String?, body: String, mimeType: String): String =
        withContext(Dispatchers.IO) {
            val metadata = if (fileId == null) {
                JSONObject().apply {
                    put("name", FILE_NAME)
                    put("mimeType", mimeType)
                }
            } else {
                JSONObject().apply {
                    put("name", FILE_NAME)
                    put("mimeType", mimeType)
                }
            }

            val multipart = MultipartBody.Builder()
                .setType("multipart/related; boundary=expense_tracker_sync".toMediaType())
                .addPart(metadata.toString().toRequestBody(JSON))
                .addPart(body.toRequestBody(mimeType.toMediaType()))
                .build()

            val url = if (fileId == null) {
                "$baseUrl/upload/drive/v3/files?uploadType=multipart"
            } else {
                "$baseUrl/upload/drive/v3/files/$fileId?uploadType=multipart"
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${accessToken()}")
                .apply {
                    if (fileId == null) post(multipart) else patch(multipart)
                }
                .build()

            executeWithRetry(request) { resp ->
                val text = resp.body?.string().orEmpty()
                val id = JSONObject(text).optString("id", "")
                require(id.isNotEmpty()) { "Drive upload returned no id: $text" }
                id
            }
        }

    override suspend fun download(fileId: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer ${accessToken()}")
                .get()
                .build()

            try {
                executeWithRetry(request, retryOn404 = false) { resp -> resp.body?.string().orEmpty() }
            } catch (e: DriveApiException.NotFound) {
                null
            }
        }

    private suspend fun <T> executeWithRetry(
        request: Request,
        retryOn404: Boolean = true,
        parse: (okhttp3.Response) -> T,
    ): T {
        var attempt = 0
        var response: okhttp3.Response? = null
        try {
            while (true) {
                response = httpClient.newCall(request).execute()
                val code = response.code
                when {
                    code in 200..299 -> return parse(response).also { response.close() }
                    code == 401 -> throw DriveApiException.AuthRevoked
                    code == 403 -> throw DriveApiException.QuotaExceeded
                    code == 404 && retryOn404 -> throw DriveApiException.NotFound
                    code == 404 -> throw DriveApiException.NotFound
                    code == 429 -> {
                        response.close()
                        val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 0L
                        attempt++
                        if (attempt >= MAX_RETRIES_429) throw DriveApiException.RateLimited
                        delay(retryAfter * 1000L)
                    }
                    code in 500..599 -> {
                        response.close()
                        attempt++
                        if (attempt >= MAX_RETRIES_5XX) throw DriveApiException.ServerError(code)
                        delay(RETRY_BACKOFF_5XX_MS)
                    }
                    else -> {
                        val body = response.body?.string().orEmpty()
                        response.close()
                        throw DriveApiException.Generic(code, body)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: IOException) {
            response?.close()
            throw DriveApiException.Generic(0, e.message ?: "I/O error")
        }
    }

    private companion object {
        const val FILE_NAME = "ExpenseTracker-sync.json"
        const val MAX_RETRIES_429 = 3
        const val MAX_RETRIES_5XX = 2
        const val RETRY_BACKOFF_5XX_MS = 1000L
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
