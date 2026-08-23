package app.recibozen.android.feature.plan

import app.recibozen.android.core.calculation.DacRiskLevel
import app.recibozen.android.core.calculation.DacRiskSnapshot
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Presentation layer for [DacRiskSnapshot]. The screen must answer four
 * questions at a glance, so the mapping happens here — free of Compose — and is
 * covered by unit tests. No number is invented: everything comes from the
 * snapshot, and anything the snapshot cannot state stays absent.
 */

/** Which of the four questions the hero block is answering. */
enum class DacGlanceKind {
    /** The tariff has no high-consumption limit, or there is no usage yet. */
    UNAVAILABLE,

    /** Not enough billed history: a trend, never a verdict. */
    NO_VERDICT,

    /** Below the tariff limit — the hero number is the remaining cushion. */
    HEADROOM,

    /** Above the limit while still billed under a subsidised tariff. */
    OVER_LIMIT,

    /** Already DAC — the question is how much has to come off to get out. */
    EXIT,
}

enum class DacGlanceTone { NEUTRAL, SAFE, CAUTION, OVER }

/** The three figures that answer "what happens when my next bill closes". */
data class DacLedger(
    /** kWh that age out of the rolling year when the projected period closes. */
    val expiringKwh: Int,
    /** kWh the current pace projects for that period. */
    val projectedKwh: Int,
    /** Signed margin left afterwards: >= 0 below the limit, < 0 above it. */
    val resultingMarginKwh: Int,
    val exceeds: Boolean,
)

data class DacGlance(
    val kind: DacGlanceKind = DacGlanceKind.UNAVAILABLE,
    val tone: DacGlanceTone = DacGlanceTone.NEUTRAL,
    /** Signed: >= 0 kWh left before the limit, < 0 kWh already above it. */
    val marginKwh: Int = 0,
    /**
     * Energy plotted on the bar. With a verdict it is the annualised comparable,
     * the same figure [marginKwh] is derived from. Without one it is the energy
     * actually billed, because an extrapolation must not be labelled "accumulated".
     */
    val accumulatedKwh: Int = 0,
    val annualLimitKwh: Int = 0,
    /** Signed monthly rate versus the limit — the actionable figure to exit DAC. */
    val monthlyMarginKwh: Int = 0,
    val coveredMonths: Int = 0,
    val ledger: DacLedger? = null,
    /** Ceiling for the period in progress — the bill that has not arrived yet. */
    val recommendedMaxKwh: Int? = null,
    /** Ceiling for the period after that one. Only exists on top of a projection. */
    val recommendedFollowingMaxKwh: Int? = null,
) {
    /** The hero number: unsigned, because its label states the direction. */
    val heroKwh: Int
        get() = if (kind == DacGlanceKind.NO_VERDICT) accumulatedKwh else kotlin.math.abs(marginKwh)

    val isOverLimit: Boolean get() = marginKwh < 0

    private val scaleKwh: Float get() = maxOf(accumulatedKwh, annualLimitKwh, 1).toFloat()

    /** Bar fill for the accumulated energy, scaled so an overshoot stays visible. */
    val fillFraction: Float get() = (accumulatedKwh / scaleKwh).coerceIn(0f, 1f)

    /** Where the annual limit sits on that same bar. */
    val limitMarkerFraction: Float get() = (annualLimitKwh / scaleKwh).coerceIn(0f, 1f)
}

/**
 * Projects the audited snapshot onto the glanceable model. [DacGlanceKind.NO_VERDICT]
 * wins over every other level: without a substantially complete rolling year the
 * screen shows a trend and says so, it never states a margin as fact.
 */
