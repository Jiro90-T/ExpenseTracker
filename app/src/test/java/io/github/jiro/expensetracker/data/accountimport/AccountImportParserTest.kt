package io.github.jiro.expensetracker.data.accountimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountImportParserTest {

    private fun parse(bytes: ByteArray) = AccountImportParser.parse(bytes)

    @Test fun parse_simpleFile_returnsRows() {
        val csv = """
            name,type,currency,balance
            Cash,CASH,USD,250.00
            BPI,BANK,PHP,15000.00
            AmEx,CREDIT_CARD,USD,-120.50
        """.trimIndent()
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(3, ok.rows.size)
        assertEquals("Cash", ok.rows[0].name)
        assertEquals("CASH", ok.rows[0].type)
        assertEquals("USD", ok.rows[0].currency)
        assertEquals(25_000L, ok.rows[0].balanceMinor)
        assertEquals(2, ok.rows[0].lineNumber)
        assertEquals(-12_050L, ok.rows[2].balanceMinor)
        assertEquals(0, ok.rejected.size)
    }

    @Test fun parse_stripsUtf8Bom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val csv = "name,type,currency,balance\nCash,CASH,USD,1.00\n".toByteArray()
        val result = parse(bom + csv)
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(1, ok.rows.size)
    }

    @Test fun parse_handlesCrlfAndLf() {
        val csv = "name,type,currency,balance\r\nCash,CASH,USD,1.00\r\nBPI,BANK,PHP,2.00\n"
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(2, ok.rows.size)
    }

    @Test fun parse_handlesRfc4180QuotedFields() {
        val quotedRow1 = "\"Cash, primary\",CASH,USD,1.00"
        val quotedRow2 = "\"He said \"\"hi\"\"\",BANK,USD,2.00"
        val csv = ("name,type,currency,balance\n" +
            quotedRow1 + "\n" +
            quotedRow2 + "\n")
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(2, ok.rows.size)
        assertEquals("Cash, primary", ok.rows[0].name)
        assertEquals("He said \"hi\"", ok.rows[1].name)
    }

    @Test fun parse_skipsBlankLines() {
        val csv = """
            name,type,currency,balance

            Cash,CASH,USD,1.00

            BPI,BANK,PHP,2.00
        """.trimIndent()
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(2, ok.rows.size)
    }

    @Test fun parse_invalidHeader_returnsFailure() {
        val csv = "foo,bar,baz\nA,B,C"
        val result = parse(csv.toByteArray())
        assertTrue("expected Failed, got $result", result is ParseResult.Failed)
        val failed = result as ParseResult.Failed
        assertTrue(failed.reason.contains("name,type,currency,balance"))
    }

    @Test fun parse_wrongColumnCount_marksRowRejected() {
        val csv = """
            name,type,currency,balance
            Cash,CASH,USD,1.00
            Bad,Only,Three
            BPI,BANK,PHP,2.00
        """.trimIndent()
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(2, ok.rows.size)
        assertEquals(1, ok.rejected.size)
        assertEquals(3, ok.rejected[0].first)
        assertTrue(ok.rejected[0].second.contains("3 columns"))
    }

    @Test fun parse_blankName_marksRowRejected() {
        val csv = """
            name,type,currency,balance
            ,CASH,USD,1.00
        """.trimIndent()
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(0, ok.rows.size)
        assertEquals(1, ok.rejected.size)
        assertTrue(ok.rejected[0].second.contains("name is required"))
    }

    @Test fun parse_invalidCurrency_marksRowRejected() {
        val csv = """
            name,type,currency,balance
            Cash,CASH,US,1.00
        """.trimIndent()
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(1, ok.rejected.size)
        assertTrue(ok.rejected[0].second.contains("3-letter code"))
    }

    @Test fun parse_invalidBalance_marksRowRejected() {
        val csv = """
            name,type,currency,balance
            Cash,CASH,USD,not-a-number
        """.trimIndent()
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(1, ok.rejected.size)
        assertTrue(ok.rejected[0].second.contains("valid amount"))
    }

    @Test fun parse_negativeBalanceAccepted() {
        val csv = """
            name,type,currency,balance
            AmEx,CREDIT_CARD,USD,-120.50
        """.trimIndent()
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(-12_050L, ok.rows[0].balanceMinor)
    }

    @Test fun parse_emptyFile_returnsFailure() {
        val result = parse(ByteArray(0))
        assertTrue("expected Failed, got $result", result is ParseResult.Failed)
        val failed = result as ParseResult.Failed
        assertEquals("File is empty.", failed.reason)
    }

    @Test fun parse_headerOnly_returnsEmptyOk() {
        val csv = "name,type,currency,balance\n"
        val result = parse(csv.toByteArray())
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val ok = result as ParseResult.Ok
        assertEquals(0, ok.rows.size)
        assertEquals(0, ok.rejected.size)
    }
}