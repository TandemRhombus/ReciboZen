package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Consumption accumulated per elapsed day of the current period, for the curve
 * on "Análisis del periodo".
 *
 * Between two readings the meter only tells us the total, so the segment is
 * drawn as a straight line: honest about the fact that the shape inside the gap
 * is unknown.
 *
 * This used to live inline in the screen's view model and built the curve from
 * `reading.kwh - base`, clamped with `coerceAtLeast(0)`. Two things were wrong
 * with that, and the clamp hid both:
 *
 *  - A register that wrapped mid-period made every later point negative, so the
 *    clamp pinned the line to zero and left it there. That reads as "this
 *    household consumed nothing for three weeks" -- a plausible-looking answer,
 *    and therefore worse than a visible error.
 *  - The subtraction worked in raw register units while the single-reading
 *    fallback works in billed kWh, so on a service with a CFE multiplier the two
 *    branches of the same function disagreed by exactly that factor.
 */
object AccumulatedConsumption {

    /**
     * @param observedKwh the period total, used only for the straight-line
     *   fallback when the readings do not establish a day-by-day shape.
     * @return one value per elapsed day, from 0 to [observedDays] inclusive, or
     *   an empty list when the period has not started producing days yet.
     */
    fun byDay(
        readings: List<HistoryReadingEntry>,
        register: MeterRegister,
        periodStartEpochDay: Long,
        observedDays: Int,
        observedKwh: Int,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<Int> {
        if (observedDays <= 0) return emptyList()
        val entries = readings
            .filter { it.method != ReadingMethod.PDF }
            .mapNotNull { reading ->
                val timestamp = reading.timestampEpochMillis ?: return@mapNotNull null
                val day = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toEpochDay()
                if (day < periodStartEpochDay) null else (day - periodStartEpochDay).toInt() to reading
            }
            .sortedBy { it.first }
            .distinctBy { it.first }

        // Each reading becomes kWh accumulated since the period's opening one,
        // resolved through MeterDelta rather than by subtracting register values.
        val base = entries.firstOrNull()
        val points = if (base == null) emptyList() else entries.mapNotNull { (day, entry) ->
            val delta = MeterDelta.consumption(
                previousKwh = base.second.kwh,
                currentKwh = entry.kwh,
                elapsedDays = (day - base.first).toDouble(),
                capacityKwh = register.capacityKwh,
                multiplier = register.multiplier,
                attestedWraps = ReadingInterval.attestedWrapsBetween(readings, base.second, entry),
                replacement = ReadingInterval.replacementForDelta(readings, base.second, entry),
                // No pace is passed: this curve is the evidence a pace is drawn
                // from, so measuring it against one would be circular.
                plausibleDailyKwh = null,
            )
            (delta as? MeterDelta.Measured)?.let { day to it.kwh }
        }

        if (points.size < 2) {
            // One usable reading still draws a straight line from the period's
            // start. A period whose readings cannot be resolved lands here too,
            // and a straight line to the observed total is the honest shape for
            // it: it claims a total, not a day-by-day history nobody can back.
            return (0..observedDays).map { day ->
                (observedKwh.toDouble() * day / observedDays).roundToInt()
            }
        }

        // From here the values are accumulated kWh, which only move forward, so
        // interpolating between them is ordinary arithmetic.
        return (0..observedDays).map { day ->
            val after = points.firstOrNull { it.first >= day }
            val before = points.lastOrNull { it.first <= day }
            when {
                after != null && before != null && after.first == before.first -> after.second
                after != null && before != null -> {
                    val span = (after.first - before.first).coerceAtLeast(1)
                    val progress = (day - before.first).toDouble() / span
                    (before.second + (after.second - before.second) * progress).roundToInt()
                }
                before != null -> before.second
                else -> 0
            }.coerceAtLeast(0)
        }
    }
}
