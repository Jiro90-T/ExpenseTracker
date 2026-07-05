package io.github.jiro.expensetracker.sync.google.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.google.DefaultSyncTokensRepository
import io.github.jiro.expensetracker.sync.google.DefaultTokenExchangeClient
import io.github.jiro.expensetracker.sync.google.DriveApiClient
import io.github.jiro.expensetracker.sync.google.DriveApiClientImpl
import io.github.jiro.expensetracker.sync.google.GoogleAuth
import io.github.jiro.expensetracker.sync.google.GoogleDriveCloudSyncRepository
import io.github.jiro.expensetracker.sync.google.GoogleSignInAuthImpl
import io.github.jiro.expensetracker.sync.google.KeystoreTokenCrypto
import io.github.jiro.expensetracker.sync.google.SyncTokensRepository
import io.github.jiro.expensetracker.sync.google.TokenCrypto
import io.github.jiro.expensetracker.sync.google.TokenExchangeClient
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GoogleDriveModule {

    @Binds
    @Singleton
    abstract fun bindCloudSyncRepository(
        impl: GoogleDriveCloudSyncRepository,
    ): CloudSyncRepository

    @Binds
    @Singleton
    abstract fun bindGoogleAuth(
        impl: GoogleSignInAuthImpl,
    ): GoogleAuth

    @Binds
    @Singleton
    abstract fun bindDriveApiClient(
        impl: DriveApiClientImpl,
    ): DriveApiClient

    @Binds
    @Singleton
    abstract fun bindSyncTokensRepository(
        impl: DefaultSyncTokensRepository,
    ): SyncTokensRepository

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        @Provides
        @Singleton
        fun provideTokenCrypto(): TokenCrypto = KeystoreTokenCrypto()

        @Provides
        @Singleton
        fun provideTokenExchangeClient(httpClient: OkHttpClient): TokenExchangeClient =
            DefaultTokenExchangeClient(httpClient)
    }
}