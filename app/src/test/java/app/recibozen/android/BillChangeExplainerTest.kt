package app.recibozen.android

import app.recibozen.android.core.calculation.BillChangeExplainer
import app.recibozen.android.core.calculation.BillChangeFactor
import app.recibozen.android.core.calculation.TariffBlock
import app.recibozen.android.core.calculation.TariffEngine
import app.recibozen.android.core.model.BillPeriod
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Consumí lo mismo y me subió" is the most common complaint about a CFE bill,
 * and it is usually true: the period was longer, or the same habits crossed into
 * a costlier block. These tests pin the arithmetic that tells them apart.
 */
class BillChangeExplainerTest {
    private val engine = TariffEngine(
        blocks = listOf(
            TariffBlock(limitKwh = 150, rateMxnPerKwh = 1.00),
            TariffBlock(limitKwh = 430, rateMxnPerKwh = 2.00),
            TariffBlock(limitKwh = null, rateMxnPerKwh = 6.00),
        ),
        fixedChargeMxn = 50.0,
        taxRate = 0.16,
    )

    @Test
    fun aLongerPeriodIsNamedAsTheCauseInsteadOfBlamingTheUser() {
        val previous = bill("Ene-Feb", kwh = 300, days = 60, amountMxn = engine.estimate(300).totalMxn)
        // Same 5 kWh/day, but the period ran ten days longer.
        val current = bill("Mar-Abr", kwh = 350, days = 70, amountMxn = engine.estimate(350).totalMxn)

        val explanation = BillChangeExplainer.explain(previous, current, engine)!!

        assertTrue("la explicación principal debe ser la duración", explanation.isMostlyPeriodLength)
        val usage = explanation.components.firstOrNull { it.factor == BillChangeFactor.DAILY_USAGE }
        assertNull("el ritmo diario no cambió, no debe aparecer como causa", usage)
    }

    @Test
    fun crossingIntoTheExpensiveBlockIsItsOwnLine() {
        // Same 60 days. 420 kWh sits inside the 2.00 block; 480 pushes 50 kWh
        // into the 6.00 one. Charging all 60 extra kWh at 2.00 would understate
        // the bill by exactly the step.
        val previous = bill("Ene-Feb", kwh = 420, days = 60, amountMxn = engine.estimate(420).totalMxn)
        val current = bill("Mar-Abr", kwh = 480, days = 60, amountMxn = engine.estimate(480).totalMxn)

        val explanation = BillChangeExplainer.explain(previous, current, engine)!!
        val usage = explanation.components.first { it.factor == BillChangeFactor.DAILY_USAGE }
        val step = explanation.components.first { it.factor == BillChangeFactor.TARIFF_STEP }

        // 60 kWh × 2.00 × 1.16 = 139.2
        assertEquals(139.0, usage.amountMxn.toDouble(), 1.0)
        // 50 of those kWh cost 6.00 instead of 2.00: 50 × 4.00 × 1.16 = 232
        assertEquals(232.0, step.amountMxn.toDouble(), 1.0)
        assertEquals(60.0, usage.magnitude, 0.001)
    }

    @Test
    fun everyPesoOfTheDifferenceIsAccountedFor() {
        val previous = bill("Ene-Feb", kwh = 380, days = 58, amountMxn = engine.estimate(380).totalMxn)
        val current = bill("Mar-Abr", kwh = 610, days = 63, amountMxn = engine.estimate(610).totalMxn)

        val explanation = BillChangeExplainer.explain(previous, current, engine)!!
        val addedUp = explanation.components.sumOf { it.amountMxn }

        assertEquals(
            "las causas deben sumar la diferencia real del recibo",
            explanation.deltaMxn.toDouble(),
            addedUp.toDouble(),
            2.0,
        )
    }

    @Test
    fun aRateChangeIsSeparatedFromConsumption() {
        val summerEngine = TariffEngine(
            blocks = listOf(
                TariffBlock(limitKwh = 150, rateMxnPerKwh = 1.00),
                TariffBlock(limitKwh = 430, rateMxnPerKwh = 2.00),
                TariffBlock(limitKwh = null, rateMxnPerKwh = 6.00),
            ),
            fixedChargeMxn = 50.0,
            taxRate = 0.16,
        )
        val winterEngine = TariffEngine(
            blocks = listOf(
                TariffBlock(limitKwh = 150, rateMxnPerKwh = 1.40),
                TariffBlock(limitKwh = 430, rateMxnPerKwh = 2.80),
                TariffBlock(limitKwh = null, rateMxnPerKwh = 6.00),
            ),
            fixedChargeMxn = 50.0,
            taxRate = 0.16,
        )
        // Identical consumption and identical period: only the rates moved.
        val previous = bill("Jul-Ago", kwh = 400, days = 61, amountMxn = summerEngine.estimate(400).totalMxn)
        val current = bill("Sep-Oct", kwh = 400, days = 61, amountMxn = winterEngine.estimate(400).totalMxn)

        val explanation = BillChangeExplainer.explain(previous, current, summerEngine, winterEngine)!!

        assertEquals(1, explanation.components.size)
        assertEquals(BillChangeFactor.TARIFF_RATES, explanation.components.first().factor)
    }

