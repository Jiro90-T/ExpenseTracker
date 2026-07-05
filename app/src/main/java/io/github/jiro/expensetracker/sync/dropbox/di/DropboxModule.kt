package io.github.jiro.expensetracker.sync.dropbox.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.sync.dropbox.AppAuthDropboxAuth
import io.github.jiro.expensetracker.sync.dropbox.DefaultDropboxSyncTokensRepository
import io.github.jiro.expensetracker.sync.dropbox.DropboxApiClient
import io.github.jiro.expensetracker.sync.dropbox.DropboxApiClientImpl
import io.github.jiro.expensetracker.sync.dropbox.DropboxAuth
import io.github.jiro.expensetracker.sync.dropbox.DropboxSyncTokensRepository
import io.github.jiro.expensetracker.sync.dropbox.KeystoreTokenCrypto
import io.github.jiro.expensetracker.sync.dropbox.TokenCrypto
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DropboxModule {

    @Binds
    @Singleton
    abstract fun bindDropboxAuth(impl: AppAuthDropboxAuth): DropboxAuth

    @Binds
    @Singleton
    abstract fun bindDropboxApiClient(impl: DropboxApiClientImpl): DropboxApiClient

    @Binds
    @Singleton
    abstract fun bindDropboxSyncTokensRepository(
        impl: DefaultDropboxSyncTokensRepository,
    ): DropboxSyncTokensRepository

    companion object {
        @Provides
        @Singleton
        fun provideTokenCrypto(): TokenCrypto = KeystoreTokenCrypto()
    }
}