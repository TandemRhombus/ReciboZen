package app.recibozen.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.recibozen.android.core.notifications.ReminderRearm
import app.recibozen.android.core.tariff.TariffSyncWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TariffSyncWorker.schedule(applicationContext)
        // One-time cleanup for installs upgrading from the WorkManager-based
        // reminders. The alarms themselves are armed by the repository once it
        // knows the entitlement tier, and re-armed on boot -- not here. If
        // reminders only arrive after the app is opened, the fix in
        // ReminderAlarms has regressed.
        ReminderRearm.cancelLegacyPeriodicWork(applicationContext)
        setContent {
            ReciboZenApp()
        }
    }
}
