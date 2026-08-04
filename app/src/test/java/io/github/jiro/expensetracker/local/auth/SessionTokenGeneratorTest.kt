package io.github.jiro.expensetracker.local.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionTokenGeneratorTest {

    private val generator = SessionTokenGenerator()

    @Test fun token_isBase64UrlSafe() {
        val token = generator.generate()
        // No padding, no '+', no '/'
        assertTrue("token contains '+': $token", '+' !in token)
        assertTrue("token contains '/': $token", '/' !in token)
        assertTrue("token contains '=': $token", '=' !in token)
        assertTrue("token contains whitespace: $token", token.none { it.isWhitespace() })
    }

    @Test fun token_lengthIs43Chars() {
        // 32 bytes base64url-encoded without padding = ceil(32 * 8 / 6) = 43 chars
        val token = generator.generate()
        assertEquals(43, token.length)
    }

    @Test fun consecutiveCalls_returnDistinctTokens() {
        val a = generator.generate()
        val b = generator.generate()
        val c = generator.generate()
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)
    }
}
