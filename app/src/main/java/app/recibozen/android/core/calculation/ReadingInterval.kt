package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.HistoryReadingEntry

/**
 * How much time a pair of readings actually covers.
 *
 * The confirm screen used to work this out inline, and it was wrong twice over:
 *
 *  - It read `readings.firstOrNull()?.timestampEpochMillis`. The list is sorted
 *    newest-first, but an entry's timestamp is nullable, so a newest entry
 *    without one made the whole interval unknown even when every older reading
 *    carried a perfectly good timestamp.
 *  - When that happened it substituted `76.0 / 24.0` -- three hours and change,
 *    a leftover from the demo fixture. Every daily average computed from an
 *    invented interval is an invented average, and it was shown as a measurement.
 *
 * Null here means "the readings do not establish an interval". Screens must say
 * so rather than divide by a number nobody measured.
 */
object ReadingInterval {

    /**
     * Millis in a day. Kept local so this object does not depend on a repository
     * constant it would otherwise have to reach across a layer for.
     */
    private const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * The most recent reading that carries a timestamp, ignoring any newer ones
     * that do not. Those exist -- imported fixtures and older builds wrote
     * entries without a clock -- and skipping them is what keeps a real interval
     * available instead of collapsing to unknown.
     */
    fun latestTimestamped(readings: List<HistoryReadingEntry>): HistoryReadingEntry? =
        readings.filter { it.timestampEpochMillis != null }.maxByOrNull { it.timestampEpochMillis!! }

    /**
     * Days between the latest timestamped reading and [nowMillis], or null when
     * no reading carries a timestamp.
     *
     * Never clamped up to a minimum. Two readings taken minutes apart really are
     * minutes apart, and [MeterDelta] already refuses to derive a rate from an
     * interval that short -- which is the honest handling. Forcing the interval
     * up to a quarter of a day instead invented an elapsed time and then hid the
     * problem by making the rate look calculable.
     */
    fun daysSince(readings: List<HistoryReadingEntry>, nowMillis: Long): Double? {
        val latest = latestTimestamped(readings)?.timestampEpochMillis ?: return null
        return ((nowMillis - latest) / MILLIS_PER_DAY).coerceAtLeast(0.0)
    }

    /**
     * Wraps attested across every reading taken after [after], up to and
     * including [upTo].
     *
     * The period summary compares a baseline against the newest reading, and
     * those two are frequently not adjacent. An attestation belongs to the one
     * interval it was given for, so the total across a longer span is their sum
     * -- taking only the newest entry's value would drop every wrap attested in
     * between.
     *
     * Null when no reading in the span carries an attestation: nobody was asked,
     * which is not the same as everybody answering zero.
     */
    fun attestedWrapsBetween(
        readings: List<HistoryReadingEntry>,
        after: HistoryReadingEntry,
        upTo: HistoryReadingEntry,
    ): Int? {
        val from = after.timestampEpochMillis ?: return null
        val to = upTo.timestampEpochMillis ?: return null
        val span = readings.filter { entry ->
            val at = entry.timestampEpochMillis ?: return@filter false
            at > from && at <= to
        }
        if (span.none { it.wrapsSincePrevious != null }) return null
        return span.sumOf { it.wrapsSincePrevious ?: 0 }
    }
}
