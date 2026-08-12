package com.recibozen.app.core.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.recibozen.app.R
import java.util.Calendar
import kotlinx.coroutines.delay

internal fun greetingResourceForHour(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.greeting_morning
    in 12..18 -> R.string.greeting_afternoon
    else -> R.string.greeting_evening
}

/** Greeting driven by the phone's local clock and refreshed while the app is open. */
@Composable
fun rzTimeGreeting(userName: String): String {
    val hour by produceState(initialValue = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        while (true) {
            delay(60_000)
            value = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        }
    }
    if (userName.isBlank()) return stringResource(R.string.home_greeting_unconfigured)
    return stringResource(greetingResourceForHour(hour), userName)
}
