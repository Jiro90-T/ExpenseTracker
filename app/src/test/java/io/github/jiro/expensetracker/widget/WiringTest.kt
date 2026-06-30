// app/src/test/java/io/github/jiro/expensetracker/widget/WiringTest.kt
package io.github.jiro.expensetracker.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WiringTest {
    @Test fun nextIndex_countZero_returnsZero() {
        assertEquals(0, Wiring.nextIndex(current = 0, count = 0))
        assertEquals(0, Wiring.nextIndex(current = 3, count = 0))
        assertEquals(0, Wiring.nextIndex(current = -1, count = 0))
    }

    @Test fun nextIndex_countOne_returnsZero() {
        assertEquals(0, Wiring.nextIndex(current = 0, count = 1))
        assertEquals(0, Wiring.nextIndex(current = 7, count = 1))
    }

    @Test fun nextIndex_countThree_wrapsFromLastToZero() {
        assertEquals(0, Wiring.nextIndex(current = 2, count = 3))
    }

    @Test fun nextIndex_countFive_advancesByOne() {
        assertEquals(1, Wiring.nextIndex(current = 0, count = 5))
        assertEquals(3, Wiring.nextIndex(current = 2, count = 5))
        assertEquals(4, Wiring.nextIndex(current = 3, count = 5))
    }

    @Test fun nextIndex_negativeCount_returnsZero() {
        assertEquals(0, Wiring.nextIndex(current = 0, count = -3))
    }
}