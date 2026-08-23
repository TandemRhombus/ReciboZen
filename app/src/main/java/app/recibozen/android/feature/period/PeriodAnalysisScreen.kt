package app.recibozen.android.feature.period

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.calculation.BillChangeComponent
import app.recibozen.android.core.calculation.BillChangeExplanation
import app.recibozen.android.core.calculation.BillChangeFactor
import app.recibozen.android.core.components.RzBannerTone
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.ChartLineStyle
import app.recibozen.android.core.components.RzChartSeries
import app.recibozen.android.core.components.RzLineChart
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzInlineBanner
import app.recibozen.android.core.components.RzLinearProgress
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.components.RzStatusChip
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.rzViewModel
import java.util.Locale
import kotlin.math.roundToInt

/** Detailed, non-technical view of the current open billing period. */
@Composable
fun PeriodAnalysisRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { PeriodAnalysisViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PeriodAnalysisScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onHowWeCalculate = { navController.navigate(Routes.HowWeCalculate) },
    )
}

@Composable
fun PeriodAnalysisScreen(
    uiState: PeriodAnalysisUiState,
    onBack: () -> Unit,
    onHowWeCalculate: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RzFlowTopBar(title = stringResource(R.string.period_top_bar_title), onBack = onBack) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = RzSpacing.screenHorizontal,
                end = RzSpacing.screenHorizontal,
                top = innerPadding.calculateTopPadding() + RzSpacing.sm,
                bottom = innerPadding.calculateBottomPadding() + RzSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            uiState.billChange?.let { change -> item { BillChangeCard(change) } }
            if (!uiState.hasUsageData) {
                item { EmptyPeriodCard() }
            } else {
                item { CurrentPeriodOverviewCard(uiState) }
                if (uiState.accumulatedKwhByDay.size >= 2) item { PeriodCurveCard(uiState) }
                item { PeriodComparisonCard(uiState) }
                item { ReadingEvidenceCard(uiState) }
                item {
                    RzSecondaryButton(
                        text = stringResource(R.string.period_how_we_calculate),
                        onClick = onHowWeCalculate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPeriodCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.period_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.period_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

/**
 * Accumulated consumption against the pace the goal allows. The real curve
 * stops where the readings stop; the dashed line continues to the projected
 * close so the measured part is never confused with the forecast.
 */
@Composable
private fun PeriodCurveCard(uiState: PeriodAnalysisUiState) {
    val days = uiState.billingDays.coerceAtLeast(uiState.accumulatedKwhByDay.size)
    val ceiling = maxOf(
        uiState.projectedKwh,
        uiState.goalKwh,
        uiState.accumulatedKwhByDay.maxOrNull() ?: 0,
        1,
    ).toFloat()
    val observedDays = uiState.accumulatedKwhByDay.lastIndex.coerceAtLeast(1)

    // One value per day of the period, normalised to the same ceiling so the
    // three series can be compared by eye.
    val realSeries = (0..days).map { day ->
        (uiState.accumulatedKwhByDay.getOrNull(day) ?: uiState.accumulatedKwhByDay.last()) / ceiling
    }
    val projectionSeries = (0..days).map { day ->
        val observedKwh = uiState.accumulatedKwhByDay.last().toFloat()
        if (day <= observedDays) {
            (uiState.accumulatedKwhByDay.getOrNull(day) ?: 0).toFloat() / ceiling
        } else {
            val remaining = (days - observedDays).coerceAtLeast(1)
            val extra = (uiState.projectedKwh - observedKwh) * (day - observedDays) / remaining
            (observedKwh + extra) / ceiling
        }
    }
    val goalSeries = (0..days).map { day -> uiState.goalKwh * day / days.toFloat() / ceiling }

    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.period_curve_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.period_curve_subtitle, uiState.accumulatedKwhByDay.last(), uiState.observedDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        RzLineChart(
            series = buildList {
                if (uiState.goalKwh > 0) {
                    add(
                        RzChartSeries(
                            values = goalSeries,
                            color = RzTheme.extendedColors.success,
                            style = ChartLineStyle.DOTTED,
                        ),
                    )
                }
                add(
                    RzChartSeries(
                        values = projectionSeries,
                        color = MaterialTheme.colorScheme.outline,
                        style = ChartLineStyle.DASHED,
                    ),
                )
                add(
                    RzChartSeries(
                        values = realSeries,
                        color = MaterialTheme.colorScheme.primary,
                        markEnd = true,
                        visibleUntilIndex = observedDays,
                    ),
                )
            },
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.period_curve_legend_real),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.period_curve_legend_projection),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (uiState.goalKwh > 0) {
                Text(
                    stringResource(R.string.period_curve_legend_goal),
                    style = MaterialTheme.typography.labelSmall,
                    color = RzTheme.extendedColors.success,
                )
            }
        }
    }
}

@Composable
private fun CurrentPeriodOverviewCard(uiState: PeriodAnalysisUiState) {
    val timeProgress = if (uiState.billingDays > 0) {
        (uiState.observedDays.toFloat() / uiState.billingDays).coerceIn(0f, 1f)
    } else 0f
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.period_current_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    uiState.currentPeriodDateRange.ifBlank { uiState.currentPeriodLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            RzStatusChip(
                text = stringResource(R.string.period_day_chip, uiState.observedDays, uiState.billingDays),
                icon = RzIcons.Schedule,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        }

        RzLinearProgress(
            progress = timeProgress,
            progressColor = MaterialTheme.colorScheme.primary,
            contentDescription = stringResource(R.string.period_time_summary, uiState.observedDays, uiState.billingDays),
            modifier = Modifier.padding(top = RzSpacing.lg),
        )
        Text(
            stringResource(R.string.period_time_remaining, uiState.daysRemaining),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            PeriodStat(
                label = stringResource(R.string.period_observed_label),
                value = stringResource(R.string.period_kwh_value, uiState.observedKwh),
                modifier = Modifier.weight(1f),
            )
            PeriodStat(
                label = stringResource(R.string.period_projected_label),
                value = stringResource(R.string.period_kwh_value, uiState.projectedKwh),
                modifier = Modifier.weight(1f),
            )
        }
        if (uiState.goalKwh > 0) {
            val difference = uiState.projectedKwh - uiState.goalKwh
            RzInlineBanner(
                text = if (difference <= 0) {
                    stringResource(R.string.period_projection_within_goal, -difference, uiState.goalKwh)
                } else stringResource(R.string.period_projection_over_goal, difference, uiState.goalKwh),
                tone = if (difference <= 0) RzBannerTone.SUCCESS else RzBannerTone.WARNING,
                icon = if (difference <= 0) RzIcons.CheckCircle else RzIcons.ErrorInfo,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
        }
    }
}

/**
 * "¿Por qué subió mi recibo?" answered in pesos per cause. The order matters:
 * the biggest cause first, because the first line is the one people read.
 */
@Composable
private fun BillChangeCard(change: BillChangeExplanation) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                if (change.increased) R.string.period_change_title_up else R.string.period_change_title_down,
                kotlin.math.abs(change.deltaMxn),
            ),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(
                R.string.period_change_subtitle,
                change.previousLabel,
                change.previousAmountMxn,
                change.currentLabel,
                change.currentAmountMxn,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        change.components.forEach { component ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(billChangeLabel(component.factor)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    billChangeDetail(component)?.let { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Text(
                    stringResource(
                        if (component.amountMxn >= 0) R.string.period_change_amount_up
                        else R.string.period_change_amount_down,
                        kotlin.math.abs(component.amountMxn),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (component.amountMxn >= 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        if (change.tariffCodeChanged) {
            RzInlineBanner(
                text = stringResource(R.string.period_change_tariff_changed),
                tone = RzBannerTone.WARNING,
                icon = RzIcons.ErrorInfo,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
        }
    }
}

private fun billChangeLabel(factor: BillChangeFactor): Int = when (factor) {
    BillChangeFactor.PERIOD_LENGTH -> R.string.period_change_factor_length
    BillChangeFactor.DAILY_USAGE -> R.string.period_change_factor_usage
    BillChangeFactor.TARIFF_STEP -> R.string.period_change_factor_step
    BillChangeFactor.TARIFF_RATES -> R.string.period_change_factor_rates
    BillChangeFactor.UNEXPLAINED -> R.string.period_change_factor_unexplained
}

@Composable
private fun billChangeDetail(component: BillChangeComponent): String? = when (component.factor) {
    BillChangeFactor.PERIOD_LENGTH -> stringResource(
        R.string.period_change_detail_length,
        kotlin.math.abs(component.magnitude).roundToInt(),
    )
    BillChangeFactor.DAILY_USAGE -> stringResource(
        R.string.period_change_detail_usage,
        kotlin.math.abs(component.magnitude).roundToInt(),
    )
    BillChangeFactor.TARIFF_STEP -> stringResource(
        R.string.period_change_detail_step,
        component.magnitude,
    )
    else -> null
}

@Composable
private fun PeriodStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PeriodComparisonCard(uiState: PeriodAnalysisUiState) {
    val scale = maxOf(uiState.observedKwh, uiState.projectedKwh, uiState.goalKwh, uiState.previousBillKwh, 1)
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.period_comparison_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.period_comparison_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
            modifier = Modifier.padding(top = RzSpacing.lg),
        ) {
            ComparisonBar(
                label = stringResource(R.string.period_observed_label),
                value = uiState.observedKwh,
                scale = scale,
                color = MaterialTheme.colorScheme.primary,
            )
            ComparisonBar(
                label = stringResource(R.string.period_projected_label),
                value = uiState.projectedKwh,
                scale = scale,
                color = if (uiState.goalKwh > 0 && uiState.projectedKwh > uiState.goalKwh) {
                    RzTheme.extendedColors.warning
                } else RzTheme.extendedColors.success,
            )
            if (uiState.goalKwh > 0) {
                ComparisonBar(
                    label = stringResource(R.string.period_goal_label),
                    value = uiState.goalKwh,
                    scale = scale,
                    color = RzTheme.extendedColors.chartGoalLine,
                )
            }
            if (uiState.previousBillKwh > 0) {
                ComparisonBar(
                    label = stringResource(R.string.period_previous_label, uiState.previousPeriodLabel),
                    value = uiState.previousBillKwh,
                    scale = scale,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (uiState.previousBillKwh > 0) {
            val difference = uiState.projectedKwh - uiState.previousBillKwh
            Text(
                if (difference <= 0) stringResource(R.string.period_previous_lower, -difference)
                else stringResource(R.string.period_previous_higher, difference),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
        }
    }
}

@Composable
private fun ComparisonBar(label: String, value: Int, scale: Int, color: Color) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.period_kwh_value, value), style = MaterialTheme.typography.labelLarge)
        }
        RzLinearProgress(
            progress = value.toFloat() / scale.coerceAtLeast(1),
            progressColor = color,
            contentDescription = stringResource(R.string.period_comparison_content_description, label, value, scale),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ReadingEvidenceCard(uiState: PeriodAnalysisUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
            Icon(RzIcons.Monitoring, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.period_evidence_title), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(
                R.string.period_evidence_reading,
                formatThousands(uiState.lastReadingKwh),
                uiState.lastReadingDateLabel,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Text(
            stringResource(R.string.period_evidence_cost, uiState.costToDateMxn),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun formatThousands(value: Int): String = String.format(Locale.US, "%,d", value)

@Preview(showBackground = true, heightDp = 1500)
@Composable
private fun PeriodAnalysisScreenPreview() {
    ReciboZenTheme {
        PeriodAnalysisScreen(
            uiState = PeriodAnalysisUiState(
                hasUsageData = true,
                lastReadingKwh = 9_974,
                lastReadingDateLabel = "13 ago, 4:03 p.m.",
                observedKwh = 171,
                observedDays = 30,
                billingDays = 62,
                daysRemaining = 32,
                costToDateMxn = 192,
                goalKwh = 443,
                projectedKwh = 353,
                currentPeriodLabel = "Jul–Sep 26",
                currentPeriodDateRange = "14 jul–14 sep 2026",
                previousPeriodLabel = "May–Jul 26",
                previousBillKwh = 646,
            ),
            onBack = {},
            onHowWeCalculate = {},
        )
    }
}
