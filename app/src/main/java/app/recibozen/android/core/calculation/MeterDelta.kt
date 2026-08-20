package app.recibozen.android.core.calculation

import kotlin.math.roundToInt

/**
 * Consumption between two meter readings.
 *
 * The app used to compute this by plain subtraction in three places. That is
 * wrong in three separate ways, all of them verified against real code:
 *
 *  - It ignores the CFE multiplier. Bill import already multiplies the register
 *    delta ([app.recibozen.android.feature.history.BillImportScreen]), so a
 *    reading-derived figure silently disagreed with the user's own bills.
 *  - A register that wraps past its capacity produces a negative delta, which
 *    was shown to the user as a measurement and then quietly discarded.
 *  - A wrap that lands *above* the previous reading produces a plausible-looking
 *    positive delta that is understated by a whole capacity, with no warning at
 *    all. That case is invisible and, with infrequent readings, common.
 *
 * The rule at the centre: **this function never guesses.** Where the numbers
 * admit more than one reading of events, it returns [Undecidable] and the app
 * asks. A wrong guess here corrupts the bill projection and the DAC verdict,
 * which is the most consequential thing the app says.
 */
sealed interface MeterDelta {

    /** Consumption the readings actually establish, already multiplied. */
    data class Measured(
        val kwh: Int,
        /** How many times the register wrapped. 0 for an ordinary interval. */
        val wraps: Int,
        val multiplier: Int,
    ) : MeterDelta {
        /** True when this figure rests on the user attesting a wrap, not on arithmetic alone. */
        val restsOnAttestation: Boolean get() = wraps > 0
    }

    /**
     * The readings do not determine the consumption. [candidates] are the
     * readings of events that are arithmetically possible, so the screen can
     * show what each would imply instead of inventing one.
     */
    data class Undecidable(
        val reason: Reason,
        val candidates: List<Candidate> = emptyList(),
    ) : MeterDelta

    enum class Reason {
        /** Current is below previous: wrap, typo, meter swap or wrong service. */
        WENT_BACKWARDS,

        /** Below previous and the meter's digit count was never established. */
        CAPACITY_UNKNOWN,

        /** A wrap could have happened inside this interval and would be invisible. */
        WRAP_POSSIBLE,

        /** A stored reading contradicts the attested digit count. */
        READING_EXCEEDS_CAPACITY,

        /** The implied daily rate is far outside anything this household does. */
        RATE_IMPLAUSIBLE,
    }

    /** One arithmetically possible reading of events, with what it would imply. */
    data class Candidate(
        val wraps: Int,
        val kwh: Int,
        val dailyKwh: Double,
    )

