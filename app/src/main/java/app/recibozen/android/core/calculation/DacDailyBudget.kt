package app.recibozen.android.core.calculation

/**
 * How many kWh a day the household can still use for the rest of the billing
 * period without pushing its rolling year past the tariff's high-consumption
 * limit -- and, for a household already in DAC, without staying above it.
 *
 * [DacRiskCalculator] already produces the period ceiling. That figure is a
 * bimester total, which is not something anyone can act on: nobody rations by
 * the bimester. Turning it into a daily rate takes two facts the rolling-year
 * rule knows nothing about -- what this period has already consumed, and how
 * many days are left to spread the rest over -- so the arithmetic lives here
 * rather than inside the rule.
 *
 * The ceiling is the same one in both directions. A household under the limit
 * is pacing so as not to cross it; a household already in DAC is pacing so the
 * rolling average drops back under it. Only the wording differs, which is why
 * [isExitBudget] travels with the result instead of the screen inferring it.
 */
sealed interface DacDailyBudget {

    /** True when the ceiling is the one that gets the household out of DAC. */
    val isExitBudget: Boolean

    /**
     * There is budget left and days to spend it over.
     *
     * [dailyKwh] is deliberately not rounded here: at two days remaining the
     * difference between 7.5 and 8 is a quarter of the remaining allowance, and
     * the screen is better placed to decide how to present it.
     */
    data class Available(
        val dailyKwh: Double,
        val remainingKwh: Int,
        val daysRemaining: Int,
        val periodCeilingKwh: Int,
        val consumedKwh: Int,
        override val isExitBudget: Boolean,
        /**
         * False when [consumedKwh] does not come from meter readings taken in
         * this period. The budget is then an upper bound on an unknown, and the
         * screen has to say so rather than present it as a measured allowance.
         */
        val basedOnMeasuredConsumption: Boolean,
    ) : DacDailyBudget

    /**
     * The period's ceiling is already spent. There is no daily allowance to
     * report, and reporting zero would read as "use nothing and you are fine",
     * which is not true: the overshoot has already happened.
     */
    data class Exhausted(
        val overshootKwh: Int,
        val periodCeilingKwh: Int,
        val consumedKwh: Int,
        val daysRemaining: Int,
        override val isExitBudget: Boolean,
        val basedOnMeasuredConsumption: Boolean,
    ) : DacDailyBudget

    /** No figure can be stated. [reason] names the fact that is missing. */
    data class Unavailable(val reason: Reason, override val isExitBudget: Boolean = false) : DacDailyBudget

    enum class Reason {
        /** The tariff has no high-consumption limit, so there is no DAC to pace against. */
        NO_LIMIT,

        /** Fewer than a substantially complete billed year: a ceiling here would be a guess. */
        INCOMPLETE_HISTORY,

        /** The length of the period in progress is unknown, so it has no ceiling yet. */
        PERIOD_LENGTH_UNKNOWN,

        /** The period has no days left; what is left to decide belongs to the next one. */
        PERIOD_CLOSED,
    }

    companion object {

        /**
         * @param snapshot the audited rolling-year verdict.
         * @param consumedKwh energy already used in the period in progress.
         * @param daysRemaining days left before the period closes.
         * @param consumptionIsMeasured whether [consumedKwh] rests on meter
         *   readings taken during this period rather than on a projection or an
         *   older bill.
         */
        fun of(
            snapshot: DacRiskSnapshot,
            consumedKwh: Int,
            daysRemaining: Int,
            consumptionIsMeasured: Boolean,
        ): DacDailyBudget {
            val isExit = snapshot.level == DacRiskLevel.ALREADY_DAC
            if (snapshot.level == DacRiskLevel.UNSUPPORTED || snapshot.monthlyLimitKwh == null) {
                return Unavailable(Reason.NO_LIMIT, isExit)
            }
            if (!snapshot.hasCompleteHistory) {
                return Unavailable(Reason.INCOMPLETE_HISTORY, isExit)
            }
            val ceiling = snapshot.recommendedNextBillMaxKwh
                ?: return Unavailable(Reason.PERIOD_LENGTH_UNKNOWN, isExit)

            val consumed = consumedKwh.coerceAtLeast(0)
            val remaining = ceiling - consumed
            // Exhausted is checked before the calendar on purpose. A household
            // that has already crossed its ceiling has crossed it whether or not
            // days remain, and saying "0 kWh/día" would frame a past overshoot
            // as a future restriction they can still comply with.
            if (remaining <= 0) {
                return Exhausted(
                    overshootKwh = consumed - ceiling,
                    periodCeilingKwh = ceiling,
                    consumedKwh = consumed,
                    daysRemaining = daysRemaining.coerceAtLeast(0),
                    isExitBudget = isExit,
                    basedOnMeasuredConsumption = consumptionIsMeasured,
                )
            }
            if (daysRemaining <= 0) return Unavailable(Reason.PERIOD_CLOSED, isExit)

            return Available(
                dailyKwh = remaining.toDouble() / daysRemaining,
                remainingKwh = remaining,
                daysRemaining = daysRemaining,
                periodCeilingKwh = ceiling,
                consumedKwh = consumed,
                isExitBudget = isExit,
                basedOnMeasuredConsumption = consumptionIsMeasured,
            )
        }
    }
}
