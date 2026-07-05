package io.github.jiro.expensetracker.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Provides a single shared [OkHttpClient] for the entire sync subsystem.
 * Both cloud-sync providers (Google Drive, Dropbox) need an HTTP client;
 * one shared instance lets them reuse the connection pool.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}