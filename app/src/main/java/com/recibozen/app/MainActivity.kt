package com.recibozen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.recibozen.app.core.tariff.TariffSyncWorker

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
