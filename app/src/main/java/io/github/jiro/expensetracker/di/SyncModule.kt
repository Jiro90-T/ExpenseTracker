package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.DefaultDeviceIdProvider
import io.github.jiro.expensetracker.sync.DeviceIdProvider
import io.github.jiro.expensetracker.sync.dropbox.DropboxCloudSyncRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindCloudSyncRepository(
        impl: DropboxCloudSyncRepository,
    ): CloudSyncRepository

    @Binds
    @Singleton
    abstract fun bindDeviceIdProvider(
        impl: DefaultDeviceIdProvider,
    ): DeviceIdProvider
}