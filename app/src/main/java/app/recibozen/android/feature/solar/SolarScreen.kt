package app.recibozen.android.feature.solar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.recibozen.android.R
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzDashedCard
import app.recibozen.android.core.components.RzDualBarChart
import app.recibozen.android.core.components.RzDualBarEntry
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzFilterChip
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSegmentedTabs
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.core.model.MonthlyEnergyPoint
import app.recibozen.android.core.model.SolarScenario
import app.recibozen.android.core.export.SolarReportExporter
import app.recibozen.android.core.calculation.MeterProfile
import app.recibozen.android.core.calculation.SolarEstimator
import app.recibozen.android.core.location.CityLocator
import app.recibozen.android.core.solar.SolarResource
import app.recibozen.android.core.solar.SolarResourceClient
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pushed flow screen "Preestudio solar": scenarios, chart and native PDF export. */
data class SolarUiState(
    val scenarios: List<SolarScenario> = emptyList(),
    val selectedIndex: Int = 1,
    val monthlyEnergy: List<MonthlyEnergyPoint> = emptyList(),
    val annualConsumptionKwh: Int = 0,
    val rollingAnnualConsumptionKwh: Int = 0,
    val historicalPeakAnnualConsumptionKwh: Int = 0,
    val demandGrowthPercent: Int = 0,
    val plannedLoadsKwh: Map<String, Int> = emptyMap(),
    val coveredDays: Int = 0,
    val historySpanDays: Int = 0,
    val manualEvidenceDays: Int = 0,
    val annualYieldKwhPerKwp: Int = 0,
    val location: String = "",
    val resourceSource: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedScenario: SolarScenario? get() = scenarios.getOrNull(selectedIndex.coerceIn(0, scenarios.lastIndex.coerceAtLeast(0)))
}

