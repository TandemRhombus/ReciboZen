package app.recibozen.android

import app.recibozen.android.core.ocr.MeterReadingParser
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

    // --- A real CFE faceplate, transcribed from a four-dial meter ---

    private val realFaceplate = """
        COMISION FEDERAL DE ELECTRICIDAD
        Rr 71 1/9
        WATTHORIMETRO MONOFASICO
        kWh
        IUSA TIPO AP-76  Prueba  Kh 25/8
        SIC DGE 4348 SIC DGN 250
        CL-100 - 120 V - 2H - 15A - 60 Hz
        COD MED F121 COD LOTE 79 EG
        X 7690 W
    """.trimIndent()

    @Test
    fun aDialMeterFaceplateYieldsNoReadingBecauseThereAreNoDigitsToRead() {
        // The value on this meter lives in needle angles. Every number printed on
        // it is a code. The old parser returned SIC DGE 4348 as the reading.
        assertNull(MeterReadingParser.parse(realFaceplate, 3_000, meterDigitCount = 4))
    }

    @Test
    fun theHomologationCodeIsNeverReturnedNoMatterHowCloseItSits() {
        // 4348 sits inside the plausible window for a household around 3,000 kWh,
        // which is exactly what made the closeness heuristic pick it.
        assertNull(MeterReadingParser.parse(realFaceplate, 3_000))
        assertNull(MeterReadingParser.parse(realFaceplate, 4_000))
        assertNull(MeterReadingParser.parse(realFaceplate, 7_500))
    }

    @Test
    fun aRunWithTheWrongNumberOfDigitsIsNotAReadingForThisMeter() {
        // A four-digit meter cannot display 18426.
        assertNull(MeterReadingParser.parse("LECTURA 18426", 1_000, meterDigitCount = 4))
        assertEquals(1_842, MeterReadingParser.parse("LECTURA 1842", 1_000, meterDigitCount = 4))
    }

    @Test
    fun twoCrediblereadingsMeanThePhotoDidNotEstablishOne() {
        // Guessing here writes a wrong number into the history and into every
        // projection that follows it.
        assertNull(MeterReadingParser.parse(listOf("18420", "18426").joinToString(System.lineSeparator()), 18_397))
    }

    @Test
    fun aValueBelowThePreviousReadingIsNotTreatedAsAReading() {
        // It may be a wrapped register, but that is MeterDelta's call to make on
        // a confirmed reading, not something to infer from a blurry photo.
        assertNull(MeterReadingParser.parse("LECTURA 00030", 9_950))
    }
}
