package app.recibozen.android.core.calculation

enum class ConsumptionPaceRelation { BELOW_RECOMMENDED, ON_RECOMMENDED, ABOVE_RECOMMENDED }

data class ConsumptionPaceGoal(
    val goalKwh: Int = 0,
    val remainingKwh: Int = 0,
    val recommendedDailyKwh: Double = 0.0,
)

object ConsumptionPaceCalculator {
    private const val MAX_REASONABLE_GOAL_KWH = 100_000
    private const val DISPLAY_TOLERANCE_KWH = 0.05

    /**
     * Converts the monetary goal with the active tariff engine, then spreads
     * only the still-available energy across the days that remain.
     */
    fun goal(
        goalMxn: Int,
        observedKwh: Int,
        daysRemaining: Int,
        estimateCostMxn: (Int) -> Int,
    ): ConsumptionPaceGoal {
        if (goalMxn <= 0) return ConsumptionPaceGoal()

        val goalKwh = maxKwhAtOrBelow(goalMxn, estimateCostMxn)
        val remainingKwh = (goalKwh - observedKwh).coerceAtLeast(0)
        val recommendedDailyKwh = if (daysRemaining > 0) {
            remainingKwh.toDouble() / daysRemaining
        } else 0.0
        return ConsumptionPaceGoal(goalKwh, remainingKwh, recommendedDailyKwh)
    }

    fun relation(nowKwh: Double, recommendedKwh: Double): ConsumptionPaceRelation = when {
        nowKwh > recommendedKwh + DISPLAY_TOLERANCE_KWH -> ConsumptionPaceRelation.ABOVE_RECOMMENDED
        nowKwh < recommendedKwh - DISPLAY_TOLERANCE_KWH -> ConsumptionPaceRelation.BELOW_RECOMMENDED
        else -> ConsumptionPaceRelation.ON_RECOMMENDED
    }

    private fun maxKwhAtOrBelow(goalMxn: Int, estimateCostMxn: (Int) -> Int): Int {
        var high = 1
        while (high < MAX_REASONABLE_GOAL_KWH && estimateCostMxn(high) <= goalMxn) {
            high = (high * 2).coerceAtMost(MAX_REASONABLE_GOAL_KWH)
        }
        if (high == MAX_REASONABLE_GOAL_KWH && estimateCostMxn(high) <= goalMxn) return high

        var low = 0
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (estimateCostMxn(middle) <= goalMxn) low = middle else high = middle - 1
        }
        return low
    }
}
