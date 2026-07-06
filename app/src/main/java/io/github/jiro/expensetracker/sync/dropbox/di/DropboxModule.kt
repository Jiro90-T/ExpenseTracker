package io.github.jiro.expensetracker.sync.dropbox.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.BuildConfig
import io.github.jiro.expensetracker.sync.dropbox.AppAuthDropboxAuth
import io.github.jiro.expensetracker.sync.dropbox.DefaultDropboxSyncTokensRepository
import io.github.jiro.expensetracker.sync.dropbox.DropboxApiClient
import io.github.jiro.expensetracker.sync.dropbox.DropboxApiClientImpl
import io.github.jiro.expensetracker.sync.dropbox.DropboxAuth
import io.github.jiro.expensetracker.sync.dropbox.DropboxSyncTokensRepository
import io.github.jiro.expensetracker.sync.dropbox.KeystoreTokenCrypto
import io.github.jiro.expensetracker.sync.dropbox.TokenCrypto
import javax.inject.Named
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

        private const val HOST_CONTENT = "https://content.dropboxapi.com"
        private const val HOST_API = "https://api.dropboxapi.com"
        private const val REDIRECT_URI = "io.github.jiro.expensetracker:/oauth2redirect"

        @Provides
        @Singleton
        @Named("dropboxClientId")
        fun provideDropboxClientId(): String = BuildConfig.DROPBOX_CLIENT_ID

        @Provides
        @Singleton
        @Named("dropboxRedirectUri")
        fun provideDropboxRedirectUri(): String = REDIRECT_URI

        @Provides
        @Singleton
        @Named("dropboxContentHost")
        fun provideDropboxContentHost(): String = HOST_CONTENT

        @Provides
        @Singleton
        @Named("dropboxApiHost")
        fun provideDropboxApiHost(): String = HOST_API

        @Provides
        @Singleton
        @Named("dropboxNowProvider")
        fun provideNowProvider(): () -> Long = { System.currentTimeMillis() }
    }
}