package app.recibozen.android.core.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.security.PremiumAccessStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Why these reminders are alarms and not WorkManager jobs.
 *
 * They used to be periodic work requests, and they arrived when the household
 * opened the app instead of at the hour they asked for. Two documented pieces of
 * WorkManager behaviour make that inevitable, and neither is something we could
 * fix on our side:
 *
 * 1. A periodic request with no explicit flex has a run window as wide as its
 *    own period. setInitialDelay anchors the FIRST run and nothing else, so a
 *    22:00 reminder was free to land anywhere in the following 24 hours.
 * 2. WorkManager work is deferrable by contract: it does not run in Doze. A
 *    phone that has been still since 21:00 is dozing at 22:00, so the work waits
 *    for a maintenance window -- or for the screen to come on.
 *
 * setAndAllowWhileIdle is the API for the opposite requirement: it fires during
 * Doze and needs no permission. We deliberately do NOT use
 * setExactAndAllowWhileIdle: that one needs SCHEDULE_EXACT_ALARM, and on Android
 * 14 USE_EXACT_ALARM is reserved for alarm clocks and calendars. A bedtime nudge
 * does not need to-the-second precision, and claiming otherwise on Play is a
 * rejection waiting to happen.
 *
 * The price of one-shot alarms is that every firing has to arm the next one, and
 * that a reboot forgets them all. Both are covered -- [ReminderFiring.fireAndRearm]
 * and [ReminderBootReceiver] -- and both fail silently when they break, so both
 * are tested.
 *
 * The other price is precision. Measured on an emulator in forced deep Doze: an
 * alarm armed from the foreground got a 51-second window, and the one it armed
 * for itself from inside Doze got an hour. That is the trade being made -- an
 * hour late beats not arriving until the app is next opened -- and the settings
 * screen says so instead of promising a minute it cannot keep.
 */
enum class ReminderKind {
    READING,
    NIGHT,
    ;

    /** App-private: the alarm targets our own receiver by class, not by action. */
    val action: String get() = "app.recibozen.android.action.REMINDER_$name"

    val requestCode: Int
        get() = when (this) {
            READING -> 4101
            NIGHT -> 4102
        }
}

/**
 * When each reminder is next due. Pure, so the answer can be checked without a
 * device -- getting this wrong either fires a notification the instant someone
 * saves the setting or skips a day without leaving a trace.
 */
object ReminderSchedule {
    /**
     * A meter reading is not tied to an hour the way a bedtime nudge is, but it
     * still cannot land at 03:00 just because that is when the household enabled
     * it. Mid-morning is late enough that nobody is asleep and early enough that
     * there is a day left to act on it.
     */
    const val READING_HOUR = 10

    fun nextNight(target: LocalTime, now: LocalDateTime): LocalDateTime {
        val todayAtTarget = now.toLocalDate().atTime(target)
        return if (todayAtTarget.isAfter(now)) todayAtTarget else todayAtTarget.plusDays(1)
    }

    /**
     * @param lastFiredEpochDay the day the reminder last went out, or null when
     * it never has -- in which case the interval is counted from today.
     *
     * A phone that was off for two weeks comes back with a due date long past.
     * That is not a reason to fire the second it boots: the reminder rolls
     * forward to the next [READING_HOUR] instead.
     */
    fun nextReading(intervalDays: Int, lastFiredEpochDay: Long?, now: LocalDateTime): LocalDateTime {
        val anchor = LocalTime.of(READING_HOUR, 0)
        val since = lastFiredEpochDay?.let(LocalDate::ofEpochDay) ?: now.toLocalDate()
        val due = since.plusDays(intervalDays.toLong()).atTime(anchor)
        if (due.isAfter(now)) return due
        val todayAtAnchor = now.toLocalDate().atTime(anchor)
        return if (todayAtAnchor.isAfter(now)) todayAtAnchor else todayAtAnchor.plusDays(1)
    }
}

/** The only place that talks to AlarmManager. */
object ReminderAlarms {