    companion object {
        /** Anything beyond this multiple of the household's own pace is not credible. */
        const val IMPLAUSIBLE_RATE_FACTOR = 6.0

        /** Below this many days, a rate says nothing; two readings minutes apart are normal. */
        const val MIN_DAYS_FOR_RATE = 0.5

        /** Wrap candidates offered before giving up. A meter wrapping 4 times unnoticed is not a UI problem. */
        const val MAX_WRAPS_OFFERED = 3

        /**
         * Consumption between [previousKwh] and [currentKwh], both raw register
         * values as printed on the meter.
         *
         * @param capacityKwh 10^digits for this meter, or null when the digit
         *   count was never established. Never defaulted: assuming five digits
         *   for a four-digit meter is what produces phantom 90,000 kWh readings.
         * @param multiplier the CFE constant. 1 when the meter has none.
         * @param attestedWraps how many wraps the user confirmed for this
         *   reading, or null when they have not been asked.
         * @param plausibleDailyKwh the household's own established pace, used to
         *   rank candidates and to notice an interval long enough to hide a wrap.
         *   Null disables both checks rather than substituting a made-up pace.
         */
        fun consumption(
            previousKwh: Int,
            currentKwh: Int,
            elapsedDays: Double,
            capacityKwh: Int? = null,
            multiplier: Int = 1,
            attestedWraps: Int? = null,
            plausibleDailyKwh: Double? = null,
        ): MeterDelta {
            val factor = multiplier.coerceAtLeast(1)

            // A reading at or above capacity contradicts the attested digit count.
            // Do not truncate the reading and do not widen the capacity: one of the
            // two is wrong and only the user knows which.
            if (capacityKwh != null && (previousKwh >= capacityKwh || currentKwh >= capacityKwh)) {
                return Undecidable(Reason.READING_EXCEEDS_CAPACITY)
            }

            if (currentKwh < previousKwh) {
                if (capacityKwh == null) return Undecidable(Reason.CAPACITY_UNKNOWN)
                if (attestedWraps == null || attestedWraps < 1) {
                    return Undecidable(
                        reason = Reason.WENT_BACKWARDS,
                        candidates = wrapCandidates(previousKwh, currentKwh, elapsedDays, capacityKwh, factor, from = 1),
                    )
                }
                val kwh = (attestedWraps.toLong() * capacityKwh - previousKwh + currentKwh) * factor
                return Measured(kwh.toInt(), attestedWraps, factor)
            }

            // Forward, but a wrap here is arithmetically invisible: it would land
            // above the previous reading and look like an ordinary interval. The
            // only honest signal is whether the household's own pace could have
            // covered the distance to capacity in this many days.
            val straight = (currentKwh - previousKwh) * factor
            if (attestedWraps == null && wrapWasPossible(previousKwh, elapsedDays, capacityKwh, factor, plausibleDailyKwh)) {
                return Undecidable(
                    reason = Reason.WRAP_POSSIBLE,
                    candidates = wrapCandidates(previousKwh, currentKwh, elapsedDays, capacityKwh!!, factor, from = 0),
                )
            }
            if (attestedWraps != null && attestedWraps > 0 && capacityKwh != null) {
                val kwh = (attestedWraps.toLong() * capacityKwh + currentKwh - previousKwh) * factor
                return Measured(kwh.toInt(), attestedWraps, factor)
            }
            if (isRateImplausible(straight, elapsedDays, plausibleDailyKwh)) {
                return Undecidable(Reason.RATE_IMPLAUSIBLE)
            }
            return Measured(straight, wraps = 0, multiplier = factor)
        }

        /**
         * Whether the register could have reached capacity inside this interval at
         * the household's own pace. Unknown capacity or unknown pace means the
         * question cannot be asked honestly, so it is not asked.
         */
        private fun wrapWasPossible(
            previousKwh: Int,
            elapsedDays: Double,
            capacityKwh: Int?,
            multiplier: Int,
            plausibleDailyKwh: Double?,
        ): Boolean {
            if (capacityKwh == null || plausibleDailyKwh == null) return false
            if (elapsedDays < MIN_DAYS_FOR_RATE || plausibleDailyKwh <= 0.0) return false
            // The pace is in billed kWh; the register advances at pace / multiplier.
            val registerAdvance = plausibleDailyKwh * elapsedDays / multiplier
            return previousKwh + registerAdvance >= capacityKwh
        }

        private fun isRateImplausible(kwh: Int, elapsedDays: Double, plausibleDailyKwh: Double?): Boolean {
            if (plausibleDailyKwh == null || plausibleDailyKwh <= 0.0) return false
            if (elapsedDays < MIN_DAYS_FOR_RATE) return false
            return kwh / elapsedDays > plausibleDailyKwh * IMPLAUSIBLE_RATE_FACTOR
        }

        private fun wrapCandidates(
            previousKwh: Int,
            currentKwh: Int,
            elapsedDays: Double,
            capacityKwh: Int,
            multiplier: Int,
            from: Int,
        ): List<Candidate> = (from..MAX_WRAPS_OFFERED).map { wraps ->
            val kwh = ((wraps.toLong() * capacityKwh + currentKwh - previousKwh) * multiplier).toInt()
            Candidate(
                wraps = wraps,
                kwh = kwh,
                dailyKwh = if (elapsedDays >= MIN_DAYS_FOR_RATE) kwh / elapsedDays else 0.0,
            )
        }.filter { it.kwh >= 0 }

        /** Register capacity for a meter with [digits] faces, or null when unknown. */
        fun capacityFor(digits: Int?): Int? = when {
            digits == null || digits < 1 || digits > 9 -> null
            else -> generateSequence(1) { it * 10 }.elementAt(digits)
        }

        /** Rounds a daily rate the way the screens display it. */
        fun displayDaily(value: Double): Int = value.roundToInt()
    }
}
