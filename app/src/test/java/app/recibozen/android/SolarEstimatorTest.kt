package app.recibozen.android

import app.recibozen.android.core.calculation.MeterRegister
import app.recibozen.android.core.calculation.SolarEstimator
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

class SolarEstimatorTest {
    @Test
    fun sizesPanelsFromDatedBillsAndLocationSpecificSolarResource() {
        val start = LocalDate.of(2025, 1, 1)
        val history = (0 until 12).map { index ->
            val from = start.plusMonths(index.toLong())
            val to = from.plusMonths(1)
            BillPeriod(
                monthLabel = "",
                amountMxn = 0,
                consumptionKwh = 300,
                periodStartEpochDay = from.toEpochDay(),
                periodEndEpochDay = to.toEpochDay(),
            )
        }

        val estimate = SolarEstimator.estimate(history, List(12) { 5.0 })

        assertNotNull(estimate)
        estimate!!
        assertEquals(3_600, estimate.annualConsumptionKwh)
        assertEquals(365, estimate.coveredDays)
        assertEquals(1_464, estimate.annualYieldKwhPerKwp)
        assertEquals("4", estimate.scenarios[1].panels)
        assertEquals(12, estimate.monthlyEnergy.size)
    }

    @Test
    fun usesHistoricalPeakThenAddsGrowthAndPlannedLoads() {
        val start = LocalDate.of(2024, 1, 1)
        val history = (0 until 24).map { index ->
            val from = start.plusMonths(index.toLong())
            BillPeriod(
                monthLabel = "",
                amountMxn = 0,
                consumptionKwh = if (index < 12) 400 else 300,
                periodStartEpochDay = from.toEpochDay(),
                periodEndEpochDay = from.plusMonths(1).toEpochDay(),
            )
        }

        val estimate = SolarEstimator.estimate(
            history = history,
            monthlyDailyIrradiance = List(12) { 5.0 },
            demandGrowthPercent = 20,
            plannedAnnualKwh = 2_500,
        )!!

        assertEquals(3_600, estimate.rollingAnnualConsumptionKwh)
        assertTrue(estimate.historicalPeakAnnualConsumptionKwh > estimate.rollingAnnualConsumptionKwh)
        assertEquals(
            (estimate.historicalPeakAnnualConsumptionKwh * 1.20).roundToInt() + 2_500,
            estimate.annualConsumptionKwh,
        )
        assertEquals(20, estimate.designGrowthPercent)
        assertEquals(2_500, estimate.plannedAnnualKwh)
    }

    @Test
    fun extendsBillEvidenceWithNonOverlappingManualReadings() {
        val billStart = LocalDate.of(2025, 1, 1)
        val billEnd = LocalDate.of(2025, 2, 1)
        val readingEnd = billEnd.plusDays(30)
        val history = listOf(
            BillPeriod(
                monthLabel = "",
                amountMxn = 0,
                consumptionKwh = 100,
                periodStartEpochDay = billStart.toEpochDay(),
                periodEndEpochDay = billEnd.toEpochDay(),
            ),
        )
        val readings = listOf(
            HistoryReadingEntry(1_000, "", ReadingMethod.PDF, "", billEnd.toEpochMillis()),
            HistoryReadingEntry(1_300, "", ReadingMethod.PDF, "", readingEnd.toEpochMillis()),
        )

        val estimate = SolarEstimator.estimate(history, List(12) { 5.0 }, readings = readings)!!

        assertEquals(61, estimate.coveredDays)
        assertEquals(30, estimate.manualEvidenceDays)
        assertEquals(61, estimate.historySpanDays)
        assertTrue(estimate.rollingAnnualConsumptionKwh > 2_300)
    }

    private fun LocalDate.toEpochMillis(): Long = toEpochDay() * 86_400_000L

    // --- Reading periods across a register wrap ---

    @Test
    fun aReadingPeriodAcrossAWrapIsUsedInsteadOfBeingSilentlyDropped() {
        // 9,900 -> 0,150 on a four-dial meter over 30 days is 250 kWh. The old
        // `consumed <= 0` filter dropped it, and the array was then sized on
        // whatever evidence happened to survive -- always downwards.
        val start = LocalDate.of(2025, 3, 1)
        val end = start.plusDays(30)
        val readings = listOf(
            HistoryReadingEntry(9_900, "", ReadingMethod.MANUAL, "", start.toEpochMillis()),
            HistoryReadingEntry(150, "", ReadingMethod.MANUAL, "", end.toEpochMillis(), wrapsSincePrevious = 1),
        )

        val estimate = SolarEstimator.estimate(
            history = emptyList(),
            monthlyDailyIrradiance = List(12) { 5.0 },
            readings = readings,
            register = MeterRegister(capacityKwh = 10_000, multiplier = 1),
        )

        assertNotNull("el periodo no debe descartarse", estimate)
        assertEquals(30, estimate!!.manualEvidenceDays)
        // 250 kWh in 30 days annualised: 250 * 365 / 30.
        assertEquals(3_042, estimate.rollingAnnualConsumptionKwh)
    }

    @Test
    fun aWrapNobodyAttestedLeavesTheSolarStudyWithoutThatPeriod() {
        // Sizing an array on a guessed wrap would be worse than sizing it on
        // less evidence, so the period is still dropped -- but now because the
        // consumption is undetermined, not because a subtraction came out negative.
        val start = LocalDate.of(2025, 3, 1)
        val end = start.plusDays(30)
        val readings = listOf(
            HistoryReadingEntry(9_900, "", ReadingMethod.MANUAL, "", start.toEpochMillis()),
            HistoryReadingEntry(150, "", ReadingMethod.MANUAL, "", end.toEpochMillis()),
        )

        val estimate = SolarEstimator.estimate(
            history = emptyList(),
            monthlyDailyIrradiance = List(12) { 5.0 },
            readings = readings,
            register = MeterRegister(capacityKwh = 10_000, multiplier = 1),
        )

        assertNull(estimate)
    }
}
