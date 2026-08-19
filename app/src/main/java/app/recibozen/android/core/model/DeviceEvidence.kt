package app.recibozen.android.core.model

import kotlin.math.abs
import kotlin.math.max

/**
 * What a piece of evidence measures. The metric owns its unit so the two can
 * never disagree: a value carrying a unit field that contradicts its metric is
 * a defect waiting to be displayed.
 */
enum class EvidenceMetric(val unit: EvidenceUnit) {
    /** Energy over a full year — what a NOM label and ENERGY STAR both publish. */
    ANNUAL_KWH(EvidenceUnit.KWH_PER_YEAR),

    /** Energy over one wash/dry/dishwashing cycle. */
    KWH_PER_CYCLE(EvidenceUnit.KWH_PER_CYCLE),

    /** Power drawn while the appliance is doing its job. */
    ACTIVE_WATTS(EvidenceUnit.WATTS),

    /** Power drawn while plugged in but idle. */
    STANDBY_WATTS(EvidenceUnit.WATTS),
}

enum class EvidenceUnit { KWH_PER_YEAR, KWH_PER_CYCLE, WATTS }

/**
 * Where a number came from, ranked by the weight it carries when two sources
 * contradict each other. Higher [trustRank] wins.
 *
 * [AI_SUGGESTION] sits at the bottom on purpose. A language model may fill gaps
 * the catalogues leave — Mexican brands are almost absent from ENERGY STAR — but
 * it must never override something that was actually measured or printed on a
 * label. This ordering is the reason the schema exists before the AI does.
 */
enum class EvidenceSource(val trustRank: Int) {
    /** The user metered this appliance themselves. */
    USER_MEASURED(5),

    /** Read off the NOM-015 energy label physically attached to the appliance. */
    NOM_LABEL(4),

    /** Manufacturer specification sheet or manual. */
    MANUFACTURER_SPEC(3),

    /** A certification dataset such as ENERGY STAR. */
    CERTIFICATION_CATALOG(2),

    /** Generic per-category default shipped with the app. */
    CATEGORY_DEFAULT(1),

    /** Produced by a language model. Never outranks a real measurement. */
    AI_SUGGESTION(0),
}

/** How the number was obtained, which bounds how much it can be trusted. */
enum class EvidenceMethodology {
    /** Metered over a stated interval. */
    METERED,

    /** Obtained under a standard test procedure (NOM-015, ENERGY STAR method). */
    STANDARD_TEST,

    /** Nameplate rating: what the device *may* draw, not what it does draw. */
    NAMEPLATE,

    /** Derived from a comparable model or a category average. */
    MODELED,

    /** Somebody typed a number without stating how they got it. */
    DECLARED,
}

/**
 * One assertion about one appliance, with everything needed to judge it later:
 * what it says, in which unit, who said it, when, how they got it, and how
 * confident that combination makes us.
 *
 * [confidenceAsOf] is derived rather than stored. A caller that could stamp
 * `HIGH` on a modelled guess would reintroduce exactly the defect this app keeps
 * removing — an estimate that looks like a measurement.
 */
data class DeviceEvidence(
    val metric: EvidenceMetric,
    val value: Double,
    val source: EvidenceSource,
    val methodology: EvidenceMethodology,
    /** Null when the source did not state when the value was obtained. */
    val retrievedAtEpochDay: Long? = null,
    /** Lower bound when the source published a range instead of a point. */
    val rangeMinValue: Double? = null,
    val rangeMaxValue: Double? = null,
    /** Human-readable attribution, e.g. "ENERGY STAR" or "Etiqueta NOM del equipo". */
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
    /** Null means the source did not assert a target market. */
    val marketMexicoVerified: Boolean? = null,
) {
    val unit: EvidenceUnit get() = metric.unit

    /** True when the source published a range wide enough to matter. */
    val hasRange: Boolean
        get() = rangeMinValue != null && rangeMaxValue != null &&
            rangeMaxValue - rangeMinValue >= RANGE_EPSILON

    /**
     * Confidence for this single assertion, as of [asOfEpochDay]. Starts from the
     * source and is demoted by a weak methodology, a retrieval date old enough to
     * no longer describe what is on sale, and a range too wide to be useful.
     */
    fun confidenceAsOf(asOfEpochDay: Long?): ConfidenceLevel {
        var score = when (source) {
            EvidenceSource.USER_MEASURED, EvidenceSource.NOM_LABEL -> HIGH_SCORE
            EvidenceSource.MANUFACTURER_SPEC, EvidenceSource.CERTIFICATION_CATALOG -> MEDIUM_SCORE
            EvidenceSource.CATEGORY_DEFAULT, EvidenceSource.AI_SUGGESTION -> LOW_SCORE
        }
        if (methodology in WEAK_METHODOLOGIES) score--
        // A missing date is not evidence of staleness, so it costs nothing. Only a
        // date we can actually compare against can demote the assertion.
        if (retrievedAtEpochDay != null && asOfEpochDay != null &&
            asOfEpochDay - retrievedAtEpochDay > STALE_AFTER_DAYS
        ) {
            score--
        }
        if (isRangeTooWide()) score--
        return scoreToConfidence(score)
    }

    private fun isRangeTooWide(): Boolean {
        val low = rangeMinValue ?: return false
        val high = rangeMaxValue ?: return false
        if (low <= 0.0) return false
        return high / low > WIDE_RANGE_FACTOR
    }

    /**
     * Whether this assertion and [other] are close enough to be treated as the
     * same finding. Overlapping published ranges count as agreement even when the
     * midpoints differ — two sources that both say "between 300 and 400" do not
     * contradict each other.
     */
    fun agreesWith(other: DeviceEvidence): Boolean {
        if (metric != other.metric) return false
        if (rangesOverlap(other)) return true
        val scale = max(abs(value), abs(other.value))
        if (scale <= 0.0) return true
        return abs(value - other.value) / scale <= AGREEMENT_TOLERANCE
    }

    private fun rangesOverlap(other: DeviceEvidence): Boolean {
        val thisLow = rangeMinValue ?: return false
        val thisHigh = rangeMaxValue ?: return false
        val otherLow = other.rangeMinValue ?: return false
        val otherHigh = other.rangeMaxValue ?: return false
        return thisLow <= otherHigh && otherLow <= thisHigh
    }

    companion object {
        /** Two values within this relative distance are treated as the same finding. */
        const val AGREEMENT_TOLERANCE = 0.15

        /** Beyond five years a published figure stops describing what is on sale. */
        const val STALE_AFTER_DAYS = 1_826L

        /** A range whose top doubles its floor is too wide to call precise. */
        const val WIDE_RANGE_FACTOR = 2.0

        private const val RANGE_EPSILON = 0.1
        private const val HIGH_SCORE = 3
        private const val MEDIUM_SCORE = 2
        private const val LOW_SCORE = 1

        private val WEAK_METHODOLOGIES = setOf(
            EvidenceMethodology.NAMEPLATE,
            EvidenceMethodology.MODELED,
            EvidenceMethodology.DECLARED,
        )

        internal fun scoreToConfidence(score: Int): ConfidenceLevel = when {
            score >= HIGH_SCORE -> ConfidenceLevel.HIGH
            score == MEDIUM_SCORE -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        internal fun confidenceToScore(level: ConfidenceLevel): Int = when (level) {
            ConfidenceLevel.HIGH -> HIGH_SCORE
            ConfidenceLevel.MEDIUM -> MEDIUM_SCORE
            ConfidenceLevel.LOW -> LOW_SCORE
        }
    }
}
