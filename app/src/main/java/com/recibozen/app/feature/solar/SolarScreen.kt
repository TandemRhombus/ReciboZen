package com.recibozen.app.feature.solar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzDashedCard
import com.recibozen.app.core.components.RzDualBarChart
import com.recibozen.app.core.components.RzDualBarEntry
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSegmentedTabs
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.model.MonthlyEnergyPoint
import com.recibozen.app.core.model.SolarScenario
import com.recibozen.app.core.export.SolarReportExporter
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenSessionState
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Pushed flow screen "Preestudio solar": scenarios, chart and native PDF export. */
data class SolarUiState(
    val scenarios: List<SolarScenario> = DemoReciboZenData.solarScenarios,
    val selectedIndex: Int = DemoReciboZenData.DEFAULT_SOLAR_SCENARIO_INDEX,
    val monthlyEnergy: List<MonthlyEnergyPoint> = DemoReciboZenData.monthlyEnergy,
    val annualConsumptionKwh: Int = DemoReciboZenData.ANNUAL_CONSUMPTION_KWH,
    val location: String = DemoReciboZenData.LOCATION,
    val currentKwp: String = DemoReciboZenData.SOLAR_CURRENT_KWP,
    val currentPanels: String = DemoReciboZenData.SOLAR_CURRENT_PANELS,
    val withPlanKwp: String = DemoReciboZenData.SOLAR_WITH_PLAN_KWP,
    val withPlanPanels: String = DemoReciboZenData.SOLAR_WITH_PLAN_PANELS,
) {
    val selectedScenario: SolarScenario get() = scenarios[selectedIndex.coerceIn(0, scenarios.lastIndex)]
}

class SolarViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<SolarUiState> = repository.session
        .map { toUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), toUiState(repository.session.value))

    private fun toUiState(state: ReciboZenSessionState) = SolarUiState(
        selectedIndex = state.solarScenarioIndex,
        location = state.location,
        annualConsumptionKwh = repository.billHistory(state)
            .filterNot { it.isProjected }
            .map { it.consumptionKwh }
            .filter { it > 0 }
            .average()
            .takeIf { !it.isNaN() }
            ?.let { average -> (average * if (state.billingCycle.contains("bi", ignoreCase = true)) 6 else 12).toInt() }
            ?: DemoReciboZenData.ANNUAL_CONSUMPTION_KWH,
    )

    fun selectScenario(index: Int) {
        repository.setSolarScenarioIndex(index)
    }
}

@Composable
fun SolarRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { SolarViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SolarScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onScenarioSelected = viewModel::selectScenario,
        onDownloadClick = {
            SolarReportExporter.createAndShare(
                context = context,
                householdName = repository.session.value.householdName,
                location = uiState.location,
                annualConsumptionKwh = uiState.annualConsumptionKwh,
                scenario = uiState.selectedScenario,
            )
        },
    )
}

