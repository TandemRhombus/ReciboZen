package app.recibozen.android

import app.recibozen.android.core.calculation.AccumulatedConsumption
import app.recibozen.android.core.calculation.MeterRegister
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The "Análisis del periodo" curve.
 *
 * It was built from `reading.kwh - base` and clamped with `coerceAtLeast(0)`,
 * which is the most dangerous shape a defect can take here: a wrapped register
 * did not draw a broken line, it drew a flat one, and a flat line is a perfectly
 * believable thing for a consumption chart to show.
 */
class AccumulatedConsumptionTest {

    private val zone: ZoneId = ZoneId.of("America/Mexico_City")
    private val periodStart = LocalDate.of(2026, 8, 1)

    private fun reading(dayOffset: Long, kwh: Int, wraps: Int? = null) = HistoryReadingEntry(
        kwh = kwh,
        dateLabel = "dia $dayOffset",
        method = ReadingMethod.MANUAL,
        note = "",
        timestampEpochMillis = periodStart.plusDays(dayOffset)
            .atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli(),
        wrapsSincePrevious = wraps,
    )

    private fun byDay(
        readings: List<HistoryReadingEntry>,
        register: MeterRegister,
        observedDays: Int,
        observedKwh: Int,
    ) = AccumulatedConsumption.byDay(
        readings = readings,
        register = register,
        periodStartEpochDay = periodStart.toEpochDay(),
        observedDays = observedDays,
        observedKwh = observedKwh,
        zoneId = zone,
    )

    private val fourDial = MeterRegister(capacityKwh = 10_000, multiplier = 1)

    @Test
    fun anOrdinaryPeriodAccumulatesFromTheOpeningReading() {
        val curve = byDay(
            readings = listOf(reading(0, 1_000), reading(10, 1_100)),
            register = fourDial,
            observedDays = 10,
            observedKwh = 100,
        )

        assertEquals(11, curve.size)
        assertEquals(0, curve.first())
        assertEquals(100, curve.last())
        // The gap between two readings is drawn as a straight line.
        assertEquals(50, curve[5])
    }

    @Test
    fun aWrapMidPeriodNoLongerFlattensTheCurveToZero() {
        // 9,950 on day 0, wraps, 0,150 on day 10. That is 200 kWh. The old
        // subtraction produced -9,800 and the clamp drew a flat zero line for
        // the rest of the period -- a believable chart of a household that
        // consumed nothing for ten days.
        val curve = byDay(
            readings = listOf(reading(0, 9_950), reading(10, 150, wraps = 1)),
            register = fourDial,
            observedDays = 10,
            observedKwh = 200,
        )

        assertEquals(200, curve.last())
        assertEquals(100, curve[5])
        assertTrue("la curva no debe quedarse plana", curve[9] > curve[1])
    }

    @Test
    fun anUnattestedWrapFallsBackToTheStraightLineInsteadOfADrawnZero() {
        // Nobody attested, so the day-by-day shape is unknown. Drawing a
        // straight line to the period total claims a total and nothing more,
        // which is what the app can actually defend. A flat zero would have
        // claimed a history.
        val curve = byDay(
            readings = listOf(reading(0, 9_950), reading(10, 150)),
            register = fourDial,
            observedDays = 10,
            observedKwh = 200,
        )

        assertEquals(200, curve.last())
        assertEquals(100, curve[5])
    }

    @Test
    fun theMultiplierPutsTheCurveInTheSameUnitAsTheFallback() {
        // Both branches of this function have to speak billed kWh. The old
        // subtraction returned raw register units, so a CT service got a curve
        // and a total that differed by the multiplier.
        val withCt = MeterRegister(capacityKwh = 100_000, multiplier = 20)

        val curve = byDay(
            readings = listOf(reading(0, 1_000), reading(10, 1_010)),
            register = withCt,
            observedDays = 10,
            observedKwh = 200,
        )

        // 10 register units x 20 = 200 billed kWh, matching observedKwh.
        assertEquals(200, curve.last())
    }

    @Test
    fun readingsBeforeThePeriodStartedAreNotPartOfIt() {
        val curve = byDay(
            readings = listOf(reading(-5, 900), reading(0, 1_000), reading(10, 1_100)),
            register = fourDial,
            observedDays = 10,
            observedKwh = 100,
        )

        assertEquals(0, curve.first())
        assertEquals(100, curve.last())
    }

    @Test
    fun aPeriodWithNoElapsedDaysDrawsNothing() {
        assertTrue(byDay(listOf(reading(0, 1_000)), fourDial, observedDays = 0, observedKwh = 0).isEmpty())
    }
}
