package app.recibozen.android

import app.recibozen.android.core.calculation.MeterProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three probes this replaces all asked `meterType.contains("4")`. These
 * tests pin the two things that made that wrong beyond being fragile: a stray
 * "4" anywhere in the label used to mean "four dials", and everything that did
 * not match was silently treated as five.
 */
class MeterProfileTest {

    @Test
    fun theDigitCountComesFromTheDialCountTheLabelStates() {
        assertEquals(4, MeterProfile.digitCount("De manecillas, 4 carátulas"))
        assertEquals(5, MeterProfile.digitCount("De manecillas, 5 carátulas"))
    }

    @Test
    fun theAccentIsNotRequiredAndSpacingDoesNotMatter() {
        assertEquals(4, MeterProfile.digitCount("De manecillas, 4 caratulas"))
        assertEquals(4, MeterProfile.digitCount("4carátulas"))
    }

    @Test
    fun aDigitalMeterHasNoStatedDigitCountAndIsNotAssumedToHaveFive() {
        // This is the whole point. A digital meter shows 5, 6 or 7 digits and
        // nobody asked which. Defaulting to five is what lets the app do
        // rollover arithmetic against a capacity the meter does not have.
        assertNull(MeterProfile.digitCount("Digital, con números"))
        assertNull(MeterProfile.capacity("Digital, con números"))
    }

    @Test
    fun anUnansweredOrUnrecognisedLabelYieldsNothing() {
        assertNull(MeterProfile.digitCount(""))
        assertNull(MeterProfile.digitCount("No estoy seguro"))
    }

    @Test
    fun aFourAppearingElsewhereInTheLabelIsNotADialCount() {
        // The old probe read "contains 4" and would have called each of these a
        // four-dial meter.
        assertNull(MeterProfile.digitCount("Digital, 4 hilos"))
        assertNull(MeterProfile.digitCount("Tipo AP-76 lote 4"))
        assertNull(MeterProfile.digitCount("Medidor 240 V"))
    }

    @Test
    fun capacityIsTenToTheDigitCount() {
        assertEquals(10_000, MeterProfile.capacity("De manecillas, 4 carátulas"))
        assertEquals(100_000, MeterProfile.capacity("De manecillas, 5 carátulas"))
    }

    @Test
    fun aFourDialMeterDropsTheLeadingModelledFace() {
        assertEquals(listOf(1, 2, 3, 4), MeterProfile.faceIndices("De manecillas, 4 carátulas"))
        assertEquals(1, MeterProfile.firstFaceIndex("De manecillas, 4 carátulas"))
    }

    @Test
    fun aFiveDialMeterKeepsEveryFace() {
        assertEquals(listOf(0, 1, 2, 3, 4), MeterProfile.faceIndices("De manecillas, 5 carátulas"))
        assertEquals(0, MeterProfile.firstFaceIndex("De manecillas, 5 carátulas"))
    }

    @Test
    fun anUnknownMeterKeepsEveryFaceRatherThanGuessingATrim() {
        assertEquals(listOf(0, 1, 2, 3, 4), MeterProfile.faceIndices("Digital, con números"))
    }

    @Test
    fun trimmingKeepsTheLeastSignificantDigitsTheMeterActuallyShows() {
        val modelled = listOf(1, 2, 3, 4, 5)

        assertEquals(listOf(2, 3, 4, 5), MeterProfile.trimDigits("De manecillas, 4 carátulas", modelled))
        assertEquals(modelled, MeterProfile.trimDigits("De manecillas, 5 carátulas", modelled))
        assertEquals(modelled, MeterProfile.trimDigits("Digital, con números", modelled))
    }
}
