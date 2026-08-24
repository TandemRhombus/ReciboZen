package app.recibozen.android

import app.recibozen.android.core.calculation.MeterDelta
import app.recibozen.android.core.calculation.MeterDelta.Companion.consumption
import app.recibozen.android.core.calculation.ReadingInterval
import app.recibozen.android.core.calculation.ReplacementInSpan
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.MeterReplacement
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.demo.repository.DemoReciboZenRepository
import app.recibozen.android.demo.repository.ReciboZenSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fifth thing a reading that goes backwards can mean: CFE replaced the
 * meter.
 *
 * It was the only one the app could not record, and the consequence was not a
 * bad number for one interval -- it was permanent. Every later reading was
 * compared against a history built on a register that no longer exists, so the
 * household stayed stuck at "no podemos saber" forever.
 *
 * A replacement is not a rollover and these tests exist mostly to keep the two
 * apart. A rollover is one register passing its maximum, and the capacity closes
 * the arithmetic. A replacement is two registers, and nothing about the new one
 * follows from the old one.
 */
class MeterReplacementTest {

    private class Repository : DemoReciboZenRepository() {
        fun hydrate(state: ReciboZenSessionState) { mutableSession.value = state }
    }

    private val fourDial = "De manecillas, 4 carátulas"
    private val nowMillis = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun reading(kwh: Int, daysAgo: Long, replacement: MeterReplacement? = null) =
        HistoryReadingEntry(
            kwh = kwh,
            dateLabel = "hace $daysAgo d",
            method = ReadingMethod.MANUAL,
            note = "",
            timestampEpochMillis = nowMillis - daysAgo * day,
            meterReplacement = replacement,
        )

    // --- Closing the interval across the swap ---

    @Test
    fun consumptionAcrossASwapIsWhatTheOldMeterStillGavePlusWhatTheNewOneCounted() {
        // Previous reading 9,800. The old meter reached 9,950 before it was
        // pulled, the new one was installed at 0 and now reads 120.
        val result = consumption(
            previousKwh = 9_800,
            currentKwh = 120,
            elapsedDays = 30.0,
            capacityKwh = 10_000,
            replacement = MeterReplacement(previousMeterFinalKwh = 9_950, newMeterStartKwh = 0),
        )

        assertEquals(270, (result as MeterDelta.Measured).kwh)
        assertTrue(result.acrossMeterReplacement)
        assertTrue("la cifra descansa en lo que declaró el usuario", result.restsOnAttestation)
    }

    @Test
    fun aRefurbishedMeterInstalledWithANonZeroRegisterIsHandled() {
        // Assuming zero here would invent whatever the unit already showed.
        val result = consumption(
            previousKwh = 9_800,
            currentKwh = 500,
            elapsedDays = 30.0,
            capacityKwh = 10_000,
            replacement = MeterReplacement(previousMeterFinalKwh = 9_850, newMeterStartKwh = 380),
        )

        // 50 left on the old meter, 120 on the new one.
        assertEquals(170, (result as MeterDelta.Measured).kwh)
    }

    @Test
    fun theCfeMultiplierStillApplasAcrossASwap() {
        val result = consumption(
            previousKwh = 1_000,
            currentKwh = 30,
            elapsedDays = 30.0,
            multiplier = 20,
            replacement = MeterReplacement(previousMeterFinalKwh = 1_010, newMeterStartKwh = 0),
        )

        // (10 + 30) register units at x20.
        assertEquals(800, (result as MeterDelta.Measured).kwh)
    }

    // --- What it refuses ---

    @Test
    fun aSwapWithoutItsBoundaryReadingsIsUndeterminedRatherThanGuessedAtZero() {
        val result = consumption(
            previousKwh = 9_800,
            currentKwh = 120,
            elapsedDays = 30.0,
            capacityKwh = 10_000,
            replacement = MeterReplacement(),
        )

        assertEquals(MeterDelta.Undecidable(MeterDelta.Reason.METER_REPLACED), result)
    }

    @Test
    fun halfAnAnswerIsStillNotAnAnswer() {
        val onlyStart = consumption(
            previousKwh = 9_800,
            currentKwh = 120,
            elapsedDays = 30.0,
            replacement = MeterReplacement(newMeterStartKwh = 0),
        )

        assertEquals(MeterDelta.Undecidable(MeterDelta.Reason.METER_REPLACED), onlyStart)
    }

    @Test
    fun boundaryReadingsThatImplyNegativeConsumptionAreRejected() {
        // A "final" reading below the previous one means one of the two numbers
        // is wrong, and only the household can say which.
        val result = consumption(
            previousKwh = 9_800,
            currentKwh = 120,
            elapsedDays = 30.0,
            replacement = MeterReplacement(previousMeterFinalKwh = 9_700, newMeterStartKwh = 0),
        )

        assertEquals(MeterDelta.Undecidable(MeterDelta.Reason.METER_REPLACED), result)
    }

    // --- A swap is not a rollover ---

    @Test
    fun aSwapNeverGoesThroughTheRolloverArithmetic() {
        // Same readings as a textbook four-dial wrap. Attesting a wrap would give
        // 10,000 - 9,800 + 120 = 320; the swap gives 270. They are different
        // events and must not produce the same number.
        val asWrap = consumption(
            previousKwh = 9_800,
            currentKwh = 120,
            elapsedDays = 30.0,
            capacityKwh = 10_000,
            attestedWraps = 1,
        )
        val asSwap = consumption(
            previousKwh = 9_800,
            currentKwh = 120,
            elapsedDays = 30.0,
            capacityKwh = 10_000,
            attestedWraps = 1,
            replacement = MeterReplacement(previousMeterFinalKwh = 9_950, newMeterStartKwh = 0),
        )

        assertEquals(320, (asWrap as MeterDelta.Measured).kwh)
        assertEquals(270, (asSwap as MeterDelta.Measured).kwh)
        assertEquals(0, asSwap.wraps)
    }

