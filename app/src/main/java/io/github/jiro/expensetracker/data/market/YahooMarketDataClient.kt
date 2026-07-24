package io.github.jiro.expensetracker.data.market

import io.github.jiro.expensetracker.data.local.MoneyFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
class YahooMarketDataClient @Inject constructor(
    private val httpClient: OkHttpClient,
    /** Indirection so tests can inject a MockWebServer URL. Production
     *  binding returns the public endpoint. */
    private val baseUrlProvider: () -> String = { DEFAULT_BASE_URL },
) : MarketDataClient {

    override suspend fun fetchQuotes(symbols: List<String>): List<Quote?> {
        if (symbols.isEmpty()) return emptyList()
        val failures = mutableMapOf<String, String>()
        val results = symbols.mapIndexed { index, symbol ->
            if (index > 0) delay(REQUEST_DELAY_MS)
            val outcome = fetchSingleQuote(symbol)
            if (outcome == null) failures[symbol] = lastFailure ?: "unknown"
            outcome
        }
        // If every symbol failed, surface a transport-level error so the
        // caller can show the user something concrete. Partial failures
        // (some null, some Quote) stay silent — the cached entries for
        // the nulls are preserved, and any successfully fetched symbols
        // are written through.
        if (results.isNotEmpty() && results.all { it == null }) {
            val details = failures.entries.joinToString("; ") { (s, why) -> "$s: $why" }
            throw MarketDataException("All ${symbols.size} symbols failed to refresh ($details)")
        }
        return results
    }

    private var lastFailure: String? = null

    private fun fetchSingleQuote(symbol: String): Quote? {
        val url = baseUrlProvider().toHttpUrl().newBuilder()
            .addPathSegments("v8/finance/chart/$symbol")
            .addQueryParameter("interval", "1d")
            .addQueryParameter("range", "5d")
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
            lastFailure = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            return null
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                lastFailure = "HTTP ${resp.code}"
                return null
            }
            val body = resp.body?.string()
            if (body.isNullOrBlank()) {
                lastFailure = "empty body"
                return null
            }
            return try {
                val chart = JSONObject(body).getJSONObject("chart")
                if (chart.optJSONObject("error") != null) {
                    lastFailure = "chart.error: ${chart.getJSONObject("error").optString("description", "?")}"
                    return null
                }
                val result = chart.optJSONArray("result")
                    ?.optJSONObject(0)
                if (result == null) {
                    lastFailure = "empty result array"
                    return null
                }
                val meta = result.getJSONObject("meta")
                val currency = meta.optString("currency", "USD")
                val price = meta.optDouble("regularMarketPrice", Double.NaN)
                if (!price.isFinite()) {
                    lastFailure = "price NaN"
                    return null
                }
                if (price <= 0.0) {
                    lastFailure = "price $price <= 0"
                    return null
                }
                val asOfSec = meta.optLong("regularMarketTime", 0L)
                Quote(
                    symbol = symbol,
                    priceMinor = MoneyFormat.priceToMinor(price, currency),
                    currencyCode = currency,
                    asOfEpochMillis = asOfSec * 1000L,
                )
            } catch (e: Exception) {
                lastFailure = "parse: ${e.javaClass.simpleName}: ${e.message ?: "?"}"
                null
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://query1.finance.yahoo.com"
        const val USER_AGENT = "Mozilla/5.0"
        const val REQUEST_DELAY_MS = 50L
    }
}