class SolarViewModel(
    private val repository: ReciboZenRepository,
    private val client: SolarResourceClient = SolarResourceClient(),
) : ViewModel() {
    private data class ResourceState(
        val locationKey: String = "",
        val resource: SolarResource? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val resourceState = MutableStateFlow(ResourceState())

    val uiState: StateFlow<SolarUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        resourceState,
    ) { state, history, resource ->
        val estimate = resource.resource?.let {
            SolarEstimator.estimate(
                history = history,
                monthlyDailyIrradiance = it.monthlyDailyAverage,
                readings = state.readings,
                register = MeterProfile.register(state.meterType, state.meterDigitCount, state.meterMultiplier),
                demandGrowthPercent = state.solarDemandGrowthPercent,
                plannedAnnualKwh = state.solarPlannedLoadsKwh.values.sum(),
            )
        }
        SolarUiState(
            scenarios = estimate?.scenarios.orEmpty(),
            selectedIndex = state.solarScenarioIndex.coerceIn(0, 2),
            monthlyEnergy = estimate?.monthlyEnergy.orEmpty(),
            annualConsumptionKwh = estimate?.annualConsumptionKwh ?: 0,
            rollingAnnualConsumptionKwh = estimate?.rollingAnnualConsumptionKwh ?: 0,
            historicalPeakAnnualConsumptionKwh = estimate?.historicalPeakAnnualConsumptionKwh ?: 0,
            demandGrowthPercent = state.solarDemandGrowthPercent,
            plannedLoadsKwh = state.solarPlannedLoadsKwh,
            coveredDays = estimate?.coveredDays ?: 0,
            historySpanDays = estimate?.historySpanDays ?: 0,
            manualEvidenceDays = estimate?.manualEvidenceDays ?: 0,
            annualYieldKwhPerKwp = estimate?.annualYieldKwhPerKwp ?: 0,
            location = state.location,
            resourceSource = resource.resource?.sourceName.orEmpty(),
            isLoading = resource.loading,
            errorMessage = resource.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SolarUiState())

    fun load(context: android.content.Context, location: String, force: Boolean = false) {
        val key = location.trim()
        if (key.isBlank() || (!force && resourceState.value.locationKey == key)) return
        resourceState.value = ResourceState(locationKey = key, loading = true)
        viewModelScope.launch {
            val result = runCatching {
                val coordinates = CityLocator(context.applicationContext).coordinatesFor(key)
                    ?: error("No pudimos ubicar esa ciudad.")
                client.load(coordinates.first, coordinates.second)
            }
            resourceState.update {
                result.fold(
                    onSuccess = { value -> ResourceState(locationKey = key, resource = value) },
                    onFailure = { error -> ResourceState(locationKey = key, error = error.message ?: "No se pudo consultar el recurso solar.") },
                )
            }
        }
    }

    fun selectScenario(index: Int) {
        repository.setSolarScenarioIndex(index.coerceIn(0, 2))
    }

    fun selectDemandGrowth(percent: Int) {
        repository.setSolarDemandGrowthPercent(percent)
    }

    fun togglePlannedLoad(loadId: String) {
        val option = SOLAR_PLANNED_LOADS.firstOrNull { it.id == loadId } ?: return
        val enabled = repository.session.value.solarPlannedLoadsKwh.containsKey(loadId)
        repository.setSolarPlannedLoad(loadId, if (enabled) null else option.annualKwh)
    }

    fun changePlannedLoadQuantity(loadId: String, delta: Int) {
        val option = SOLAR_PLANNED_LOADS.firstOrNull { it.id == loadId } ?: return
        val current = repository.session.value.solarPlannedLoadsKwh[loadId] ?: 0
        val next = current + delta.coerceIn(-1, 1) * option.annualKwh
        repository.setSolarPlannedLoad(loadId, next.takeIf { it > 0 })
    }
}

@Composable
fun SolarRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { SolarViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(uiState.location) { viewModel.load(context, uiState.location) }
    SolarScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onScenarioSelected = viewModel::selectScenario,
        onDemandGrowthSelected = viewModel::selectDemandGrowth,
        onPlannedLoadToggle = viewModel::togglePlannedLoad,
        onPlannedLoadQuantityChange = viewModel::changePlannedLoadQuantity,
        onDownloadClick = {
            uiState.selectedScenario?.let { scenario -> SolarReportExporter.createAndShare(
                context = context,
                householdName = repository.session.value.householdName,
                location = uiState.location,
                annualConsumptionKwh = uiState.annualConsumptionKwh,
                scenario = scenario,
            ) }
        },
        onRetryClick = { viewModel.load(context, uiState.location, force = true) },
    )
}

