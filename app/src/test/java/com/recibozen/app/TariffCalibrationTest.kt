package com.recibozen.app

import com.recibozen.app.core.calculation.TariffCalibration
import com.recibozen.app.core.calculation.TariffCalibration.CalibrationCharge
import com.recibozen.app.core.calculation.TariffEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TariffCalibrationTest {

    private fun calibrate(charges: List<CalibrationCharge>) =
        TariffCalibration.fromCharges(
            tariffCode = "1C",
            effectivePeriod = "2026-06/2026-08",
            charges = charges,
        )

    @Test
    fun usesRealRatesAndDerivesThresholdsFromExhaustedTiers() {
        val result = calibrate(
            listOf(
                CalibrationCharge("BASICO", "SUMMER", quantityKwh = 150.0, unitRateMxn = 1.121),
                CalibrationCharge("INTERMEDIO", "SUMMER", quantityKwh = 130.0, unitRateMxn = 1.406),
                CalibrationCharge("EXCEDENTE", "SUMMER", quantityKwh = 102.0, unitRateMxn = 4.115),
            ),
        )

        assertEquals(TariffCalibration.Coverage.FULL, result.coverage)
        val blocks = result.snapshot.blocks
        assertEquals(3, blocks.size)
        // Rates come verbatim from the bill, not from the generic defaults.
        assertEquals(1.121, blocks[0].rateMxnPerKwh, 0.0001)
        assertEquals(4.115, blocks[2].rateMxnPerKwh, 0.0001)
        // A tier with a successor was exhausted, so its running total is its real ceiling.
        assertEquals(150, blocks[0].limitKwh)
        assertEquals(280, blocks[1].limitKwh)
        // The excedente tier is open-ended.
        assertNull(blocks[2].limitKwh)
    }

    @Test
    fun keepsFallbackCeilingForTierTheUserNeverCrossed() {
        // Low consumption: only the basic tier was billed, so its true ceiling
        // is still unknown and must not be invented from the billed quantity.
        val result = calibrate(
            listOf(CalibrationCharge("BASICO", "NONE", quantityKwh = 90.0, unitRateMxn = 1.121)),
        )

        assertEquals(TariffCalibration.Coverage.PARTIAL, result.coverage)
        val blocks = result.snapshot.blocks
        assertEquals(1.121, blocks[0].rateMxnPerKwh, 0.0001)
        assertNotEquals(90, blocks[0].limitKwh)
        assertEquals(TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS[0].limitKwh, blocks[0].limitKwh)
        // Upper tiers stay on the generic fallback rather than disappearing.
        assertEquals(3, blocks.size)
        assertEquals(
            TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS[2].rateMxnPerKwh,
            blocks[2].rateMxnPerKwh,
            0.0001,
        )
    }

    @Test
    fun picksSeasonWithMostConsumptionOnSplitBimonthlyBill() {
        val result = calibrate(
            listOf(
                CalibrationCharge("BASICO", "OUT_OF_SUMMER", quantityKwh = 40.0, unitRateMxn = 1.000),
                CalibrationCharge("INTERMEDIO", "OUT_OF_SUMMER", quantityKwh = 20.0, unitRateMxn = 1.200),
                CalibrationCharge("BASICO", "SUMMER", quantityKwh = 150.0, unitRateMxn = 1.121),
                CalibrationCharge("INTERMEDIO", "SUMMER", quantityKwh = 130.0, unitRateMxn = 1.406),
                CalibrationCharge("EXCEDENTE", "SUMMER", quantityKwh = 60.0, unitRateMxn = 4.115),
            ),
        )

        // SUMMER carries 340 kWh vs 60 kWh, so its rates win.
        assertEquals(1.121, result.snapshot.blocks[0].rateMxnPerKwh, 0.0001)
        assertEquals(150, result.snapshot.blocks[0].limitKwh)
    }

    @Test
    fun fallsBackCleanlyWhenBillHasNoUsableTierRates() {
        val result = calibrate(
            listOf(
                CalibrationCharge("FIXED", "NONE", quantityKwh = null, unitRateMxn = null),
                CalibrationCharge("BASICO", "NONE", quantityKwh = 100.0, unitRateMxn = 0.0),
            ),
        )

        assertEquals(TariffCalibration.Coverage.NONE, result.coverage)
        assertEquals(TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS, result.snapshot.blocks)
    }

    @Test
    fun calibratedEngineBillsDifferentlyThanGenericDefault() {
        val result = calibrate(
            listOf(
                CalibrationCharge("BASICO", "SUMMER", quantityKwh = 150.0, unitRateMxn = 1.121),
                CalibrationCharge("INTERMEDIO", "SUMMER", quantityKwh = 130.0, unitRateMxn = 1.406),
                CalibrationCharge("EXCEDENTE", "SUMMER", quantityKwh = 102.0, unitRateMxn = 4.115),
            ),
        )

        val calibrated = result.snapshot.engine().estimate(382).totalMxn
        val generic = TariffEngine().estimate(382).totalMxn
        // The whole point of calibrating: the user's real rates move the number.
        assertNotEquals(generic, calibrated)
    }

    @Test
    fun missingEarlierTierFallsBackWithoutBackwardsThresholds() {
        val result = calibrate(
            listOf(
                CalibrationCharge("INTERMEDIO", "SUMMER", quantityKwh = 130.0, unitRateMxn = 1.406),
                CalibrationCharge("EXCEDENTE", "SUMMER", quantityKwh = 102.0, unitRateMxn = 4.115),
            ),
        )

        assertEquals(TariffCalibration.Coverage.NONE, result.coverage)
        assertEquals(TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS, result.snapshot.blocks)
    }
}
