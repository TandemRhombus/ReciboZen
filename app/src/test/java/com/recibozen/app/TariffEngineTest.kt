package com.recibozen.app

import com.recibozen.app.core.calculation.TariffBlock
import com.recibozen.app.core.calculation.TariffEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class TariffEngineTest {
    private val engine = TariffEngine(
        blocks = listOf(
            TariffBlock(100, 1.0),
            TariffBlock(200, 2.0),
            TariffBlock(null, 4.0),
        ),
        fixedChargeMxn = 0.0,
        taxRate = 0.0,
    )

    @Test
    fun appliesProgressiveBlocks() {
        assertEquals(0, engine.estimate(0).totalMxn)
        assertEquals(50, engine.estimate(50).totalMxn)
        assertEquals(200, engine.estimate(150).totalMxn)
        assertEquals(500, engine.estimate(250).totalMxn)
    }

    @Test
    fun negativeConsumptionIsClamped() {
        assertEquals(0, engine.estimate(-12).consumptionKwh)
    }
}
