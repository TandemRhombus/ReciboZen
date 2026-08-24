package app.recibozen.android.core.ai

import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.DeviceEvidence
import app.recibozen.android.core.model.EvidenceMethodology
import app.recibozen.android.core.model.EvidenceMetric
import kotlin.math.roundToInt

/**
 * What an AI suggestion would write into the appliance form, and what it would
 * leave alone.
 *
 * Separated from the screen so the unit conversions are testable, because that
 * is where this can go quietly wrong: an annual kWh figure is not a period
 * figure, and a per-cycle figure is neither.
 */
data class AiApplianceFill(
    val usageModel: ApplianceUsageModel?,
    val powerWatts: Int?,
    val unitKwh: Double?,
    val standbyWatts: Int?,
    /** Metrics that arrived but could not be applied, with the reason. */
    val unapplied: List<UnappliedMetric>,
) {
    val hasSomethingToApply: Boolean
        get() = powerWatts != null || unitKwh != null || standbyWatts != null
}

data class UnappliedMetric(val metric: EvidenceMetric, val reason: UnappliedReason)

enum class UnappliedReason {
    /**
     * A per-cycle figure needs a number of cycles per period, and nobody has
     * declared one. Assuming "about four washes a week" would be inventing the
     * very input the calculation depends on, so the figure is shown and not
     * applied.
     */
    NEEDS_CYCLES_PER_PERIOD,

    /**
     * The figure is a nameplate rating, and the form multiplies power by hours.
     *
     * A PC with a 600 W supply draws 600 W under full load and nothing like it
     * sitting on the desktop; an air conditioner's rating is what its compressor
     * pulls at startup, not its average. Multiplying a nameplate by a weekly
     * schedule produces a bill nobody will ever receive, so the figure is shown
     * as information and never written into the field.
     */
    NAMEPLATE_IS_NOT_TYPICAL_USE,

    /**
     * A power figure arrived alongside an energy total, and the total wins.
     *
     * ApplianceCatalog settled this already: declared kWh, then official annual
     * kWh, then a typical 30-day figure, and watts only as a last resort. A
     * total already contains the duty cycle; watts still need the hours the
     * appliance actually works, which nobody knows for a compressor.
     */
    ENERGY_TOTAL_IS_MORE_RELIABLE,
}

private const val DAYS_PER_YEAR = 365.0

/**
 * Builds the fill from evidence, preferring an energy total over a power figure.
 *
 * That order is not arbitrary — it is the one ApplianceCatalog already uses:
 * declared kWh, then official annual kWh, then a typical 30-day figure, and only
 * as a last resort watts multiplied by a schedule. The reason is duty cycle. A
 * fridge rated 120 W is not drawing 120 W for the 24 hours it is plugged in; its
 * compressor runs about a third of the time, which is why the catalogue models
 * one with 30 kWh per 30 days instead of 120 W × 720 h. Taking the watts and
 * pairing them with "always on" overstates that fridge by roughly three times.
 *
 * An energy total already contains the cycling. Watts only become correct once
 * somebody knows the hours the appliance actually works, which is a thing the
 * household knows for a television and does not know for a compressor.
 */
fun aiApplianceFill(evidence: List<DeviceEvidence>, billingDays: Int): AiApplianceFill {
    val byMetric = evidence.associateBy { it.metric }
    val unapplied = mutableListOf<UnappliedMetric>()

    /**
     * Only a figure derived from real use may fill a field the form multiplies.
     * A nameplate answers a different question — what the device can draw — and
     * the two get confused precisely because they share a unit.
     */
    fun typicalUseWatts(metric: EvidenceMetric): Int? {
        val entry = byMetric[metric] ?: return null
        if (entry.methodology == EvidenceMethodology.NAMEPLATE) {
            unapplied += UnappliedMetric(metric, UnappliedReason.NAMEPLATE_IS_NOT_TYPICAL_USE)
            return null
        }
        return entry.value.takeIf { it > 0.0 }?.roundToInt()?.takeIf { it > 0 }
    }

    val annual = byMetric[EvidenceMetric.ANNUAL_KWH]
    val annualKwh = annual?.value?.takeIf { it > 0.0 && annual.methodology != EvidenceMethodology.NAMEPLATE }
    if (annual != null && annual.methodology == EvidenceMethodology.NAMEPLATE) {
        unapplied += UnappliedMetric(EvidenceMetric.ANNUAL_KWH, UnappliedReason.NAMEPLATE_IS_NOT_TYPICAL_USE)
    }
    val periodKwh = annualKwh?.takeIf { billingDays > 0 }?.let { it * billingDays / DAYS_PER_YEAR }

    // Watts are the fallback, and only when no energy total arrived. Filling
    // both would count the same appliance twice.
    val activeWatts = if (periodKwh == null) typicalUseWatts(EvidenceMetric.ACTIVE_WATTS) else null
    if (periodKwh != null && byMetric.containsKey(EvidenceMetric.ACTIVE_WATTS)) {
        unapplied += UnappliedMetric(EvidenceMetric.ACTIVE_WATTS, UnappliedReason.ENERGY_TOTAL_IS_MORE_RELIABLE)
    }

    // Standby is different: an annual total already contains it, so adding it on
    // top would double-count the very hours the total was measured over.
    val standbyWatts = if (periodKwh == null) typicalUseWatts(EvidenceMetric.STANDBY_WATTS) else null

    if (byMetric.containsKey(EvidenceMetric.KWH_PER_CYCLE)) {
        unapplied += UnappliedMetric(EvidenceMetric.KWH_PER_CYCLE, UnappliedReason.NEEDS_CYCLES_PER_PERIOD)
    }

    return AiApplianceFill(
        usageModel = when {
            activeWatts != null -> ApplianceUsageModel.POWER_SCHEDULE
            periodKwh != null -> ApplianceUsageModel.PERIOD_KWH
            else -> null
        },
        powerWatts = activeWatts,
        unitKwh = periodKwh,
        standbyWatts = standbyWatts,
        unapplied = unapplied,
    )
}
