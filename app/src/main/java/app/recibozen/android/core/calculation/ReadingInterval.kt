package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.MeterReplacement

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
        val span = spanBetween(readings, after, upTo) ?: return null
        if (span.none { it.wrapsSincePrevious != null }) return null
        return span.sumOf { it.wrapsSincePrevious ?: 0 }
    }

    /**
     * The meter replacement declared inside the span, when there is exactly one
     * and it is complete.
     *
     * Returns null when nothing was replaced, and [Ambiguous] when something was
     * but the span cannot be closed: a replacement missing one of its boundary
     * readings, or two swaps inside the same span. The caller must not treat
     * that as "no replacement" -- the readings at either end came off different
     * physical registers and subtracting them is meaningless.
     */
    fun replacementBetween(
        readings: List<HistoryReadingEntry>,
        after: HistoryReadingEntry,
        upTo: HistoryReadingEntry,
    ): ReplacementInSpan {
        val span = spanBetween(readings, after, upTo) ?: return ReplacementInSpan.None
        val declared = span.mapNotNull { it.meterReplacement }
        return when {
            declared.isEmpty() -> ReplacementInSpan.None
            // Two swaps between the same pair of endpoints would need both
            // meters' boundary readings chained, and the app has never asked for
            // a chain. Refusing keeps it from silently using one of the two.
            declared.size > 1 -> ReplacementInSpan.Ambiguous
            declared.single().isComplete -> ReplacementInSpan.Declared(declared.single())
            else -> ReplacementInSpan.Ambiguous
        }
    }

    /**
     * What to hand [MeterDelta.consumption] as its `replacement` for this span.
     *
     * The three-way answer collapses to two values there, and getting the
     * collapse wrong is the dangerous part: an ambiguous span must arrive as an
     * *incomplete* replacement so MeterDelta refuses, never as null, which would
     * quietly subtract a reading off one register from a reading off another.
     */
    fun replacementForDelta(
        readings: List<HistoryReadingEntry>,
        after: HistoryReadingEntry,
        upTo: HistoryReadingEntry,
    ): MeterReplacement? = when (val found = replacementBetween(readings, after, upTo)) {
        ReplacementInSpan.None -> null
        is ReplacementInSpan.Declared -> found.replacement
        ReplacementInSpan.Ambiguous -> MeterReplacement()
    }

    /** Readings strictly after [after] and up to [upTo], or null if either lacks a timestamp. */
    private fun spanBetween(
        readings: List<HistoryReadingEntry>,
        after: HistoryReadingEntry,
        upTo: HistoryReadingEntry,
    ): List<HistoryReadingEntry>? {
        val from = after.timestampEpochMillis ?: return null
        val to = upTo.timestampEpochMillis ?: return null
        return readings.filter { entry ->
            val at = entry.timestampEpochMillis ?: return@filter false
            at > from && at <= to
        }
    }
}

/** What a span of readings says about the meter having been replaced inside it. */
sealed interface ReplacementInSpan {
    /** Same physical meter at both ends. Ordinary arithmetic applies. */
    data object None : ReplacementInSpan

    /** One replacement, with both boundary readings known. */
    data class Declared(val replacement: MeterReplacement) : ReplacementInSpan

    /**
     * A meter changed in here and the span cannot be closed. Not the same as
     * [None]: the two endpoints belong to different registers.
     */
    data object Ambiguous : ReplacementInSpan
}
