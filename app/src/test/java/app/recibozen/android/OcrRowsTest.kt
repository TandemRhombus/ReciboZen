package app.recibozen.android

import app.recibozen.android.core.ocr.MeterReadingParser
import app.recibozen.android.core.ocr.OcrRows
import app.recibozen.android.core.ocr.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrRowsTest {

    /** Five drum windows, 40 px apart, returned by the recognizer out of order. */
    private val drumDigits = listOf(
        OcrWord("2", left = 280, top = 402, right = 300, bottom = 440),
        OcrWord("0", left = 120, top = 400, right = 140, bottom = 438),
        OcrWord("5", left = 240, top = 399, right = 260, bottom = 437),
        OcrWord("1", left = 320, top = 401, right = 340, bottom = 439),
        OcrWord("4", left = 160, top = 403, right = 180, bottom = 441),
    )

    @Test
    fun separateWindowsOnOneRowComeBackLeftToRight() {
        assertEquals("0 4 5 2 1", OcrRows.reassemble(drumDigits))
    }

    @Test
    fun theRecognizersOneDigitPerLineTextIsReadableOnlyThroughTheRows() {
        // What the plain text looks like when each window is its own block.
        val plain = drumDigits.joinToString("\n") { it.text }
        assertNull(MeterReadingParser.parse(plain, 4_480, meterDigitCount = 5))
        assertEquals(
            4_521,
            MeterReadingParser.parse(plain + "\n" + OcrRows.reassemble(drumDigits), 4_480, meterDigitCount = 5),
        )
    }

    @Test
    fun rowsStackTopToBottomAndDoNotBleedIntoEachOther() {
        val words = drumDigits + listOf(
            OcrWord("kWh", left = 200, top = 330, right = 260, bottom = 360),
            OcrWord("SERIE", left = 100, top = 500, right = 180, bottom = 530),
            OcrWord("9374621", left = 190, top = 502, right = 300, bottom = 532),
        )
        assertEquals("kWh\n0 4 5 2 1\nSERIE 9374621", OcrRows.reassemble(words))
    }
}
