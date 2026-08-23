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
    /**
     * True when [observedKwh] rests on the household attesting that the register
     * wrapped during the night, not on the subtraction alone. The screen has to
     * say so: it is a measurement supported by testimony, not by arithmetic.
     */
    val restsOnAttestation: Boolean = false,
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

    /**
     * @param register the physical register these readings came off. Without it
     *   a night in which the meter crossed its maximum cannot be measured, and
     *   the pair is skipped rather than read as consumption going backwards.
     */
    fun evaluate(
        readings: List<HistoryReadingEntry>,
        appliances: List<Appliance>,
        register: MeterRegister,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): OvernightTest? {
        if (appliances.isEmpty()) return null
        val points = readings
            .filter { it.method != ReadingMethod.PDF }
            .mapNotNull { reading ->
                val timestamp = reading.timestampEpochMillis ?: return@mapNotNull null
                Point(reading, timestamp, Instant.ofEpochMilli(timestamp).atZone(zoneId).hour)
            }
            .sortedBy { it.timestamp }
        if (points.size < 2) return null

        // The most recent qualifying night, so the result follows the user's
        // latest attempt instead of freezing on the first one they ever made.
        //
        // The pair used to qualify on `to.kwh >= from.kwh`, which quietly threw
        // away every night the register wrapped. That is not an exotic case: a
        // four-dial meter going 9,998 -> 0,003 overnight consumed five kilowatt
        // hours, an entirely ordinary night, and the test simply never appeared
        // with nothing said about why. Qualification now rests on MeterDelta
        // establishing a consumption, which it does once the household has
        // attested the wrap on the confirm screen.
        val measured = points.zipWithNext()
            .mapNotNull { (from, to) ->
                val hours = (to.timestamp - from.timestamp) / MILLIS_PER_HOUR
                if (hours !in MIN_HOURS..MAX_HOURS) return@mapNotNull null
                if (from.hour !in START_HOURS && from.hour !in START_HOURS_AFTER_MIDNIGHT) return@mapNotNull null
                if (to.hour !in END_HOURS) return@mapNotNull null
                val delta = MeterDelta.consumption(
                    previousKwh = from.entry.kwh,
                    currentKwh = to.entry.kwh,
                    elapsedDays = hours / HOURS_PER_DAY,
                    capacityKwh = register.capacityKwh,
                    multiplier = register.multiplier,
                    attestedWraps = ReadingInterval.attestedWrapsBetween(readings, from.entry, to.entry),
                    // A meter swapped overnight is not a night this test can
                    // measure unless the household supplied both boundary
                    // readings, and it must never be measured by subtraction.
                    replacement = ReadingInterval.replacementForDelta(readings, from.entry, to.entry),
                    // No pace is passed on purpose. A night is shorter than the
                    // half day MeterDelta needs before a rate means anything,
                    // and the wrap-possible check would fire on the very kind of
                    // interval this function exists to measure.
                    plausibleDailyKwh = null,
                )
                (delta as? MeterDelta.Measured)?.let { Triple(from, to, it) }
            }
            .lastOrNull() ?: return null

        val (from, to, delta) = measured
        val hours = (to.timestamp - from.timestamp) / MILLIS_PER_HOUR
        val observedKwh = delta.kwh.toDouble()
        val expectedWatts = baseWatts(appliances)
        val expectedKwh = expectedWatts * hours / 1_000.0
        return OvernightTest(
            hours = hours,
            observedKwh = observedKwh,
            expectedKwh = expectedKwh,
            unexplainedKwh = (observedKwh - expectedKwh).coerceAtLeast(0.0),
            expectedWatts = expectedWatts,
            observedWatts = if (hours > 0) observedKwh * 1_000.0 / hours else 0.0,
            startLabel = from.entry.dateLabel,
            endLabel = to.entry.dateLabel,
            restsOnAttestation = delta.restsOnAttestation,
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

    /** Keeps the original entry so its attestation stays reachable. */
    private data class Point(val entry: HistoryReadingEntry, val timestamp: Long, val hour: Int)
}
