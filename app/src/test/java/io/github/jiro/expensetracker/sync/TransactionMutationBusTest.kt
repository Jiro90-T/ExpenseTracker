package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionMutationBusTest {

    @Test
    fun emit_emitsValue() = runBlocking {
        val bus = TransactionMutationBus()
        val received = async { bus.events.first() }
        bus.tryEmit()
        assertEquals(Unit, received.await())
    }

    @Test
    fun tryEmit_returnsTrue_whenBuffered() {
        val bus = TransactionMutationBus()
        assertTrue(bus.tryEmit())
    }

    @Test
    fun multipleSubscribers_allReceive() = runBlocking {
        val bus = TransactionMutationBus()
        val received1 = async { bus.events.take(2).toList() }
        val received2 = async { bus.events.take(2).toList() }
        // Give the subscribers a chance to register
        kotlinx.coroutines.yield()
        bus.tryEmit()
        bus.tryEmit()
        assertEquals(listOf(Unit, Unit), received1.await())
        assertEquals(listOf(Unit, Unit), received2.await())
    }
}