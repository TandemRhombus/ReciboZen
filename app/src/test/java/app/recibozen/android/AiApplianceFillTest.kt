package app.recibozen.android

import app.recibozen.android.core.ai.UnappliedReason
import app.recibozen.android.core.ai.aiApplianceFill
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.DeviceEvidence
import app.recibozen.android.core.model.EvidenceMethodology
import app.recibozen.android.core.model.EvidenceMetric
import app.recibozen.android.core.model.EvidenceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun suggestion(
    metric: EvidenceMetric,
    value: Double,
    methodology: EvidenceMethodology = EvidenceMethodology.MODELED,
) = DeviceEvidence(
    metric = metric,
    value = value,
    source = EvidenceSource.AI_SUGGESTION,
    methodology = methodology,
)

class AiApplianceFillTest {

    @Test
    fun powerFillsWattsWhenNoEnergyTotalArrived() {
        val fill = aiApplianceFill(listOf(suggestion(EvidenceMetric.ACTIVE_WATTS, 150.4)), billingDays = 60)

        assertEquals(150, fill.powerWatts)
        assertEquals(ApplianceUsageModel.POWER_SCHEDULE, fill.usageModel)
        assertNull("watts and a period total would double-count", fill.unitKwh)
        assertTrue(fill.hasSomethingToApply)
    }

    @Test
    fun anAnnualTotalIsScaledIntoTheBillingPeriod() {
        // 720 kWh a year over a 60-day period is 118.4, not 720.
        val fill = aiApplianceFill(listOf(suggestion(EvidenceMetric.ANNUAL_KWH, 720.0)), billingDays = 60)

        assertEquals(ApplianceUsageModel.PERIOD_KWH, fill.usageModel)
        assertEquals(720.0 * 60 / 365.0, fill.unitKwh!!, 0.001)
        assertNull(fill.powerWatts)
    }

    @Test
    fun theScalingFollowsTheRealPeriodLength() {
        val short = aiApplianceFill(listOf(suggestion(EvidenceMetric.ANNUAL_KWH, 365.0)), billingDays = 30)
        val long = aiApplianceFill(listOf(suggestion(EvidenceMetric.ANNUAL_KWH, 365.0)), billingDays = 61)
        assertEquals(30.0, short.unitKwh!!, 0.001)
        assertEquals(61.0, long.unitKwh!!, 0.001)
    }

    @Test
    fun anEnergyTotalBeatsAPowerFigure() {
        // The order ApplianceCatalog already uses. A fridge rated 120 W does not
        // draw 120 W for the 24 hours it is plugged in, and the household will
        // enter "always on" because that is what a fridge is.
        val fill = aiApplianceFill(
            listOf(
                suggestion(EvidenceMetric.ANNUAL_KWH, 420.0),
                suggestion(EvidenceMetric.ACTIVE_WATTS, 120.0),
            ),
            billingDays = 30,
        )
        assertEquals(420.0 * 30 / 365.0, fill.unitKwh!!, 0.001)
        assertNull("watts paired with always-on would treble this fridge", fill.powerWatts)
        assertEquals(ApplianceUsageModel.PERIOD_KWH, fill.usageModel)
        assertEquals(UnappliedReason.ENERGY_TOTAL_IS_MORE_RELIABLE, fill.unapplied.single().reason)
    }

    @Test
    fun standbyIsNotAddedOnTopOfAnEnergyTotal() {
        // An annual total was measured over hours that already included standby.
        val fill = aiApplianceFill(
            listOf(
                suggestion(EvidenceMetric.ANNUAL_KWH, 420.0),
                suggestion(EvidenceMetric.STANDBY_WATTS, 2.0),
            ),
            billingDays = 30,
        )
        assertNull("the total already contains it", fill.standbyWatts)
    }

    @Test
    fun standbyIsFilledAlongsideEitherModel() {
        val fill = aiApplianceFill(
            listOf(
                suggestion(EvidenceMetric.ACTIVE_WATTS, 90.0),
                suggestion(EvidenceMetric.STANDBY_WATTS, 3.0),
            ),
            billingDays = 60,
        )
        assertEquals(3, fill.standbyWatts)
        assertEquals(90, fill.powerWatts)
    }

    @Test
    fun aPerCycleFigureIsReportedButNeverApplied() {
        // Turning kWh per cycle into a period total needs a number of cycles
        // nobody declared. Assuming one would invent the input.
        val fill = aiApplianceFill(listOf(suggestion(EvidenceMetric.KWH_PER_CYCLE, 0.8)), billingDays = 60)

        assertFalse(fill.hasSomethingToApply)
        assertNull(fill.unitKwh)
        assertNull(fill.usageModel)
        assertEquals(EvidenceMetric.KWH_PER_CYCLE, fill.unapplied.single().metric)
        assertEquals(UnappliedReason.NEEDS_CYCLES_PER_PERIOD, fill.unapplied.single().reason)
    }

