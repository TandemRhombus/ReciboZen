package app.recibozen.android.feature.appliances

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.calculation.ApplianceAdvice
import app.recibozen.android.core.calculation.ApplianceAdviceEngine
import app.recibozen.android.core.calculation.InventoryApplianceAdvice
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzDashedCard
import app.recibozen.android.core.components.RzFilterChip
import app.recibozen.android.core.components.RzLinearProgress
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.components.RzStatusChip
import app.recibozen.android.core.components.RzTabHeader
import app.recibozen.android.core.components.rzTimeGreeting
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.OpportunityLevel
import app.recibozen.android.core.model.HouseSpace
import app.recibozen.android.demo.data.DemoReciboZenData
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.demo.repository.ReciboZenSessionState
import app.recibozen.android.demo.repository.EnergyForecastBasis
import app.recibozen.android.core.components.rzCoachAnchor
import app.recibozen.android.navigation.RzTourAnchors
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.navigateToBottomTab
import app.recibozen.android.navigation.rzViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
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
    val selectedSpaceId: String?,
    val selectedSort: ApplianceSort,
    val applianceProjectedKwh: Int = 0,
    val applianceProjectedMxn: Int = 0,
    val meterProjectedKwh: Int = 0,
    val hasCurrentMeterProjection: Boolean = false,
    val inventoryCoveragePercent: Int = 0,
    val spaceCount: Int = 0,
    val filterSpaces: List<HouseSpace> = emptyList(),
    val primaryAdviceByAppliance: Map<String, ApplianceAdvice> = emptyMap(),
    val topSavingsActions: List<InventoryApplianceAdvice> = emptyList(),
)

/** How the inventory is sorted or narrowed, independently of the space. */
enum class ApplianceSort { COST, STANDBY, INCOMPLETE, NAME }

/**
 * Space and sort are separate questions: "the kitchen ones, by cost" has to be
 * expressible. They used to share a single index, so choosing a room silently
 * reset the ordering.
 */
internal fun filterAppliances(
    appliances: List<Appliance>,
    spaceId: String?,
    sort: ApplianceSort,
    spaces: List<HouseSpace>,
): List<Appliance> {
    val space = spaces.firstOrNull { it.id == spaceId }
    val inSpace = if (space == null) appliances else appliances.filter { appliance ->
        appliance.spaceId == space.id ||
            (appliance.spaceId == null && appliance.room.equals(space.name, ignoreCase = true))
    }
    return when (sort) {
        ApplianceSort.COST -> inSpace.sortedByDescending { it.costMxn }
        ApplianceSort.STANDBY -> inSpace.filter { it.standbyCostMxn != null }.sortedByDescending { it.standbyCostMxn }
        ApplianceSort.INCOMPLETE -> inSpace.filter { it.confidence != ConfidenceLevel.HIGH }.sortedBy { it.name }
        ApplianceSort.NAME -> inSpace.sortedWith(compareBy(Appliance::room, Appliance::name))
    }
}

class AppliancesViewModel(private val repository: ReciboZenRepository) : ViewModel() {

    private val selectedSpaceId = MutableStateFlow<String?>(null)
    private val selectedSort = MutableStateFlow(ApplianceSort.COST)

    val uiState: StateFlow<AppliancesUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        selectedSpaceId,
        selectedSort,
        repository.tariffCalibration,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val session = values[0] as ReciboZenSessionState
        @Suppress("UNCHECKED_CAST")
        val importedBills = values[1] as List<app.recibozen.android.core.model.BillPeriod>
        session.toUiState(
            spaceId = values[2] as String?,
            sort = values[3] as ApplianceSort,
            referencePeriodLabel = importedBills.lastOrNull()?.monthLabel.orEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = repository.session.value.toUiState(
            selectedSpaceId.value,
            selectedSort.value,
            repository.importedBillHistory.value.lastOrNull()?.monthLabel.orEmpty(),
        ),
    )

