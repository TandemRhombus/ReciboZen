package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import java.time.Instant
import java.time.ZoneId

/**
 * Result of comparing an overnight reading pair against the base load the
 * registered inventory can justify. The gap is what the household is paying for
 * without knowing it.
 */
data class OvernightTest(
    val hours: Double,
    val observedKwh: Double,
    val expectedKwh: Double,
    val unexplainedKwh: Double,
    /** Base power the inventory justifies while nobody is using anything. */
    val expectedWatts: Double,
    val observedWatts: Double,
    val startLabel: String,
    val endLabel: String,
)

/**
 * Finds a reading pair taken across a night and prices the difference against
 * the appliances that legitimately keep drawing power while the house sleeps.
 *
 * The test is only reported when the evidence supports it: two manual readings
 * across a real night, and an inventory to compare against. A "phantom
 * consumption" number with nothing to compare it to would be noise.
 */
object OvernightBaselineCalculator {
    private const val MIN_HOURS = 4.0
    private const val MAX_HOURS = 14.0
    private const val HOURS_PER_DAY = 24.0

    /** A night starts late in the evening and ends in the morning. */
    private val START_HOURS = 19..23
    private val START_HOURS_AFTER_MIDNIGHT = 0..2
    private val END_HOURS = 4..11

    fun evaluate(
        readings: List<HistoryReadingEntry>,
        appliances: List<Appliance>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): OvernightTest? {
        if (appliances.isEmpty()) return null
        val points = readings
            .filter { it.method != ReadingMethod.PDF }
            .mapNotNull { reading ->
                val timestamp = reading.timestampEpochMillis ?: return@mapNotNull null
                Point(timestamp, reading.kwh, reading.dateLabel, Instant.ofEpochMilli(timestamp).atZone(zoneId).hour)
            }
            .sortedBy { it.timestamp }
        if (points.size < 2) return null

        // The most recent qualifying night, so the result follows the user's
        // latest attempt instead of freezing on the first one they ever made.
        val pair = points.zipWithNext()
            .lastOrNull { (from, to) ->
                val hours = (to.timestamp - from.timestamp) / MILLIS_PER_HOUR
                hours in MIN_HOURS..MAX_HOURS &&
                    (from.hour in START_HOURS || from.hour in START_HOURS_AFTER_MIDNIGHT) &&
                    to.hour in END_HOURS &&
                    to.kwh >= from.kwh
            } ?: return null

        val hours = (pair.second.timestamp - pair.first.timestamp) / MILLIS_PER_HOUR
        val observedKwh = (pair.second.kwh - pair.first.kwh).toDouble()
        val expectedWatts = baseWatts(appliances)
        val expectedKwh = expectedWatts * hours / 1_000.0
        return OvernightTest(
            hours = hours,
            observedKwh = observedKwh,
            expectedKwh = expectedKwh,
            unexplainedKwh = (observedKwh - expectedKwh).coerceAtLeast(0.0),
            expectedWatts = expectedWatts,
            observedWatts = if (hours > 0) observedKwh * 1_000.0 / hours else 0.0,
            startLabel = pair.first.label,
            endLabel = pair.second.label,
        )
    }

    /**
     * Power the inventory justifies overnight.
     *
     * An appliance billed by period energy (a fridge, a router, a pump) runs on
     * its own cycle regardless of the hour, so its average power counts. A
     * scheduled appliance is assumed off, and contributes only the standby the
     * user actually declared — never an assumed one.
     */
    fun baseWatts(appliances: List<Appliance>): Double = appliances.sumOf { appliance ->
        val quantity = appliance.quantity.coerceAtLeast(1)
        val periodDays = appliance.referencePeriodDays.coerceAtLeast(1)
        val unitKwh = appliance.unitKwh ?: (appliance.kwh.toDouble() / quantity)
        val isScheduled = appliance.usageModel == ApplianceUsageModel.POWER_SCHEDULE &&
            appliance.hoursPerWeek != null
        val watts = if (isScheduled) {
            appliance.standbyWatts?.takeIf { appliance.standbyEnabled }?.toDouble() ?: 0.0
        } else {
            // Average power over the period: kWh ÷ days ÷ 24 h × 1000.
            unitKwh / periodDays / HOURS_PER_DAY * 1_000.0
        }
        watts * quantity
    }

    private const val MILLIS_PER_HOUR = 3_600_000.0

    private data class Point(val timestamp: Long, val kwh: Int, val label: String, val hour: Int)
}
