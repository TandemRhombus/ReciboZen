package app.recibozen.android.feature.readings

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzExtraShapes
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.demo.repository.EnergyForecastBasis
import app.recibozen.android.demo.repository.TariffRateOrigin
import app.recibozen.android.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

data class HowWeCalculateUiState(
    val observedKwh: Int = 0,
    val observedDays: Int = 0,
    val dailyAverageKwh: Double = 0.0,
    val daysRemaining: Int = 0,
    val projectedKwh: Int = 0,
    val projectedMxn: Int = 0,
    val tariff: String = "",
    val billingDays: Int = 0,
    val applianceCount: Int = 0,
    val applianceProjectedKwh: Int = 0,
    val meterProjectedKwh: Int = 0,
    val forecastBasis: EnergyForecastBasis = EnergyForecastBasis.NONE,
    val rateOrigin: TariffRateOrigin = TariffRateOrigin.GENERIC,
)

class HowWeCalculateViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<HowWeCalculateUiState> = combine(repository.session, repository.tariffCalibration) { state, _ ->
            val energy = repository.energySummary(state)
            HowWeCalculateUiState(
                observedKwh = energy.observedKwh,
                observedDays = energy.observedDays,
                dailyAverageKwh = energy.dailyAverageKwh,
                daysRemaining = energy.daysRemaining,
                projectedKwh = energy.projectedKwh,
                projectedMxn = repository.billProjection(state).projectedMxn,
                tariff = state.tariff,
                billingDays = energy.billingDays,
                applianceCount = repository.appliances(state).size,
                applianceProjectedKwh = energy.applianceProjectedKwh,
                meterProjectedKwh = energy.meterProjectedKwh,
                forecastBasis = energy.forecastBasis,
                rateOrigin = repository.tariffRateOrigin(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HowWeCalculateUiState())
}

@Composable
fun HowWeCalculateRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { HowWeCalculateViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HowWeCalculateScreen(uiState = uiState, onBack = { navController.popBackStack() })
}

@Composable
fun HowWeCalculateScreen(uiState: HowWeCalculateUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.how_we_calculate_title), onBack = onBack) },
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
                    stringResource(
                        if (uiState.forecastBasis == EnergyForecastBasis.APPLIANCES) {
                            R.string.how_we_calculate_appliance_flow_title
                        } else R.string.how_we_calculate_flow_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(modifier = Modifier.padding(top = RzSpacing.md), verticalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                    when (uiState.forecastBasis) {
                        EnergyForecastBasis.APPLIANCES -> {
                            CalcStep(1, stringResource(R.string.how_we_calculate_appliance_step1_label), stringResource(R.string.how_we_calculate_appliance_step1_body, uiState.applianceCount))
                            CalcStep(2, stringResource(R.string.how_we_calculate_appliance_step2_label), stringResource(R.string.how_we_calculate_appliance_step2_body))
                            CalcStep(3, stringResource(R.string.how_we_calculate_step3_label), stringResource(R.string.how_we_calculate_appliance_step3_body, uiState.applianceProjectedKwh, uiState.billingDays))
                            CalcStep(4, stringResource(R.string.how_we_calculate_step4_label), stringResource(R.string.how_we_calculate_cost_body, uiState.tariff.ifBlank { "estimada" }, uiState.projectedMxn))
                        }
                        EnergyForecastBasis.CLOSED_BILL -> {
                            CalcStep(1, stringResource(R.string.how_we_calculate_bill_step1_label), stringResource(R.string.how_we_calculate_bill_step1_body, uiState.observedKwh, uiState.billingDays))
                            CalcStep(2, stringResource(R.string.how_we_calculate_bill_step2_label), stringResource(R.string.how_we_calculate_bill_step2_body))
                            if (uiState.applianceProjectedKwh > 0) {
                                CalcStep(3, stringResource(R.string.how_we_calculate_appliance_step1_label), stringResource(R.string.how_we_calculate_bill_appliance_body, uiState.applianceProjectedKwh))
                            }
                        }
                        EnergyForecastBasis.METER -> {
                            CalcStep(1, stringResource(R.string.how_we_calculate_step1_label), stringResource(R.string.how_we_calculate_observed_body, uiState.observedKwh, uiState.observedDays))
                            CalcStep(2, stringResource(R.string.how_we_calculate_step2_label), stringResource(R.string.how_we_calculate_pace_body, formatOneDecimal(uiState.dailyAverageKwh)))
                            CalcStep(3, stringResource(R.string.how_we_calculate_step3_label), stringResource(R.string.how_we_calculate_projected_body, uiState.daysRemaining, uiState.projectedKwh))
                            CalcStep(4, stringResource(R.string.how_we_calculate_step4_label), stringResource(R.string.how_we_calculate_cost_body, uiState.tariff.ifBlank { "estimada" }, uiState.projectedMxn))
                        }
                        EnergyForecastBasis.NONE -> {
                            CalcStep(1, stringResource(R.string.how_we_calculate_pending_label), stringResource(R.string.how_we_calculate_pending_body))
                        }
                    }
                }
            }

            if (uiState.forecastBasis == EnergyForecastBasis.METER && uiState.applianceProjectedKwh > 0) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.how_we_calculate_two_forecasts_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            R.string.how_we_calculate_two_forecasts_body,
                            uiState.meterProjectedKwh,
                            uiState.applianceProjectedKwh,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.how_we_calculate_rates_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(
                        when (uiState.rateOrigin) {
                            TariffRateOrigin.BILL -> R.string.how_we_calculate_rates_bill
                            TariffRateOrigin.REMOTE_CATALOG -> R.string.how_we_calculate_rates_catalog
                            TariffRateOrigin.GENERIC -> R.string.how_we_calculate_rates_generic
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.how_we_calculate_assumptions_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.how_we_calculate_assumptions_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RzExtraShapes.cardSmall)
                    .padding(horizontal = RzSpacing.lg, vertical = RzSpacing.md),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
            ) {
                GlossaryLine("kWh", stringResource(R.string.how_we_calculate_glossary_kwh_body))
                GlossaryLine("kW", stringResource(R.string.how_we_calculate_glossary_kw_body))
                GlossaryLine("kWp", stringResource(R.string.how_we_calculate_glossary_kwp_body))
            }

            Text(
                stringResource(R.string.how_we_calculate_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun CalcStep(number: Int, label: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        val onSurface = MaterialTheme.colorScheme.onSurface
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append(label) }
            append(" ")
            withStyle(SpanStyle(color = onSurfaceVariant)) { append(body) }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GlossaryLine(term: String, body: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append(term) }
        append(" ")
        withStyle(SpanStyle(color = onSurfaceVariant)) { append(body) }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

private fun formatOneDecimal(value: Double): String = String.format(Locale.forLanguageTag("es-MX"), "%.1f", value)

@Preview(showBackground = true)
@Composable
private fun HowWeCalculateScreenPreview() {
    ReciboZenTheme {
        HowWeCalculateScreen(uiState = HowWeCalculateUiState(), onBack = {})
    }
}
