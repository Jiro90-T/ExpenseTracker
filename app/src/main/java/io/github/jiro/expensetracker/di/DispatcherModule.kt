package io.github.jiro.expensetracker.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Qualifier for the IO [CoroutineDispatcher]. Lets the AddReceiptViewModel
 * (and any future caller) accept an injected dispatcher for testability —
 * JVM unit tests pass a `TestDispatcher` so the OCR pipeline's
 * `withContext(IO)` doesn't escape the test scheduler.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Hilt bindings for shared coroutine dispatchers. Centralised here so we
 * never hardcode `Dispatchers.IO` inside feature code.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
