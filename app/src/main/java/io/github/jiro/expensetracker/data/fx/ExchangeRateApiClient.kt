package io.github.jiro.expensetracker.data.fx

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
class ExchangeRateApiClient @Inject constructor(
    private val httpClient: OkHttpClient,
    /** Indirection so tests can inject a MockWebServer URL. Production
     *  binding returns the public Frankfurter endpoint. */
    private val baseUrlProvider: () -> String = { DEFAULT_BASE_URL },
) : FxRateClient {

    override suspend fun fetchLatestUsdRates(): Map<String, Double> =
        // Same IO-dispatcher pattern as YahooMarketDataClient — callers
        // (SettingsViewModel) default to Main; OkHttp's blocking execute()
        // throws NetworkOnMainThreadException on Android otherwise.
        withContext(Dispatchers.IO) { fetchLatestUsdRatesBlocking() }

    private fun fetchLatestUsdRatesBlocking(): Map<String, Double> {
        val url = baseUrlProvider().toHttpUrl().newBuilder()
            .addPathSegments("v1/latest")
            .addQueryParameter("base", "USD")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FxRateFetchException(
                "${e.javaClass.simpleName}: ${e.message ?: "no message"}",
            )
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw FxRateFetchException("HTTP ${resp.code}")
            }
            val body = resp.body?.string()
            if (body.isNullOrBlank()) {
                throw FxRateFetchException("empty body")
            }
            return try {
                val root = JSONObject(body)
                val baseCode = root.optString("base", "")
                if (baseCode != "USD") {
                    // Defensive: would be writing "USD_to_XXX" keys for a
                    // response that isn't actually USD-based.
                    throw FxRateFetchException("unexpected base: $baseCode")
                }
                val rates = root.optJSONObject("rates") ?: throw FxRateFetchException("missing rates object")
                val out = mutableMapOf<String, Double>()
                val keys = rates.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val rate = rates.optDouble(code, Double.NaN)
                    if (!rate.isFinite() || rate <= 0.0) continue
                    if (code == "USD") continue  // USD_to_USD = 1 is implicit
                    if (code.length != 3) continue  // defensive
                    out["USD_to_$code"] = rate
                }
                if (out.isEmpty()) {
                    throw FxRateFetchException("no usable rates in response")
                }
                out.toMap()
            } catch (e: FxRateFetchException) {
                throw e
            } catch (e: Exception) {
                throw FxRateFetchException(
                    "parse: ${e.javaClass.simpleName}: ${e.message ?: "?"}",
                )
            }
        }
    }

    companion object {
        // Frankfurter (api.frankfurter.dev) — free, no key, ECB-backed,
        // MYR/EUR/GBP/JPY/SGD/HKD included. Switched from open.er-api.com
        // which was geo-blocking users in some regions.
        const val DEFAULT_BASE_URL = "https://api.frankfurter.dev"
        const val USER_AGENT = "Mozilla/5.0"
    }
}