    @Test
    fun whatTheEngineCannotReproduceIsReportedAsUnexplained() {
        val previous = bill("Ene-Feb", kwh = 300, days = 60, amountMxn = engine.estimate(300).totalMxn)
        // The real bill carries 400 pesos the modelled tariff does not produce:
        // a DAC surcharge, an adjustment, a debt. Spreading it over the other
        // causes would put a number next to a wrong explanation.
        val current = bill("Mar-Abr", kwh = 300, days = 60, amountMxn = engine.estimate(300).totalMxn + 400)

        val explanation = BillChangeExplainer.explain(previous, current, engine)!!
        val unexplained = explanation.components.first { it.factor == BillChangeFactor.UNEXPLAINED }

        assertEquals(400, unexplained.amountMxn)
        assertEquals(1, explanation.components.size)
    }

    @Test
    fun aBillThatWentDownIsExplainedTheSameWay() {
        val previous = bill("Ene-Feb", kwh = 500, days = 60, amountMxn = engine.estimate(500).totalMxn)
        val current = bill("Mar-Abr", kwh = 380, days = 60, amountMxn = engine.estimate(380).totalMxn)

        val explanation = BillChangeExplainer.explain(previous, current, engine)!!

        assertTrue(explanation.deltaMxn < 0)
        assertTrue(explanation.components.all { it.amountMxn <= 0 })
        assertEquals(
            explanation.deltaMxn.toDouble(),
            explanation.components.sumOf { it.amountMxn }.toDouble(),
            2.0,
        )
    }

    @Test
    fun withoutPeriodDatesTheLengthIsNeverInvented() {
        val previous = BillPeriod("Ene-Feb", amountMxn = engine.estimate(300).totalMxn, consumptionKwh = 300)
        val current = BillPeriod("Mar-Abr", amountMxn = engine.estimate(360).totalMxn, consumptionKwh = 360)

        val explanation = BillChangeExplainer.explain(previous, current, engine)!!

        assertNull(explanation.previousDays)
        assertNull(explanation.components.firstOrNull { it.factor == BillChangeFactor.PERIOD_LENGTH })
        assertNotNull(explanation.components.firstOrNull { it.factor == BillChangeFactor.DAILY_USAGE })
    }

    @Test
    fun aProjectedOrEmptyBillIsNotComparable() {
        val real = bill("Ene-Feb", kwh = 300, days = 60, amountMxn = 900)
        val projected = bill("Mar-Abr", kwh = 320, days = 60, amountMxn = 950).copy(isProjected = true)
        val withoutReadings = bill("Mar-Abr", kwh = 0, days = 60, amountMxn = 950)

        assertNull(BillChangeExplainer.explain(real, projected, engine))
        assertNull(BillChangeExplainer.explain(real, withoutReadings, engine))
    }

    @Test
    fun anIdenticalBillHasNothingToExplain() {
        val previous = bill("Ene-Feb", kwh = 300, days = 60, amountMxn = 900)
        val current = bill("Mar-Abr", kwh = 300, days = 60, amountMxn = 900)

        assertNull(BillChangeExplainer.explain(previous, current, engine))
    }

    @Test
    fun theStepIsZeroWhenNothingCrossedABlock() {
        // 200 → 260 kWh stays inside the 2.00 block the whole way.
        val previous = bill("Ene-Feb", kwh = 200, days = 60, amountMxn = engine.estimate(200).totalMxn)
        val current = bill("Mar-Abr", kwh = 260, days = 60, amountMxn = engine.estimate(260).totalMxn)

        val explanation = BillChangeExplainer.explain(previous, current, engine)!!
        val step = explanation.components.firstOrNull { it.factor == BillChangeFactor.TARIFF_STEP }

        assertTrue("no cruzó ningún escalón", step == null || abs(step.amountMxn) < 5)
    }

    private fun bill(label: String, kwh: Int, days: Int, amountMxn: Int) = BillPeriod(
        monthLabel = label,
        amountMxn = amountMxn,
        consumptionKwh = kwh,
        periodStartEpochDay = 20_000L,
        periodEndEpochDay = 20_000L + days,
    )
}
