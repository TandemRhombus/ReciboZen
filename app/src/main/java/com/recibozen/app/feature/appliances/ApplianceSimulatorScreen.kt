package com.recibozen.app.feature.appliances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceDataSource
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.OpportunityLevel
import com.recibozen.app.navigation.rzViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Hypothetical purchase scenario calculated against the current projection.
 * It can be saved or added as a persistent appliance in the household.
 */
data class ApplianceSimulatorUiState(
    val name: String = "",
    val watts: String = "",
    val hoursPerWeek: String = "",
    val isSaved: Boolean = false,
    val hasProjection: Boolean = false,
    val currentProjectionMxn: Int = 0,
    val billingDays: Int = 61,
    /** kWh the simulated appliance would add over a full billing period. */
    val addedKwh: Int = 0,
    /** Marginal cost: what the bill grows by, honouring the progressive tiers. */
    val addedCostMxn: Int = 0,
    val newProjectionMxn: Int = 0,
) {
    val isValid: Boolean
        get() = name.isNotBlank() &&
            (watts.toIntOrNull() ?: 0) > 0 &&
            (hoursPerWeek.toDoubleOrNull() ?: 0.0) in 0.01..168.0
}

class ApplianceSimulatorViewModel(private val repository: ReciboZenRepository) : ViewModel() {

    private val draft = MutableStateFlow(
        repository.session.value.let { state ->
            Triple(
                state.simulatorScenarioName,
                state.simulatorScenarioWatts.takeIf { it > 0 }?.toString().orEmpty(),
                state.simulatorScenarioHoursPerWeek.takeIf { it > 0.0 }?.let(::formatInputNumber).orEmpty(),
            )
        },
    )

    val uiState: StateFlow<ApplianceSimulatorUiState> =
        combine(repository.session, draft, repository.tariffCalibration) { state, input, _ ->
            val name = input.first
            val watts = input.second
            val hours = input.third
            val energy = repository.energySummary(state)
            val projection = repository.billProjection(state)
            val billingDays = energy.billingDays.takeIf { it > 0 } ?: DEFAULT_BILLING_DAYS
            val addedKwh = simulatedKwh(watts, hours, billingDays)
            // Marginal, not average: the same appliance costs more once the
            // household is already inside the excedente tier.
            val baseCost = repository.estimateCostMxn(energy.projectedKwh)
            val withAppliance = repository.estimateCostMxn(energy.projectedKwh + addedKwh)
            val addedCost = (withAppliance - baseCost).coerceAtLeast(0)
            ApplianceSimulatorUiState(
                name = name,
                watts = watts,
                hoursPerWeek = hours,
                isSaved = state.savedSimulatorScenario &&
                    state.simulatorScenarioName == name.trim() &&
                    state.simulatorScenarioWatts == watts.toIntOrNull() &&
                    kotlin.math.abs(state.simulatorScenarioHoursPerWeek - (hours.toDoubleOrNull() ?: 0.0)) < 0.001,
                hasProjection = projection.hasUsageEvidence,
                currentProjectionMxn = projection.projectedMxn,
                billingDays = billingDays,
                addedKwh = addedKwh,
                addedCostMxn = addedCost,
                newProjectionMxn = projection.projectedMxn + addedCost,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApplianceSimulatorUiState())

    fun onNameChange(value: String) { draft.value = draft.value.copy(first = value.take(40)) }
    fun onWattsChange(value: String) { draft.value = draft.value.copy(second = value.filter(Char::isDigit).take(5)) }
    fun onHoursChange(value: String) {
        draft.value = draft.value.copy(third = value.filter { it.isDigit() || it == '.' }.take(5))
    }

    fun saveScenario() {
        val current = uiState.value
        if (!current.isValid) return
        repository.saveSimulatorScenario(
            current.name,
            current.watts.toIntOrNull() ?: return,
            current.hoursPerWeek.toDoubleOrNull() ?: return,
        )
    }

    fun addToHome() {
        val current = uiState.value
        if (!current.isValid) return
        repository.addAppliance(
            Appliance(
                id = "simulated_${System.currentTimeMillis()}",
                name = current.name,
                room = "Hogar",
                costMxn = current.addedCostMxn,
                kwh = current.addedKwh,
                sharePercent = 0,
                hoursPerWeek = current.hoursPerWeek.toDoubleOrNull()?.roundToInt()?.coerceIn(1, 168),
                confidence = ConfidenceLevel.MEDIUM,
                opportunity = OpportunityLevel.NONE,
                calculationNote = "${current.watts} W × ${current.hoursPerWeek} h/semana × ${current.billingDays} días ÷ 7 ÷ 1,000",
                powerWatts = current.watts.toIntOrNull(),
                dataSource = ApplianceDataSource.USER_ESTIMATE,
            ),
        )
    }

    private companion object {
        const val DEFAULT_BILLING_DAYS = 61

        fun simulatedKwh(watts: String, hoursPerWeek: String, billingDays: Int): Int {
            val w = watts.toIntOrNull() ?: return 0
            val hours = hoursPerWeek.toDoubleOrNull() ?: return 0
            val weeks = billingDays / 7.0
            return (w / 1000.0 * hours * weeks).roundToInt()
        }

        fun formatInputNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }
}

@Composable
fun ApplianceSimulatorRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ApplianceSimulatorViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ApplianceSimulatorScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSaveScenario = { viewModel.saveScenario() },
        onAddToHome = {
            viewModel.addToHome()
            navController.popBackStack()
        },
        onNameChange = viewModel::onNameChange,
        onWattsChange = viewModel::onWattsChange,
        onHoursChange = viewModel::onHoursChange,
    )
}

