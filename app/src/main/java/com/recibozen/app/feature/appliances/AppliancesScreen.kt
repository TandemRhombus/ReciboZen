package com.recibozen.app.feature.appliances

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzDashedCard
import com.recibozen.app.core.components.RzFilterChip
import com.recibozen.app.core.components.RzLinearProgress
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.components.RzStatusChip
import com.recibozen.app.core.components.RzTabHeader
import com.recibozen.app.core.components.rzTimeGreeting
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.OpportunityLevel
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenSessionState
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Visible appliance list plus the active sorting/filter mode. */
data class AppliancesUiState(
    val greetingUserName: String,
    val householdName: String,
    val userInitial: String,
    val appliances: List<Appliance>,
    val unidentifiedCostMxn: Int?,
    val unidentifiedKwh: Int?,
    val billingDays: Int,
    val referencePeriodLabel: String,
    val selectedFilterIndex: Int,
)

class AppliancesViewModel(private val repository: ReciboZenRepository) : ViewModel() {

    private val selectedFilterIndex = MutableStateFlow(0)

    val uiState: StateFlow<AppliancesUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        selectedFilterIndex,
    ) { session, importedBills, filterIndex ->
        session.toUiState(filterIndex, importedBills.lastOrNull()?.monthLabel.orEmpty())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = repository.session.value.toUiState(
            selectedFilterIndex.value,
            repository.importedBillHistory.value.lastOrNull()?.monthLabel.orEmpty(),
        ),
    )

    private fun ReciboZenSessionState.toUiState(filterIndex: Int, referencePeriodLabel: String): AppliancesUiState {
        val all = repository.appliances(this)
        val energy = repository.energySummary(this)
        val explainedKwh = all.sumOf { it.kwh }.coerceAtMost(energy.observedKwh)
        val unidentifiedKwh = if (energy.hasUsageEvidence) (energy.observedKwh - explainedKwh).coerceAtLeast(0) else null
        val unidentifiedCost = unidentifiedKwh?.let { missing ->
            if (energy.observedKwh > 0) (energy.costToDateMxn * missing.toDouble() / energy.observedKwh).toInt() else 0
        }
        val visible = when (filterIndex) {
            1 -> all.sortedWith(compareBy(Appliance::room, Appliance::name))
            2 -> all.filter { it.standbyCostMxn != null }.sortedByDescending { it.standbyCostMxn }
            3 -> all.filter { it.confidence != ConfidenceLevel.HIGH }.sortedBy { it.name }
            else -> all.sortedByDescending { it.costMxn }
        }
        return AppliancesUiState(
        greetingUserName = userName,
        householdName = householdName,
        userInitial = userName.firstOrNull()?.uppercase() ?: "?",
        appliances = visible,
        unidentifiedCostMxn = unidentifiedCost,
        unidentifiedKwh = unidentifiedKwh,
        billingDays = energy.billingDays.takeIf { it > 0 }
            ?: if (billingCycle.contains("bi", ignoreCase = true)) 61 else 30,
        referencePeriodLabel = referencePeriodLabel,
        selectedFilterIndex = filterIndex,
    )
    }

    fun onFilterSelected(index: Int) {
        selectedFilterIndex.value = index
    }
}

@Composable
fun AppliancesRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { AppliancesViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppliancesScreen(
        uiState = uiState,
        onProfileClick = { navController.navigate(Routes.Profile) },
        onHouseholdClick = { navController.navigate(Routes.Services) },
        onFilterSelected = viewModel::onFilterSelected,
        onApplianceClick = { id -> navController.navigate(Routes.applianceDetail(id)) },
        onEditUsageClick = { id -> navController.navigate(Routes.applianceUsage(id)) },
        onUnidentifiedClick = { navController.navigate(Routes.Balance) },
        onAddApplianceClick = { navController.navigate(Routes.ApplianceEmpty) },
        onSimulateClick = { navController.navigate(Routes.Simulator) },
    )
}

