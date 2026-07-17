package io.github.jiro.expensetracker.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountDeleteGuardTest {

    @Test fun noTransactions_noHoldings_allowsDelete() {
        assertEquals(
            DeleteGuard.ALLOW,
            evaluateDelete(referenceCount = 0, holdingsCount = 0),
        )
    }

    @Test fun hasTransactions_blocksEvenWithoutHoldings() {
        assertEquals(
            DeleteGuard.BLOCK_TRANSACTIONS_EXIST,
            evaluateDelete(referenceCount = 1, holdingsCount = 0),
        )
    }

    @Test fun noTransactions_hasHoldings_blocks() {
        assertEquals(
            DeleteGuard.BLOCK_HOLDINGS_EXIST,
            evaluateDelete(referenceCount = 0, holdingsCount = 1),
        )
    }

    @Test fun hasBoth_blocksTransactionsWins() {
        // Transactions-block is the older / higher-priority guard.
        assertEquals(
            DeleteGuard.BLOCK_TRANSACTIONS_EXIST,
            evaluateDelete(referenceCount = 2, holdingsCount = 3),
        )
    }
}
