package com.recibozen.app.feature.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.recibozen.app.core.components.ChartLineStyle
import com.recibozen.app.core.components.RzBannerTone
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzChartSeries
import com.recibozen.app.core.components.RzConfidenceIndicator
import com.recibozen.app.core.components.RzInlineBanner
import com.recibozen.app.core.components.RzLineChart
import com.recibozen.app.core.components.RzLinearProgress
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.components.RzStatusChip
import com.recibozen.app.core.components.RzTabHeader
import com.recibozen.app.core.components.RzTextButton
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzPalette
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlin.math.roundToInt

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
        onHouseholdClick = { },
        onToggleMinisplitAction = viewModel::onToggleMinisplitAction,
        onSeeWhyMinisplit = { navController.navigate(Routes.applianceDetail("minisplit")) },
        onViewFullAnalysis = { navController.navigate(Routes.PeriodAnalysis) },
        onApplianceClick = { id -> navController.navigate(Routes.applianceDetail(id)) },
        onUnidentifiedClick = { navController.navigate(Routes.Balance) },
        onViewAllAppliances = { navController.navigate(Routes.Appliances) },
        onRegisterReading = { navController.navigate(Routes.ReadingMethod) },
        onHowWeCalculate = { navController.navigate(Routes.HowWeCalculate) },
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
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRegisterReading,
                containerColor = RzPalette.Lime,
                contentColor = MaterialTheme.colorScheme.onBackground,
                icon = { Icon(RzIcons.Schedule, contentDescription = null) },
                text = { Text(stringResource(R.string.action_register_reading)) },
                elevation = FloatingActionButtonDefaults.elevation(),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = RzSpacing.screenHorizontal,
                end = RzSpacing.screenHorizontal,
                top = RzSpacing.sm,
                bottom = innerPadding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            item {
                RzTabHeader(
                    greeting = stringResource(R.string.home_greeting, uiState.userName),
                    householdName = uiState.householdName,
                    userInitial = uiState.userInitial,
                    onHouseholdClick = onHouseholdClick,
                    onProfileClick = onProfileClick,
                )
            }
            item { ProjectedBillCard(uiState) }
            item {
                MinisplitTipCard(
                    uiState = uiState,
                    onToggleMinisplitAction = onToggleMinisplitAction,
                    onSeeWhy = onSeeWhyMinisplit,
                )
            }
            item { PaceCard(uiState) }
            item { PeriodChartCard(uiState, onViewFullAnalysis) }
            item {
                InfluenceCard(
                    uiState = uiState,
                    onApplianceClick = onApplianceClick,
                    onUnidentifiedClick = onUnidentifiedClick,
                    onViewAll = onViewAllAppliances,
                )
            }
            item { ConfidenceCard(uiState, onRegisterReading) }
            item { HowWeCalculateRow(onHowWeCalculate) }
        }
    }
}

