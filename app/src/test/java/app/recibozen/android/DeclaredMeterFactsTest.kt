package app.recibozen.android

import app.recibozen.android.core.calculation.MeterDelta
import app.recibozen.android.core.calculation.MeterProfile
import app.recibozen.android.core.calculation.ReadingInterval
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.demo.repository.DemoReciboZenRepository
import app.recibozen.android.demo.repository.ReciboZenSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digit count and the CFE multiplier as facts the household states.
 *
 * Before this, both were inferred: the digit count from a Spanish label that
 * says nothing for a digital meter, and the multiplier not at all -- readings
 * were never multiplied while bill import always was, so the two disagreed by
 * exactly that factor on every service read through a current transformer.
 */
class DeclaredMeterFactsTest {

    /** Exposes the protected session so a test can start from a known household. */
    private class Repository : DemoReciboZenRepository() {
        fun hydrate(state: ReciboZenSessionState) { mutableSession.value = state }
    }

    private val digital = "Digital, con números"
    private val fourDial = "De manecillas, 4 carátulas"
    private val fiveDial = "De manecillas, 5 carátulas"

    // --- The declaration outranks the label ---

    @Test
    fun aDeclaredCountAnswersForADigitalMeterWhoseLabelCannot() {
        // The whole reason this exists. Without a declaration a digital meter
        // has no capacity, so MeterDelta cannot even offer wrap candidates.
        assertNull(MeterProfile.digitCount(digital))
        assertEquals(6, MeterProfile.digitCount(digital, declaredDigitCount = 6))
        assertEquals(1_000_000, MeterProfile.capacity(digital, declaredDigitCount = 6))
    }

    @Test
    fun aDeclarationBeatsALabelThatContradictsIt() {
        // The label is a string picked off a list; the count is testimony from
        // someone looking at the register. Testimony wins.
        assertEquals(5, MeterProfile.digitCount(fourDial, declaredDigitCount = 5))
        assertEquals(100_000, MeterProfile.capacity(fourDial, declaredDigitCount = 5))
    }

    @Test
    fun anImpossibleDeclarationFallsBackToTheLabelInsteadOfBeingClamped() {
        // Clamping 0 up to 4 would manufacture a declaration nobody made.
        assertEquals(4, MeterProfile.digitCount(fourDial, declaredDigitCount = 0))
        assertEquals(4, MeterProfile.digitCount(fourDial, declaredDigitCount = 99))
        // With nothing to fall back to, the honest answer is still nothing.
        assertNull(MeterProfile.digitCount(digital, declaredDigitCount = 0))
    }

    @Test
    fun theLabelInferenceStaysAvailableOnItsOwn() {
        // Perfil shows where the current answer comes from, so it needs to ask
        // the label separately from the resolved value.
        assertEquals(5, MeterProfile.inferredDigitCount(fiveDial))
        assertNull(MeterProfile.inferredDigitCount(digital))
    }

    @Test
    fun theDeclarationAlsoDecidesWhichDialFacesExist() {
        assertEquals(listOf(1, 2, 3, 4), MeterProfile.faceIndices(digital, declaredDigitCount = 4))
        assertEquals(listOf(2, 3, 4, 5), MeterProfile.trimDigits(digital, listOf(1, 2, 3, 4, 5), 4))
    }

    // --- The owner's reported case, end to end ---

    @Test
    fun theOwnersFourDialWrapIsResolvedByTheAttestationTheScreenCollects() {
        // Reported against v5: previous 9803, meter now reads 0022. The register
        // is really at 10022, so 219 kWh were consumed. Before the attestation
        // exists this is undecidable, and it must stay that way.
        val unresolved = MeterDelta.consumption(
            previousKwh = 9_803,
            currentKwh = 22,
            elapsedDays = 12.0,
            capacityKwh = MeterProfile.capacity(fourDial),
        )
        assertTrue(unresolved is MeterDelta.Undecidable)
        assertEquals(MeterDelta.Reason.WENT_BACKWARDS, (unresolved as MeterDelta.Undecidable).reason)
        // The screen shows these; the first one is the answer in this case.
        assertEquals(219, unresolved.candidates.first().kwh)
        assertEquals(1, unresolved.candidates.first().wraps)

        val resolved = MeterDelta.consumption(
            previousKwh = 9_803,
            currentKwh = 22,
            elapsedDays = 12.0,
            capacityKwh = MeterProfile.capacity(fourDial),
            attestedWraps = 1,
        )
        assertEquals(MeterDelta.Measured(219, wraps = 1, multiplier = 1), resolved)
        assertTrue((resolved as MeterDelta.Measured).restsOnAttestation)
    }

    // --- The multiplier now reaches the reading path ---

    @Test
    fun aConfirmedReadingCarriesTheAttestationIntoHistory() {
        val repository = Repository()
        repository.hydrate(
            ReciboZenSessionState(
                meterType = fourDial,
                lastConfirmedReadingKwh = 9_803,
                readings = listOf(
                    HistoryReadingEntry(9_803, "antes", ReadingMethod.MANUAL, "", nowMillis() - TWELVE_DAYS),
                ),
            ),
        )

        repository.confirmReading(22, ReadingMethod.MANUAL, wrapsSincePrevious = 1)

        val stored = repository.session.value.readings.first()
        assertEquals(22, stored.kwh)
        assertEquals(1, stored.wrapsSincePrevious)
        // The note used to be built from a raw subtraction and read
        // "lectura menor a la anterior" for exactly this reading.
        assertTrue(stored.note, stored.note.contains("219"))
        assertTrue(stored.note, stored.note.contains("vuelta"))
    }