@Composable
fun SolarScreen(
    uiState: SolarUiState,
    onBack: () -> Unit,
    onScenarioSelected: (Int) -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scenario = uiState.selectedScenario
    Scaffold(
        modifier = modifier,
        topBar = { RzFlowTopBar(title = stringResource(R.string.solar_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            RzCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = RzTheme.extendedColors.solarContainer,
                borderColor = RzTheme.extendedColors.solarBorder,
            ) {
                Text(
                    stringResource(R.string.solar_eyebrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = RzTheme.extendedColors.solarText,
                )
                Text(
                    stringResource(R.string.solar_intro_headline),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
                Text(
                    stringResource(R.string.solar_intro_body, uiState.annualConsumptionKwh),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }

            RzSegmentedTabs(
                options = uiState.scenarios.map { it.tabLabel },
                selectedIndex = uiState.selectedIndex,
                onSelectedChange = onScenarioSelected,
            )

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(scenario.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    modifier = Modifier.padding(top = RzSpacing.sm),
                ) {
                    Text(scenario.coverageLabel, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(R.string.solar_coverage_caption),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
                ) {
                    SolarSpecRow(stringResource(R.string.solar_spec_panels), scenario.panels)
                    SolarSpecRow(stringResource(R.string.solar_spec_system_power), scenario.systemPower)
                    SolarSpecRow(stringResource(R.string.solar_spec_area), scenario.area)
                    SolarSpecRow(stringResource(R.string.solar_spec_production), scenario.production)
                    SolarSpecRow(stringResource(R.string.solar_spec_grid_remaining), scenario.gridRemaining)
                    SolarSpecRow(
                        stringResource(R.string.solar_spec_confidence),
                        stringResource(R.string.solar_confidence_medium),
                        valueColor = RzTheme.extendedColors.warningText,
                    )
                }
                Text(
                    scenario.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.lg),
                )
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.solar_monthly_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                RzDualBarChart(
                    entries = uiState.monthlyEnergy.map { RzDualBarEntry(it.monthLabel, it.consumptionFraction, it.productionFraction) },
                    colorA = RzTheme.extendedColors.chartHistoryBar,
                    colorB = RzTheme.extendedColors.chartSolarProduction,
                    modifier = Modifier.padding(top = RzSpacing.lg),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg),
                ) {
                    LegendDot(color = RzTheme.extendedColors.chartHistoryBar, label = stringResource(R.string.solar_legend_consumption))
                    LegendDot(color = RzTheme.extendedColors.chartSolarProduction, label = stringResource(R.string.solar_legend_production))
                }
                Text(
                    stringResource(R.string.solar_monthly_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.solar_reduce_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.md),
                ) {
                    ReduceCompareCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.solar_reduce_current_label),
                        kwp = uiState.currentKwp,
                        panels = uiState.currentPanels,
                        containerColor = MaterialTheme.colorScheme.background,
                        labelColor = MaterialTheme.colorScheme.outline,
                    )
                    ReduceCompareCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.solar_reduce_with_plan_label),
                        kwp = uiState.withPlanKwp,
                        panels = uiState.withPlanPanels,
                        containerColor = RzTheme.extendedColors.successContainer,
                        labelColor = RzTheme.extendedColors.success,
                    )
                }
                Text(
                    stringResource(R.string.solar_reduce_caption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.solar_refine_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.solar_refine_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                ) {
                    RefineRow(
                        answered = true,
                        label = stringResource(R.string.solar_refine_location),
                        value = uiState.location,
                    )
                    RefineRow(
                        answered = true,
                        label = stringResource(R.string.solar_refine_roof_type),
                        value = stringResource(R.string.solar_refine_roof_type_value),
                    )
                    RefineRow(
                        answered = false,
                        label = stringResource(R.string.solar_refine_shading),
                        onAnswerClick = {},
                    )
                    RefineRow(
                        answered = false,
                        label = stringResource(R.string.solar_refine_available_space),
                        onAnswerClick = {},
                    )
                }
            }

            RzDashedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.solar_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RzPrimaryButton(
                text = stringResource(R.string.solar_download_action),
                onClick = onDownloadClick,
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SolarSpecRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReduceCompareCard(
    label: String,
    kwp: String,
    panels: String,
    containerColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    RzCard(modifier = modifier, containerColor = containerColor, borderColor = null) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        Text(
            kwp,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(panels, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RefineRow(
    answered: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onAnswerClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
    ) {
        Icon(
            if (answered) RzIcons.CheckCircle else RzIcons.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (answered) RzTheme.extendedColors.success else MaterialTheme.colorScheme.outline,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (answered && value != null) {
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else if (onAnswerClick != null) {
            Text(
                stringResource(R.string.solar_refine_answer_action),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SolarScreenPreview() {
    ReciboZenTheme {
        SolarScreen(
            uiState = SolarUiState(),
            onBack = {},
            onScenarioSelected = {},
            onDownloadClick = {},
        )
    }
}
