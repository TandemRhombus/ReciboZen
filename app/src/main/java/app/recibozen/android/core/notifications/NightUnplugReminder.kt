package app.recibozen.android.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import app.recibozen.android.R
import app.recibozen.android.core.calculation.NightUnplugAdvice
import app.recibozen.android.core.calculation.NightUnplugAdvisor
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.data.repository.readStoredSession
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A nightly nudge to unplug the appliances the household leaves in standby.
 *
 * Separate from [ReadingReminderSettings] on purpose: that one repeats every N
 * days and only needs to land at a civilised hour, because a meter reading is
 * not tied to a moment. This one is tied to an hour and to nothing else --
 * "before I go to bed" is the whole point, and a reminder that lands at noon is
 * worse than none.
 *
 * That requirement is also why neither of them is WorkManager work any more; see
 * [ReminderAlarms].
 */
data class NightUnplugSettings(
    val enabled: Boolean = false,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = 0,
) {
    val time: LocalTime get() = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    companion object {
        const val DEFAULT_HOUR = 22

        /** The hours a "before bed" reminder can sensibly land on. */
        val SUPPORTED_HOURS = listOf(20, 21, 22, 23, 0)
    }
}

class NightUnplugStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): NightUnplugSettings = NightUnplugSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        hour = preferences.getInt(KEY_HOUR, NightUnplugSettings.DEFAULT_HOUR).coerceIn(0, 23),
        minute = preferences.getInt(KEY_MINUTE, 0).coerceIn(0, 59),
    )

    /**
     * Written with commit(), not apply(). These settings are read from a
     * different process instance than the one that wrote them -- the alarm
     * receiver is started cold, hours later -- so an asynchronous write that has
     * not reached disk yet reads back as "disabled" and the reminder cancels
     * itself. Observed: an emulator run where the writing process was killed
     * right after the toggle and the alarm fired to find the setting off.
     */
    fun save(settings: NightUnplugSettings) {
        preferences.edit(commit = true) {
            putBoolean(KEY_ENABLED, settings.enabled)
            putInt(KEY_HOUR, settings.hour.coerceIn(0, 23))
            putInt(KEY_MINUTE, settings.minute.coerceIn(0, 59))
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "recibozen_night_unplug"
        const val KEY_ENABLED = "enabled_v1"
        const val KEY_HOUR = "hour_v1"
        const val KEY_MINUTE = "minute_v1"
    }
}

object NightUnplugScheduler {

    fun apply(
        context: Context,
        settings: NightUnplugSettings,
        tier: EntitlementTier,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        if (!settings.enabled || !PremiumFeaturePolicy.notificationsAllowed(tier)) {
            ReminderAlarms.cancel(context, ReminderKind.NIGHT)
            return
        }
        // Arming replaces whatever alarm was already there for this kind, so
        // changing the hour re-anchors the schedule instead of leaving the old
        // one to fire once more before it lapses.
        ReminderAlarms.arm(
            context = context,
            kind = ReminderKind.NIGHT,
            at = ReminderSchedule.nextNight(settings.time, now),
        )
    }
}

/**
 * The notification itself. Called from [ReminderFiring], which owns the decision
 * of whether it should go out at all.
 */
internal suspend fun postNightUnplugReminder(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    // A broadcast receiver has seconds, not minutes. If the inventory cannot be
    // read in time the reminder still goes out with the generic wording it
    // already has for households with no inventory -- better than a receiver
    // that never finishes, which is an ANR.
    val session = withTimeoutOrNull(SESSION_READ_TIMEOUT_MS) {
        runCatching { readStoredSession(context) }.getOrNull()
    }
    val advice = session?.let {
        NightUnplugAdvisor.advise(it.customAppliances, it.applianceHoursPerWeek)
    }
    notifyNightUnplug(
        context = context,
        title = context.getString(R.string.notifications_night_title),
        body = bodyFor(context, advice),
    )
}

/**
 * With an inventory the reminder names the appliances and what they draw,
 * because that is what makes anyone actually get up and unplug something.
 * Without one it still fires -- the household asked to be reminded -- but it
 * says plainly that it has nothing specific yet instead of inventing a saving or
 * listing appliances nobody registered.
 */
private fun bodyFor(context: Context, advice: NightUnplugAdvice?): String {
    if (advice == null) return context.getString(R.string.notifications_night_body_generic)
    val named = advice.items.take(MAX_NAMED)
    val list = named.joinToString(", ") {
        context.getString(R.string.notifications_night_item, it.name, it.standbyWatts)
    }
    val remaining = advice.items.size - named.size
    val listWithTail = if (remaining > 0) {
        context.resources.getQuantityString(
            R.plurals.notifications_night_more,
            remaining,
            list,
            remaining,
        )
    } else {
        list
    }
    return context.getString(
        R.string.notifications_night_body,
        listWithTail,
        advice.totalStandbyWatts,
        NightUnplugAdvisor.ASSUMED_NIGHT_HOURS.toInt(),
        String.format(java.util.Locale.forLanguageTag("es-MX"), "%.2f", advice.nightlyKwh),
    )
}

private fun notifyNightUnplug(context: Context, title: String, body: String) {
    // Re-checked here and not only by the caller. The guard and the post used to
    // sit in different functions, which is exactly the shape a caller added
    // later would get wrong -- and which lint flags, correctly.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notifications_channel_night),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notifications_channel_night_description)
        },
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(title)
        .setContentText(body)
        // The list of appliances does not fit on one line, and truncating it to
        // "Tele, Cons..." removes the only part worth reading.
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            },
        )
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
}

private const val CHANNEL_ID = "night_unplug_reminders"
private const val NOTIFICATION_ID = 3102
private const val MAX_NAMED = 3
private const val SESSION_READ_TIMEOUT_MS = 5_000L
