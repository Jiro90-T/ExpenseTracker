package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.market.MarketDataClient
import io.github.jiro.expensetracker.data.market.YahooMarketDataClient
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class MarketDataModule {

    @Binds
    @Singleton
    abstract fun bindMarketDataClient(impl: YahooMarketDataClient): MarketDataClient

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", YahooMarketDataClient.USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .build()
    }
}
