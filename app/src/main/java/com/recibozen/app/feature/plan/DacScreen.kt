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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.calculation.DacRiskCalculator
import com.recibozen.app.core.calculation.DacRiskLevel
import com.recibozen.app.core.calculation.DacRiskSnapshot
import com.recibozen.app.core.calculation.TariffBlock
import com.recibozen.app.core.components.RzBarSegment
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzMarkedProgress
import com.recibozen.app.core.components.RzStackedBarRow
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenSessionState
import com.recibozen.app.navigation.rzViewModel
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DacTariffTier(
    val usedKwh: Int,
    val startsAtKwh: Int,
    val fraction: Float,
    val amountMxn: Int,
    val isOpenEnded: Boolean,
)

data class DacOpportunity(val name: String, val monthlyKwh: Int)

data class DacUiState(
    val risk: DacRiskSnapshot = DacRiskSnapshot(),
    val tariff: String = "",
    val tariffTiers: List<DacTariffTier> = emptyList(),
    val tariffCalibrated: Boolean = false,
    val todayMarkerFraction: Float = 0f,
    val opportunities: List<DacOpportunity> = emptyList(),
) {
    val rollingAverageKwh: Int get() = risk.monthlyAverageKwh?.roundToInt() ?: 0
    val referenceLimitKwh: Int get() = risk.monthlyLimitKwh ?: 0
    val hasUsageData: Boolean get() = risk.monthlyAverageKwh != null
    private val scaleKwh: Float = maxOf(rollingAverageKwh, referenceLimitKwh, 1).toFloat()
    val averageProgressFraction: Float get() = (rollingAverageKwh / scaleKwh).coerceIn(0f, 1f)
    val limitMarkerFraction: Float get() = (referenceLimitKwh / scaleKwh).coerceIn(0f, 1f)
}

private fun buildTariffTiers(basisKwh: Int, blocks: List<TariffBlock>): List<DacTariffTier> {
    if (basisKwh <= 0 || blocks.isEmpty()) return emptyList()
    val safeBasis = basisKwh.toFloat()
    var previousLimit = 0
    var remaining = basisKwh
    return blocks.map { block ->
        val used = block.limitKwh?.let { minOf(remaining, (it - previousLimit).coerceAtLeast(0)) } ?: remaining
        val tier = DacTariffTier(
            usedKwh = used,
            startsAtKwh = previousLimit,
            fraction = used / safeBasis,
            amountMxn = (used * block.rateMxnPerKwh).roundToInt(),
            isOpenEnded = block.limitKwh == null,
        )
        remaining = (remaining - used).coerceAtLeast(0)
        previousLimit = block.limitKwh ?: previousLimit
        tier
    }
}

class DacViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<DacUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        repository.tariffCalibration,
    ) { state, importedHistory, calibration ->
        toUiState(state, importedHistory, calibration?.isCalibrated == true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DacUiState())

    private fun toUiState(
        state: ReciboZenSessionState,
        importedHistory: List<com.recibozen.app.core.model.BillPeriod>,
        tariffCalibrated: Boolean,
    ): DacUiState {
        val energy = repository.energySummary(state)
        val latestBillEndMillis = importedHistory.lastOrNull()?.periodEndEpochDay?.times(MILLIS_PER_DAY)
        val hasLiveReading = latestBillEndMillis != null && state.readings.any { reading ->
            reading.method != ReadingMethod.PDF && (reading.timestampEpochMillis ?: Long.MIN_VALUE) > latestBillEndMillis
        }
        val projectedKwh = energy.projectedKwh.takeIf { hasLiveReading && it > 0 }
        val billingDays = energy.billingDays.takeIf { hasLiveReading && it > 0 } ?: 0
        val risk = DacRiskCalculator.calculate(importedHistory, state.tariff, projectedKwh, billingDays)
        val basisKwh = projectedKwh ?: energy.observedKwh
        val tiers = if (tariffCalibrated) buildTariffTiers(basisKwh, repository.activeTariffBlocks()) else emptyList()
        val monthlyFactor = DacRiskSnapshot.AVERAGE_DAYS_PER_MONTH / energy.billingDays.coerceAtLeast(1)
        val opportunities = repository.appliances(state)
            .filter { it.kwh > 0 }
            .sortedByDescending { it.kwh }
            .take(3)
            .map { appliance -> DacOpportunity(appliance.name, (appliance.kwh * monthlyFactor).roundToInt()) }
        return DacUiState(
            risk = risk,
            tariff = state.tariff,
            tariffTiers = tiers,
            tariffCalibrated = tariffCalibrated,
            todayMarkerFraction = (energy.observedKwh.toFloat() / basisKwh.coerceAtLeast(1)).coerceIn(0f, 1f),
            opportunities = opportunities,
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

@Composable
fun DacRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { DacViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DacScreen(uiState = uiState, onBack = { navController.popBackStack() })
}

@Composable
fun DacScreen(uiState: DacUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { RzFlowTopBar(title = stringResource(R.string.dac_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal).padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            if (uiState.risk.level == DacRiskLevel.ALREADY_DAC) {
                DacAlreadyClassifiedCard()
                return@Column
            }
            if (uiState.risk.level == DacRiskLevel.UNSUPPORTED) {
                DacMissingTariffCard()
                return@Column
            }
            if (!uiState.hasUsageData) {
                DacEmptyCard(hasTariff = uiState.referenceLimitKwh > 0)
                return@Column
            }

            DacStatusCard(uiState)
            DacNextBillCard(uiState)

            if (uiState.opportunities.isNotEmpty()) {
                Text(stringResource(R.string.dac_opportunities_title), style = MaterialTheme.typography.titleMedium)
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    uiState.opportunities.forEachIndexed { index, opportunity ->
                        Text(
                            stringResource(R.string.dac_opportunity_dynamic, opportunity.name, opportunity.monthlyKwh),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (index == 0) Modifier else Modifier.padding(top = RzSpacing.xs),
                        )
                    }
                }
            }

            TariffBreakdownCard(uiState)
        }
    }
}

@Composable
private fun DacStatusCard(uiState: DacUiState) {
    val headline = when (uiState.risk.level) {
        DacRiskLevel.UNKNOWN -> R.string.dac_headline_unknown
        DacRiskLevel.NORMAL -> R.string.dac_headline_normal
        DacRiskLevel.WATCH -> R.string.dac_headline_watch
        DacRiskLevel.RISK -> R.string.dac_headline_risk
        DacRiskLevel.ABOVE_LIMIT -> R.string.dac_headline_over
        else -> R.string.dac_headline_unknown
    }
    val progressColor = when (uiState.risk.level) {
        DacRiskLevel.NORMAL -> RzTheme.extendedColors.success
        DacRiskLevel.WATCH -> RzTheme.extendedColors.warning
        else -> RzTheme.extendedColors.warningText
    }
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(headline), style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            DacStat(
                stringResource(R.string.dac_rolling_average_label),
                stringResource(R.string.dac_kwh_per_month, uiState.rollingAverageKwh),
                progressColor,
                Modifier.weight(1f),
            )
            DacStat(
                stringResource(R.string.dac_reference_limit_label),
                stringResource(R.string.dac_kwh_per_month, uiState.referenceLimitKwh),
                MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f),
            )
        }
        RzMarkedProgress(
            progress = uiState.averageProgressFraction,
            markerAt = uiState.limitMarkerFraction,
            progressColor = progressColor,
            modifier = Modifier.padding(top = RzSpacing.lg),
        )
        val margin = uiState.risk.marginMonthlyKwh?.roundToInt() ?: 0
        Text(
            if (margin >= 0) stringResource(R.string.dac_margin_below, margin)
            else stringResource(R.string.dac_margin_above, -margin),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Text(
            stringResource(
                R.string.dac_annual_equivalent,
                uiState.risk.annualEquivalentLimitKwh ?: 0,
                uiState.tariff.ifBlank { "—" },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            if (uiState.risk.hasCompleteHistory) {
                stringResource(R.string.dac_history_complete, uiState.risk.coveredMonths)
            } else {
                stringResource(R.string.dac_history_incomplete, uiState.risk.coveredMonths)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun DacNextBillCard(uiState: DacUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_next_bill_title), style = MaterialTheme.typography.titleMedium)
        val projected = uiState.risk.projectedNextBillKwh
        val recommended = uiState.risk.recommendedNextBillMaxKwh
        val message = when {
            !uiState.risk.hasCompleteHistory -> stringResource(R.string.dac_next_bill_need_history)
            recommended == null -> stringResource(R.string.dac_next_bill_need_reading)
            projected != null && uiState.risk.wouldExceedWithProjection ->
                stringResource(R.string.dac_next_bill_risk, projected, recommended)
            projected != null -> stringResource(R.string.dac_next_bill_ok, projected, recommended)
            else -> stringResource(R.string.dac_next_bill_limit, recommended)
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.dac_prediction_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun TariffBreakdownCard(uiState: DacUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_tariff_title, uiState.tariff.ifBlank { "—" }), style = MaterialTheme.typography.titleMedium)
        if (!uiState.tariffCalibrated || uiState.tariffTiers.isEmpty()) {
            Text(
                stringResource(R.string.dac_tariff_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
            return@RzCard
        }
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
        Box(modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.xs)) {
            Text(
                stringResource(R.string.dac_today_marker),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(
                    BiasAlignment(horizontalBias = uiState.todayMarkerFraction * 2f - 1f, verticalBias = -1f),
                ),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            uiState.tariffTiers.forEachIndexed { index, tier ->
                TariffTierRow(tierColors[index % tierColors.size], index, tier)
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

@Composable
private fun DacAlreadyClassifiedCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_already_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.dac_already_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun DacMissingTariffCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_missing_tariff_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.dac_missing_tariff_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun DacEmptyCard(hasTariff: Boolean) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_no_data_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(if (hasTariff) R.string.dac_no_data_body else R.string.dac_missing_tariff_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun DacStat(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = valueColor)
    }
}

@Composable
private fun TariffTierRow(color: Color, index: Int, tier: DacTariffTier) {
    val labelRes = when {
        tier.isOpenEnded -> R.string.dac_tier_excess
        index == 0 -> R.string.dac_tier_basic
        else -> R.string.dac_tier_intermediate
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(
            stringResource(labelRes, if (tier.isOpenEnded) tier.startsAtKwh else tier.usedKwh),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (tier.amountMxn > 0) dacMoneyFormat.format(tier.amountMxn) else "—",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val dacMoneyFormat: NumberFormat =
    NumberFormat.getCurrencyInstance(Locale("es", "MX")).apply { maximumFractionDigits = 0 }

@Preview(showBackground = true)
@Composable
private fun DacScreenPreview() {
    ReciboZenTheme {
        DacScreen(
            uiState = DacUiState(
                risk = DacRiskSnapshot(
                    tariffCode = "1C",
                    monthlyLimitKwh = 850,
                    monthlyAverageKwh = 790.0,
                    marginMonthlyKwh = 60.0,
                    utilizationFraction = 0.93,
                    coveredDays = 365,
                    hasCompleteHistory = true,
                    level = DacRiskLevel.RISK,
                    projectedNextBillKwh = 1_500,
                    recommendedNextBillMaxKwh = 1_380,
                    wouldExceedWithProjection = true,
                ),
                tariff = "1C",
                opportunities = listOf(DacOpportunity("Minisplit", 120)),
            ),
            onBack = {},
        )
    }
}
