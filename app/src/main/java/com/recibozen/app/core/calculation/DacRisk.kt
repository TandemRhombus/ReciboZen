package com.recibozen.app.core.calculation

import com.recibozen.app.core.model.BillPeriod
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
    val monthlyLimitKwh: Int? = null,
    val annualEquivalentLimitKwh: Int? = monthlyLimitKwh?.times(12),
    val monthlyAverageKwh: Double? = null,
    val marginMonthlyKwh: Double? = null,
    val utilizationFraction: Double? = null,
    val coveredDays: Int = 0,
    val hasCompleteHistory: Boolean = false,
    val level: DacRiskLevel = DacRiskLevel.UNKNOWN,
    val projectedNextBillKwh: Int? = null,
    val recommendedNextBillMaxKwh: Int? = null,
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
    ): DacRiskSnapshot {
        val normalizedTariff = TariffCatalog.normalize(tariffCode)
        if (normalizedTariff == "DAC") {
            return DacRiskSnapshot(tariffCode = normalizedTariff, level = DacRiskLevel.ALREADY_DAC)
        }
        val limit = TariffCatalog.find(normalizedTariff)?.highConsumptionLimitKwhPerMonth
            ?: return DacRiskSnapshot(tariffCode = normalizedTariff, level = DacRiskLevel.UNSUPPORTED)

        val dated = history.mapNotNull { period ->
            val start = period.periodStartEpochDay ?: return@mapNotNull null
            val end = period.periodEndEpochDay ?: return@mapNotNull null
            if (end <= start || period.consumptionKwh < 0) null else DatedConsumption(start, end, period.consumptionKwh)
        }
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
        val recommendedMax = if (hasCompleteHistory && nextDays > 0) {
            floor(limit * 12.0 - retainedKwh).toInt().coerceAtLeast(0)
        } else null
        val safeProjection = projectedNextBillKwh?.coerceAtLeast(0)

        return DacRiskSnapshot(
            tariffCode = normalizedTariff,
            monthlyLimitKwh = limit,
            monthlyAverageKwh = monthlyAverage,
            marginMonthlyKwh = limit - monthlyAverage,
            utilizationFraction = utilization,
            coveredDays = window.coveredDays,
            hasCompleteHistory = hasCompleteHistory,
            level = level,
            projectedNextBillKwh = safeProjection,
            recommendedNextBillMaxKwh = recommendedMax,
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
