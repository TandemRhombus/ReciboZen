package app.recibozen.android

import app.recibozen.android.core.calculation.MeterDelta
import app.recibozen.android.core.calculation.MeterDelta.Companion.capacityFor
import app.recibozen.android.core.calculation.MeterDelta.Companion.consumption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here comes from a defect that was verified in the shipped code or
 * found by adversarially reviewing the first design. The theme is constant: the
 * function may report consumption, or report that it cannot tell, and nothing
 * in between.
 */
class MeterDeltaTest {

    // --- Ordinary intervals ---

    @Test
    fun aForwardReadingIsSimplySubtracted() {
        val result = consumption(previousKwh = 1_000, currentKwh = 1_120, elapsedDays = 10.0)

        assertEquals(MeterDelta.Measured(120, wraps = 0, multiplier = 1), result)
    }

    @Test
    fun anUnchangedReadingIsZeroConsumptionNotUnknownConsumption() {
        val result = consumption(previousKwh = 500, currentKwh = 500, elapsedDays = 3.0)

        // Zero is a real measurement -- an empty house consumes zero. Conflating
        // it with "cannot tell" would hide a working result.
        assertEquals(MeterDelta.Measured(0, wraps = 0, multiplier = 1), result)
    }

    // --- The CFE multiplier, which the reading paths ignored entirely ---

    @Test
    fun theMultiplierIsAppliedToTheRegisterDeltaTheWayBillImportDoes() {
        val result = consumption(previousKwh = 100, currentKwh = 150, elapsedDays = 5.0, multiplier = 10)

        assertEquals(MeterDelta.Measured(500, wraps = 0, multiplier = 10), result)
    }

    @Test
    fun aMissingOrAbsurdMultiplierFallsBackToOneRatherThanToZero() {
        // A zero multiplier would silently erase every reading-derived figure.
        assertEquals(
            MeterDelta.Measured(50, wraps = 0, multiplier = 1),
            consumption(previousKwh = 100, currentKwh = 150, elapsedDays = 5.0, multiplier = 0),
        )
    }

    // --- The defect the owner reported: a four-dial meter wrapping past 9999 ---

    @Test
    fun aWrapIsNeverAppliedOnItsOwnAuthority() {
        val result = consumption(
            previousKwh = 9_950,
            currentKwh = 30,
            elapsedDays = 8.0,
            capacityKwh = 10_000,
        )

        assertTrue(result is MeterDelta.Undecidable)
        assertEquals(MeterDelta.Reason.WENT_BACKWARDS, (result as MeterDelta.Undecidable).reason)
    }

    @Test
    fun anAttestedWrapProducesTheConsumptionTheMeterActuallyRecorded() {
        val result = consumption(
            previousKwh = 9_950,
            currentKwh = 30,
            elapsedDays = 8.0,
            capacityKwh = 10_000,
            attestedWraps = 1,
        )

        // 10000 - 9950 + 30, not the -9920 the old subtraction produced.
        assertEquals(MeterDelta.Measured(80, wraps = 1, multiplier = 1), result)
        assertTrue((result as MeterDelta.Measured).restsOnAttestation)
    }

    @Test
    fun theWrapArithmeticIsNotSpecificToFourDials() {
        val fiveDial = consumption(
            previousKwh = 99_950,
            currentKwh = 30,
            elapsedDays = 8.0,
            capacityKwh = 100_000,
            attestedWraps = 1,
        )

        assertEquals(MeterDelta.Measured(80, wraps = 1, multiplier = 1), fiveDial)
    }

    @Test
    fun theBackwardsCaseOffersWhatEachInterpretationWouldImply() {
        val result = consumption(
            previousKwh = 9_950,
            currentKwh = 30,
            elapsedDays = 8.0,
            capacityKwh = 10_000,
        ) as MeterDelta.Undecidable

        assertTrue(result.candidates.isNotEmpty())
        assertEquals(80, result.candidates.first().kwh)
        assertEquals(1, result.candidates.first().wraps)
        assertEquals(10.0, result.candidates.first().dailyKwh, 0.01)
        // Never a candidate that is itself impossible.
        assertTrue(result.candidates.all { it.kwh >= 0 })
    }

    @Test
    fun moreThanOneWrapIsRepresentableInsteadOfBeingHardcodedToOne() {
        val result = consumption(
            previousKwh = 9_950,
            currentKwh = 950,
            elapsedDays = 400.0,
            capacityKwh = 10_000,
            attestedWraps = 2,
        )

        // 2 * 10000 - 9950 + 950. A single-wrap assumption would understate this
        // by exactly one capacity.
        assertEquals(MeterDelta.Measured(11_000, wraps = 2, multiplier = 1), result)
    }

    // --- Without a digit count there is no honest rollover arithmetic ---

    @Test
    fun aBackwardsReadingWithoutAKnownDigitCountIsNotGuessedAt() {
        val result = consumption(previousKwh = 9_950, currentKwh = 30, elapsedDays = 8.0, capacityKwh = null)

        assertEquals(
            MeterDelta.Undecidable(MeterDelta.Reason.CAPACITY_UNKNOWN),
            result,
        )
    }

    // --- A reading that contradicts the attested meter ---