@Composable
fun AppliancesScreen(
    uiState: AppliancesUiState,
    onProfileClick: () -> Unit,
    onFilterSelected: (Int) -> Unit,
    onApplianceClick: (String) -> Unit,
    onEditUsageClick: (String) -> Unit,
    onUnidentifiedClick: () -> Unit,
    onAddApplianceClick: () -> Unit,
    onSimulateClick: () -> Unit,
    onHouseholdClick: () -> Unit = {},
) {
    val filterLabels = listOf(
        stringResource(R.string.appliances_filter_top_spend),
        stringResource(R.string.appliances_filter_room),
        stringResource(R.string.appliances_filter_standby),
        stringResource(R.string.appliances_filter_incomplete),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        RzTabHeader(
            greeting = rzTimeGreeting(uiState.greetingUserName),
            householdName = uiState.householdName.ifBlank { stringResource(R.string.home_household_unconfigured) },
            userInitial = uiState.userInitial,
            onHouseholdClick = onHouseholdClick,
            onProfileClick = onProfileClick,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            Column {
                Text(
                    stringResource(R.string.appliances_headline),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.appliances_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.appliances_period_context, uiState.billingDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                filterLabels.forEachIndexed { index, label ->
                    RzFilterChip(
                        text = label,
                        selected = index == uiState.selectedFilterIndex,
                        onClick = { onFilterSelected(index) },
                    )
                }
            }

            if (uiState.appliances.isEmpty()) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appliances_real_empty_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.appliances_real_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
            }

            uiState.appliances.forEach { appliance ->
                ApplianceCard(
                    appliance = appliance,
                    billingDays = uiState.billingDays,
                    onClick = { onApplianceClick(appliance.id) },
                    onEditUsageClick = { onEditUsageClick(appliance.id) },
                )
            }

            RzDashedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onUnidentifiedClick),
            ) {
                Row {
                    Text(
                        stringResource(R.string.appliances_unidentified_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        uiState.unidentifiedCostMxn?.let { cost ->
                            stringResource(R.string.appliances_unidentified_value, cost, uiState.unidentifiedKwh ?: 0)
                        } ?: stringResource(R.string.appliances_unidentified_pending),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (uiState.unidentifiedKwh != null) {
                        stringResource(
                            R.string.appliances_unidentified_description_dynamic,
                            uiState.unidentifiedKwh,
                            uiState.referencePeriodLabel.ifBlank { stringResource(R.string.appliances_current_reference) },
                        )
                    } else stringResource(R.string.appliances_unidentified_description_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                Text(
                    stringResource(R.string.appliances_unidentified_cta),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                RzPrimaryButton(
                    text = stringResource(R.string.appliances_add_cta),
                    onClick = onAddApplianceClick,
                    modifier = Modifier.weight(1f),
                )
                RzSecondaryButton(
                    text = stringResource(R.string.appliances_simulate_cta),
                    onClick = onSimulateClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ApplianceCard(
    appliance: Appliance,
    billingDays: Int,
    onClick: () -> Unit,
    onEditUsageClick: () -> Unit,
) {
    RzCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(RzSpacing.xl)) {
        Column(modifier = Modifier.clickable(onClick = onClick)) {
            Row {
                Text(
                    appliance.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (appliance.costMxn > 0) stringResource(R.string.appliances_cost_period, appliance.costMxn, billingDays)
                    else stringResource(R.string.appliances_cost_pending),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = if (appliance.standbyCostMxn != null) {
                    stringResource(
                        R.string.appliances_card_subtitle_with_standby,
                        appliance.room,
                        appliance.kwh,
                        appliance.sharePercent,
                        appliance.standbyCostMxn,
                        billingDays,
                    )
                } else {
                    stringResource(R.string.appliances_card_subtitle, appliance.room, appliance.kwh, billingDays, appliance.sharePercent)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
            RzLinearProgress(
                progress = appliance.sharePercent / 100f,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
        }

        if (appliance.hoursPerWeek != null) {
            Row(
                modifier = Modifier.padding(top = RzSpacing.md),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                Text(
                    text = if (appliance.usageNote != null) {
                        stringResource(R.string.appliances_hours_per_week_note, appliance.hoursPerWeek, appliance.usageNote)
                    } else {
                        stringResource(R.string.appliances_hours_per_week, appliance.hoursPerWeek)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                RzSecondaryButton(
                    text = stringResource(R.string.appliances_edit_usage),
                    onClick = onEditUsageClick,
                )
            }
        } else {
            Text(
                stringResource(R.string.appliances_fridge_cycle_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
        }

        Row(
            modifier = Modifier.padding(top = RzSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            ApplianceStatusChips(appliance)
        }
    }
}

@Composable
private fun ApplianceStatusChips(appliance: Appliance) {
    val ext = RzTheme.extendedColors
    if (appliance.isEssential) {
        RzStatusChip(
            text = stringResource(R.string.appliances_chip_essential),
            icon = RzIcons.Lock,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when (appliance.opportunity) {
        OpportunityLevel.HIGH -> RzStatusChip(
            text = stringResource(R.string.appliances_chip_high_opportunity),
            icon = RzIcons.Savings,
            containerColor = ext.successContainer,
            contentColor = ext.success,
        )
        OpportunityLevel.MEDIUM -> RzStatusChip(
            text = stringResource(R.string.appliances_chip_medium_opportunity),
            icon = RzIcons.Savings,
            containerColor = ext.warningContainer,
            contentColor = ext.warningText,
        )
        OpportunityLevel.NONE -> Unit
    }
    val (confidenceLabelRes, confidenceIcon, confidenceContainer, confidenceContent) = when (appliance.confidence) {
        ConfidenceLevel.HIGH -> ConfidenceChipStyle(R.string.appliances_chip_confidence_high, RzIcons.CheckCircle, ext.successContainer, ext.success)
        ConfidenceLevel.MEDIUM -> ConfidenceChipStyle(R.string.appliances_chip_confidence_medium, RzIcons.GppMaybe, ext.warningContainer, ext.warningText)
        ConfidenceLevel.LOW -> ConfidenceChipStyle(R.string.appliances_chip_confidence_low, RzIcons.Help, ext.warningContainer, ext.warningText)
    }
    RzStatusChip(
        text = stringResource(confidenceLabelRes),
        icon = confidenceIcon,
        containerColor = confidenceContainer,
        contentColor = confidenceContent,
    )
}

private data class ConfidenceChipStyle(
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val containerColor: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color,
)

@Preview(showBackground = true)
@Composable
private fun AppliancesScreenPreview() {
    ReciboZenTheme {
        AppliancesScreen(
            uiState = AppliancesUiState(
                greetingUserName = DemoReciboZenData.USER_NAME,
                householdName = DemoReciboZenData.HOUSEHOLD_NAME,
                userInitial = DemoReciboZenData.USER_INITIAL,
                appliances = DemoReciboZenData.appliances,
                unidentifiedCostMxn = DemoReciboZenData.UNIDENTIFIED_COST_MXN,
                unidentifiedKwh = DemoReciboZenData.UNEXPLAINED_KWH,
                billingDays = 61,
                referencePeriodLabel = DemoReciboZenData.PERIOD_LABEL,
                selectedFilterIndex = 0,
            ),
            onProfileClick = {},
            onFilterSelected = {},
            onApplianceClick = {},
            onEditUsageClick = {},
            onUnidentifiedClick = {},
            onAddApplianceClick = {},
            onSimulateClick = {},
        )
    }
}
