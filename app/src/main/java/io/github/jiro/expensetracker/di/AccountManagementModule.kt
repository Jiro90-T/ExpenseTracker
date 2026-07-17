package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.accountimport.AccountImportRepository
import io.github.jiro.expensetracker.data.accountimport.AccountImportRepositoryImpl
import io.github.jiro.expensetracker.data.repository.AccountDataSource
import io.github.jiro.expensetracker.data.repository.AccountRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountManagementModule {

    @Binds
    @Singleton
    abstract fun bindAccountImportRepository(
        impl: AccountImportRepositoryImpl
    ): AccountImportRepository

    @Binds
    @Singleton
    abstract fun bindAccountDataSource(impl: AccountRepository): AccountDataSource
}