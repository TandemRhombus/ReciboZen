package app.recibozen.android

import app.recibozen.android.core.calculation.NightUnplugAdvisor
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.notifications.ReminderSchedule
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the nightly reminder is allowed to tell a household to unplug.
 *
 * The rule these tests exist to protect: an appliance billed by period energy
 * runs on its own cycle regardless of the hour, and must never be suggested.
 * Telling someone to unplug their fridge overnight is advice that spoils food.
 */
class NightUnplugAdviceTest {

    private fun appliance(
        id: String,
        name: String,
        usageModel: ApplianceUsageModel,
        hoursPerWeek: Int?,
        standbyWatts: Int?,
        standbyEnabled: Boolean,
        quantity: Int = 1,
    ) = Appliance(
        id = id,
        name = name,
        room = "",
        costMxn = 0,
        kwh = 10,
        sharePercent = 0,
        hoursPerWeek = hoursPerWeek,
        confidence = ConfidenceLevel.MEDIUM,
        usageModel = usageModel,
        powerWatts = 100,
        standbyWatts = standbyWatts,
        standbyEnabled = standbyEnabled,
        quantity = quantity,
        unitKwh = 10.0,
        referencePeriodDays = 30,
    )

    private val television = appliance(
        "tv", "Televisión", ApplianceUsageModel.POWER_SCHEDULE,
        hoursPerWeek = 28, standbyWatts = 10, standbyEnabled = true,
    )

    private val fridge = appliance(
        "fridge", "Refrigerador", ApplianceUsageModel.PERIOD_KWH,
        hoursPerWeek = null, standbyWatts = 40, standbyEnabled = true,
    )

    // --- The safety rule ---

    @Test
    fun anAppliancePricedByPeriodEnergyIsNeverSuggestedForUnplugging() {
        // The fridge here even declares 40 W of "standby" and says it is
        // enabled. It is still not scheduled, so it is still not asleep, and
        // unplugging it overnight ruins the food inside.
        val advice = NightUnplugAdvisor.advise(listOf(fridge, television))

        assertNotNull(advice)
        assertEquals(listOf("Televisión"), advice!!.items.map { it.name })
    }

    @Test
    fun anInventoryOfOnlyAlwaysOnAppliancesProducesNoAdviceAtAll() {
        // Not an empty list of names with a reminder that still fires: null, so
        // the caller can decide to say nothing specific rather than imply there
        // is something to unplug when there is not.
        assertNull(NightUnplugAdvisor.advise(listOf(fridge)))
    }

    // --- Only what the household actually declared ---

    @Test
    fun anApplianceTheHouseholdSaidItDoesNotLeavePluggedInIsNotNamed() {
        val unplugged = television.copy(standbyEnabled = false)

        assertNull(NightUnplugAdvisor.advise(listOf(unplugged)))
    }

    @Test
    fun anApplianceWithNoDeclaredStandbyIsNotGivenATypicalOne() {
        // A typical figure for that model is not evidence about this house.
        val unknownStandby = television.copy(standbyWatts = null)

        assertNull(NightUnplugAdvisor.advise(listOf(unknownStandby)))
    }

    @Test
    fun aScheduleTheHouseholdEditedDecidesWhetherTheApplianceCountsAsScheduled() {
        // hoursPerWeek null on the row, but the household set a schedule in the
        // inventory. The override is the answer, same as everywhere else.
        val noStoredHours = television.copy(hoursPerWeek = null)

        assertNull(NightUnplugAdvisor.advise(listOf(noStoredHours)))
        assertNotNull(NightUnplugAdvisor.advise(listOf(noStoredHours), mapOf("tv" to 20)))
    }

    // --- The figures ---

    @Test
    fun standbyIsCountedForEveryUnitOfTheRow() {
        val threeConsoles = appliance(
            "console", "Consola", ApplianceUsageModel.POWER_SCHEDULE,
            hoursPerWeek = 10, standbyWatts = 12, standbyEnabled = true, quantity = 3,
        )

        val advice = NightUnplugAdvisor.advise(listOf(threeConsoles))!!

        assertEquals(36, advice.items.single().standbyWatts)
        assertEquals(36, advice.totalStandbyWatts)
    }

    @Test
    fun theBiggestDrawIsNamedFirstBecauseThatIsTheOrderWorthActingIn() {
        val console = appliance(
            "console", "Consola", ApplianceUsageModel.POWER_SCHEDULE,
            hoursPerWeek = 10, standbyWatts = 25, standbyEnabled = true,
        )

        val advice = NightUnplugAdvisor.advise(listOf(television, console))!!

        assertEquals(listOf("Consola", "Televisión"), advice.items.map { it.name })
        assertEquals(35, advice.totalStandbyWatts)
    }

    @Test
    fun theNightlyEnergyRestsOnTheStatedNumberOfHours() {
        val advice = NightUnplugAdvisor.advise(listOf(television))!!

        // 10 W over the assumed 8-hour night. The hours are an assumption and
        // every string that shows this figure names them.
        assertEquals(10 * NightUnplugAdvisor.ASSUMED_NIGHT_HOURS / 1_000.0, advice.nightlyKwh, 0.0001)
    }

    // --- When the reminder fires ---

    @Test
    fun anHourStillToComeTodaySchedulesForTonight() {
        val next = ReminderSchedule.nextNight(
            target = LocalTime.of(22, 0),
            now = LocalDateTime.of(2026, 8, 23, 19, 30),
        )

        assertEquals(LocalDateTime.of(2026, 8, 23, 22, 0), next)
    }

    @Test
    fun anHourAlreadyPastBelongsToTomorrowInsteadOfFiringImmediately() {
        val next = ReminderSchedule.nextNight(
            target = LocalTime.of(22, 0),
            now = LocalDateTime.of(2026, 8, 23, 23, 15),
        )

        // Saving the setting at 23:15 for a 22:00 reminder must not pop a
        // notification the same second, nor skip the whole next day.
        assertEquals(LocalDateTime.of(2026, 8, 24, 22, 0), next)
    }

    @Test
    fun theExactMinuteCountsAsPastSoTheReminderDoesNotDoubleFire() {
        val next = ReminderSchedule.nextNight(
            target = LocalTime.of(22, 0),
            now = LocalDateTime.of(2026, 8, 23, 22, 0),
        )

        assertEquals(LocalDateTime.of(2026, 8, 24, 22, 0), next)
    }
}
