package com.recibozen.app

import com.recibozen.app.demo.repository.EnergySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Guards the mid-period reading bug: appliance consumption is expressed per
 * full billing period, so comparing it raw against a 3-day reading made the
 * appliances "explain" 100% and left unidentified consumption at zero.
 */
class ObservedWindowFractionTest {

    private fun summary(observedKwh: Int, observedDays: Int, billingDays: Int) = EnergySummary(
        observedKwh = observedKwh,
        observedDays = observedDays,
        billingDays = billingDays,
        daysRemaining = (billingDays - observedDays).coerceAtLeast(0),
        dailyAverageKwh = if (observedDays > 0) observedKwh.toDouble() / observedDays else 0.0,
        projectedKwh = if (observedDays > 0) (observedKwh.toDouble() / observedDays * billingDays).roundToInt() else 0,
        costToDateMxn = 0,
        projectedCostMxn = 0,
        hasUsageEvidence = true,
    )

    @Test
    fun closedBillCoversTheWholeWindow() {
        val energy = summary(observedKwh = 382, observedDays = 61, billingDays = 61)
        assertEquals(1.0, energy.observedWindowFraction, 0.0001)
    }

    @Test
    fun midPeriodReadingCoversOnlyItsOwnDays() {
        val energy = summary(observedKwh = 20, observedDays = 3, billingDays = 61)
        assertEquals(3.0 / 61.0, energy.observedWindowFraction, 0.0001)
    }

    @Test
    fun proratedAppliancesLeaveUnidentifiedConsumptionVisible() {
        // 3 days measured, meter saw 20 kWh. Declared appliances use 300 kWh
        // across the whole bimonthly period.
        val energy = summary(observedKwh = 20, observedDays = 3, billingDays = 61)
        val periodApplianceKwh = 300

        val expectedInWindow = (periodApplianceKwh * energy.observedWindowFraction).roundToInt()
        val explained = expectedInWindow.coerceAtMost(energy.observedKwh)
        val unidentified = (energy.observedKwh - explained).coerceAtLeast(0)

        // ~15 kWh expected for 3 days, not the full 300 that used to saturate it.
        assertEquals(15, expectedInWindow)
        assertEquals(15, explained)
        // The whole point: unidentified consumption is no longer forced to zero.
        assertTrue("unidentified should stay visible", unidentified > 0)
        assertEquals(5, unidentified)
    }

    @Test
    fun rawComparisonWouldHaveHiddenUnidentifiedConsumption() {
        // Documents the old behaviour so the regression is unmistakable.
        val energy = summary(observedKwh = 20, observedDays = 3, billingDays = 61)
        val naiveExplained = 300.coerceAtMost(energy.observedKwh)
        assertEquals(0, (energy.observedKwh - naiveExplained).coerceAtLeast(0))
    }

    @Test
    fun guardsAgainstMissingBillingDays() {
        val energy = summary(observedKwh = 100, observedDays = 10, billingDays = 0)
        assertEquals(1.0, energy.observedWindowFraction, 0.0001)
    }
}
