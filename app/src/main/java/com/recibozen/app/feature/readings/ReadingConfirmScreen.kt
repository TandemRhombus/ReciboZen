package com.recibozen.app.feature.readings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzInlineBanner
import com.recibozen.app.core.components.RzBannerTone
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The confirm screen always reflects the current dial/manual repository state
 * ([ReciboZenRepository.dialReading]), regardless of which input method the
 * user came from -- an accepted simplification for this handoff build.
 */
data class ReadingConfirmUiState(
    val readingKwh: Int = 0,
    val previousReadingKwh: Int = 0,
    val consumptionSinceKwh: Int = 0,
    val averagePerDay: Double = 0.0,
    val isAbnormal: Boolean = false,
    val warningMessage: String? = null,
    val method: ReadingMethod = ReadingMethod.DIAL,
)

class ReadingConfirmViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ReadingConfirmUiState> = repository.session
        .map { state ->
            val reading = state.pendingReadingKwh ?: repository.dialReading(state).valueKwh
            val method = state.pendingReadingMethod ?: ReadingMethod.DIAL
            val difference = reading - state.lastConfirmedReadingKwh
            val latestTimestamp = state.readings.firstOrNull()?.timestampEpochMillis
            val elapsedDays = latestTimestamp?.let { (System.currentTimeMillis() - it) / 86_400_000.0 }
                ?.coerceAtLeast(0.25) ?: (76.0 / 24.0)
            val average = difference / elapsedDays
            val abnormal = difference < 0 || difference == 0 || average > 20.0
            val warning = when {
                difference < 0 -> "Esta lectura es menor que la anterior. Revisa el número o confirma si cambió el medidor."
                difference == 0 -> "Tu lectura no cambió desde el último registro. Puedes guardarla si es correcta."
                average > 20.0 -> "Esta lectura implicaría ${average.roundToInt()} kWh diarios, muy por encima de tu consumo habitual."
                else -> null
            }
            ReadingConfirmUiState(
                readingKwh = reading,
                previousReadingKwh = state.lastConfirmedReadingKwh,
                consumptionSinceKwh = difference,
                averagePerDay = average,
                isAbnormal = abnormal,
                warningMessage = warning,
                method = method,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingConfirmUiState())

    fun confirm() {
        val reading = uiState.value.readingKwh
        repository.confirmReading(reading, uiState.value.method)
    }
}

@Composable
fun ReadingConfirmRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ReadingConfirmViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReadingConfirmScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onReviewDial = { navController.navigate(Routes.ReadingDial) },
        onConfirm = {
            viewModel.confirm()
            navController.popBackStack(Routes.Home, inclusive = false)
        },
        onFix = { navController.navigate(Routes.ReadingDial) },
    )
}

@Composable
fun ReadingConfirmScreen(
    uiState: ReadingConfirmUiState,
    onBack: () -> Unit,
    onReviewDial: () -> Unit,
    onConfirm: () -> Unit,
    onFix: () -> Unit,
) {
    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.action_register_reading), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.reading_confirm_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    Text(
                        String.format(Locale("es", "MX"), "%,d", uiState.readingKwh),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        " " + stringResource(R.string.reading_confirm_unit_suffix),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(modifier = Modifier.padding(top = RzSpacing.lg), verticalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                    ConfirmRow(
                        label = stringResource(R.string.reading_confirm_previous_reading),
                        value = String.format(Locale("es", "MX"), "%,d", uiState.previousReadingKwh) + " " +
                            stringResource(R.string.reading_confirm_unit_suffix),
                    )
                    ConfirmRow(
                        label = stringResource(R.string.reading_confirm_consumption_since),
                        value = stringResource(
                            R.string.reading_confirm_consumption_value,
                            String.format(Locale("es", "MX"), "%,d", uiState.consumptionSinceKwh),
                        ),
                    )
                    ConfirmRow(
                        label = stringResource(R.string.reading_confirm_elapsed_time),
                        value = stringResource(R.string.reading_confirm_elapsed_time_value),
                    )
                    ConfirmRow(
                        label = stringResource(R.string.reading_confirm_daily_average),
                        value = stringResource(
                            R.string.reading_confirm_daily_average_value,
                            formatOneDecimal(uiState.averagePerDay),
                        ),
                    )
                }
            }

            if (uiState.isAbnormal && uiState.warningMessage != null) {
                RzInlineBanner(
                    text = uiState.warningMessage,
                    tone = RzBannerTone.WARNING,
                    icon = RzIcons.ErrorInfo,
                )
                Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_confirm_review_dial),
                        onClick = onReviewDial,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_confirm_save_anyway),
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            RzPrimaryButton(
                text = stringResource(R.string.reading_confirm_button),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
            RzSecondaryButton(
                text = stringResource(R.string.reading_confirm_fix_button),
                onClick = onFix,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.reading_confirm_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatOneDecimal(value: Double): String = String.format(Locale("es", "MX"), "%.1f", value)

@Preview(showBackground = true)
@Composable
private fun ReadingConfirmScreenPreview() {
    ReciboZenTheme {
        ReadingConfirmScreen(
            uiState = ReadingConfirmUiState(
                readingKwh = 18426,
                previousReadingKwh = 18397,
                consumptionSinceKwh = 29,
                averagePerDay = 9.1,
                isAbnormal = true,
                warningMessage = "Esta lectura implicaría 9 kWh diarios, muy por encima de tu promedio de 8.3.",
            ),
            onBack = {},
            onReviewDial = {},
            onConfirm = {},
            onFix = {},
        )
    }
}