fun DacRiskSnapshot.toGlance(): DacGlance {
    val annualLimit = annualEquivalentLimitKwh
    if (level == DacRiskLevel.UNSUPPORTED || annualLimit == null || monthlyAverageKwh == null) {
        return DacGlance()
    }
    val kind = when {
        !hasCompleteHistory -> DacGlanceKind.NO_VERDICT
        level == DacRiskLevel.ALREADY_DAC -> DacGlanceKind.EXIT
        level == DacRiskLevel.ABOVE_LIMIT -> DacGlanceKind.OVER_LIMIT
        else -> DacGlanceKind.HEADROOM
    }
    val margin = annualMarginKwh?.roundToInt() ?: 0
    val tone = when (kind) {
        DacGlanceKind.UNAVAILABLE, DacGlanceKind.NO_VERDICT -> DacGlanceTone.NEUTRAL
        DacGlanceKind.OVER_LIMIT -> DacGlanceTone.OVER
        DacGlanceKind.EXIT -> if (margin >= 0) DacGlanceTone.SAFE else DacGlanceTone.OVER
        DacGlanceKind.HEADROOM ->
            if (level == DacRiskLevel.NORMAL) DacGlanceTone.SAFE else DacGlanceTone.CAUTION
    }
    return DacGlance(
        kind = kind,
        tone = tone,
        marginKwh = margin,
        accumulatedKwh = if (kind == DacGlanceKind.NO_VERDICT) {
            rollingWindowKwh?.roundToInt() ?: 0
        } else {
            annualizedRollingKwh?.roundToInt() ?: 0
        },
        annualLimitKwh = annualLimit,
        monthlyMarginKwh = marginMonthlyKwh?.roundToInt() ?: 0,
        coveredMonths = coveredMonths,
        ledger = ledgerOrNull(kind),
        recommendedMaxKwh = recommendedNextBillMaxKwh.takeIf { kind != DacGlanceKind.NO_VERDICT },
        recommendedFollowingMaxKwh = recommendedFollowingBillMaxKwh.takeIf { kind != DacGlanceKind.NO_VERDICT },
    )
}

/**
 * The ledger only exists when all three figures do: what leaves the window, what
 * the pace projects, and what is left afterwards. A partial ledger would invite
 * the reader to complete it by guessing.
 */
private fun DacRiskSnapshot.ledgerOrNull(kind: DacGlanceKind): DacLedger? {
    if (kind == DacGlanceKind.NO_VERDICT || kind == DacGlanceKind.UNAVAILABLE) return null
    val expiring = expiringBeforeNextBillKwh?.roundToInt() ?: return null
    val projected = projectedNextBillKwh ?: return null
    val resulting = projectedAnnualMarginKwh?.roundToInt() ?: return null
    return DacLedger(
        expiringKwh = expiring,
        projectedKwh = projected,
        resultingMarginKwh = resulting,
        exceeds = wouldExceedWithProjection,
    )
}

private val dacIntegerFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-MX"))

/** Grouped magnitude, e.g. `1,240`. */
fun dacKwh(kwh: Int): String = dacIntegerFormat.format(kwh)

/**
 * A daily allowance, e.g. `18.4` or `240`.
 *
 * Kept to one decimal below a hundred and rounded above it. A household pacing
 * at 7.5 kWh a day needs the half; one pacing at 240 does not, and the extra
 * digit would only suggest a precision the underlying ceiling does not have.
 */
fun dacDaily(kwh: Double): String = when {
    kwh >= 100.0 -> dacIntegerFormat.format(kwh.roundToInt())
    else -> String.format(Locale.forLanguageTag("es-MX"), "%.1f", kwh)
}

/**
 * Grouped magnitude with an explicit sign, e.g. `+410` / `−340`. Uses U+2212, not
 * a hyphen, so the ledger column reads as arithmetic instead of as a dash.
 */
fun dacSignedKwh(kwh: Int): String = when {
    kwh > 0 -> "+" + dacIntegerFormat.format(kwh)
    kwh < 0 -> "−" + dacIntegerFormat.format(-kwh)
    else -> dacIntegerFormat.format(0)
}
