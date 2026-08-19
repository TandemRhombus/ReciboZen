package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.DeviceEvidence
import app.recibozen.android.core.model.EvidenceMethodology
import app.recibozen.android.core.model.EvidenceMetric
import app.recibozen.android.core.model.EvidenceSource
import kotlin.math.abs
import kotlin.math.max

/** Outcome of weighing every assertion available for one metric. */
sealed interface EvidenceResolution {
    /** No source said anything about this metric. */
    data object None : EvidenceResolution

    /** One assertion, or several that agree with each other. */
    data class Settled(
        val winner: DeviceEvidence,
        val corroborating: List<DeviceEvidence>,
        val confidence: ConfidenceLevel,
    ) : EvidenceResolution

    /**
     * At least two assertions contradict. [winner] is the one shown; [dissenting]
     * is what disagreed and is never folded into it.
     */
    data class Conflicted(
        val winner: DeviceEvidence,
        val dissenting: List<DeviceEvidence>,
        val confidence: ConfidenceLevel,
        /** Relative distance to the furthest dissenter, e.g. 0.42 for 42%. */
        val disagreementFraction: Double,
    ) : EvidenceResolution
}

/**
 * Decides what to show when several sources describe the same appliance.
 *
 * The rule when two sources contradict is deliberate and has one prohibition at
 * its centre: **contradicting values are never averaged**. A blend is a number
 * nobody asserted, presented with the authority of both. Instead one assertion
 * wins on stated grounds, the loser is kept and reported, and confidence drops.
 *
 * Precedence, applied in order:
 *  1. Higher [EvidenceSource.trustRank] — a measurement beats a label beats a
 *     catalogue beats a default beats anything a model suggested.
 *  2. More recently retrieved; an assertion with no date never displaces a dated one.
 *  3. The narrower published range.
 *  4. The lower value, purely so the outcome is deterministic.
 */
object EvidenceResolver {

    fun resolve(
        evidence: List<DeviceEvidence>,
        metric: EvidenceMetric,
        asOfEpochDay: Long? = null,
    ): EvidenceResolution {
        val candidates = evidence.filter { it.metric == metric }
        if (candidates.isEmpty()) return EvidenceResolution.None

        val ranked = candidates.sortedWith(precedence)
        val winner = ranked.first()
        val rest = ranked.drop(1)
        val corroborating = rest.filter { winner.agreesWith(it) }
        val dissenting = rest.filterNot { winner.agreesWith(it) }

        var score = DeviceEvidence.confidenceToScore(winner.confidenceAsOf(asOfEpochDay))
        // An independent source saying the same thing is worth something; a source
        // saying something else costs the same. Both can apply and cancel out.
        if (corroborating.any { it.source != winner.source }) score++
        if (dissenting.isNotEmpty()) score--
        val confidence = DeviceEvidence.scoreToConfidence(score)

        if (dissenting.isEmpty()) {
            return EvidenceResolution.Settled(winner, corroborating, confidence)
        }
        return EvidenceResolution.Conflicted(
            winner = winner,
            dissenting = dissenting,
            confidence = confidence,
            disagreementFraction = dissenting.maxOf { relativeDistance(winner.value, it.value) },
        )
    }

    private val precedence = compareByDescending<DeviceEvidence> { it.source.trustRank }
        .thenByDescending { it.retrievedAtEpochDay ?: Long.MIN_VALUE }
        .thenBy { it.rangeWidthOrZero() }
        .thenBy { it.value }

    private fun DeviceEvidence.rangeWidthOrZero(): Double {
        val low = rangeMinValue ?: return 0.0
        val high = rangeMaxValue ?: return 0.0
        return (high - low).coerceAtLeast(0.0)
    }

    private fun relativeDistance(a: Double, b: Double): Double {
        val scale = max(abs(a), abs(b))
        return if (scale <= 0.0) 0.0 else abs(a - b) / scale
    }
}

/**
 * Projects what an [Appliance] already carries onto the evidence schema, so the
 * new model describes today's real data instead of waiting for the NOM capture
 * and the AI to arrive.
 *
 * [ApplianceDataSource] predates this schema and is coarser: it has no slot for a
 * NOM label read by the user, for a certification dataset, or for a model
 * suggestion. Those map to [EvidenceSource] values nothing produces yet — that is
 * the room the schema adds, not an omission.
 */
fun Appliance.declaredEvidence(): List<DeviceEvidence> {
    val (source, methodology) = when (dataSource) {
        ApplianceDataSource.USER_MEASURED -> EvidenceSource.USER_MEASURED to EvidenceMethodology.METERED
        ApplianceDataSource.MANUFACTURER_LABEL -> EvidenceSource.MANUFACTURER_SPEC to EvidenceMethodology.STANDARD_TEST
        ApplianceDataSource.CATALOG -> EvidenceSource.CATEGORY_DEFAULT to EvidenceMethodology.MODELED
        ApplianceDataSource.RECEIPT_INFERENCE -> EvidenceSource.CATEGORY_DEFAULT to EvidenceMethodology.MODELED
        ApplianceDataSource.USER_ESTIMATE -> EvidenceSource.CATEGORY_DEFAULT to EvidenceMethodology.DECLARED
    }
    val items = mutableListOf<DeviceEvidence>()
    referenceAnnualKwh?.let { annual ->
        items += DeviceEvidence(
            metric = EvidenceMetric.ANNUAL_KWH,
            value = annual,
            source = source,
            methodology = methodology,
            rangeMinValue = referenceAnnualKwhMin,
            rangeMaxValue = referenceAnnualKwhMax,
            sourceLabel = sourceLabel,
            sourceUrl = sourceUrl,
            marketMexicoVerified = marketMexicoVerified,
        )
    }
    powerWatts?.takeIf { it > 0 }?.let { watts ->
        items += DeviceEvidence(
            metric = EvidenceMetric.ACTIVE_WATTS,
            value = watts.toDouble(),
            source = source,
            methodology = methodology,
            sourceLabel = sourceLabel,
            sourceUrl = sourceUrl,
            marketMexicoVerified = marketMexicoVerified,
        )
    }
    standbyWatts?.takeIf { it > 0 }?.let { watts ->
        items += DeviceEvidence(
            metric = EvidenceMetric.STANDBY_WATTS,
            value = watts.toDouble(),
            source = source,
            methodology = methodology,
            sourceLabel = sourceLabel,
            sourceUrl = sourceUrl,
            marketMexicoVerified = marketMexicoVerified,
        )
    }
    return items
}
