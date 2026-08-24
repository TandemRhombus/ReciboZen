package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceUsageModel

/** One appliance worth unplugging overnight, with the standby it actually draws. */
data class NightUnplugItem(
    val applianceId: String,
    val name: String,
    /** Standby for every unit of this row together, i.e. already multiplied by quantity. */
    val standbyWatts: Int,
    val quantity: Int,
)

data class NightUnplugAdvice(
    /** Highest standby first, because that is the order worth acting in. */
    val items: List<NightUnplugItem>,
    val totalStandbyWatts: Int,
) {
    /**
     * Energy these appliances draw across one night, over the stated
     * [NightUnplugAdvisor.ASSUMED_NIGHT_HOURS]. The hours are an assumption --
     * the app does not know when the household wakes -- so any text using this
     * has to name them.
     */
    val nightlyKwh: Double
        get() = totalStandbyWatts * NightUnplugAdvisor.ASSUMED_NIGHT_HOURS / 1_000.0
}

/**
 * Which appliances are worth unplugging before bed.
 *
 * **The safety rule that shapes this: only appliances the household declared as
 * scheduled are ever named.** An appliance billed by period energy -- a fridge,
 * a router, a water pump, a medical device -- runs on its own cycle regardless
 * of the hour. Telling someone to unplug their fridge overnight is advice that
 * spoils food, and no amount of standby saving justifies it. The same
 * distinction [OvernightBaselineCalculator.baseWatts] uses to decide what power
 * the inventory justifies at night decides here what may be suggested, so the
 * two can never disagree about which appliances are asleep.
 *
 * Beyond that, an appliance is only named when the household **declared** its
 * standby and said it is actually left plugged in. A typical standby figure for
 * that model is not evidence about this house, and suggesting a saving nobody
 * established is how a nightly reminder becomes noise the user turns off.
 */
object NightUnplugAdvisor {

    /**
     * Hours a night is assumed to last, for turning standby watts into energy.
     * Deliberately conservative and deliberately visible: it is an assumption,
     * not a measurement, and it is named wherever the figure appears.
     */
    const val ASSUMED_NIGHT_HOURS = 8.0

    /** Below this the row is not worth a line in a notification. */
    private const val MIN_WORTHWHILE_WATTS = 1

    /**
     * @param hoursPerWeekOverrides the schedule the household edited, which is
     *   what decides whether an appliance counts as scheduled -- the same
     *   override the inventory itself applies.
     * @return null when nothing can be named, which is a real answer: a reminder
     *   listing nothing is a reminder worth disabling.
     */
    fun advise(
        appliances: List<Appliance>,
        hoursPerWeekOverrides: Map<String, Int> = emptyMap(),
    ): NightUnplugAdvice? {
        val items = appliances
            .distinctBy { it.id }
            .mapNotNull { appliance ->
                val hours = hoursPerWeekOverrides[appliance.id] ?: appliance.hoursPerWeek
                val isScheduled = appliance.usageModel == ApplianceUsageModel.POWER_SCHEDULE && hours != null
                if (!isScheduled) return@mapNotNull null
                if (!appliance.standbyEnabled) return@mapNotNull null
                val perUnit = appliance.standbyWatts?.takeIf { it > 0 } ?: return@mapNotNull null
                val quantity = appliance.quantity.coerceIn(1, 999)
                val total = perUnit * quantity
                if (total < MIN_WORTHWHILE_WATTS) return@mapNotNull null
                NightUnplugItem(
                    applianceId = appliance.id,
                    name = appliance.name,
                    standbyWatts = total,
                    quantity = quantity,
                )
            }
            .sortedByDescending { it.standbyWatts }
        if (items.isEmpty()) return null
        return NightUnplugAdvice(items = items, totalStandbyWatts = items.sumOf { it.standbyWatts })
    }
}