    @Test
    fun theCapacityCheckDoesNotFireOnAReadingFromTheOtherMeter() {
        // A four-dial meter replaced by a six-digit one. The new reading exceeds
        // the old meter's capacity, which is expected and not a contradiction:
        // the capacity belongs to a device that is no longer installed.
        val result = consumption(
            previousKwh = 9_800,
            currentKwh = 45_000,
            elapsedDays = 30.0,
            capacityKwh = 10_000,
            replacement = MeterReplacement(previousMeterFinalKwh = 9_950, newMeterStartKwh = 44_800),
        )

        assertEquals(350, (result as MeterDelta.Measured).kwh)
    }

    // --- Spans that are not adjacent ---

    @Test
    fun aSwapAnywhereInTheSpanIsFoundEvenWhenTheEndpointsAreNotAdjacent() {
        val baseline = reading(9_000, daysAgo = 60)
        val middle = reading(50, daysAgo = 30, replacement = MeterReplacement(9_400, 0))
        val newest = reading(300, daysAgo = 1)
        val readings = listOf(newest, middle, baseline)

        val found = ReadingInterval.replacementBetween(readings, baseline, newest)

        assertEquals(ReplacementInSpan.Declared(MeterReplacement(9_400, 0)), found)
    }

    @Test
    fun anIncompleteSwapInTheSpanIsAmbiguousAndNeverReadsAsNoSwap() {
        val baseline = reading(9_000, daysAgo = 60)
        val middle = reading(50, daysAgo = 30, replacement = MeterReplacement())
        val newest = reading(300, daysAgo = 1)
        val readings = listOf(newest, middle, baseline)

        assertEquals(ReplacementInSpan.Ambiguous, ReadingInterval.replacementBetween(readings, baseline, newest))
        // And what reaches MeterDelta must be an incomplete replacement, not
        // null: null would have it subtract across two different registers.
        val forDelta = ReadingInterval.replacementForDelta(readings, baseline, newest)
        assertEquals(MeterReplacement(), forDelta)
    }

    @Test
    fun twoSwapsInsideOneSpanAreRefusedRatherThanResolvedWithOneOfThem() {
        val baseline = reading(9_000, daysAgo = 90)
        val first = reading(20, daysAgo = 60, replacement = MeterReplacement(9_200, 0))
        val second = reading(15, daysAgo = 30, replacement = MeterReplacement(300, 0))
        val newest = reading(400, daysAgo = 1)
        val readings = listOf(newest, second, first, baseline)

        assertEquals(ReplacementInSpan.Ambiguous, ReadingInterval.replacementBetween(readings, baseline, newest))
    }

    @Test
    fun aSpanWithNoSwapIsLeftAlone() {
        val baseline = reading(9_000, daysAgo = 60)
        val newest = reading(9_300, daysAgo = 1)

        assertEquals(ReplacementInSpan.None, ReadingInterval.replacementBetween(listOf(newest, baseline), baseline, newest))
        assertNull(ReadingInterval.replacementForDelta(listOf(newest, baseline), baseline, newest))
    }

    // --- What gets stored ---

    @Test
    fun aConfirmedSwapTravelsWithTheReadingAndItsNoteSaysSo() {
        val repository = Repository()
        repository.hydrate(
            ReciboZenSessionState(
                meterType = fourDial,
                lastConfirmedReadingKwh = 9_800,
                readings = listOf(reading(9_800, daysAgo = 30)),
            ),
        )

        repository.confirmReading(
            kwh = 120,
            method = ReadingMethod.MANUAL,
            meterReplacement = MeterReplacement(previousMeterFinalKwh = 9_950, newMeterStartKwh = 0),
        )

        val stored = repository.session.value.readings.first()
        assertEquals(MeterReplacement(9_950, 0), stored.meterReplacement)
        assertTrue(stored.note, stored.note.contains("270"))
        assertTrue(stored.note, stored.note.contains("medidor"))
    }

    @Test
    fun aSwapSavedWithoutItsReadingsStillMarksTheBoundary() {
        // The point of allowing this: the boundary alone stops every later
        // reading from being compared against a register that is gone.
        val repository = Repository()
        repository.hydrate(
            ReciboZenSessionState(
                meterType = fourDial,
                lastConfirmedReadingKwh = 9_800,
                readings = listOf(reading(9_800, daysAgo = 30)),
            ),
        )

        repository.confirmReading(kwh = 120, method = ReadingMethod.MANUAL, meterReplacement = MeterReplacement())

        val stored = repository.session.value.readings.first()
        assertEquals(MeterReplacement(), stored.meterReplacement)
        assertFalse(stored.meterReplacement!!.isComplete)
        assertTrue(stored.note, stored.note.contains("cambiaron el medidor"))
    }

    @Test
    fun anOrdinaryReadingCarriesNoReplacementAtAll() {
        val repository = Repository()
        repository.hydrate(
            ReciboZenSessionState(
                meterType = fourDial,
                lastConfirmedReadingKwh = 9_000,
                readings = listOf(reading(9_000, daysAgo = 30)),
            ),
        )

        repository.confirmReading(kwh = 9_100, method = ReadingMethod.MANUAL)

        assertNull(repository.session.value.readings.first().meterReplacement)
    }
}