    private fun ReciboZenSessionState.toUiState(
        spaceId: String?,
        sort: ApplianceSort,
        referencePeriodLabel: String,
    ): AppliancesUiState {
        val all = repository.appliances(this)
        val energy = repository.energySummary(this)
        // Same prorating as Balance: appliance kWh covers a full period, the
        // observed reading may cover only part of it.
        val expectedInWindow = (all.sumOf { it.kwh } * energy.observedWindowFraction).roundToInt()
        val explainedKwh = expectedInWindow.coerceAtMost(energy.observedKwh)
        val hasMeasuredReference = energy.forecastBasis == EnergyForecastBasis.METER ||
            energy.forecastBasis == EnergyForecastBasis.CLOSED_BILL
        val unidentifiedKwh = if (hasMeasuredReference) {
            (energy.observedKwh - explainedKwh).coerceAtLeast(0)
        } else null
        val unidentifiedCost = unidentifiedKwh?.let { missing ->
            if (energy.observedKwh > 0) (energy.costToDateMxn * missing.toDouble() / energy.observedKwh).toInt() else 0
        }
        val visible = filterAppliances(all, spaceId, sort, houseSpaces)
        val adviceByAppliance = all.associate { appliance ->
            appliance.id to ApplianceAdviceEngine.recommendations(appliance, energy.billingDays)
                .first()
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
        selectedSpaceId = spaceId,
        selectedSort = sort,
        applianceProjectedKwh = energy.applianceProjectedKwh,
        applianceProjectedMxn = energy.applianceProjectedCostMxn,
        meterProjectedKwh = energy.meterProjectedKwh,
        hasCurrentMeterProjection = energy.hasCurrentMeterProjection,
        inventoryCoveragePercent = if (energy.meterProjectedKwh > 0) {
            (energy.applianceProjectedKwh * 100f / energy.meterProjectedKwh)
                .roundToInt()
                .coerceIn(0, 100)
        } else 0,
        spaceCount = houseSpaces.size,
        filterSpaces = houseSpaces,
        primaryAdviceByAppliance = adviceByAppliance,
        topSavingsActions = ApplianceAdviceEngine.rankInventory(all, energy.billingDays),
    )
    }

    fun onSpaceSelected(spaceId: String?) {
        selectedSpaceId.value = spaceId
    }

    fun onSortSelected(sort: ApplianceSort) {
        selectedSort.value = sort
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
        onSpaceSelected = viewModel::onSpaceSelected,
        onSortSelected = viewModel::onSortSelected,
        onApplianceClick = { id -> navController.navigate(Routes.applianceDetail(id)) },
        onOpenPlan = { navController.navigateToBottomTab(Routes.Plan) },
        onEditUsageClick = { id -> navController.navigate(Routes.applianceUsage(id)) },
        onUnidentifiedClick = { navController.navigate(Routes.Balance) },
        onAddApplianceClick = { navController.navigate(Routes.ApplianceEmpty) },
        onSimulateClick = { navController.navigate(Routes.Simulator) },
        onManageSpacesClick = { navController.navigate(Routes.ApplianceSpaces) },
    )
}

