package io.github.jiro.expensetracker.data.market

import io.github.jiro.expensetracker.data.local.CachedQuoteDao
import io.github.jiro.expensetracker.data.local.CachedQuoteEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class QuoteRepository @Inject constructor(
    private val client: MarketDataClient,
    private val quoteDao: CachedQuoteDao,
) {

    fun observeCached(symbol: String): Flow<CachedQuoteEntity?> =
        quoteDao.observeBySymbols(listOf(symbol)).map { it.firstOrNull() }

    fun observeAllCached(symbols: List<String>): Flow<Map<String, CachedQuoteEntity>> =
        quoteDao.observeBySymbols(symbols).map { rows -> rows.associateBy { it.symbol } }

    /** Fetches and writes-through. Per-symbol outcome reflects what
     *  actually happened. Re-throws [MarketDataException] on full transport
     *  failure so the caller can surface it. */
    suspend fun refresh(symbols: List<String>): RefreshOutcome {
        val perSymbol = mutableMapOf<String, SymbolOutcome>()
        val quotes = try {
            client.fetchQuotes(symbols)
        } catch (e: MarketDataException) {
            // Mark all as Failed and re-throw. Cache untouched.
            symbols.forEach { perSymbol[it] = SymbolOutcome.Failed(e.message ?: "unknown") }
            throw e
        }
        symbols.forEachIndexed { i, symbol ->
            val q = quotes.getOrNull(i)
            when {
                q == null -> {
                    // Unknown to the feed — preserve any existing cache.
                    perSymbol[symbol] = SymbolOutcome.Unknown
                }
                else -> {
                    quoteDao.upsert(
                        CachedQuoteEntity(
                            symbol = q.symbol,
                            priceMinor = q.priceMinor,
                            currencyCode = q.currencyCode,
                            fetchedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                    perSymbol[symbol] = SymbolOutcome.Fresh
                }
            }
        }
        return RefreshOutcome(perSymbol)
    }
}

data class RefreshOutcome(val perSymbol: Map<String, SymbolOutcome>)

sealed interface SymbolOutcome {
    object Fresh : SymbolOutcome
    object Unknown : SymbolOutcome
    data class Failed(val reason: String) : SymbolOutcome
}