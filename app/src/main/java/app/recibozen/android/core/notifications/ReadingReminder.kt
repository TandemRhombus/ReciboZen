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
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.security.PremiumAccessStore
import java.util.concurrent.TimeUnit

data class ReadingReminderSettings(
    val enabled: Boolean = false,
    val intervalDays: Int = DEFAULT_INTERVAL_DAYS,
) {
    companion object {
        const val DEFAULT_INTERVAL_DAYS = 3
        val SUPPORTED_INTERVALS = setOf(3, 7)
    }
}

object PremiumFeaturePolicy {
    fun notificationsAllowed(tier: EntitlementTier): Boolean = tier != EntitlementTier.FREE
}

class ReadingReminderStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ReadingReminderSettings = ReadingReminderSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        intervalDays = preferences.getInt(KEY_INTERVAL_DAYS, ReadingReminderSettings.DEFAULT_INTERVAL_DAYS)
            .takeIf(ReadingReminderSettings.SUPPORTED_INTERVALS::contains)
            ?: ReadingReminderSettings.DEFAULT_INTERVAL_DAYS,
    )

    fun save(settings: ReadingReminderSettings) {
        val safe = settings.copy(
            intervalDays = settings.intervalDays
                .takeIf(ReadingReminderSettings.SUPPORTED_INTERVALS::contains)
                ?: ReadingReminderSettings.DEFAULT_INTERVAL_DAYS,
        )
        preferences.edit {
            putBoolean(KEY_ENABLED, safe.enabled)
            putInt(KEY_INTERVAL_DAYS, safe.intervalDays)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "recibozen_reading_reminders"
        const val KEY_ENABLED = "enabled_v1"
        const val KEY_INTERVAL_DAYS = "interval_days_v1"
    }
}

object ReadingReminderScheduler {
    private const val UNIQUE_WORK_NAME = "premium-reading-reminder-v1"

    fun apply(context: Context, settings: ReadingReminderSettings, tier: EntitlementTier) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.enabled || !PremiumFeaturePolicy.notificationsAllowed(tier)) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val interval = settings.intervalDays.toLong()
        val request = PeriodicWorkRequestBuilder<ReadingReminderWorker>(interval, TimeUnit.DAYS)
            .setInitialDelay(interval, TimeUnit.DAYS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

class ReadingReminderWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val settings = ReadingReminderStore(applicationContext).load()
        val tier = PremiumAccessStore(applicationContext).effectiveTier()
        if (!settings.enabled || !PremiumFeaturePolicy.notificationsAllowed(tier)) return Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.notifications_channel_readings),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = applicationContext.getString(R.string.notifications_channel_readings_description)
            },
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(applicationContext.getString(R.string.notifications_reading_title))
            .setContentText(applicationContext.getString(R.string.notifications_reading_body))
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
        return Result.success()
    }

    private companion object {
        const val CHANNEL_ID = "reading_reminders"
        const val NOTIFICATION_ID = 3101
    }
}