    @Test
    fun aReadingConfirmedWithoutAnAttestationStoresSilenceRatherThanZero() {
        val repository = Repository()
        repository.hydrate(
            ReciboZenSessionState(
                meterType = fourDial,
                lastConfirmedReadingKwh = 9_000,
                readings = listOf(
                    HistoryReadingEntry(9_000, "antes", ReadingMethod.MANUAL, "", nowMillis() - TWELVE_DAYS),
                ),
            ),
        )

        repository.confirmReading(9_100, ReadingMethod.MANUAL)

        // Null, not 0. Zero is the household saying "it did not wrap"; null is
        // nobody having been asked, and a later screen must be able to tell them
        // apart before deciding whether to ask.
        assertNull(repository.session.value.readings.first().wrapsSincePrevious)
    }

    @Test
    fun theMultiplierAppliesToReadingsTheSameWayBillImportAlreadyAppliedIt() {
        val repository = Repository()
        repository.hydrate(ReciboZenSessionState(meterType = fourDial, lastConfirmedReadingKwh = 1_000))
        repository.setMeterMultiplier(40)

        val delta = MeterDelta.consumption(
            previousKwh = 1_000,
            currentKwh = 1_050,
            elapsedDays = 10.0,
            capacityKwh = MeterProfile.capacity(fourDial),
            multiplier = repository.session.value.meterMultiplier,
        )

        // 50 register units at ×40 is 2,000 billed kWh. Reading it as 50 is what
        // made the reading pace and the imported bill disagree.
        assertEquals(MeterDelta.Measured(2_000, wraps = 0, multiplier = 40), delta)
    }

    // --- Changing the meter drops testimony about the old one ---

    @Test
    fun pickingADifferentMeterTypeClearsADigitCountDeclaredForThePreviousOne() {
        val repository = Repository()
        repository.hydrate(ReciboZenSessionState(meterType = digital))
        repository.setMeterDigitCount(7)
        assertEquals(7, repository.session.value.meterDigitCount)

        repository.setMeterType(fourDial)

        assertNull(repository.session.value.meterDigitCount)
        // The label still answers, so the app is not left worse off.
        assertEquals(4, MeterProfile.digitCount(repository.session.value.meterType, null))
    }

    @Test
    fun anImpossibleDeclarationIsNotStored() {
        val repository = Repository()
        repository.hydrate(ReciboZenSessionState(meterType = digital))

        repository.setMeterDigitCount(2)

        assertNull(repository.session.value.meterDigitCount)
    }

    // --- Elapsed time ---

    @Test
    fun theIntervalSkipsNewerReadingsThatCarryNoTimestamp() {
        // The confirm screen read `readings.first()` and gave up when that entry
        // had no clock, even with perfectly good timestamps further down. Then it
        // substituted 76/24 days, a leftover from the demo fixture.
        val now = nowMillis()
        val readings = listOf(
            HistoryReadingEntry(9_000, "sin hora", ReadingMethod.MANUAL, "", null),
            HistoryReadingEntry(8_900, "con hora", ReadingMethod.MANUAL, "", now - TWELVE_DAYS),
        )

        val days = ReadingInterval.daysSince(readings, now)

        assertNotNull(days)
        assertEquals(12.0, days!!, 0.001)
    }

    @Test
    fun withNoTimestampAnywhereTheIntervalIsUnknownRatherThanInvented() {
        val readings = listOf(HistoryReadingEntry(9_000, "sin hora", ReadingMethod.MANUAL, "", null))

        assertNull(ReadingInterval.daysSince(readings, nowMillis()))
    }

    @Test
    fun wrapsAcrossASpanAreSummedBecauseTheEndpointsAreOftenNotAdjacent() {
        val now = nowMillis()
        val baseline = HistoryReadingEntry(9_000, "base", ReadingMethod.MANUAL, "", now - 3 * TWELVE_DAYS)
        val middle = HistoryReadingEntry(200, "media", ReadingMethod.MANUAL, "", now - 2 * TWELVE_DAYS, wrapsSincePrevious = 1)
        val newest = HistoryReadingEntry(400, "nueva", ReadingMethod.MANUAL, "", now - TWELVE_DAYS, wrapsSincePrevious = 1)
        val readings = listOf(newest, middle, baseline)

        // Two wraps happened between baseline and newest. Reading only the
        // newest entry's attestation would report one and understate the period
        // by a whole capacity.
        assertEquals(2, ReadingInterval.attestedWrapsBetween(readings, baseline, newest))
    }

    @Test
    fun aSpanWhereNobodyWasAskedReportsSilenceInsteadOfZeroWraps() {
        val now = nowMillis()
        val baseline = HistoryReadingEntry(9_000, "base", ReadingMethod.MANUAL, "", now - 2 * TWELVE_DAYS)
        val newest = HistoryReadingEntry(9_400, "nueva", ReadingMethod.MANUAL, "", now - TWELVE_DAYS)

        // Null keeps MeterDelta free to ask; zero would assert that the register
        // definitely did not wrap, which nobody established.
        assertNull(ReadingInterval.attestedWrapsBetween(listOf(newest, baseline), baseline, newest))
    }

    private fun nowMillis(): Long = 1_700_000_000_000L

    private companion object {
        const val TWELVE_DAYS = 12L * 86_400_000L
    }
}
