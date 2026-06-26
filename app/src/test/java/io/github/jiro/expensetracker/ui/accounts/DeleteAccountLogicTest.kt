package io.github.jiro.expensetracker.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteAccountLogicTest {

    @Test
    fun `count 0 returns ALLOW`() {
        assertEquals(DeleteGuard.ALLOW, evaluateDelete(0))
    }

    @Test
    fun `count 1 returns BLOCK_TRANSACTIONS_EXIST`() {
        assertEquals(DeleteGuard.BLOCK_TRANSACTIONS_EXIST, evaluateDelete(1))
    }

    @Test
    fun `count 14 returns BLOCK_TRANSACTIONS_EXIST`() {
        assertEquals(DeleteGuard.BLOCK_TRANSACTIONS_EXIST, evaluateDelete(14))
    }
}