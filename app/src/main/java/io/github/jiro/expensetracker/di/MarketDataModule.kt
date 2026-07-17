package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.market.MarketDataClient
import io.github.jiro.expensetracker.data.market.YahooMarketDataClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MarketDataModule {

    @Binds
    @Singleton
    abstract fun bindMarketDataClient(impl: YahooMarketDataClient): MarketDataClient
}