    fun arm(context: Context, kind: ReminderKind, at: LocalDateTime) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = broadcast(context, kind, allowCreate = true) ?: return
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
    }

    fun cancel(context: Context, kind: ReminderKind) {
        val intent = broadcast(context, kind, allowCreate = false) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(intent)
        intent.cancel()
    }

    /**
     * Whether the OS is currently holding an alarm for [kind].
     *
     * FLAG_NO_CREATE answers that without side effects, and it is the only
     * assertion available to a test that the reminder is armed with the system
     * rather than merely written to preferences.
     */
    fun isArmed(context: Context, kind: ReminderKind): Boolean =
        broadcast(context, kind, allowCreate = false) != null

    private fun broadcast(context: Context, kind: ReminderKind, allowCreate: Boolean): PendingIntent? {
        val appContext = context.applicationContext
        val intent = Intent(appContext, ReminderAlarmReceiver::class.java).setAction(kind.action)
        var flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        if (!allowCreate) flags = flags or PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(appContext, kind.requestCode, intent, flags)
    }
}

/**
 * Posts a reminder and arms the next one.
 *
 * Separate from [ReminderAlarmReceiver] on purpose: a test can call this
 * directly and then ask [ReminderAlarms.isArmed] whether tomorrow's alarm
 * exists. Through the receiver it could only send a broadcast and hope.
 */
object ReminderFiring {

    suspend fun fireAndRearm(
        context: Context,
        kind: ReminderKind,
        now: LocalDateTime = LocalDateTime.now(),
        tier: EntitlementTier = PremiumAccessStore(context.applicationContext).effectiveTier(),
    ) {
        val appContext = context.applicationContext
        when (kind) {
            ReminderKind.READING -> {
                val store = ReadingReminderStore(appContext)
                if (!store.load().enabled || !PremiumFeaturePolicy.notificationsAllowed(tier)) {
                    ReminderAlarms.cancel(appContext, kind)
                    return
                }
                if (postReadingReminder(appContext)) {
                    store.recordFired(now.toLocalDate().toEpochDay())
                }
                // Re-read, so the next alarm is anchored to the firing that just
                // happened instead of to the value loaded before it.
                ReadingReminderScheduler.apply(appContext, store.load(), tier, now)
            }

            ReminderKind.NIGHT -> {
                val store = NightUnplugStore(appContext)
                val settings = store.load()
                if (!settings.enabled || !PremiumFeaturePolicy.notificationsAllowed(tier)) {
                    ReminderAlarms.cancel(appContext, kind)
                    return
                }
                postNightUnplugReminder(appContext)
                NightUnplugScheduler.apply(appContext, settings, tier, now)
            }
        }
    }
}

/**
 * Re-arms everything from the stores.
 *
 * Called on boot, on an app update, on a timezone change, and once when the app
 * starts. That last one is a safety net, not the mechanism: if it were the
 * mechanism we would be back to reminders that only arrive when the app is
 * opened, which is the defect this file exists to fix.
 */
object ReminderRearm {

    /**
     * Both reminders, together. Callers that hold a tier pass it: every place
     * that changes the entitlement has to re-apply BOTH reminders, and the
     * version of this that took only the reading one left a lapsed subscription
     * still receiving the nightly nudge.
     */
    fun applyAll(context: Context, tier: EntitlementTier, now: LocalDateTime = LocalDateTime.now()) {
        val appContext = context.applicationContext
        ReadingReminderScheduler.apply(appContext, ReadingReminderStore(appContext).load(), tier, now)
        NightUnplugScheduler.apply(appContext, NightUnplugStore(appContext).load(), tier, now)
    }

    fun applyAll(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val appContext = context.applicationContext
        applyAll(appContext, PremiumAccessStore(appContext).effectiveTier(), now)
    }

    /**
     * Versions up to 1.1.0 scheduled these as periodic work. Those requests
     * survive an update inside WorkManager's own database and their worker
     * classes no longer exist, so they would sit there failing until something
     * cleared them. This clears them.
     *
     * Deliberately not part of [applyAll]: the boot receiver calls that one, and
     * initialising WorkManager during boot to cancel work that is already inert
     * is cost for nothing. The app opening is soon enough.
     */
    fun cancelLegacyPeriodicWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LEGACY_READING_WORK)
        workManager.cancelUniqueWork(LEGACY_NIGHT_WORK)
    }

    private const val LEGACY_READING_WORK = "premium-reading-reminder-v1"
    private const val LEGACY_NIGHT_WORK = "night-unplug-reminder-v1"
}

/** Receives the alarm. Delegates immediately; holds no logic worth testing. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = ReminderKind.entries.firstOrNull { it.action == intent.action } ?: return
        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderFiring.fireAndRearm(appContext, kind)
            } finally {
                result.finish()
            }
        }
    }
}

/** Alarms do not survive a reboot, a reinstall, or a jump in the clock. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> ReminderRearm.applyAll(context)

            else -> Unit
        }
    }
}
