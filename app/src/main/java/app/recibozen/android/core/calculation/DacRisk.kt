package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.BillPeriod
import kotlin.math.floor
import kotlin.math.roundToInt

enum class DacRiskLevel { UNKNOWN, NORMAL, WATCH, RISK, ABOVE_LIMIT, ALREADY_DAC, UNSUPPORTED }

/**
 * Auditable snapshot of the rolling DAC rule. Classification remains
 * [DacRiskLevel.UNKNOWN] until the imported bills cover approximately one
 * complete year; partial history is useful as a trend, never as a verdict.
 */
data class DacRiskSnapshot(
    val tariffCode: String = "",
    /** Domestic tariff whose locality limit applies before/after DAC. */
    val referenceDomesticTariffCode: String? = null,
    val monthlyLimitKwh: Int? = null,
    val annualEquivalentLimitKwh: Int? = monthlyLimitKwh?.times(12),
    val monthlyAverageKwh: Double? = null,
    val marginMonthlyKwh: Double? = null,
    /** Energy actually represented inside the latest 365-day billed window. */
    val rollingWindowKwh: Double? = null,
    /** 365-day comparable value after correcting small uncovered date gaps. */
    val annualizedRollingKwh: Double? = null,
    val annualMarginKwh: Double? = null,
    val rollingWindowStartEpochDay: Long? = null,
    val rollingWindowEndEpochDay: Long? = null,
    val utilizationFraction: Double? = null,
    val coveredDays: Int = 0,
    val hasCompleteHistory: Boolean = false,
    val level: DacRiskLevel = DacRiskLevel.UNKNOWN,
    val projectedNextBillKwh: Int? = null,
    /** Ceiling for the billing period currently in progress. */
    val recommendedNextBillMaxKwh: Int? = null,
    /**
     * Ceiling for the period after the one in progress, assuming the current one
     * closes at [projectedNextBillKwh] and the next one lasts the same days.
     * Null without a projection: a ceiling built on nothing is a guess.
     */
    val recommendedFollowingBillMaxKwh: Int? = null,
    /** Billed energy that will age out when the projected next period closes. */
    val expiringBeforeNextBillKwh: Double? = null,
    /** Historical energy that remains before adding the projected next bill. */
    val retainedBeforeNextBillKwh: Double? = null,
    val projectedRollingWindowKwh: Double? = null,
    val projectedAnnualMarginKwh: Double? = null,
    val wouldExceedWithProjection: Boolean = false,
) {
    val coveredMonths: Int
        get() = (coveredDays / AVERAGE_DAYS_PER_MONTH).roundToInt().coerceIn(0, 12)

    companion object {
        const val AVERAGE_DAYS_PER_MONTH = 365.2425 / 12.0
    }
}

object DacRiskCalculator {
    private const val WINDOW_DAYS = 365L

    // Billing dates can leave a few administrative gap days. This tolerance
    // still requires substantially a complete rolling year before warning that
    // CFE may reclassify the service.
    private const val COMPLETE_HISTORY_DAYS = 350

