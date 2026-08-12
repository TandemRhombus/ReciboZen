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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.map
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

    val uiState: StateFlow<ApplianceUsageUiState> = repository.session.map { session ->
        toUiState(repository.appliance(applianceId, session))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = toUiState(repository.appliance(applianceId)),
    )

    private fun toUiState(appliance: Appliance?): ApplianceUsageUiState {
        val baseHours = appliance?.hoursPerWeek ?: 0
        return ApplianceUsageUiState(
            applianceId = applianceId,
            applianceName = appliance?.name,
            hoursPerWeek = baseHours,
            costMxn = appliance?.costMxn ?: 0,
            kwh = appliance?.kwh ?: 0,
            isSupported = appliance?.hoursPerWeek != null,
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
                            uiState.hoursPerWeek.toString(),
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

                    FlowRow(
                        modifier = Modifier.padding(top = RzSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    ) {
                        ApplianceHourOptions.forEach { option ->
                            val selected = option.representativeHours == uiState.hoursPerWeek
                            HourChip(
                                label = stringResource(option.labelRes),
                                selected = selected,
                                onClick = { onSelectHours(option.representativeHours) },
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
                        DetailRow(stringResource(R.string.appliances_usage_detail_days_label), stringResource(R.string.appliances_usage_detail_days_value))
                        DetailRow(stringResource(R.string.appliances_usage_detail_standby_label), stringResource(R.string.appliances_usage_detail_standby_value))
                        DetailRow(stringResource(R.string.appliances_usage_detail_seasonal_label), stringResource(R.string.appliances_usage_detail_seasonal_value))
                    }
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