    @Test
    fun aPreviousReadingAboveCapacityNeverProducesANegativeMeasurement() {
        // Reachable when the digit count is corrected downwards, or when readings
        // from a different service leak in. The first design returned
        // Measured(-88550) here -- a negative inside the type whose entire
        // purpose is to make negatives unrepresentable.
        val result = consumption(
            previousKwh = 98_700,
            currentKwh = 150,
            elapsedDays = 20.0,
            capacityKwh = 10_000,
            attestedWraps = 1,
        )

        assertEquals(MeterDelta.Undecidable(MeterDelta.Reason.READING_EXCEEDS_CAPACITY), result)
    }

    @Test
    fun aCurrentReadingAboveCapacityIsAlsoRefusedRatherThanTruncated() {
        val result = consumption(
            previousKwh = 100,
            currentKwh = 12_345,
            elapsedDays = 20.0,
            capacityKwh = 10_000,
        )

        assertEquals(MeterDelta.Undecidable(MeterDelta.Reason.READING_EXCEEDS_CAPACITY), result)
    }

    @Test
    fun noInputEverYieldsANegativeMeasurement() {
        val cases = listOf(
            Triple(9_950, 30, 1),
            Triple(9_999, 0, 1),
            Triple(0, 0, 1),
            Triple(5_000, 4_999, 3),
        )
        cases.forEach { (previous, current, wraps) ->
            val result = consumption(
                previousKwh = previous,
                currentKwh = current,
                elapsedDays = 30.0,
                capacityKwh = 10_000,
                attestedWraps = wraps,
            )
            if (result is MeterDelta.Measured) {
                assertTrue("negative for $previous -> $current", result.kwh >= 0)
            }
        }
    }

    // --- The invisible wrap: it lands ABOVE the previous reading ---

    @Test
    fun aWrapThatLandsAboveThePreviousReadingIsFlaggedInsteadOfLookingOrdinary() {
        // 200 -> 300 over 400 days at 25 kWh/day. Naive subtraction says 100 kWh
        // and warns about nothing; the register actually travelled 10,100.
        val result = consumption(
            previousKwh = 200,
            currentKwh = 300,
            elapsedDays = 400.0,
            capacityKwh = 10_000,
            plausibleDailyKwh = 25.0,
        )

        assertTrue(result is MeterDelta.Undecidable)
        assertEquals(MeterDelta.Reason.WRAP_POSSIBLE, (result as MeterDelta.Undecidable).reason)
        assertEquals(listOf(100, 10_100, 20_100, 30_100), result.candidates.map { it.kwh })
    }

    @Test
    fun anIntervalTooShortToReachCapacityIsNotFlagged() {
        // The same readings four days apart. Raising a wrap question here would
        // be noise, and noise is what makes people ignore real warnings.
        val result = consumption(
            previousKwh = 200,
            currentKwh = 300,
            elapsedDays = 4.0,
            capacityKwh = 10_000,
            plausibleDailyKwh = 25.0,
        )

        assertEquals(MeterDelta.Measured(100, wraps = 0, multiplier = 1), result)
    }

    @Test
    fun withoutAnEstablishedPaceTheWrapQuestionIsNotAskedAtAll() {
        // Asking would require inventing a pace for a household we know nothing
        // about, which is exactly what this function refuses to do.
        val result = consumption(
            previousKwh = 200,
            currentKwh = 300,
            elapsedDays = 400.0,
            capacityKwh = 10_000,
            plausibleDailyKwh = null,
        )

        assertEquals(MeterDelta.Measured(100, wraps = 0, multiplier = 1), result)
    }

    // --- Implausible rates, in the direction nobody was checking ---

    @Test
    fun anAbsurdlyHighRateIsRefusedRatherThanPropagated() {
        val result = consumption(
            previousKwh = 100,
            currentKwh = 5_000,
            elapsedDays = 2.0,
            plausibleDailyKwh = 10.0,
        )

        assertEquals(MeterDelta.Undecidable(MeterDelta.Reason.RATE_IMPLAUSIBLE), result)
    }

    @Test
    fun aBusyButBelievableIntervalIsNotRefused() {
        val result = consumption(
            previousKwh = 100,
            currentKwh = 200,
            elapsedDays = 4.0,
            plausibleDailyKwh = 10.0,
        )

        // 25 kWh/day against a 10 kWh/day pace: high, but people do buy air
        // conditioners. The threshold has to leave room for real life.
        assertEquals(MeterDelta.Measured(100, wraps = 0, multiplier = 1), result)
    }

    @Test
    fun tworeadingsMinutesApartAreNotJudgedByTheirRate() {
        val result = consumption(
            previousKwh = 100,
            currentKwh = 103,
            elapsedDays = 0.02,
            plausibleDailyKwh = 10.0,
        )

        // 150 kWh/day extrapolated from 30 minutes means nothing.
        assertEquals(MeterDelta.Measured(3, wraps = 0, multiplier = 1), result)
    }

    // --- Capacity from the digit count ---

    @Test
    fun capacityComesFromTheDigitCountAndIsNeverDefaulted() {
        assertEquals(10_000, capacityFor(4))
        assertEquals(100_000, capacityFor(5))
        assertEquals(1_000_000, capacityFor(6))
        assertNull(capacityFor(null))
        assertNull(capacityFor(0))
        assertNull(capacityFor(99))
    }
}
