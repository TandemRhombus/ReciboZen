package com.recibozen.app

import com.recibozen.app.core.calculation.SolarEstimator
import com.recibozen.app.core.model.BillPeriod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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
}
