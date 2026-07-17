package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.market.MarketDataClient
import io.github.jiro.expensetracker.data.market.QuoteDataSource
import io.github.jiro.expensetracker.data.market.QuoteRepository
import io.github.jiro.expensetracker.data.market.YahooMarketDataClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MarketDataModule {

    @Binds
    @Singleton
    abstract fun bindMarketDataClient(impl: YahooMarketDataClient): MarketDataClient

    @Binds
    @Singleton
    abstract fun bindQuoteDataSource(impl: QuoteRepository): QuoteDataSource

    companion object {
        /** Default Yahoo Finance base URL. Tests override via the constructor
         *  parameter on YahooMarketDataClient directly; this provider exists
         *  solely to satisfy the Hilt graph. */
        @Provides
        @Singleton
        fun provideYahooBaseUrlProvider(): () -> String =
            { YahooMarketDataClient.DEFAULT_BASE_URL }
    }
}
