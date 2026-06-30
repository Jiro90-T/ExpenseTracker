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

    @Test fun coerceInRange_countZero_returnsZero() {
        assertEquals(0, (-1).coerceInRange(0))
        assertEquals(0, 0.coerceInRange(0))
        assertEquals(0, 7.coerceInRange(0))
    }

    @Test fun coerceInRange_negativeIndexToZero() {
        assertEquals(0, (-5).coerceInRange(3))
        assertEquals(0, (-1).coerceInRange(1))
    }

    @Test fun coerceInRange_outOfRangeClampsHigh() {
        assertEquals(2, 7.coerceInRange(3))   // maxExclusive=3 → max index 2
        assertEquals(0, 9.coerceInRange(1))   // maxExclusive=1 → max index 0
    }

    @Test fun coerceInRange_inRangePassesThrough() {
        assertEquals(0, 0.coerceInRange(5))
        assertEquals(4, 4.coerceInRange(5))
    }
}