package app.recibozen.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.recibozen.android.core.tariff.TariffSyncWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TariffSyncWorker.schedule(applicationContext)
        setContent {
            ReciboZenApp()
        }
    }
}
