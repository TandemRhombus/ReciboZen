package com.recibozen.app.feature.appliances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.OpportunityLevel
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Hypothetical purchase scenario calculated against the current projection.
 * It can be saved or added as a persistent appliance in the household.
 */
data class ApplianceSimulatorUiState(
    val isReady: Boolean = true,
    val isSaved: Boolean = false,
    val currentProjectionMxn: Int = 0,
    val addedCostLowMxn: Int = 280,
    val addedCostHighMxn: Int = 390,
)

class ApplianceSimulatorViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ApplianceSimulatorUiState> = repository.session
        .map { state ->
            ApplianceSimulatorUiState(
                isSaved = state.savedSimulatorScenario,
                currentProjectionMxn = repository.billProjection(state).projectedMxn,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApplianceSimulatorUiState())

    fun saveScenario() = repository.saveSimulatorScenario()

    fun addToHome() = repository.addAppliance(
        Appliance(
            id = "simulated_minisplit_1150w",
            name = "Minisplit convencional 1,150 W",
            room = "Hogar",
            costMxn = 335,
            kwh = 126,
            sharePercent = 0,
            hoursPerWeek = 25,
            confidence = ConfidenceLevel.MEDIUM,
            opportunity = OpportunityLevel.HIGH,
        ),
    )
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
    )
}

@Composable
fun ApplianceSimulatorScreen(
    uiState: ApplianceSimulatorUiState,
    onBack: () -> Unit,
    onSaveScenario: () -> Unit,
    onAddToHome: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RzFlowTopBar(title = stringResource(R.string.appliances_simulator_top_bar_title), onBack = onBack)

        Column(
            modifier = Modifier
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
                Text(
                    stringResource(R.string.appliances_simulator_model_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(modifier = Modifier.padding(top = RzSpacing.md)) {
                    SpecColumn(stringResource(R.string.appliances_simulator_power_label), stringResource(R.string.appliances_simulator_power_value))
                    SpecColumn(stringResource(R.string.appliances_simulator_usage_label), stringResource(R.string.appliances_simulator_usage_value))
                    SpecColumn(stringResource(R.string.appliances_simulator_consumption_label), stringResource(R.string.appliances_simulator_consumption_value))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = RzSpacing.lg))
                Text(
                    stringResource(
                        R.string.appliances_simulator_impact,
                        "$${uiState.addedCostLowMxn}–$${uiState.addedCostHighMxn}",
                        "$${uiState.currentProjectionMxn}",
                        "$${uiState.currentProjectionMxn + uiState.addedCostLowMxn}–$${uiState.currentProjectionMxn + uiState.addedCostHighMxn}",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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

            Text(
                stringResource(R.string.appliances_simulator_compare_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                ModelCompareCard(
                    modifier = Modifier.weight(1f),
                    name = stringResource(R.string.appliances_simulator_model_a_name),
                    type = stringResource(R.string.appliances_simulator_model_a_type),
                    purchasePrice = "$9,500",
                    usageCost = "$320",
                    usageCostColor = MaterialTheme.colorScheme.error,
                    highlighted = false,
                )
                ModelCompareCard(
                    modifier = Modifier.weight(1f),
                    name = stringResource(R.string.appliances_simulator_model_b_name),
                    type = stringResource(R.string.appliances_simulator_model_b_type),
                    purchasePrice = "$13,000",
                    usageCost = "$215",
                    usageCostColor = RzTheme.extendedColors.success,
                    highlighted = true,
                )
            }

            RzCard(
                containerColor = RzTheme.extendedColors.successContainer,
                borderColor = null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.appliances_simulator_payback_note, "17–24 meses"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                RzSecondaryButton(
                    text = stringResource(if (uiState.isSaved) R.string.appliances_simulator_saved_scenario else R.string.appliances_simulator_save_scenario),
                    onClick = onSaveScenario,
                    enabled = !uiState.isSaved,
                    modifier = Modifier.weight(1f),
                )
                RzPrimaryButton(
                    text = stringResource(R.string.appliances_simulator_add_home),
                    onClick = onAddToHome,
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

@Composable
private fun ModelCompareCard(
    modifier: Modifier = Modifier,
    name: String,
    type: String,
    purchasePrice: String,
    usageCost: String,
    usageCostColor: Color,
    highlighted: Boolean,
) {
    RzCard(
        modifier = modifier,
        borderColor = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        borderWidth = if (highlighted) 2.dp else 1.dp,
        contentPadding = PaddingValues(RzSpacing.lg),
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(
            stringResource(R.string.appliances_simulator_purchase_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Text(purchasePrice, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.appliances_simulator_usage_cost_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(usageCost, style = MaterialTheme.typography.titleMedium, color = usageCostColor)
            Text(
                stringResource(R.string.appliances_simulator_per_period),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ApplianceSimulatorScreenPreview() {
    ReciboZenTheme {
        ApplianceSimulatorScreen(
            uiState = ApplianceSimulatorUiState(),
            onBack = {},
            onSaveScenario = {},
            onAddToHome = {},
        )
    }
}
