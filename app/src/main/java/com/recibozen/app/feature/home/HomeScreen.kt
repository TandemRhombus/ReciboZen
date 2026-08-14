package com.recibozen.app.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzBannerTone
import com.recibozen.app.core.components.RzBarEntry
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzConfidenceIndicator
import com.recibozen.app.core.components.RzInlineBanner
import com.recibozen.app.core.components.RzLinearProgress
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.components.RzScrollableBarChart
import com.recibozen.app.core.components.RzSegmentedTabs
import com.recibozen.app.core.components.RzStatusChip
import com.recibozen.app.core.components.RzTabHeader
import com.recibozen.app.core.components.RzTextButton
import com.recibozen.app.core.components.rzTimeGreeting
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzPalette
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.core.calculation.DacRiskLevel
import com.recibozen.app.core.calculation.ConsumptionPaceRelation
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import com.recibozen.app.navigation.navigateToBottomTab
import kotlin.math.roundToInt
import java.util.Locale

/**
 * "Inicio" bottom-nav tab root. Owns its own [Scaffold] so it can pin the
 * "Registrar lectura" FAB, independent of the app-level Scaffold that hosts
 * the bottom navigation bar.
 */
@Composable
fun HomeRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { HomeViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreen(
        uiState = uiState,
        onProfileClick = { navController.navigate(Routes.Profile) },
        onHouseholdClick = { navController.navigate(Routes.Services) },
        onToggleMinisplitAction = viewModel::onToggleMinisplitAction,
        onSeeWhyMinisplit = { navController.navigate(Routes.applianceDetail("minisplit")) },
        onViewFullAnalysis = { navController.navigate(Routes.PeriodAnalysis) },
        onApplianceClick = { id -> navController.navigate(Routes.applianceDetail(id)) },
        onUnidentifiedClick = { navController.navigate(Routes.Balance) },
        onViewAllAppliances = { navController.navigateToBottomTab(Routes.Appliances) },
        onRegisterReading = { navController.navigate(Routes.ReadingMethod) },
        onHowWeCalculate = { navController.navigate(Routes.HowWeCalculate) },
        onImportBill = { navController.navigate(Routes.BillImport) },
        onDacClick = { navController.navigate(Routes.Dac) },
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onProfileClick: () -> Unit,
    onHouseholdClick: () -> Unit,
    onToggleMinisplitAction: () -> Unit,
    onSeeWhyMinisplit: () -> Unit,
    onViewFullAnalysis: () -> Unit,
    onApplianceClick: (String) -> Unit,
    onUnidentifiedClick: () -> Unit,
    onViewAllAppliances: () -> Unit,
    onRegisterReading: () -> Unit,
    onHowWeCalculate: () -> Unit,
    onImportBill: () -> Unit = {},
    onDacClick: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            AnimatedVisibility(visible = !listState.canScrollBackward) {
                ExtendedFloatingActionButton(
                    onClick = onRegisterReading,
                    containerColor = RzPalette.Lime,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    icon = { Icon(RzIcons.Schedule, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_register_reading)) },
                    elevation = FloatingActionButtonDefaults.elevation(),
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            RzTabHeader(
                greeting = rzTimeGreeting(uiState.userName),
                householdName = uiState.householdName.ifBlank { stringResource(R.string.home_household_unconfigured) },
                userInitial = uiState.userInitial,
                onHouseholdClick = onHouseholdClick,
                onProfileClick = onProfileClick,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(
                    start = RzSpacing.screenHorizontal,
                    end = RzSpacing.screenHorizontal,
                    top = RzSpacing.sm,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
            ) {
            when (uiState.dataMode) {
                HomeDataMode.EMPTY -> item {
                    MissingUsageDataCard(uiState.profileConfigured, onImportBill, onProfileClick)
                }
                HomeDataMode.BILL_REFERENCE -> {
                    item { LatestRealBillCard(uiState) }
                    if (uiState.realBillHistory.size >= 2) item { HistoricalBillsChartCard(uiState) }
                    item { BuildProjectionCard(onRegisterReading, onViewAllAppliances) }
                    item { ApplianceDataCard(uiState, onViewAllAppliances) }
                    item { ConfidenceCard(uiState, onRegisterReading) }
                }
                HomeDataMode.PROJECTION -> {
                    item { ProjectedBillCard(uiState) }
                    item { PaceCard(uiState) }
                    item { PeriodProgressCard(uiState, onViewFullAnalysis) }
                    if (uiState.realBillHistory.size >= 2) item { HistoricalBillsChartCard(uiState) }
                    item { ApplianceDataCard(uiState, onViewAllAppliances) }
                    item { ConfidenceCard(uiState, onRegisterReading) }
                }
            }
            item { DacRiskCard(uiState, onDacClick) }
            if (uiState.dataMode != HomeDataMode.EMPTY) {
                item { HowWeCalculateRow(onHowWeCalculate) }
            }
            }
        }
    }
}

@Composable
private fun DacRiskCard(uiState: HomeUiState, onClick: () -> Unit) {
    val risk = uiState.dacRisk
    val title = when (risk.level) {
        DacRiskLevel.NORMAL -> stringResource(R.string.home_dac_normal_title)
        DacRiskLevel.WATCH -> stringResource(R.string.home_dac_watch_title)
        DacRiskLevel.RISK -> stringResource(R.string.home_dac_risk_title)
        DacRiskLevel.ABOVE_LIMIT -> stringResource(R.string.home_dac_over_title)
        DacRiskLevel.ALREADY_DAC -> stringResource(R.string.home_dac_already_title)
        DacRiskLevel.UNKNOWN -> stringResource(
            if (risk.monthlyAverageKwh != null) R.string.home_dac_partial_title else R.string.home_dac_setup_title,
        )
        else -> stringResource(R.string.home_dac_setup_title)
    }
    val body = when {
        risk.level == DacRiskLevel.ALREADY_DAC -> stringResource(R.string.home_dac_already_body)
        risk.monthlyLimitKwh == null -> stringResource(R.string.home_dac_setup_body)
        risk.monthlyAverageKwh == null -> stringResource(R.string.home_dac_import_body, risk.monthlyLimitKwh)
        !risk.hasCompleteHistory -> stringResource(
            R.string.home_dac_partial_body,
            risk.monthlyAverageKwh.roundToInt(),
            risk.monthlyLimitKwh,
            risk.coveredMonths,
        )
        risk.wouldExceedWithProjection -> stringResource(
            R.string.home_dac_projection_risk_body,
            risk.projectedNextBillKwh ?: 0,
            risk.recommendedNextBillMaxKwh ?: 0,
        )
        else -> stringResource(
            R.string.home_dac_margin_body,
            risk.monthlyAverageKwh.roundToInt(),
            risk.monthlyLimitKwh,
        )
    }
    RzCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
            Icon(RzIcons.Monitoring, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(RzIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.home_dac_action),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun MissingUsageDataCard(
    profileConfigured: Boolean,
    onImportBill: () -> Unit,
    onProfileClick: () -> Unit,
) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        RzPrimaryButton(
            stringResource(R.string.home_empty_import),
            onImportBill,
            Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
        )
        if (!profileConfigured) {
            RzSecondaryButton(
                stringResource(R.string.home_empty_profile),
                onProfileClick,
                Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
            )
        }
    }
}

@Composable
private fun LatestRealBillCard(uiState: HomeUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_latest_bill_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            RzStatusChip(
                text = stringResource(R.string.home_latest_bill_badge),
                icon = RzIcons.CheckCircle,
                containerColor = RzTheme.extendedColors.success.copy(alpha = 0.14f),
                contentColor = RzTheme.extendedColors.success,
            )
        }
        Text(
            "$${uiState.latestBillMxn}",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.home_latest_bill_detail, uiState.periodLabel, uiState.latestBillKwh),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.home_latest_bill_history, uiState.importedBillCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
    }
}