    fun calculate(
        history: List<BillPeriod>,
        tariffCode: String,
        projectedNextBillKwh: Int? = null,
        projectedBillingDays: Int = 0,
        domesticReferenceTariffCode: String? = null,
    ): DacRiskSnapshot {
        val normalizedTariff = TariffCatalog.normalize(tariffCode)
        if (normalizedTariff == "DAC") {
            val reference = domesticReferenceTariffCode
                ?.let(TariffCatalog::normalize)
                ?.takeIf { TariffCatalog.find(it)?.highConsumptionLimitKwhPerMonth != null }
                ?: return DacRiskSnapshot(tariffCode = normalizedTariff, level = DacRiskLevel.ALREADY_DAC)
            return calculate(
                history = history,
                tariffCode = reference,
                projectedNextBillKwh = projectedNextBillKwh,
                projectedBillingDays = projectedBillingDays,
            ).copy(
                tariffCode = normalizedTariff,
                referenceDomesticTariffCode = reference,
                level = DacRiskLevel.ALREADY_DAC,
            )
        }
        val limit = TariffCatalog.find(normalizedTariff)?.highConsumptionLimitKwhPerMonth
            ?: return DacRiskSnapshot(tariffCode = normalizedTariff, level = DacRiskLevel.UNSUPPORTED)

        val dated = history.mapNotNull { period ->
            val start = period.periodStartEpochDay ?: return@mapNotNull null
            val end = period.periodEndEpochDay ?: return@mapNotNull null
            if (end <= start || period.consumptionKwh < 0) null else DatedConsumption(start, end, period.consumptionKwh)
        }.distinctBy { it.start to it.end }
        if (dated.isEmpty()) {
            return DacRiskSnapshot(tariffCode = normalizedTariff, monthlyLimitKwh = limit)
        }

        val asOf = dated.maxOf { it.end }
        val windowStart = asOf - WINDOW_DAYS
        val window = consumptionInWindow(dated, windowStart, asOf)
        val hasCompleteHistory = window.coveredDays >= COMPLETE_HISTORY_DAYS
        val annualizedConsumption = if (window.coveredDays > 0) {
            window.kwh * WINDOW_DAYS / window.coveredDays
        } else 0.0
        val monthlyAverage = annualizedConsumption / 12.0
        val utilization = monthlyAverage / limit
        val level = if (!hasCompleteHistory) {
            DacRiskLevel.UNKNOWN
        } else when {
            utilization < 0.70 -> DacRiskLevel.NORMAL
            utilization < 0.85 -> DacRiskLevel.WATCH
            utilization <= 1.0 -> DacRiskLevel.RISK
            else -> DacRiskLevel.ABOVE_LIMIT
        }

        val nextDays = projectedBillingDays.coerceAtLeast(0)
        val futureWindowEnd = asOf + nextDays
        val futureWindowStart = futureWindowEnd - WINDOW_DAYS
        val retainedKwh = if (nextDays > 0) consumptionInWindow(dated, futureWindowStart, asOf).kwh else 0.0
        val expiringKwh = if (nextDays > 0) (window.kwh - retainedKwh).coerceAtLeast(0.0) else null
        val recommendedMax = if (hasCompleteHistory && nextDays > 0) {
            floor(limit * 12.0 - retainedKwh).toInt().coerceAtLeast(0)
        } else null
        val safeProjection = projectedNextBillKwh?.coerceAtLeast(0)
        val projectedRollingWindow = if (recommendedMax != null && safeProjection != null) {
            retainedKwh + safeProjection
        } else null
        val annualLimit = limit * 12.0

        // Ceiling for the period after the one in progress. It can only be built
        // on top of two assumptions -- that the current period closes at its
        // projection, and that the next one lasts the same number of days -- so
        // it stays null whenever there is no projection to stand on.
        val recommendedFollowingMax = if (hasCompleteHistory && nextDays > 0 && safeProjection != null) {
            val projectedPeriod = DatedConsumption(asOf, asOf + nextDays, safeProjection)
            val followingWindowEnd = asOf + nextDays * 2L
            val retainedFollowingKwh = consumptionInWindow(
                dated + projectedPeriod,
                followingWindowEnd - WINDOW_DAYS,
                followingWindowEnd,
            ).kwh
            floor(annualLimit - retainedFollowingKwh).toInt().coerceAtLeast(0)
        } else null

        return DacRiskSnapshot(
            tariffCode = normalizedTariff,
            monthlyLimitKwh = limit,
            monthlyAverageKwh = monthlyAverage,
            marginMonthlyKwh = limit - monthlyAverage,
            rollingWindowKwh = window.kwh,
            annualizedRollingKwh = annualizedConsumption,
            annualMarginKwh = annualLimit - annualizedConsumption,
            rollingWindowStartEpochDay = windowStart,
            rollingWindowEndEpochDay = asOf,
            utilizationFraction = utilization,
            coveredDays = window.coveredDays,
            hasCompleteHistory = hasCompleteHistory,
            level = level,
            projectedNextBillKwh = safeProjection,
            recommendedNextBillMaxKwh = recommendedMax,
            recommendedFollowingBillMaxKwh = recommendedFollowingMax,
            expiringBeforeNextBillKwh = expiringKwh,
            retainedBeforeNextBillKwh = retainedKwh.takeIf { nextDays > 0 },
            projectedRollingWindowKwh = projectedRollingWindow,
            projectedAnnualMarginKwh = projectedRollingWindow?.let { annualLimit - it },
            wouldExceedWithProjection = recommendedMax != null && safeProjection != null && safeProjection > recommendedMax,
        )
    }

    private fun consumptionInWindow(
        periods: List<DatedConsumption>,
        windowStart: Long,
        windowEnd: Long,
    ): WindowConsumption {
        val overlaps = periods.mapNotNull { period ->
            val start = maxOf(period.start, windowStart)
            val end = minOf(period.end, windowEnd)
            val overlapDays = end - start
            if (overlapDays <= 0) null else {
                val periodDays = (period.end - period.start).coerceAtLeast(1)
                Overlap(start, end, period.kwh * overlapDays.toDouble() / periodDays)
            }
        }
        val coveredDays = mergeCoveredDays(overlaps)
        return WindowConsumption(overlaps.sumOf { it.kwh }, coveredDays)
    }

    private fun mergeCoveredDays(overlaps: List<Overlap>): Int {
        if (overlaps.isEmpty()) return 0
        val sorted = overlaps.sortedBy { it.start }
        var mergedStart = sorted.first().start
        var mergedEnd = sorted.first().end
        var total = 0L
        sorted.drop(1).forEach { overlap ->
            if (overlap.start <= mergedEnd) {
                mergedEnd = maxOf(mergedEnd, overlap.end)
            } else {
                total += mergedEnd - mergedStart
                mergedStart = overlap.start
                mergedEnd = overlap.end
            }
        }
        total += mergedEnd - mergedStart
        return total.toInt().coerceAtMost(WINDOW_DAYS.toInt())
    }

    private data class DatedConsumption(val start: Long, val end: Long, val kwh: Int)
    private data class Overlap(val start: Long, val end: Long, val kwh: Double)
    private data class WindowConsumption(val kwh: Double, val coveredDays: Int)
}
