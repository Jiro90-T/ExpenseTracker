package io.github.jiro.expensetracker.data.accountimport

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTypeDefaultsTest {

    @Test fun iconFor_knownType_returnsExpected() {
        assertEquals("💵", AccountTypeDefaults.iconFor("CASH"))
        assertEquals("🏦", AccountTypeDefaults.iconFor("BANK"))
        assertEquals("💳", AccountTypeDefaults.iconFor("CREDIT_CARD"))
        assertEquals("📱", AccountTypeDefaults.iconFor("EWALLET"))
        assertEquals("💰", AccountTypeDefaults.iconFor("OTHER"))
    }

    @Test fun iconFor_unknownType_returnsFallback() {
        assertEquals("💵", AccountTypeDefaults.iconFor("UNKNOWN_TYPE"))
        assertEquals("💵", AccountTypeDefaults.iconFor(""))
        // Case-insensitive lookup.
        assertEquals("💵", AccountTypeDefaults.iconFor("cash"))
    }

    @Test fun colorFor_knownType_returnsExpected() {
        assertEquals(0xFF43A047.toInt(), AccountTypeDefaults.colorFor("CASH"))
        assertEquals(0xFF1976D2.toInt(), AccountTypeDefaults.colorFor("BANK"))
        assertEquals(0xFFC62828.toInt(), AccountTypeDefaults.colorFor("CREDIT_CARD"))
        assertEquals(0xFFF57C00.toInt(), AccountTypeDefaults.colorFor("EWALLET"))
        assertEquals(0xFF455A64.toInt(), AccountTypeDefaults.colorFor("OTHER"))
    }

    @Test fun colorFor_unknownType_returnsFallback() {
        assertEquals(0xFF1976D2.toInt(), AccountTypeDefaults.colorFor("UNKNOWN_TYPE"))
        assertEquals(0xFF1976D2.toInt(), AccountTypeDefaults.colorFor(""))
    }
}