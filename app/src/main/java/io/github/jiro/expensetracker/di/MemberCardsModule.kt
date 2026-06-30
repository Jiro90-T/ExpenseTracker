package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import io.github.jiro.expensetracker.data.repository.MemberCardRepositoryImpl
import io.github.jiro.expensetracker.widget.WidgetRefresher
import io.github.jiro.expensetracker.widget.WidgetRefresherImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MemberCardsModule {

    @Binds
    @Singleton
    abstract fun bindMemberCardRepository(
        impl: MemberCardRepositoryImpl
    ): MemberCardRepository

    companion object {
        @Provides
        @Singleton
        fun provideWidgetRefresher(impl: WidgetRefresherImpl): WidgetRefresher = impl
    }
}