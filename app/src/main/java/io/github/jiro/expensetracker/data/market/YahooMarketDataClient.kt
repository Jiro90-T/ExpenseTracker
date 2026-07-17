package io.github.jiro.expensetracker.data.market

import io.github.jiro.expensetracker.data.local.MoneyFormat
import javax.inject.Inject
import javax.inject.Singleton
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
        val url = baseUrlProvider().toHttpUrl().newBuilder()
            .addPathSegments("v7/finance/quote")
            .addQueryParameter("symbols", symbols.joinToString(","))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Throwable) {
            throw MarketDataException("network failure: ${e.message}", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw MarketDataException("HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: throw MarketDataException("empty body")
            val parsed = try {
                JSONObject(body)
            } catch (e: Throwable) {
                throw MarketDataException("parse failure: ${e.message}", e)
            }
            val result = parsed
                .getJSONObject("quoteResponse")
                .optJSONArray("result")
            val bySymbol = mutableMapOf<String, Quote>()
            if (result != null) {
                for (i in 0 until result.length()) {
                    val obj = result.getJSONObject(i)
                    val sym = obj.getString("symbol")
                    val price = obj.optDouble("regularMarketPrice", Double.NaN)
                    if (price.isNaN()) continue
                    val currency = obj.optString("currency", "USD")
                    val asOfSec = obj.optLong("regularMarketTime", 0L)
                    bySymbol[sym] = Quote(
                        symbol = sym,
                        priceMinor = MoneyFormat.priceToMinor(price, currency),
                        currencyCode = currency,
                        asOfEpochMillis = asOfSec * 1000L,
                    )
                }
            }
            return symbols.map { bySymbol[it] }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://query1.finance.yahoo.com"
        const val USER_AGENT = "Mozilla/5.0"
    }
}