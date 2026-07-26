package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.fx.ExchangeRateApiClient
import io.github.jiro.expensetracker.data.fx.FxRateClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FxRateModule {

    @Binds
    @Singleton
    abstract fun bindFxRateClient(impl: ExchangeRateApiClient): FxRateClient
}