@Composable
private fun HistoricalBillsChartCard(uiState: HomeUiState) {
    val bills = uiState.realBillHistory
    var selectedIndex by remember(bills.size) { mutableIntStateOf(bills.lastIndex.coerceAtLeast(0)) }
    val maximum = bills.maxOfOrNull { it.consumptionKwh }?.coerceAtLeast(1) ?: 1
    val entries = bills.map { bill ->
        RzBarEntry(
            categoryLabel = bill.monthLabel,
            valueLabel = "${bill.consumptionKwh}",
            heightFraction = bill.consumptionKwh.toFloat() / maximum,
            color = RzTheme.extendedColors.chartHistoryBar,
        )
    }
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_real_history_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            RzStatusChip(
                text = stringResource(R.string.home_real_history_badge, bills.size),
                icon = RzIcons.CheckCircle,
                containerColor = RzTheme.extendedColors.success.copy(alpha = 0.14f),
                contentColor = RzTheme.extendedColors.success,
            )
        }
        RzScrollableBarChart(
            entries = entries,
            selectedIndex = selectedIndex.coerceIn(0, entries.lastIndex),
            onSelected = { selectedIndex = it },
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        val selected = bills[selectedIndex.coerceIn(0, bills.lastIndex)]
        Text(
            stringResource(R.string.chart_selected_bill, selected.monthLabel, selected.consumptionKwh, selected.amountMxn),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.chart_swipe_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
    }
}

