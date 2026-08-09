package com.recibozen.app.feature.plan

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzBarSegment
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzMarkedProgress
import com.recibozen.app.core.components.RzStackedBarRow
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Pushed flow screen "Promedio de consumo" (DAC): rolling average vs the
 * tariff's reference limit, plus a tariff-tier breakdown for the period.
 */
data class DacUiState(
    val rollingAverageKwh: Int = DemoReciboZenData.ROLLING_AVERAGE_KWH,
    val referenceLimitKwh: Int = DemoReciboZenData.REFERENCE_LIMIT_KWH,
    val kwhOverLimit: Int = DemoReciboZenData.KWH_OVER_LIMIT,
    val kwhToReduce: Int = DemoReciboZenData.KWH_TO_REDUCE,
    val periodsConsidered: String = DemoReciboZenData.DAC_PERIODS_CONSIDERED,
    val tariffReviewDate: String = DemoReciboZenData.DAC_TARIFF_REVIEW_DATE,
    val tariff: String = DemoReciboZenData.TARIFF,
    val tariffTiers: List<DemoReciboZenData.TariffTier> = DemoReciboZenData.tariffTiers,
    val todayMarkerFraction: Float = DemoReciboZenData.TARIFF_TODAY_MARKER_FRACTION,
) {
    // The design's "promedio móvil / límite" bar uses a fixed reference scale
    // (limit sits at ~85%, average at ~88%). 1,000 kWh reproduces that ratio
    // without inventing a new demo-data constant.
    private val scaleKwh: Float = 1000f
    val averageProgressFraction: Float get() = (rollingAverageKwh / scaleKwh).coerceIn(0f, 1f)
    val limitMarkerFraction: Float get() = (referenceLimitKwh / scaleKwh).coerceIn(0f, 1f)
}

class DacViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<DacUiState> = repository.session
        .map { DacUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DacUiState())
}

@Composable
fun DacRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { DacViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DacScreen(uiState = uiState, onBack = { navController.popBackStack() })
}

@Composable
fun DacScreen(
    uiState: DacUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { RzFlowTopBar(title = stringResource(R.string.dac_title), onBack = onBack) },
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
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.dac_headline),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg),
                ) {
                    DacStat(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.dac_rolling_average_label),
                        value = stringResource(R.string.dac_kwh_per_month, uiState.rollingAverageKwh),
                        valueColor = RzTheme.extendedColors.warning,
                    )
                    DacStat(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.dac_reference_limit_label),
                        value = stringResource(R.string.dac_kwh_per_month, uiState.referenceLimitKwh),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                RzMarkedProgress(
                    progress = uiState.averageProgressFraction,
                    markerAt = uiState.limitMarkerFraction,
                    progressColor = RzTheme.extendedColors.warning,
                    modifier = Modifier.padding(top = RzSpacing.lg),
                )
                Text(
                    run {
                        val prefix = stringResource(R.string.dac_over_limit_prefix)
                        val bold = stringResource(R.string.dac_over_limit_bold, uiState.kwhOverLimit)
                        val suffix = stringResource(R.string.dac_over_limit_suffix, uiState.kwhToReduce)
                        val emphasisColor = MaterialTheme.colorScheme.onSurface
                        buildAnnotatedString {
                            append(prefix)
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = emphasisColor)) {
                                append(bold)
                            }
                            append(suffix)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
                Text(
                    stringResource(
                        R.string.dac_footnote,
                        uiState.periodsConsidered,
                        uiState.tariffReviewDate,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
            }

            Text(
                stringResource(R.string.dac_opportunities_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.dac_opportunity_minisplit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.dac_opportunity_ps5),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.dac_opportunity_unidentified),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.dac_tariff_title, uiState.tariff),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val tierColors = listOf(
                    RzTheme.extendedColors.success,
                    RzTheme.extendedColors.warning,
                    MaterialTheme.colorScheme.surfaceVariant,
                )
                RzStackedBarRow(
                    segments = uiState.tariffTiers.mapIndexed { index, tier ->
                        RzBarSegment(weight = tier.fraction, color = tierColors[index % tierColors.size])
                    },
                    height = 16.dp,
                    modifier = Modifier.padding(top = RzSpacing.lg),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.xs),
                ) {
                    Text(
                        stringResource(R.string.dac_today_marker),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(
                            BiasAlignment(horizontalBias = uiState.todayMarkerFraction * 2f - 1f, verticalBias = -1f),
                        ),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                ) {
                    uiState.tariffTiers.forEachIndexed { index, tier ->
                        TariffTierRow(color = tierColors[index % tierColors.size], tier = tier)
                    }
                }
                Text(
                    stringResource(R.string.dac_tariff_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
            }
        }
    }
}

@Composable
private fun DacStat(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = valueColor)
    }
}

@Composable
private fun TariffTierRow(color: Color, tier: DemoReciboZenData.TariffTier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Text(
            tier.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(tier.amountLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true)
@Composable
private fun DacScreenPreview() {
    ReciboZenTheme {
        DacScreen(uiState = DacUiState(), onBack = {})
    }
}