@Composable
private fun ProjectedBillCard(uiState: HomeUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_projected_period_label, uiState.periodLabel),
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

        Row(modifier = Modifier.padding(top = RzSpacing.lg)) {
            StatColumn(
                label = stringResource(R.string.home_stat_goal_label),
                value = "$${uiState.goalMxn}",
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg),
            modifier = Modifier.padding(top = RzSpacing.md),
        ) {
            PaceColumn(
                label = stringResource(R.string.home_pace_now_label),
                value = uiState.dailyRateNow,
                progressFraction = (uiState.dailyRateNow / 8.0).toFloat(),
                color = RzTheme.extendedColors.warning,
                contentDescription = stringResource(R.string.home_content_desc_pace_now, uiState.dailyRateNow.toString()),
                modifier = Modifier.weight(1f),
            )
            PaceColumn(
                label = stringResource(R.string.home_pace_recommended_label),
                value = uiState.dailyRateRecommended,
                progressFraction = (uiState.dailyRateRecommended / 8.0).toFloat(),
                color = RzTheme.extendedColors.success,
                contentDescription = stringResource(R.string.home_content_desc_pace_recommended, uiState.dailyRateRecommended.toString()),
                modifier = Modifier.weight(1f),
            )
        }

        val diff = ((uiState.dailyRateNow - uiState.dailyRateRecommended) * 10).roundToInt() / 10.0
        val remainingKwh = (uiState.daysRemaining * uiState.dailyRateRecommended).roundToInt()
        val diffLabel = stringResource(R.string.home_pace_explanation_bold, diff.toString())
        val annotated = buildAnnotatedString {
            append(stringResource(R.string.home_pace_explanation_prefix))
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)) {
                append(diffLabel)
            }
            append(stringResource(R.string.home_pace_explanation_suffix, remainingKwh, uiState.daysRemaining))
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
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
        RzLinearProgress(
            progress = progressFraction,
            progressColor = color,
            contentDescription = contentDescription,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PeriodChartCard(uiState: HomeUiState, onViewFullAnalysis: () -> Unit) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_period_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .background(RzTheme.extendedColors.chartObserved),
                )
                Text(
                    stringResource(R.string.home_period_legend_observed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        RzLineChart(
            series = homeChartSeries(),
            modifier = Modifier.padding(top = RzSpacing.md),
            height = 120.dp,
        )

        val projectedTotal = (uiState.observedTodayKwh.toDouble() / 0.703).roundToInt()
        val overGoalKwh = (projectedTotal - 350).coerceAtLeast(0)
        Text(
            stringResource(
                R.string.home_period_explanation,
                uiState.observedTodayKwh,
                uiState.observedDays,
                projectedTotal,
                overGoalKwh,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )

        RzSecondaryButton(
            text = stringResource(R.string.home_view_full_analysis),
            onClick = onViewFullAnalysis,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
        )
    }
}

/**
 * Stylized "so far vs. projected vs. goal pace" sparkline. [RzLineChart]
 * spaces every series evenly across the full canvas width regardless of its
 * point count, so all three series intentionally share the same point count
 * (8) to stay x-aligned: the projected/goal series repeat the observed
 * values for the first 6 points (invisible overlap) before diverging in the
 * final 2, approximating the mock's "today" break point without needing to
 * change the shared chart component.
 */
@Composable
private fun homeChartSeries(): List<RzChartSeries> {
    val observed = listOf(0.06f, 0.20f, 0.34f, 0.48f, 0.60f, 0.70f, 0.70f, 0.70f)
    val projected = listOf(0.06f, 0.20f, 0.34f, 0.48f, 0.60f, 0.70f, 0.82f, 0.92f)
    val goal = listOf(0f, 0.12f, 0.24f, 0.35f, 0.47f, 0.59f, 0.71f, 0.83f)
    return listOf(
        RzChartSeries(values = goal, color = RzTheme.extendedColors.chartGoalLine, style = ChartLineStyle.DOTTED, strokeWidth = 2.dp),
        RzChartSeries(values = projected, color = RzTheme.extendedColors.chartObserved, style = ChartLineStyle.DASHED, strokeWidth = 2.dp),
        RzChartSeries(values = observed, color = RzTheme.extendedColors.chartObserved, style = ChartLineStyle.SOLID, strokeWidth = 3.dp, markEnd = true),
    )
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
            stringResource(R.string.home_confidence_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        RzConfidenceIndicator(
            level = uiState.confidenceLevel,
            label = stringResource(R.string.home_confidence_medium_label),
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Text(
            stringResource(R.string.home_confidence_explanation),
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
                    stringResource(R.string.home_confidence_readings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 25.dp, top = 2.dp),
                )
            }
            ConfidenceStatusRow(
                icon = RzIcons.RadioButtonUnchecked,
                iconTint = MaterialTheme.colorScheme.outline,
                text = stringResource(R.string.home_confidence_schedules_status, uiState.missingApplianceSchedules),
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