@Composable
private fun BuildProjectionCard(onRegisterReading: () -> Unit, onViewAllAppliances: () -> Unit) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_build_projection_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.home_build_projection_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        RzPrimaryButton(
            stringResource(R.string.home_register_reading_button),
            onRegisterReading,
            Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
        )
        RzSecondaryButton(
            stringResource(R.string.home_view_all),
            onViewAllAppliances,
            Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun ApplianceDataCard(uiState: HomeUiState, onViewAllAppliances: () -> Unit) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_appliance_data_title), style = MaterialTheme.typography.titleLarge)
        Text(
            if (uiState.hasAppliances) stringResource(R.string.home_appliance_data_count, uiState.applianceCount)
            else stringResource(R.string.home_appliance_data_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        RzSecondaryButton(
            stringResource(R.string.home_view_all),
            onViewAllAppliances,
            Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
        )
    }
}

@Composable
private fun ProjectedBillCard(uiState: HomeUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.home_projected_period_label,
                    uiState.currentPeriodLabel.ifBlank { stringResource(R.string.home_current_period_fallback) },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            RzStatusChip(
                text = stringResource(R.string.home_projection_chip),
                icon = RzIcons.Timeline,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            modifier = Modifier.padding(top = RzSpacing.sm),
        ) {
            Text(
                "$${uiState.projectedMxn}",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.home_currency_suffix),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        Text(
            stringResource(R.string.home_range_text, "$${uiState.rangeLowMxn}", "$${uiState.rangeHighMxn}"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (uiState.goalMxn > 0) {
            val statusText = if (uiState.isWithinGoal) {
                stringResource(R.string.home_status_within_goal, "$${uiState.goalMxn}", uiState.daysRemaining)
            } else {
                stringResource(
                    R.string.home_status_over_goal,
                    "$${uiState.projectedMxn - uiState.goalMxn}",
                    "$${uiState.goalMxn}",
                    uiState.daysRemaining,
                )
            }
            RzInlineBanner(
                text = statusText,
                tone = if (uiState.isWithinGoal) RzBannerTone.SUCCESS else RzBannerTone.WARNING,
                icon = if (uiState.isWithinGoal) RzIcons.CheckCircle else RzIcons.ErrorInfo,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
        }

        Row(modifier = Modifier.padding(top = RzSpacing.lg)) {
            StatColumn(
                label = stringResource(R.string.home_stat_goal_label),
                value = uiState.goalMxn.takeIf { it > 0 }?.let { "$$it" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            StatColumn(
                label = stringResource(R.string.home_stat_days_label),
                value = stringResource(R.string.home_stat_days_value, uiState.daysRemaining),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            StatColumn(
                label = stringResource(R.string.home_stat_observed_label),
                value = stringResource(R.string.home_stat_observed_value, uiState.observedTodayKwh),
                modifier = Modifier.weight(1f),
            )
        }

        if (uiState.latestBillKwh > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = RzSpacing.lg)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                Icon(RzIcons.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(
                        R.string.home_previous_bill_context,
                        uiState.periodLabel,
                        uiState.latestBillKwh,
                        uiState.latestBillMxn,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun MinisplitTipCard(
    uiState: HomeUiState,
    onToggleMinisplitAction: () -> Unit,
    onSeeWhy: () -> Unit,
) {
    RzCard(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(RzIcons.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.home_tip_eyebrow),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            stringResource(R.string.home_tip_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.home_tip_savings, uiState.minisplitSavingsLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            modifier = Modifier.padding(top = RzSpacing.md),
        ) {
            RzStatusChip(
                text = stringResource(R.string.home_chip_effort_low),
                icon = RzIcons.Bolt,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RzStatusChip(
                text = stringResource(R.string.home_chip_confidence_medium),
                icon = RzIcons.Help,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = RzTheme.extendedColors.warningText,
            )
        }

        val isTracking = uiState.minisplitStatus == PlanActionStatus.TRACKING
        RzPrimaryButton(
            text = stringResource(if (isTracking) R.string.home_tracking else R.string.home_add_to_plan),
            onClick = onToggleMinisplitAction,
            containerColor = if (isTracking) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
            contentColor = if (isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
        )
        RzTextButton(
            text = stringResource(R.string.home_see_why),
            onClick = onSeeWhy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PaceCard(uiState: HomeUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.home_pace_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Scale both bars off whichever value is larger instead of a fixed
        // demo constant, so real households whose daily rate is above (or
        // well below) the old ~7-8 kWh/day demo range still compare visually.
        val paceScale = maxOf(uiState.dailyRateNow, uiState.dailyRateRecommended, 0.1)
        Row(
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg),
            modifier = Modifier.padding(top = RzSpacing.md),
        ) {
            PaceColumn(
                label = stringResource(R.string.home_pace_now_label),
                value = uiState.dailyRateNow,
                progressFraction = (uiState.dailyRateNow / paceScale).toFloat(),
                color = if (uiState.paceRelation == ConsumptionPaceRelation.ABOVE_RECOMMENDED) {
                    RzTheme.extendedColors.warning
                } else RzTheme.extendedColors.success,
                contentDescription = stringResource(R.string.home_content_desc_pace_now, formatRate(uiState.dailyRateNow)),
                modifier = Modifier.weight(1f),
            )
            PaceColumn(
                label = stringResource(R.string.home_pace_recommended_label),
                value = uiState.dailyRateRecommended,
                progressFraction = (uiState.dailyRateRecommended / paceScale).toFloat(),
                color = RzTheme.extendedColors.success,
                contentDescription = stringResource(R.string.home_content_desc_pace_recommended, formatRate(uiState.dailyRateRecommended)),
                displayOverride = stringResource(R.string.home_pace_no_goal).takeIf { uiState.goalMxn <= 0 },
                modifier = Modifier.weight(1f),
            )
        }
        if (uiState.goalMxn <= 0) {
            Text(
                stringResource(R.string.home_pace_no_goal_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
            return@RzCard
        }

        val diff = kotlin.math.abs(uiState.dailyRateNow - uiState.dailyRateRecommended)
        val diffLabel = when (uiState.paceRelation) {
            ConsumptionPaceRelation.ABOVE_RECOMMENDED -> stringResource(
                R.string.home_pace_explanation_more,
                formatRate(diff),
            )
            ConsumptionPaceRelation.BELOW_RECOMMENDED -> stringResource(
                R.string.home_pace_explanation_less,
                formatRate(diff),
            )
            ConsumptionPaceRelation.ON_RECOMMENDED -> stringResource(R.string.home_pace_explanation_on_track)
        }
        val annotated = buildAnnotatedString {
            append(stringResource(R.string.home_pace_explanation_intro).trimEnd())
            append(" ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)) {
                append(diffLabel)
            }
            append(" ")
            append(
                stringResource(
                    R.string.home_pace_explanation_remaining,
                    uiState.remainingGoalKwh,
                    uiState.daysRemaining,
                ).trimStart(),
            )
        }
        Text(
            annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
    }
}

@Composable
private fun PaceColumn(
    label: String,
    value: Double,
    progressFraction: Float,
    color: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    displayOverride: String? = null,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            displayOverride ?: formatRate(value),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
        RzLinearProgress(
            progress = if (displayOverride != null) 0f else progressFraction,
            progressColor = color,
            contentDescription = contentDescription,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun formatRate(value: Double): String = String.format(Locale.forLanguageTag("es-MX"), "%.1f", value)

@Composable
private fun PeriodProgressCard(uiState: HomeUiState, onViewFullAnalysis: () -> Unit) {
    var selectedMetric by remember(uiState.currentPeriodLabel, uiState.observedTodayKwh, uiState.projectedKwh) {
        mutableIntStateOf(1)
    }
    val options = listOf(
        stringResource(R.string.home_period_tab_today),
        stringResource(R.string.home_period_tab_close),
        stringResource(R.string.home_period_tab_previous),
    )
    val selectedValue = when (selectedMetric) {
        0 -> uiState.observedTodayKwh
        2 -> uiState.latestBillKwh
        else -> uiState.projectedKwh
    }
    val selectedDetail = when (selectedMetric) {
        0 -> stringResource(
            R.string.home_period_today_detail,
            uiState.observedDays,
            uiState.latestReadingDateLabel,
        )
        2 -> stringResource(R.string.home_period_previous_detail, uiState.periodLabel)
        else -> stringResource(
            R.string.home_period_projection_detail,
            uiState.currentPeriodDateRange.ifBlank { uiState.currentPeriodLabel },
        )
    }
    val comparisonMax = maxOf(
        uiState.observedTodayKwh,
        uiState.projectedKwh,
        uiState.goalKwh,
        uiState.latestBillKwh,
        1,
    )
    val timeProgress = if (uiState.billingDays > 0) {
        uiState.observedDays.toFloat() / uiState.billingDays
    } else 0f
    val previousDifference = uiState.projectedKwh - uiState.latestBillKwh
    val goalDifference = uiState.projectedKwh - uiState.goalKwh

    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_period_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    uiState.currentPeriodDateRange.ifBlank { uiState.currentPeriodLabel },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            RzStatusChip(
                text = stringResource(R.string.home_period_day_chip, uiState.observedDays, uiState.billingDays),
                icon = RzIcons.Schedule,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.home_period_time_elapsed), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.home_period_time_percent, (timeProgress * 100).roundToInt().coerceIn(0, 100)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RzLinearProgress(
            progress = timeProgress,
            progressColor = MaterialTheme.colorScheme.primary,
            contentDescription = stringResource(
                R.string.home_period_time_content_description,
                uiState.observedDays,
                uiState.billingDays,
            ),
            modifier = Modifier.padding(top = 6.dp),
        )

        RzSegmentedTabs(
            options = options,
            selectedIndex = selectedMetric,
            onSelectedChange = { selectedMetric = it },
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
        )
        Text(
            stringResource(R.string.home_period_kwh_value, selectedValue),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Text(
            selectedDetail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        RzLinearProgress(
            progress = selectedValue.toFloat() / comparisonMax,
            progressColor = when (selectedMetric) {
                0 -> MaterialTheme.colorScheme.primary
                1 -> if (uiState.goalKwh > 0 && goalDifference > 0) RzTheme.extendedColors.warning else RzTheme.extendedColors.success
                else -> MaterialTheme.colorScheme.outline
            },
            contentDescription = stringResource(R.string.home_period_metric_content_description, selectedValue, comparisonMax),
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 kWh", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(
                stringResource(R.string.home_period_common_scale, comparisonMax),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        val outcomeText = when {
            uiState.goalKwh > 0 && goalDifference <= 0 -> stringResource(
                R.string.home_period_goal_within,
                -goalDifference,
                uiState.goalKwh,
            )
            uiState.goalKwh > 0 -> stringResource(R.string.home_period_goal_over, goalDifference, uiState.goalKwh)
            previousDifference <= 0 -> stringResource(R.string.home_period_previous_lower, -previousDifference)
            else -> stringResource(R.string.home_period_previous_higher, previousDifference)
        }
        RzInlineBanner(
            text = outcomeText,
            tone = if ((uiState.goalKwh > 0 && goalDifference <= 0) || (uiState.goalKwh <= 0 && previousDifference <= 0)) {
                RzBannerTone.SUCCESS
            } else RzBannerTone.WARNING,
            icon = if ((uiState.goalKwh > 0 && goalDifference <= 0) || (uiState.goalKwh <= 0 && previousDifference <= 0)) {
                RzIcons.CheckCircle
            } else RzIcons.ErrorInfo,
            modifier = Modifier.padding(top = RzSpacing.lg),
        )
        if (uiState.latestBillKwh > 0) {
            Text(
                if (previousDifference <= 0) stringResource(R.string.home_period_previous_lower, -previousDifference)
                else stringResource(R.string.home_period_previous_higher, previousDifference),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }

        RzSecondaryButton(
            text = stringResource(R.string.home_view_full_analysis),
            onClick = onViewFullAnalysis,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
        )
    }
}

@Composable
private fun InfluenceCard(
    uiState: HomeUiState,
    onApplianceClick: (String) -> Unit,
    onUnidentifiedClick: () -> Unit,
    onViewAll: () -> Unit,
) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_influence_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            RzTextButton(text = stringResource(R.string.home_view_all), onClick = onViewAll)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
            modifier = Modifier.padding(top = RzSpacing.sm),
        ) {
            InfluenceRow(
                name = stringResource(R.string.home_appliance_minisplit),
                costShare = stringResource(R.string.home_cost_share_format, uiState.minisplitCostMxn, uiState.minisplitSharePercent),
                fraction = uiState.minisplitSharePercent / 100f,
                onClick = { onApplianceClick("minisplit") },
            )
            InfluenceRow(
                name = stringResource(R.string.home_appliance_refrigerador),
                costShare = stringResource(R.string.home_cost_share_format, uiState.refrigeradorCostMxn, uiState.refrigeradorSharePercent),
                fraction = uiState.refrigeradorSharePercent / 100f,
                onClick = { onApplianceClick("refrigerador") },
            )
            InfluenceRow(
                name = stringResource(R.string.home_appliance_ps5),
                costShare = stringResource(R.string.home_cost_share_format, uiState.ps5CostMxn, uiState.ps5SharePercent),
                fraction = uiState.ps5SharePercent / 100f,
                onClick = { onApplianceClick("ps5") },
            )
            InfluenceRow(
                name = stringResource(R.string.home_unidentified),
                costShare = stringResource(R.string.home_cost_share_format, uiState.unidentifiedCostMxn, uiState.unidentifiedSharePercent),
                fraction = uiState.unidentifiedSharePercent / 100f,
                onClick = onUnidentifiedClick,
                muted = true,
            )
        }
    }
}

@Composable
private fun InfluenceRow(
    name: String,
    costShare: String,
    fraction: Float,
    onClick: () -> Unit,
    muted: Boolean = false,
) {
    val textColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = RzSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = textColor, modifier = Modifier.weight(1f))
            Text(costShare, style = MaterialTheme.typography.titleSmall, color = textColor)
        }
        RzLinearProgress(
            progress = fraction,
            progressColor = if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ConfidenceCard(uiState: HomeUiState, onRegisterReading: () -> Unit) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                if (uiState.dataMode == HomeDataMode.BILL_REFERENCE) R.string.home_data_quality_title
                else R.string.home_confidence_title,
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        RzConfidenceIndicator(
            level = uiState.confidenceLevel,
            label = if (uiState.dataMode == HomeDataMode.BILL_REFERENCE) {
                stringResource(R.string.home_data_quality_label, uiState.previousBillsLoadedCount)
            } else stringResource(
                when (uiState.confidenceLevel) {
                    com.recibozen.app.core.model.ConfidenceLevel.HIGH -> R.string.home_confidence_high_label
                    com.recibozen.app.core.model.ConfidenceLevel.MEDIUM -> R.string.home_confidence_medium_label
                    com.recibozen.app.core.model.ConfidenceLevel.LOW -> R.string.home_confidence_low_label
                },
            ),
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Text(
            stringResource(
                if (uiState.dataMode == HomeDataMode.BILL_REFERENCE) R.string.home_data_quality_explanation
                else R.string.home_confidence_explanation,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            modifier = Modifier.padding(top = RzSpacing.md),
        ) {
            ConfidenceStatusRow(
                icon = RzIcons.CheckCircle,
                iconTint = RzTheme.extendedColors.success,
                text = stringResource(R.string.home_confidence_bills_status, uiState.previousBillsLoadedCount),
            )
            Column {
                ConfidenceStatusRow(
                    icon = RzIcons.Pending,
                    iconTint = RzTheme.extendedColors.warning,
                    text = uiState.recentReadingsCountLabel,
                )
                Text(
                    stringResource(
                        if (uiState.dataMode == HomeDataMode.BILL_REFERENCE) R.string.home_confidence_first_live_reading_hint
                        else R.string.home_confidence_readings_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 25.dp, top = 2.dp),
                )
            }
            ConfidenceStatusRow(
                icon = RzIcons.RadioButtonUnchecked,
                iconTint = MaterialTheme.colorScheme.outline,
                text = if (uiState.hasAppliances) stringResource(R.string.home_confidence_schedules_status, uiState.missingApplianceSchedules)
                else stringResource(R.string.home_confidence_no_appliances),
            )
        }
        RzSecondaryButton(
            text = stringResource(R.string.home_register_reading_button),
            onClick = onRegisterReading,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
        )
    }
}

@Composable
private fun ConfidenceStatusRow(icon: ImageVector, iconTint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(17.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HowWeCalculateRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = RzSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
    ) {
        Icon(RzIcons.Functions, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.home_how_we_calculate),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(RzIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Preview(showBackground = true, heightDp = 2400)
@Composable
private fun HomeScreenOverGoalPreview() {
    ReciboZenTheme {
        HomeScreen(
            uiState = HomeUiState(
                isWithinGoal = false,
                minisplitStatus = PlanActionStatus.SUGGESTED,
                minisplitSavingsLabel = "\$64–\$82",
                minisplitCostMxn = 322,
                minisplitSharePercent = 38,
                refrigeradorCostMxn = 141,
                refrigeradorSharePercent = 17,
                ps5CostMxn = 93,
                ps5SharePercent = 11,
            ),
            onProfileClick = {},
            onHouseholdClick = {},
            onToggleMinisplitAction = {},
            onSeeWhyMinisplit = {},
            onViewFullAnalysis = {},
            onApplianceClick = {},
            onUnidentifiedClick = {},
            onViewAllAppliances = {},
            onRegisterReading = {},
            onHowWeCalculate = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 2400)
@Composable
private fun HomeScreenWithinGoalPreview() {
    ReciboZenTheme {
        HomeScreen(
            uiState = HomeUiState(
                projectedMxn = 760,
                rangeLowMxn = 724,
                rangeHighMxn = 804,
                isWithinGoal = true,
                minisplitStatus = PlanActionStatus.TRACKING,
                minisplitSavingsLabel = "\$64–\$82",
                minisplitCostMxn = 322,
                minisplitSharePercent = 38,
                refrigeradorCostMxn = 141,
                refrigeradorSharePercent = 17,
                ps5CostMxn = 93,
                ps5SharePercent = 11,
            ),
            onProfileClick = {},
            onHouseholdClick = {},
            onToggleMinisplitAction = {},
            onSeeWhyMinisplit = {},
            onViewFullAnalysis = {},
            onApplianceClick = {},
            onUnidentifiedClick = {},
            onViewAllAppliances = {},
            onRegisterReading = {},
            onHowWeCalculate = {},
        )
    }
}