@Composable
fun SolarScreen(
    uiState: SolarUiState,
    onBack: () -> Unit,
    onScenarioSelected: (Int) -> Unit,
    onDemandGrowthSelected: (Int) -> Unit,
    onPlannedLoadToggle: (String) -> Unit,
    onPlannedLoadQuantityChange: (String, Int) -> Unit,
    onDownloadClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scenario = uiState.selectedScenario
    var selectedMonthIndex by remember(uiState.monthlyEnergy.size) {
        mutableIntStateOf(uiState.monthlyEnergy.indices.maxByOrNull { uiState.monthlyEnergy[it].consumptionFraction } ?: 0)
    }
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
                    if (uiState.annualConsumptionKwh > 0) {
                        stringResource(
                            R.string.solar_intro_body_real,
                            uiState.coveredDays,
                            uiState.location,
                            uiState.annualConsumptionKwh,
                        )
                    } else stringResource(R.string.solar_intro_body_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }

            if (scenario == null) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (uiState.isLoading) stringResource(R.string.solar_loading)
                        else uiState.errorMessage ?: stringResource(R.string.solar_need_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!uiState.isLoading && uiState.errorMessage != null) {
                        RzPrimaryButton(
                            text = stringResource(R.string.solar_retry_action),
                            onClick = onRetryClick,
                            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                        )
                    }
                }
                RzDashedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.solar_disclaimer), style = MaterialTheme.typography.bodySmall)
                }
                return@Column
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.solar_design_demand_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.solar_design_history_summary,
                        uiState.rollingAnnualConsumptionKwh,
                        uiState.historicalPeakAnnualConsumptionKwh,
                        uiState.historySpanDays,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
                if (uiState.manualEvidenceDays > 0) {
                    Text(
                        stringResource(R.string.solar_manual_evidence, uiState.manualEvidenceDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                }
                Text(
                    stringResource(R.string.solar_growth_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = RzSpacing.lg),
                )
                RzSegmentedTabs(
                    options = SOLAR_GROWTH_OPTIONS.map { if (it == 0) stringResource(R.string.solar_growth_current) else "+$it%" },
                    selectedIndex = SOLAR_GROWTH_OPTIONS.indexOf(uiState.demandGrowthPercent).coerceAtLeast(0),
                    onSelectedChange = { onDemandGrowthSelected(SOLAR_GROWTH_OPTIONS[it]) },
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                )
                Text(
                    stringResource(R.string.solar_planned_loads_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = RzSpacing.lg),
                )
                Text(
                    stringResource(R.string.solar_planned_loads_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                ) {
                    SOLAR_PLANNED_LOADS.forEach { load ->
                        val selectedAnnualKwh = uiState.plannedLoadsKwh[load.id]
                        Column {
                            RzFilterChip(
                                text = if (selectedAnnualKwh == null) {
                                    "${load.label} · +${load.annualKwh} kWh/año"
                                } else {
                                    "${load.label} · $selectedAnnualKwh kWh/año"
                                },
                                selected = selectedAnnualKwh != null,
                                onClick = { onPlannedLoadToggle(load.id) },
                            )
                            if (selectedAnnualKwh != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                                ) {
                                    TextButton(onClick = { onPlannedLoadQuantityChange(load.id, -1) }) {
                                        Text("−")
                                    }
                                    Text(
                                        stringResource(
                                            R.string.solar_planned_load_quantity,
                                            (selectedAnnualKwh / load.annualKwh).coerceAtLeast(1),
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    TextButton(onClick = { onPlannedLoadQuantityChange(load.id, 1) }) {
                                        Text("+")
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.solar_design_target, uiState.annualConsumptionKwh),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = RzSpacing.lg),
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
                    selectedIndex = selectedMonthIndex,
                    onSelected = { selectedMonthIndex = it },
                )
                uiState.monthlyEnergy.getOrNull(selectedMonthIndex)?.let { selected ->
                    Text(
                        stringResource(
                            R.string.solar_chart_selected,
                            selected.monthLabel,
                            selected.consumptionKwh,
                            selected.productionKwh,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
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
                    stringResource(
                        R.string.solar_resource_basis,
                        uiState.resourceSource,
                        uiState.annualYieldKwhPerKwp,
                        (SolarEstimator.PERFORMANCE_RATIO * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
                Text(
                    stringResource(R.string.solar_chart_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
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

private data class SolarPlannedLoad(val id: String, val label: String, val annualKwh: Int)

private val SOLAR_GROWTH_OPTIONS = listOf(0, 20, 40, 60)
private val SOLAR_PLANNED_LOADS = listOf(
    SolarPlannedLoad("electric_car", "Auto eléctrico", 2_500),
    SolarPlannedLoad("extra_inverter_ac", "Minisplit inverter", 1_000),
    SolarPlannedLoad("electric_heating", "Calefacción eléctrica", 700),
    SolarPlannedLoad("heat_pump", "Bomba de calor", 1_200),
    SolarPlannedLoad("electric_water_heater", "Boiler eléctrico", 1_800),
)

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

@Preview(showBackground = true)
@Composable
private fun SolarScreenPreview() {
    ReciboZenTheme {
        SolarScreen(
            uiState = SolarUiState(),
            onBack = {},
            onScenarioSelected = {},
            onDemandGrowthSelected = {},
            onPlannedLoadToggle = {},
            onPlannedLoadQuantityChange = { _, _ -> },
            onDownloadClick = {},
            onRetryClick = {},
        )
    }
}
