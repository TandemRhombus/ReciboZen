package com.recibozen.app

import com.recibozen.app.core.ocr.MeterReadingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeterReadingParserTest {
    @Test
    fun choosesReadingClosestToPreviousAndIgnoresSerialNumber() {
        val text = "MEDIDOR 9374621\nLECTURA 18426 kWh"
        assertEquals(18426, MeterReadingParser.parse(text, 18397))
    }

    @Test
    fun repairsCommonOcrCharactersInsideNumericToken() {
        assertEquals(18426, MeterReadingParser.parse("18O4S\nI8426", 18397))
    }

    @Test
    fun returnsNullWithoutPlausibleDigitGroup() {
        assertNull(MeterReadingParser.parse("sin lectura visible", 18397))
    }
}
