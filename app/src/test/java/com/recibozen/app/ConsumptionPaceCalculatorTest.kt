package com.recibozen.app

import com.recibozen.app.core.calculation.ConsumptionPaceCalculator
import com.recibozen.app.core.calculation.ConsumptionPaceRelation
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumptionPaceCalculatorTest {
    @Test
    fun `recommended pace uses remaining allowance and remaining days`() {
        val result = ConsumptionPaceCalculator.goal(
            goalMxn = 200,
            observedKwh = 20,
            daysRemaining = 10,
            estimateCostMxn = { it * 2 },
        )

        assertEquals(100, result.goalKwh)
        assertEquals(80, result.remainingKwh)
        assertEquals(8.0, result.recommendedDailyKwh, 0.001)
    }

    @Test
    fun `pace below recommendation is never described as above`() {
        assertEquals(
            ConsumptionPaceRelation.BELOW_RECOMMENDED,
            ConsumptionPaceCalculator.relation(nowKwh = 5.0, recommendedKwh = 8.0),
        )
    }

    @Test
    fun `pace above recommendation is identified`() {
        assertEquals(
            ConsumptionPaceRelation.ABOVE_RECOMMENDED,
            ConsumptionPaceCalculator.relation(nowKwh = 9.0, recommendedKwh = 8.0),
        )
    }

    @Test
    fun `values that render equally are on recommended pace`() {
        assertEquals(
            ConsumptionPaceRelation.ON_RECOMMENDED,
            ConsumptionPaceCalculator.relation(nowKwh = 8.01, recommendedKwh = 8.04),
        )
    }
}
