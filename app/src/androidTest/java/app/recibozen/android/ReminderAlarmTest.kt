package app.recibozen.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.notifications.NightUnplugScheduler
import app.recibozen.android.core.notifications.NightUnplugSettings
import app.recibozen.android.core.notifications.NightUnplugStore
import app.recibozen.android.core.notifications.PremiumFeaturePolicy
import app.recibozen.android.core.notifications.ReadingReminderSettings
import app.recibozen.android.core.notifications.ReadingReminderStore
import app.recibozen.android.core.notifications.ReminderAlarms
import app.recibozen.android.core.notifications.ReminderFiring
import app.recibozen.android.core.notifications.ReminderKind
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the reminders are armed with the operating system, and that firing one
 * arms the next.
 *
 * These have to run on a device. The defect they exist to prevent is invisible
 * to a JVM test and invisible in the UI: reminders scheduled as WorkManager jobs
 * looked scheduled, and arrived when the household next opened the app instead
 * of at the hour they chose. [ReminderAlarms.isArmed] asks the OS, which is the
 * only witness that can tell those two apart.
 *
 * The tier is passed explicitly rather than read from the store so these keep
 * testing the alarms when [app.recibozen.android.core.model.Paywall.ENFORCED]
 * flips.
 */
@RunWith(AndroidJUnit4::class)
class ReminderAlarmTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tier = EntitlementTier.PLUS

    @Before
    fun clearAlarms() = tearDown()

    @After
    fun tearDown() {
        ReminderKind.entries.forEach { ReminderAlarms.cancel(context, it) }
        ReadingReminderStore(context).save(ReadingReminderSettings(enabled = false))
        NightUnplugStore(context).save(NightUnplugSettings(enabled = false))
    }

    @Test
    fun enablingTheNightReminderArmsAnAlarmWithTheSystem() {
        assertFalse(ReminderAlarms.isArmed(context, ReminderKind.NIGHT))

        NightUnplugScheduler.apply(
            context = context,
            settings = NightUnplugSettings(enabled = true, hour = 22),
            tier = tier,
        )

        assertTrue(
            "the nightly reminder must be armed with AlarmManager, not merely saved",
            ReminderAlarms.isArmed(context, ReminderKind.NIGHT),
        )
    }

    @Test
    fun turningTheNightReminderOffTakesTheAlarmBackDown() {
        NightUnplugScheduler.apply(context, NightUnplugSettings(enabled = true, hour = 22), tier)

        NightUnplugScheduler.apply(context, NightUnplugSettings(enabled = false, hour = 22), tier)

        assertFalse(ReminderAlarms.isArmed(context, ReminderKind.NIGHT))
    }

    @Test
    fun theAlarmFollowsTheEntitlementGateAndNotTheSettingAlone() {
        // The setting on with no entitlement is what a lapsed subscription looks
        // like. Whether that may still be armed is Paywall's call, not this
        // scheduler's, so the assertion tracks the policy instead of restating
        // today's value of Paywall.ENFORCED.
        NightUnplugScheduler.apply(
            context = context,
            settings = NightUnplugSettings(enabled = true, hour = 22),
            tier = EntitlementTier.FREE,
            now = LocalDateTime.of(2026, 8, 23, 19, 30),
        )

        assertEquals(
            PremiumFeaturePolicy.notificationsAllowed(EntitlementTier.FREE),
            ReminderAlarms.isArmed(context, ReminderKind.NIGHT),
        )
    }

    @Test
    fun firingTheNightReminderArmsTheNextNight() {
        NightUnplugStore(context).save(NightUnplugSettings(enabled = true, hour = 22))
        // Nothing armed: whatever exists after this call was put there by the
        // firing itself, not left over from scheduling.
        assertFalse(ReminderAlarms.isArmed(context, ReminderKind.NIGHT))

        runBlocking {
            ReminderFiring.fireAndRearm(
                context = context,
                kind = ReminderKind.NIGHT,
                now = LocalDateTime.of(2026, 8, 23, 22, 0),
                tier = tier,
            )
        }

        assertTrue(
            "a one-shot alarm that does not arm the next one fires once and dies",
            ReminderAlarms.isArmed(context, ReminderKind.NIGHT),
        )
    }

    @Test
    fun firingTheReadingReminderArmsTheNextOne() {
        ReadingReminderStore(context).save(ReadingReminderSettings(enabled = true, intervalDays = 3))
        assertFalse(ReminderAlarms.isArmed(context, ReminderKind.READING))

        runBlocking {
            ReminderFiring.fireAndRearm(
                context = context,
                kind = ReminderKind.READING,
                now = LocalDateTime.of(2026, 8, 23, 10, 0),
                tier = tier,
            )
        }

        assertTrue(ReminderAlarms.isArmed(context, ReminderKind.READING))
    }

    @Test
    fun firingWithTheReminderTurnedOffLeavesNothingArmed() {
        ReadingReminderStore(context).save(ReadingReminderSettings(enabled = false))

        runBlocking {
            ReminderFiring.fireAndRearm(
                context = context,
                kind = ReminderKind.READING,
                now = LocalDateTime.of(2026, 8, 23, 10, 0),
                tier = tier,
            )
        }

        assertFalse(ReminderAlarms.isArmed(context, ReminderKind.READING))
    }

    @Test
    fun aFiringThatCouldNotBeDeliveredDoesNotAdvanceTheInterval() {
        // Instrumentation does not hold POST_NOTIFICATIONS, so nothing goes out.
        // Recording that day as "fired" would push the next reminder three days
        // away for a notification the household never saw.
        val store = ReadingReminderStore(context)
        store.save(ReadingReminderSettings(enabled = true, intervalDays = 3))
        val today = LocalDate.of(2026, 8, 23)

        runBlocking {
            ReminderFiring.fireAndRearm(
                context = context,
                kind = ReminderKind.READING,
                now = today.atTime(10, 0),
                tier = tier,
            )
        }

        val posted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        assertEquals(
            if (posted) today.toEpochDay() else null,
            store.load().lastFiredEpochDay,
        )
    }
}