@Composable
fun ApplianceSimulatorScreen(
    uiState: ApplianceSimulatorUiState,
    onBack: () -> Unit,
    onSaveScenario: () -> Unit,
    onAddToHome: () -> Unit,
    onNameChange: (String) -> Unit = {},
    onWattsChange: (String) -> Unit = {},
    onHoursChange: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        RzFlowTopBar(title = stringResource(R.string.appliances_simulator_top_bar_title), onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            Text(
                stringResource(R.string.appliances_simulator_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RzCard(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.appliances_simulator_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    modifier = Modifier.padding(top = RzSpacing.sm),
                ) {
                    OutlinedTextField(
                        value = uiState.watts,
                        onValueChange = onWattsChange,
                        label = { Text(stringResource(R.string.appliances_simulator_field_watts)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = uiState.hoursPerWeek,
                        onValueChange = onHoursChange,
                        label = { Text(stringResource(R.string.appliances_simulator_field_hours)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(R.string.appliances_simulator_field_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = RzSpacing.lg))

                if (!uiState.isValid) {
                    Text(
                        stringResource(R.string.appliances_simulator_awaiting_input),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row {
                        SpecColumn(
                            stringResource(R.string.appliances_simulator_consumption_label),
                            stringResource(R.string.appliances_simulator_added_kwh, uiState.addedKwh),
                        )
                        SpecColumn(
                            stringResource(R.string.appliances_simulator_added_cost_label),
                            "$${uiState.addedCostMxn}",
                        )
                    }
                    Text(
                        if (uiState.hasProjection) {
                            stringResource(
                                R.string.appliances_simulator_impact,
                                "$${uiState.addedCostMxn}",
                                "$${uiState.currentProjectionMxn}",
                                "$${uiState.newProjectionMxn}",
                            )
                        } else {
                            stringResource(R.string.appliances_simulator_impact_no_projection, "$${uiState.addedCostMxn}")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = RzSpacing.md),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.md)
                        .background(RzTheme.extendedColors.warningContainer, RzExtraShapes.control)
                        .padding(horizontal = RzSpacing.md, vertical = RzSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                ) {
                    Icon(RzIcons.Info, contentDescription = null, tint = RzTheme.extendedColors.warning)
                    Text(
                        stringResource(R.string.appliances_simulator_avg_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(R.string.appliances_simulator_confidence_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                RzSecondaryButton(
                    text = stringResource(if (uiState.isSaved) R.string.appliances_simulator_saved_scenario else R.string.appliances_simulator_save_scenario),
                    onClick = onSaveScenario,
                    enabled = !uiState.isSaved && uiState.isValid,
                    modifier = Modifier.weight(1f),
                )
                RzPrimaryButton(
                    text = stringResource(R.string.appliances_simulator_add_home),
                    onClick = onAddToHome,
                    enabled = uiState.isValid,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RowScope.SpecColumn(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(showBackground = true)
@Composable
private fun ApplianceSimulatorScreenPreview() {
    ReciboZenTheme {
        ApplianceSimulatorScreen(
            uiState = ApplianceSimulatorUiState(
                name = "Minisplit 12,000 BTU",
                watts = "1150",
                hoursPerWeek = "25",
                hasProjection = true,
                currentProjectionMxn = 846,
                addedKwh = 251,
                addedCostMxn = 612,
                newProjectionMxn = 1458,
            ),
            onBack = {},
            onSaveScenario = {},
            onAddToHome = {},
        )
    }
}
