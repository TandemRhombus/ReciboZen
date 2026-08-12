package com.recibozen.app.feature.period

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.ChartLineStyle
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzChartSeries
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzLineChart
import com.recibozen.app.core.components.RzMarkedProgress
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import java.util.Locale

/**
 * "Análisis del periodo" — pushed flow screen reached from the Inicio tab's
 * "Ver análisis completo" button.
 */
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
                top = RzSpacing.sm,
                bottom = innerPadding.calculateBottomPadding() + RzSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            if (!uiState.hasUsageData) {
                item {
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
            } else {
                item { AccumulatedChartCard(uiState) }
                item {
                    if (uiState.goalKwh > 0) {
                        GoalProgressCard(uiState)
                    } else {
                        RzCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.period_no_goal),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
private fun AccumulatedChartCard(uiState: PeriodAnalysisUiState) {
    val pointCount = 10
    val todayIndex = if (uiState.billingDays > 0) {
        ((pointCount - 1) * uiState.observedDays.toFloat() / uiState.billingDays).toInt().coerceIn(0, pointCount - 1)
    } else 0
    var selectedIndex by remember(uiState.observedDays, uiState.billingDays) { mutableIntStateOf(todayIndex) }
    val selectedDay = if (uiState.billingDays > 0) {
        (uiState.billingDays * selectedIndex.toFloat() / (pointCount - 1)).toInt()
    } else 0
    val isObserved = selectedDay <= uiState.observedDays
    val selectedKwh = if (isObserved && uiState.observedDays > 0) {
        (uiState.observedKwh * selectedDay.toFloat() / uiState.observedDays).toInt()
    } else if (uiState.billingDays > 0) {
        (uiState.projectedKwh * selectedDay.toFloat() / uiState.billingDays).toInt()
    } else uiState.projectedKwh
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.period_chart_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        RzLineChart(
            series = periodChartSeries(uiState),
            modifier = Modifier.padding(top = RzSpacing.md),
            height = 170.dp,
            selectedIndex = selectedIndex,
            onPointSelected = { selectedIndex = it },
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(
                stringResource(R.string.period_chart_start_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.period_chart_today_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.period_chart_end_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Text(
            stringResource(
                if (isObserved) R.string.period_selected_observed else R.string.period_selected_projected,
                selectedDay,
                selectedKwh,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.period_chart_tap_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = RzSpacing.md)
                .background(MaterialTheme.colorScheme.background, RzExtraShapes.control)
                .padding(horizontal = RzSpacing.lg, vertical = RzSpacing.md),
        ) {
            Text(
                stringResource(R.string.period_info_box_title, uiState.lastReadingDateLabel),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.period_info_observed_reading, formatThousands(uiState.lastReadingKwh)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.period_info_accumulated, uiState.observedKwh),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.period_info_cost_to_date, uiState.costToDateMxn),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.md),
            modifier = Modifier.padding(top = RzSpacing.md),
        ) {
            ChartLegendItem(
                label = stringResource(R.string.period_legend_observed),
                color = RzTheme.extendedColors.chartObserved,
                style = ChartLineStyle.SOLID,
            )
            ChartLegendItem(
                label = stringResource(R.string.period_legend_projected),
                color = RzTheme.extendedColors.chartObserved,
                style = ChartLineStyle.DASHED,
            )
            ChartLegendItem(
                label = stringResource(R.string.period_legend_goal),
                color = RzTheme.extendedColors.chartGoalLine,
                style = ChartLineStyle.DOTTED,
            )
        }
    }
}

@Composable
private fun ChartLegendItem(label: String, color: Color, style: ChartLineStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(3.dp)
                .background(if (style == ChartLineStyle.SOLID) color else color.copy(alpha = 0.55f)),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Same x-alignment trick as the Home mini chart (see
 * `com.recibozen.app.feature.home.homeChartSeries`): all three series share
 * the same point count so they stay aligned on the shared x-axis, with the
 * dashed/dotted series overlapping the solid one before diverging. The solid
 * observed line is clipped at the real "today" index by [RzLineChart].
 */
@Composable
private fun periodChartSeries(uiState: PeriodAnalysisUiState): List<RzChartSeries> {
    val pointCount = 10
    val scale = uiState.surplusStartKwh.coerceAtLeast(1).toFloat()
    val todayFraction = if (uiState.billingDays > 0) {
        (uiState.observedDays.toFloat() / uiState.billingDays).coerceIn(0.1f, 1f)
    } else 1f
    val todayIndex = ((pointCount - 1) * todayFraction).toInt().coerceIn(1, pointCount - 1)
    val observed = List(pointCount) { index ->
        val progress = (index.toFloat() / todayIndex).coerceIn(0f, 1f)
        uiState.observedKwh / scale * progress
    }
    val projected = List(pointCount) { index ->
        uiState.projectedKwh / scale * (index.toFloat() / (pointCount - 1))
    }
    val goal = List(pointCount) { index ->
        uiState.goalKwh / scale * (index.toFloat() / (pointCount - 1))
    }
    return listOf(
        RzChartSeries(values = goal, color = RzTheme.extendedColors.chartGoalLine, style = ChartLineStyle.DOTTED, strokeWidth = 2.dp),
        RzChartSeries(values = projected, color = RzTheme.extendedColors.chartObserved, style = ChartLineStyle.DASHED, strokeWidth = 2.5.dp),
        RzChartSeries(
            values = observed,
            color = RzTheme.extendedColors.chartObserved,
            style = ChartLineStyle.SOLID,
            strokeWidth = 3.dp,
            markEnd = true,
            visibleUntilIndex = todayIndex,
        ),
    )
}

@Composable
private fun GoalProgressCard(uiState: PeriodAnalysisUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.period_goal_progress_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        val scale = uiState.surplusStartKwh.coerceAtLeast(1)
        val observedFraction = (uiState.observedKwh.toFloat() / scale).coerceIn(0f, 1f)
        val projectedFraction = (uiState.projectedKwh.toFloat() / scale).coerceIn(0f, 1f)
        val goalFraction = (uiState.goalKwh.toFloat() / scale).coerceIn(0f, 1f)
        val screenReaderSummary = stringResource(
            R.string.period_goal_screen_reader_summary,
            uiState.observedKwh,
            uiState.goalKwh,
            uiState.projectedKwh,
            uiState.surplusStartKwh,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = RzSpacing.lg)
                .semantics { contentDescription = screenReaderSummary },
        ) {
            // Bottom layer: lighter fill up to the projection, transparent track so
            // it doesn't paint over the observed layer drawn on top of it.
            RzMarkedProgress(
                progress = projectedFraction,
                markerAt = goalFraction,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                progressColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                markerColor = Color.Transparent,
            )
            // Top layer: solid fill up to today's observed consumption + the real
            // goal marker line.
            RzMarkedProgress(
                progress = observedFraction,
                markerAt = goalFraction,
                trackColor = Color.Transparent,
                progressColor = MaterialTheme.colorScheme.primary,
                markerColor = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm)) {
            Text(
                stringResource(R.string.period_goal_label_today, uiState.observedKwh),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.period_goal_label_goal, uiState.goalKwh),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.period_goal_label_projection, uiState.projectedKwh),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.period_goal_label_surplus, uiState.surplusStartKwh),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Text(
            screenReaderSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
    }
}

private fun formatThousands(value: Int): String = String.format(Locale.US, "%,d", value)

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun PeriodAnalysisScreenPreview() {
    ReciboZenTheme {
        PeriodAnalysisScreen(
            uiState = PeriodAnalysisUiState(),
            onBack = {},
            onHowWeCalculate = {},
        )
    }
}
