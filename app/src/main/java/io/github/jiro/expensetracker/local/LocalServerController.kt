package io.github.jiro.expensetracker.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.auth.SessionTokenGenerator
import io.github.jiro.expensetracker.preferences.SettingsRepository
import java.net.BindException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class LocalServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("unused") private val transactionRepository: TransactionRepository,
    @Suppress("unused") private val accountRepository: AccountRepository,
    @Suppress("unused") private val categoryRepository: CategoryRepository,
    @Suppress("unused") private val budgetRepository: BudgetRepository,
    @Suppress("unused") private val settingsRepository: SettingsRepository,
    @Suppress("unused") private val sessionTokenGenerator: SessionTokenGenerator,
) {

    private var serviceStarter: (port: Int) -> Unit = { port ->
        LocalServerService.start(context, port)
    }
    private var serviceStopper: () -> Unit = { LocalServerService.stop(context) }

    // The service publishes its session token to a companion holder, so no binding is required here.
    private var tokenRetriever: () -> String? = { LocalServerService.currentToken }
    private var ipProvider: () -> String? = { null }

    internal constructor(
        @ApplicationContext context: Context,
        transactionRepository: TransactionRepository,
        accountRepository: AccountRepository,
        categoryRepository: CategoryRepository,
        budgetRepository: BudgetRepository,
        settingsRepository: SettingsRepository,
        sessionTokenGenerator: SessionTokenGenerator,
        serviceStarter: (port: Int) -> Unit,
        serviceStopper: () -> Unit,
        tokenRetriever: () -> String?,
        ipProvider: () -> String?,
    ) : this(context, transactionRepository, accountRepository, categoryRepository,
        budgetRepository, settingsRepository, sessionTokenGenerator) {
        this.serviceStarter = serviceStarter
        this.serviceStopper = serviceStopper
        this.tokenRetriever = tokenRetriever
        this.ipProvider = ipProvider
    }

    private val _state = MutableStateFlow(LocalServerState())
    val state: StateFlow<LocalServerState> = _state.asStateFlow()

    private val startMutex = Mutex()

    suspend fun start(): Result<Unit> = startMutex.withLock {
        if (_state.value.isRunning) return@withLock Result.success(Unit)
        try {
            serviceStarter(LocalServerState.DEFAULT_PORT)
            _state.update {
                it.copy(
                    // Dispatch only: the port binds inside the service, so BindException shows here only if the foreground start fails.
                    isRunning = true,
                    port = LocalServerState.DEFAULT_PORT,
                    ipAddress = ipProvider(),
                    token = tokenRetriever(),
                    lastError = null,
                )
            }
            Result.success(Unit)
        } catch (e: BindException) {
            _state.update {
                it.copy(
                    isRunning = false,
                    token = null,
                    lastError = "Port ${LocalServerState.DEFAULT_PORT} is in use.",
                )
            }
            Result.failure(e)
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    isRunning = false,
                    token = null,
                    lastError = "Failed to start: ${t.message ?: t::class.java.simpleName}",
                )
            }
            Result.failure(t)
        }
    }

    fun stop() {
        if (!_state.value.isRunning) return
        serviceStopper()
        _state.update { it.copy(isRunning = false, token = null, lastError = null) }
    }

    fun refreshIpAddress() {
        _state.update { it.copy(ipAddress = ipProvider()) }
    }

    /**
     * Replaces the IP-address resolver. Called once from [SettingsViewModel]'s init to
     * install the real WiFi-backed provider; the default returns null so unit tests
     * (and any caller that never installs one) stay free of Android framework calls.
     */
    fun setIpProvider(provider: () -> String?) {
        ipProvider = provider
    }

    /**
     * Re-reads the session token published by the service.
     *
     * [start] dispatches the service via `startForegroundService` and reads the token
     * immediately afterwards, but the service publishes it from `onStartCommand`, which
     * runs asynchronously — so that first read can race and come back null. Callers that
     * start the server should call this again shortly after to pick up the real token.
     * A null read is ignored so a late call can never clear a token already in state.
     */
    fun refreshToken() {
        val token = tokenRetriever() ?: return
        _state.update { it.copy(token = token) }
    }
}
