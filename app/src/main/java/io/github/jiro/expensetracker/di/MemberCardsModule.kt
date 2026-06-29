package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import io.github.jiro.expensetracker.data.repository.MemberCardRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MemberCardsModule {

    @Binds
    @Singleton
    abstract fun bindMemberCardRepository(
        impl: MemberCardRepositoryImpl
    ): MemberCardRepository
}