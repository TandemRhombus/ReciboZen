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
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.model.Paywall
import java.time.LocalDateTime

data class ReadingReminderSettings(
    val enabled: Boolean = false,
    val intervalDays: Int = DEFAULT_INTERVAL_DAYS,
    /** The day this reminder last went out, so the interval counts from it. */
    val lastFiredEpochDay: Long? = null,
) {
    companion object {
        const val DEFAULT_INTERVAL_DAYS = 3
        val SUPPORTED_INTERVALS = setOf(3, 7)
    }
}

object PremiumFeaturePolicy {
    /** Resolves through [Paywall], so the gate lifts everywhere at once. */
    fun notificationsAllowed(tier: EntitlementTier, enforced: Boolean = Paywall.ENFORCED): Boolean =
        Paywall.effective(tier, enforced) != EntitlementTier.FREE
}

class ReadingReminderStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ReadingReminderSettings = ReadingReminderSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        intervalDays = preferences.getInt(KEY_INTERVAL_DAYS, ReadingReminderSettings.DEFAULT_INTERVAL_DAYS)
            .takeIf(ReadingReminderSettings.SUPPORTED_INTERVALS::contains)
            ?: ReadingReminderSettings.DEFAULT_INTERVAL_DAYS,
        lastFiredEpochDay = preferences.getLong(KEY_LAST_FIRED, NEVER).takeIf { it != NEVER },
    )

    fun save(settings: ReadingReminderSettings) {
        val safe = settings.copy(
            intervalDays = settings.intervalDays
                .takeIf(ReadingReminderSettings.SUPPORTED_INTERVALS::contains)
                ?: ReadingReminderSettings.DEFAULT_INTERVAL_DAYS,
        )
        // commit(), not apply(): see the note in NightUnplugStore.save.
        preferences.edit(commit = true) {
            putBoolean(KEY_ENABLED, safe.enabled)
            putInt(KEY_INTERVAL_DAYS, safe.intervalDays)
            // Turning the reminder off forgets when it last went out. Otherwise
            // switching it back on months later would count the interval from a
            // reminder nobody remembers, and the first one would land the same
            // morning instead of after the interval the household just chose.
            if (!safe.enabled) remove(KEY_LAST_FIRED)
        }
    }

    /**
     * Written separately from [save] because the settings screen holds a stale
     * copy of the settings for as long as it is open: saving that copy after a
     * reminder fired would erase the timestamp and restart the interval.
     */
    fun recordFired(epochDay: Long) {
        preferences.edit(commit = true) { putLong(KEY_LAST_FIRED, epochDay) }
    }

    private companion object {
        const val PREFERENCES_NAME = "recibozen_reading_reminders"
        const val KEY_ENABLED = "enabled_v1"
        const val KEY_INTERVAL_DAYS = "interval_days_v1"
        const val KEY_LAST_FIRED = "last_fired_epoch_day_v1"
        const val NEVER = Long.MIN_VALUE
    }
}

object ReadingReminderScheduler {

    fun apply(
        context: Context,
        settings: ReadingReminderSettings,
        tier: EntitlementTier,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        if (!settings.enabled || !PremiumFeaturePolicy.notificationsAllowed(tier)) {
            ReminderAlarms.cancel(context, ReminderKind.READING)
            return
        }
        ReminderAlarms.arm(
            context = context,
            kind = ReminderKind.READING,
            at = ReminderSchedule.nextReading(settings.intervalDays, settings.lastFiredEpochDay, now),
        )
    }
}

/**
 * The notification itself. Called from [ReminderFiring], which owns the decision
 * of whether it should go out at all.
 *
 * @return whether it actually went out. False when the household revoked the
 * notification permission after enabling the reminder, in which case the
 * interval must not advance -- nothing was delivered to count from.
 */
internal fun postReadingReminder(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false

    val notificationManager = context.getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notifications_channel_readings),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notifications_channel_readings_description)
        },
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(context.getString(R.string.notifications_reading_title))
        .setContentText(context.getString(R.string.notifications_reading_body))
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
    return true
}

private const val CHANNEL_ID = "reading_reminders"
private const val NOTIFICATION_ID = 3101