    @Test
    fun aValueThatRoundsToZeroWattsIsNotApplied() {
        val fill = aiApplianceFill(listOf(suggestion(EvidenceMetric.ACTIVE_WATTS, 0.4)), billingDays = 60)
        assertNull("zero watts is not a measurement, it is a rounding artefact", fill.powerWatts)
        assertFalse(fill.hasSomethingToApply)
    }

    @Test
    fun nothingUsableLeavesEverythingUntouched() {
        val empty = aiApplianceFill(emptyList(), billingDays = 60)
        assertFalse(empty.hasSomethingToApply)
        assertNull(empty.usageModel)
        assertTrue(empty.unapplied.isEmpty())
    }

    @Test
    fun anImpossiblePeriodLengthDoesNotProduceAnInfiniteFigure() {
        val fill = aiApplianceFill(listOf(suggestion(EvidenceMetric.ANNUAL_KWH, 720.0)), billingDays = 0)
        assertNull("dividing by a zero-day period must not reach the form", fill.unitKwh)
        assertFalse(fill.hasSomethingToApply)
    }
    @Test
    fun aNameplateRatingNeverFillsAFieldTheFormMultiplies() {
        // A PC with a 600 W supply draws 600 W under load and nothing like it on
        // the desktop. Multiplying that by a weekly schedule invents a bill.
        val fill = aiApplianceFill(
            listOf(suggestion(EvidenceMetric.ACTIVE_WATTS, 600.0, EvidenceMethodology.NAMEPLATE)),
            billingDays = 60,
        )

        assertNull("a nameplate is what it can draw, not what it does", fill.powerWatts)
        assertFalse(fill.hasSomethingToApply)
        assertEquals(UnappliedReason.NAMEPLATE_IS_NOT_TYPICAL_USE, fill.unapplied.single().reason)
        assertEquals(EvidenceMetric.ACTIVE_WATTS, fill.unapplied.single().metric)
    }

    @Test
    fun aNameplateAnnualTotalIsRefusedTheSameWay() {
        val fill = aiApplianceFill(
            listOf(suggestion(EvidenceMetric.ANNUAL_KWH, 5000.0, EvidenceMethodology.NAMEPLATE)),
            billingDays = 60,
        )
        assertNull(fill.unitKwh)
        assertEquals(UnappliedReason.NAMEPLATE_IS_NOT_TYPICAL_USE, fill.unapplied.single().reason)
    }

    @Test
    fun aNameplateIsSkippedButAModelledTotalBesideItStillApplies() {
        val fill = aiApplianceFill(
            listOf(
                suggestion(EvidenceMetric.ACTIVE_WATTS, 600.0, EvidenceMethodology.NAMEPLATE),
                suggestion(EvidenceMetric.ANNUAL_KWH, 730.0),
            ),
            billingDays = 30,
        )
        assertNull("the nameplate stays out", fill.powerWatts)
        assertEquals("the modelled annual figure still lands", 60.0, fill.unitKwh!!, 0.001)
        assertEquals(ApplianceUsageModel.PERIOD_KWH, fill.usageModel)
    }

    @Test
    fun aNameplateStandbyIsAlsoRefused() {
        val fill = aiApplianceFill(
            listOf(suggestion(EvidenceMetric.STANDBY_WATTS, 15.0, EvidenceMethodology.NAMEPLATE)),
            billingDays = 60,
        )
        assertNull(fill.standbyWatts)
        assertFalse(fill.hasSomethingToApply)
    }
    @Test
    fun theSamsungAnswerFromProductionResolvesToTheModelledWatts() {
        // Verbatim from a live call on 2026-08-24 for a Samsung UN55AU7000:
        // the model marked its annual total as a nameplate figure and its power
        // as modelled. Both rules have to fire, in order, to land on 85 W.
        val fill = aiApplianceFill(
            listOf(
                suggestion(EvidenceMetric.ANNUAL_KWH, 104.0, EvidenceMethodology.NAMEPLATE),
                suggestion(EvidenceMetric.ACTIVE_WATTS, 85.0, EvidenceMethodology.MODELED),
            ),
            billingDays = 60,
        )

        assertEquals(85, fill.powerWatts)
        assertNull("the nameplate total must not win just for being a total", fill.unitKwh)
        assertEquals(ApplianceUsageModel.POWER_SCHEDULE, fill.usageModel)
        assertEquals(UnappliedReason.NAMEPLATE_IS_NOT_TYPICAL_USE, fill.unapplied.single().reason)
    }
}
