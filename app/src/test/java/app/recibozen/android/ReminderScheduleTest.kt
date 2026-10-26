package app.recibozen.android

import app.recibozen.android.core.notifications.ReminderSchedule
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the reading reminder is next due.
 *
 * The nightly reminder's own cases live in [NightUnplugAdviceTest], next to the
 * advice it carries.
 */
class ReminderScheduleTest {

    private fun epochDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).toEpochDay()

    @Test
    fun theFirstReminderIsCountedFromTheDayItWasEnabled() {
        val next = ReminderSchedule.nextReading(
            intervalDays = 3,
            lastFiredEpochDay = null,
            now = LocalDateTime.of(2026, 8, 23, 19, 30),
        )

        assertEquals(LocalDateTime.of(2026, 8, 26, ReminderSchedule.READING_HOUR, 0), next)
    }

    @Test
    fun theIntervalIsCountedFromTheLastReminderThatActuallyWentOut() {
        // Enabled long ago, fired yesterday: the next one is three days after
        // the firing, not three days after today.
        val next = ReminderSchedule.nextReading(
            intervalDays = 3,
            lastFiredEpochDay = epochDay(2026, 8, 22),
            now = LocalDateTime.of(2026, 8, 23, 19, 30),
        )

        assertEquals(LocalDateTime.of(2026, 8, 25, ReminderSchedule.READING_HOUR, 0), next)
    }

    @Test
    fun aReminderLongOverdueRollsForwardInsteadOfFiringOnTheSpot() {
        // The phone was off for two weeks. Waking it must not greet the
        // household with a notification the instant the lock screen appears --
        // which is the exact complaint the alarms were built to fix.
        val now = LocalDateTime.of(2026, 8, 23, 19, 30)
        val next = ReminderSchedule.nextReading(
            intervalDays = 3,
            lastFiredEpochDay = epochDay(2026, 8, 1),
            now = now,
        )

        assertTrue("must not be scheduled in the past", next.isAfter(now))
        assertEquals(LocalDateTime.of(2026, 8, 24, ReminderSchedule.READING_HOUR, 0), next)
    }

    @Test
    fun anOverdueReminderStillCatchesTodayWhenTheAnchorHourHasNotPassed() {
        val next = ReminderSchedule.nextReading(
            intervalDays = 7,
            lastFiredEpochDay = epochDay(2026, 8, 1),
            now = LocalDateTime.of(2026, 8, 23, 6, 15),
        )

        assertEquals(LocalDateTime.of(2026, 8, 23, ReminderSchedule.READING_HOUR, 0), next)
    }

    @Test
    fun theSevenDayIntervalIsHonouredAndNotRoundedToTheDefault() {
        val next = ReminderSchedule.nextReading(
            intervalDays = 7,
            lastFiredEpochDay = epochDay(2026, 8, 22),
            now = LocalDateTime.of(2026, 8, 23, 19, 30),
        )

        assertEquals(LocalDateTime.of(2026, 8, 29, ReminderSchedule.READING_HOUR, 0), next)
    }

    @Test
    fun theReminderNeverLandsWhileTheHouseholdIsAsleep() {
        // Whatever the inputs, a reading reminder resolves to the anchor hour.
        // A reminder at 03:00 gets the notification turned off, not the reading
        // taken.
        val candidates = listOf<Long?>(null, epochDay(2026, 1, 1), epochDay(2026, 8, 22))
        val hours = listOf(0, 3, 9, 10, 11, 23)

        for (last in candidates) {
            for (hour in hours) {
                val next = ReminderSchedule.nextReading(
                    intervalDays = 3,
                    lastFiredEpochDay = last,
                    now = LocalDateTime.of(2026, 8, 23, hour, 30),
                )
                assertEquals(ReminderSchedule.READING_HOUR, next.hour)
                assertEquals(0, next.minute)
            }
        }
    }
}
