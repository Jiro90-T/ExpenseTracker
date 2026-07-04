package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.ui.statistics.DataStoreStatisticsRangeRepository
import io.github.jiro.expensetracker.ui.statistics.StatisticsRangeRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StatisticsModule {

    @Binds
    @Singleton
    abstract fun bindStatisticsRangeRepository(
        impl: DataStoreStatisticsRangeRepository
    ): StatisticsRangeRepository
}