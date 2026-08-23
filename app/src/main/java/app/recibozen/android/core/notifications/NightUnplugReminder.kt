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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.recibozen.android.R
import app.recibozen.android.core.calculation.NightUnplugAdvice
import app.recibozen.android.core.calculation.NightUnplugAdvisor
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.security.PremiumAccessStore
import app.recibozen.android.data.repository.readStoredSession
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * A nightly nudge to unplug the appliances the household leaves in standby.
 *
 * Separate from [ReadingReminderSettings] on purpose: that one repeats every N
 * days at no particular time, because a meter reading is not tied to an hour.
 * This one is tied to an hour and to nothing else -- "before I go to bed" is the
 * whole point, and a reminder that lands at noon is worse than none.
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

    fun save(settings: NightUnplugSettings) {
        preferences.edit {
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
    private const val UNIQUE_WORK_NAME = "night-unplug-reminder-v1"

    /**
     * Delay from [now] until the next occurrence of [target].
     *
     * Exposed for tests. A reminder set for an hour that has already passed
     * today belongs to tomorrow, and getting that wrong either fires it
     * immediately on save or skips the first night silently.
     */
    fun delayUntilNext(target: LocalTime, now: LocalDateTime): Duration {
        val todayAtTarget = now.toLocalDate().atTime(target)
        val next = if (todayAtTarget.isAfter(now)) todayAtTarget else todayAtTarget.plusDays(1)
        return Duration.between(now, next)
    }

    fun apply(
        context: Context,
        settings: NightUnplugSettings,
        tier: EntitlementTier,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.enabled || !PremiumFeaturePolicy.notificationsAllowed(tier)) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<NightUnplugWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNext(settings.time, now).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        // CANCEL_AND_REENQUEUE, not UPDATE. Changing the hour has to re-anchor
        // the schedule; UPDATE can keep the already-computed next run, which
        // would leave the reminder firing at the old time until it lapses.
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }
}

class NightUnplugWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        val settings = NightUnplugStore(applicationContext).load()
        val tier = PremiumAccessStore(applicationContext).effectiveTier()
        if (!settings.enabled || !PremiumFeaturePolicy.notificationsAllowed(tier)) return Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val session = runCatching { readStoredSession(applicationContext) }.getOrNull()
        val advice = session?.let {
            NightUnplugAdvisor.advise(it.customAppliances, it.applianceHoursPerWeek)
        }

        notify(
            title = applicationContext.getString(R.string.notifications_night_title),
            body = bodyFor(advice),
        )
        return Result.success()
    }

    /**
     * With an inventory the reminder names the appliances and what they draw,
     * because that is what makes anyone actually get up and unplug something.
     * Without one it still fires -- the household asked to be reminded -- but it
     * says plainly that it has nothing specific yet instead of inventing a
     * saving or listing appliances nobody registered.
     */
    private fun bodyFor(advice: NightUnplugAdvice?): String {
        if (advice == null) return applicationContext.getString(R.string.notifications_night_body_generic)
        val named = advice.items.take(MAX_NAMED)
        val list = named.joinToString(", ") {
            applicationContext.getString(R.string.notifications_night_item, it.name, it.standbyWatts)
        }
        val remaining = advice.items.size - named.size
        val listWithTail = if (remaining > 0) {
            applicationContext.resources.getQuantityString(
                R.plurals.notifications_night_more,
                remaining,
                list,
                remaining,
            )
        } else {
            list
        }
        return applicationContext.getString(
            R.string.notifications_night_body,
            listWithTail,
            advice.totalStandbyWatts,
            NightUnplugAdvisor.ASSUMED_NIGHT_HOURS.toInt(),
            String.format(java.util.Locale.forLanguageTag("es-MX"), "%.2f", advice.nightlyKwh),
        )
    }

    private fun notify(title: String, body: String) {
        // Re-checked here and not only in doWork(). The guard and the post used
        // to sit in different functions, which is exactly the shape a caller
        // added later would get wrong -- and which lint flags, correctly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.notifications_channel_night),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = applicationContext.getString(R.string.notifications_channel_night_description)
            },
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            // The list of appliances does not fit on one line, and truncating it
            // to "Tele, Cons..." removes the only part worth reading.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(
                applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let { launchIntent ->
                    PendingIntent.getActivity(
                        applicationContext,
                        NOTIFICATION_ID,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                },
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "night_unplug_reminders"
        const val NOTIFICATION_ID = 3102
        const val MAX_NAMED = 3
    }
}