@Composable
fun AppliancesScreen(
    uiState: AppliancesUiState,
    onProfileClick: () -> Unit,
    onSpaceSelected: (String?) -> Unit,
    onSortSelected: (ApplianceSort) -> Unit,
    onApplianceClick: (String) -> Unit,
    onOpenPlan: () -> Unit = {},
    onEditUsageClick: (String) -> Unit,
    onUnidentifiedClick: () -> Unit,
    onAddApplianceClick: () -> Unit,
    onSimulateClick: () -> Unit,
    onManageSpacesClick: () -> Unit,
    onHouseholdClick: () -> Unit = {},
) {
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

        ApplianceFilterBar(
            spaces = uiState.filterSpaces,
            selectedSpaceId = uiState.selectedSpaceId,
            selectedSort = uiState.selectedSort,
            onSpaceSelected = onSpaceSelected,
            onSortSelected = onSortSelected,
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

            if (uiState.appliances.isNotEmpty()) {
                ApplianceForecastSummary(uiState)
                InventoryAdviceSummary(uiState.topSavingsActions, onOpenPlan)
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
                    advice = uiState.primaryAdviceByAppliance[appliance.id],
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
                    modifier = Modifier.rzCoachAnchor(RzTourAnchors.APPLIANCES_ADD).weight(1f),
                )
                RzSecondaryButton(
                    text = stringResource(R.string.appliances_simulate_cta),
                    onClick = onSimulateClick,
                    modifier = Modifier.weight(1f),
                )
            }
            RzSecondaryButton(
                text = stringResource(R.string.appliances_manage_spaces_cta),
                onClick = onManageSpacesClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.appliances_spaces_summary, uiState.spaceCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Two independent rows, pinned above the list.
 *
 * They stay outside the vertical scroll on purpose: filtering changes the
 * height of the content below, and when the chips scrolled with it the page
 * jumped under the finger every time one was tapped. Space comes first because
 * "which room" is the question people actually ask.
 */
@Composable
private fun ApplianceFilterBar(
    spaces: List<HouseSpace>,
    selectedSpaceId: String?,
    selectedSort: ApplianceSort,
    onSpaceSelected: (String?) -> Unit,
    onSortSelected: (ApplianceSort) -> Unit,
) {
    val sorts = listOf(
        ApplianceSort.COST to stringResource(R.string.appliances_filter_top_spend),
        ApplianceSort.STANDBY to stringResource(R.string.appliances_filter_standby),
        ApplianceSort.INCOMPLETE to stringResource(R.string.appliances_filter_incomplete),
        ApplianceSort.NAME to stringResource(R.string.appliances_filter_room),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
        modifier = Modifier.padding(bottom = RzSpacing.sm),
    ) {
        if (spaces.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = RzSpacing.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                RzFilterChip(
                    text = stringResource(R.string.appliances_filter_all_spaces),
                    selected = selectedSpaceId == null,
                    onClick = { onSpaceSelected(null) },
                )
                spaces.forEach { space ->
                    RzFilterChip(
                        text = space.name,
                        selected = selectedSpaceId == space.id,
                        onClick = { onSpaceSelected(space.id.takeIf { it != selectedSpaceId }) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            sorts.forEach { (sort, label) ->
                RzFilterChip(
                    text = label,
                    selected = sort == selectedSort,
                    onClick = { onSortSelected(sort) },
                )
            }
        }
    }
}

@Composable
private fun ApplianceForecastSummary(uiState: AppliancesUiState) {
    RzCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.appliances_forecast_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(
                R.string.appliances_forecast_inventory_total,
                uiState.applianceProjectedKwh,
                uiState.billingDays,
                uiState.applianceProjectedMxn,
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        if (uiState.hasCurrentMeterProjection) {
            Text(
                stringResource(
                    R.string.appliances_forecast_meter_comparison,
                    uiState.meterProjectedKwh,
                    uiState.inventoryCoveragePercent,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
            RzLinearProgress(
                progress = uiState.inventoryCoveragePercent / 100f,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        } else {
            Text(
                stringResource(R.string.appliances_forecast_needs_meter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        Text(
            stringResource(R.string.appliances_forecast_no_double_count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun InventoryAdviceSummary(actions: List<InventoryApplianceAdvice>, onOpenPlan: () -> Unit) {
    if (actions.isEmpty()) return
    // The full list lives in Plan. Repeating it here would mean two places to
    // keep in sync and two answers to the same question.
    RzCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPlan),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
            Icon(RzIcons.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.appliances_advice_summary_count, actions.size, actions.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    actions.first().let { "${it.applianceName} · ${it.advice.title}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(RzIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ApplianceCard(
    appliance: Appliance,
    advice: ApplianceAdvice?,
    billingDays: Int,
    onClick: () -> Unit,
    onEditUsageClick: () -> Unit,
) {
    RzCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(RzSpacing.xl)) {
        Column(modifier = Modifier.clickable(onClick = onClick)) {
            Row {
                Text(
                    if (appliance.quantity > 1) {
                        stringResource(R.string.appliances_name_with_quantity, appliance.name, appliance.quantity)
                    } else appliance.name,
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
            if (appliance.brand != null || appliance.model != null) {
                Text(
                    listOfNotNull(appliance.brand, appliance.model).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
            }
            advice?.let {
                Text(
                    stringResource(R.string.appliances_card_advice, it.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }
            RzLinearProgress(
                progress = appliance.sharePercent / 100f,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
        }

        Row(
            modifier = Modifier.padding(top = RzSpacing.md),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            Text(
                text = if (appliance.hoursPerWeek != null) {
                    if (appliance.usageNote != null) {
                        stringResource(R.string.appliances_hours_per_week_note, appliance.hoursPerWeek, appliance.usageNote)
                    } else stringResource(R.string.appliances_hours_per_week, appliance.hoursPerWeek)
                } else {
                    stringResource(
                        R.string.appliances_period_kwh_summary,
                        appliance.unitKwh ?: appliance.kwh.toDouble() / appliance.quantity.coerceAtLeast(1),
                        billingDays,
                    )
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
                selectedSpaceId = null,
                selectedSort = ApplianceSort.COST,
            ),
            onProfileClick = {},
            onSpaceSelected = {},
            onSortSelected = {},
            onApplianceClick = {},
            onEditUsageClick = {},
            onUnidentifiedClick = {},
            onAddApplianceClick = {},
            onSimulateClick = {},
            onManageSpacesClick = {},
        )
    }
}
