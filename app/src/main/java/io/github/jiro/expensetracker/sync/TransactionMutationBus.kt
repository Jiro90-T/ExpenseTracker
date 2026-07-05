package io.github.jiro.expensetracker.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus for transaction/account mutations. Any VM that
 * successfully writes a transaction, account, or membership row emits a
 * [Unit] here; [RoutingCloudSyncRepository] collects the bus with a
 * debounce so a flurry of saves (e.g. CSV import) collapses to one push.
 *
 * Using [MutableSharedFlow] (not StateFlow) because events are transient —
 * an emitter shouldn't have to know if anyone is currently collecting.
 * Buffer of 4 keeps `tryEmit()` returning true even under bursty load.
 */
@Singleton
class TransactionMutationBus @Inject constructor() {

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    suspend fun emit() { _events.emit(Unit) }
    fun tryEmit(): Boolean = _events.tryEmit(Unit)
}