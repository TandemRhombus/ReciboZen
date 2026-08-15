package com.recibozen.app.feature.appliances

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The 4 selectable hour-range chips, each mapped to a single representative value/week. */
data class HourOption(val labelRes: Int, val representativeHours: Int)

val ApplianceHourOptions = listOf(
    HourOption(R.string.appliances_usage_option_lt5, 3),
    HourOption(R.string.appliances_usage_option_5_10, 8),
    HourOption(R.string.appliances_usage_option_10_20, 17),
    HourOption(R.string.appliances_usage_option_gt20, 25),
)

data class ApplianceUsageUiState(
    val applianceId: String,
    val applianceName: String?,
    val hoursPerWeek: Int,
    val costMxn: Int,
    val kwh: Int,
    val isSupported: Boolean,
    val powerWatts: Int? = null,
    val standbyWatts: Int? = null,
    val billingDays: Int = 30,
    val homeApplianceKwh: Int = 0,
    val meterProjectedKwh: Int = 0,
    val hasCurrentMeterProjection: Boolean = false,
)

/**
 * Every appliance with an hours estimate is updated through the repository.
 * Any other
 * id (e.g. `"refrigerador"`, which has no "Editar uso" entry point) falls
 * back to [ApplianceUsageUiState.isSupported] = false instead of crashing.
 */
class ApplianceUsageViewModel(
    private val repository: ReciboZenRepository,
    private val applianceId: String,
) : ViewModel() {

    val uiState: StateFlow<ApplianceUsageUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        repository.tariffCalibration,
    ) { session, _, _ ->
        toUiState(repository.appliance(applianceId, session), session)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = toUiState(repository.appliance(applianceId), repository.session.value),
    )

    private fun toUiState(
        appliance: Appliance?,
        session: com.recibozen.app.demo.repository.ReciboZenSessionState,
    ): ApplianceUsageUiState {
        val baseHours = appliance?.hoursPerWeek ?: 0
        val energy = repository.energySummary(session)
        return ApplianceUsageUiState(
            applianceId = applianceId,
            applianceName = appliance?.name,
            hoursPerWeek = baseHours,
            costMxn = appliance?.costMxn ?: 0,
            kwh = appliance?.kwh ?: 0,
            isSupported = appliance?.hoursPerWeek != null,
            powerWatts = appliance?.powerWatts,
            standbyWatts = appliance?.standbyWatts,
            billingDays = energy.billingDays.takeIf { it > 0 } ?: 30,
            homeApplianceKwh = energy.applianceProjectedKwh,
            meterProjectedKwh = energy.meterProjectedKwh,
            hasCurrentMeterProjection = energy.hasCurrentMeterProjection,
        )
    }

    fun onSelectHours(hours: Int) {
        repository.setApplianceHoursPerWeek(applianceId, hours)
    }
}

@Composable
fun ApplianceUsageRoute(
    navController: NavHostController,
    repository: ReciboZenRepository,
    applianceId: String,
) {
    val viewModel = rzViewModel { ApplianceUsageViewModel(repository, applianceId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ApplianceUsageScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelectHours = viewModel::onSelectHours,
        onSave = { navController.popBackStack() },
    )
}

@Composable
fun ApplianceUsageScreen(
    uiState: ApplianceUsageUiState,
    onBack: () -> Unit,
    onSelectHours: (Int) -> Unit,
    onSave: () -> Unit,
) {
    var draftHours by remember(uiState.applianceId, uiState.hoursPerWeek) {
        mutableIntStateOf(uiState.hoursPerWeek)
    }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        RzFlowTopBar(title = stringResource(R.string.appliances_usage_top_bar_title), onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            if (!uiState.isSupported) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.appliances_usage_unsupported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.appliances_usage_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.appliances_usage_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            draftHours.toString(),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            " " + stringResource(R.string.appliances_usage_hours_unit),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = RzSpacing.sm),
                        )
                    }
                    Text(
                        stringResource(R.string.appliances_usage_cost_prefix) + " " +
                            stringResource(R.string.appliances_cost_format, uiState.costMxn) + " " +
                            stringResource(R.string.appliances_usage_cost_summary_suffix, uiState.kwh),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Slider(
                        value = draftHours.toFloat(),
                        onValueChange = { draftHours = it.toInt().coerceIn(0, 168) },
                        onValueChangeFinished = { onSelectHours(draftHours) },
                        valueRange = 0f..168f,
                        steps = 167,
                        modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0 h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text("168 h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(
                        stringResource(R.string.appliances_usage_daily_average, draftHours / 7.0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )

                    FlowRow(
                        modifier = Modifier.padding(top = RzSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    ) {
                        ApplianceHourOptions.forEach { option ->
                            val selected = option.representativeHours == draftHours
                            HourChip(
                                label = stringResource(option.labelRes),
                                selected = selected,
                                onClick = {
                                    draftHours = option.representativeHours
                                    onSelectHours(option.representativeHours)
                                },
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.appliances_usage_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = RzSpacing.md),
                    )
                }

                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.appliances_usage_detail_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(
                        modifier = Modifier.padding(top = RzSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    ) {
                        DetailRow(
                            stringResource(R.string.appliances_usage_detail_power_label),
                            uiState.powerWatts?.let { stringResource(R.string.appliances_usage_detail_power_value, it) }
                                ?: stringResource(R.string.appliances_usage_detail_unknown),
                        )
                        DetailRow(
                            stringResource(R.string.appliances_usage_detail_period_label),
                            stringResource(R.string.appliances_usage_detail_period_value, uiState.billingDays),
                        )
                        DetailRow(
                            stringResource(R.string.appliances_usage_detail_standby_label),
                            uiState.standbyWatts?.let { stringResource(R.string.appliances_usage_detail_standby_watts, it) }
                                ?: stringResource(R.string.appliances_usage_detail_not_reported),
                        )
                    }
                    Text(
                        if (uiState.hasCurrentMeterProjection) {
                            stringResource(
                                R.string.appliances_usage_forecast_comparison,
                                uiState.homeApplianceKwh,
                                uiState.meterProjectedKwh,
                            )
                        } else stringResource(R.string.appliances_usage_forecast_inventory, uiState.homeApplianceKwh),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.md),
                    )
                }

                RzPrimaryButton(
                    text = stringResource(R.string.appliances_usage_save),
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.appliances_usage_save_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun HourChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = RzSpacing.minTouchTarget)
            .background(background, RzExtraShapes.control)
            .border(1.dp, border, RzExtraShapes.control)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = RzSpacing.xl, vertical = RzSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.5f),
        )
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(showBackground = true)
@Composable
private fun ApplianceUsageScreenPreview() {
    ReciboZenTheme {
        ApplianceUsageScreen(
            uiState = ApplianceUsageUiState(
                applianceId = "ps5",
                applianceName = "PlayStation 5",
                hoursPerWeek = 17,
                costMxn = 93,
                kwh = 37,
                isSupported = true,
            ),
            onBack = {},
            onSelectHours = {},
            onSave = {},
        )
    }
}
