package app.recibozen.android.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
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
import app.recibozen.android.R
import app.recibozen.android.core.calculation.DacRiskCalculator
import app.recibozen.android.core.calculation.DacRiskLevel
import app.recibozen.android.core.calculation.DacRiskSnapshot
import app.recibozen.android.core.calculation.ApplianceAdviceEngine
import app.recibozen.android.core.calculation.TariffBlock
import app.recibozen.android.core.calculation.TariffCatalog
import app.recibozen.android.core.components.RzBarSegment
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzMarkedProgress
import app.recibozen.android.core.components.RzStackedBarRow
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.demo.repository.ReciboZenSessionState
import app.recibozen.android.navigation.rzViewModel
import java.text.NumberFormat
import java.time.LocalDate
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

data class DacOpportunity(
    val name: String,
    val monthlyKwh: Int,
    val actionTitle: String = "",
    val actionBody: String = "",
    val estimatedSavingsKwh: Double? = null,
)

data class DacLimitReference(
    val tariffCode: String,
    val monthlyLimitKwh: Int,
    val annualEquivalentKwh: Int,
    val isSelected: Boolean,
)

data class DacUiState(
    val risk: DacRiskSnapshot = DacRiskSnapshot(),
    val tariff: String = "",
    val tariffTiers: List<DacTariffTier> = emptyList(),
    val tariffCalibrated: Boolean = false,
    val usesLatestDacBillEffectiveRate: Boolean = false,
    val todayMarkerFraction: Float = 0f,
    val opportunities: List<DacOpportunity> = emptyList(),
    val limitReferences: List<DacLimitReference> = emptyList(),
) {
    val rollingAverageKwh: Int get() = risk.monthlyAverageKwh?.roundToInt() ?: 0
    val referenceLimitKwh: Int get() = risk.monthlyLimitKwh ?: 0
    val rollingWindowKwh: Int get() = risk.rollingWindowKwh?.roundToInt() ?: 0
    val annualComparableKwh: Int get() = risk.annualizedRollingKwh?.roundToInt() ?: 0
    val annualLimitKwh: Int get() = risk.annualEquivalentLimitKwh ?: 0
    val annualMarginKwh: Int get() = risk.annualMarginKwh?.roundToInt() ?: 0
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
        importedHistory: List<app.recibozen.android.core.model.BillPeriod>,
        tariffCalibrated: Boolean,
    ): DacUiState {
        val energy = repository.energySummary(state)
        val hasLiveReading = energy.hasCurrentMeterProjection
        val projectedKwh = energy.projectedKwh.takeIf { hasLiveReading && it > 0 }
        // The expected period length is enough to know which old kWh will age
        // out, even before the user records an intermediate reading.
        val billingDays = energy.billingDays.coerceAtLeast(0)
        val serviceKey = state.activeServiceId.takeIf { it.isNotBlank() } ?: "current"
        val dacReferenceTariff = state.dacReferenceTariffsByService[serviceKey]
        val risk = DacRiskCalculator.calculate(
            importedHistory,
            state.tariff,
            projectedKwh,
            billingDays,
            domesticReferenceTariffCode = dacReferenceTariff,
        )
        val basisKwh = projectedKwh ?: energy.observedKwh
        val tiers = if (tariffCalibrated) buildTariffTiers(basisKwh, repository.activeTariffBlocks()) else emptyList()
        val monthlyFactor = DacRiskSnapshot.AVERAGE_DAYS_PER_MONTH / energy.billingDays.coerceAtLeast(1)
        val appliances = repository.appliances(state)
        val adviceByAppliance = ApplianceAdviceEngine.rankInventory(appliances, energy.billingDays.coerceAtLeast(30), limit = 5)
            .associateBy { it.applianceId }
        val opportunities = appliances
            .filter { it.kwh > 0 }
            .sortedWith(compareByDescending<app.recibozen.android.core.model.Appliance> {
                adviceByAppliance[it.id]?.advice?.estimatedSavingsKwh ?: 0.0
            }.thenByDescending { it.kwh })
            .take(if (risk.level == DacRiskLevel.ALREADY_DAC) 5 else 3)
            .map { appliance ->
                val advice = adviceByAppliance[appliance.id]?.advice
                    ?: ApplianceAdviceEngine.recommendations(appliance, energy.billingDays.coerceAtLeast(30)).first()
                DacOpportunity(
                    name = appliance.name,
                    monthlyKwh = (appliance.kwh * monthlyFactor).roundToInt(),
                    actionTitle = advice.title,
                    actionBody = advice.description,
                    estimatedSavingsKwh = advice.estimatedSavingsKwh,
                )
            }
        val normalizedTariff = TariffCatalog.normalize(state.tariff)
        val limitReferences = TariffCatalog.supported.mapNotNull { definition ->
            definition.highConsumptionLimitKwhPerMonth?.let { monthlyLimit ->
                DacLimitReference(
                    tariffCode = definition.code,
                    monthlyLimitKwh = monthlyLimit,
                    annualEquivalentKwh = monthlyLimit * 12,
                    isSelected = definition.code == (risk.referenceDomesticTariffCode ?: normalizedTariff),
                )
            }
        }
        return DacUiState(
            risk = risk,
            tariff = state.tariff,
            tariffTiers = tiers,
            tariffCalibrated = tariffCalibrated,
            usesLatestDacBillEffectiveRate = normalizedTariff == "DAC" && !tariffCalibrated &&
                importedHistory.any {
                    TariffCatalog.normalize(it.tariffCode.orEmpty()) == "DAC" &&
                        it.consumptionKwh > 0 && it.amountMxn > 0
                },
            todayMarkerFraction = (energy.observedKwh.toFloat() / basisKwh.coerceAtLeast(1)).coerceIn(0f, 1f),
            opportunities = opportunities,
            limitReferences = limitReferences,
        )
    }

    fun setDacReferenceTariff(tariff: String) = repository.setDacReferenceTariff(tariff)
}

@Composable
fun DacRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { DacViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DacScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelectDacReferenceTariff = viewModel::setDacReferenceTariff,
    )
}

@Composable
fun DacScreen(
    uiState: DacUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectDacReferenceTariff: (String) -> Unit = {},
) {
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
                DacAlreadyClassifiedCard(uiState, onSelectDacReferenceTariff)
                if (uiState.hasUsageData && uiState.risk.referenceDomesticTariffCode != null) {
                    DacAnnualWindowCard(uiState)
                    DacNextBillCard(uiState)
                    DacAggressiveActionsCard(uiState)
                    TariffBreakdownCard(uiState)
                }
                DacLimitReferenceCard(uiState)
                return@Column
            }
            if (uiState.risk.level == DacRiskLevel.UNSUPPORTED) {
                DacMissingTariffCard()
                DacLimitReferenceCard(uiState)
                return@Column
            }
            if (!uiState.hasUsageData) {
                DacEmptyCard(hasTariff = uiState.referenceLimitKwh > 0)
                DacLimitReferenceCard(uiState)
                return@Column
            }

            DacStatusCard(uiState)
            DacAnnualWindowCard(uiState)
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

            DacLimitReferenceCard(uiState)

            TariffBreakdownCard(uiState)
        }
    }
}

@Composable
private fun DacLimitReferenceCard(uiState: DacUiState) {
    if (uiState.limitReferences.isEmpty()) return
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_limits_by_tariff_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.dac_limits_by_tariff_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            uiState.limitReferences.forEach { reference ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                ) {
                    Text(
                        if (reference.isSelected) {
                            stringResource(R.string.dac_tariff_selected, reference.tariffCode)
                        } else {
                            stringResource(R.string.dac_tariff_plain, reference.tariffCode)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (reference.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(
                            R.string.dac_tariff_limit_pair,
                            dacNumberFormat.format(reference.monthlyLimitKwh),
                            dacNumberFormat.format(reference.annualEquivalentKwh),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (reference.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.dac_limits_strictly_above),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
    }
}

@Composable
private fun DacAnnualWindowCard(uiState: DacUiState) {
    val risk = uiState.risk
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_rolling_year_title), style = MaterialTheme.typography.titleMedium)
        val start = risk.rollingWindowStartEpochDay
        val end = risk.rollingWindowEndEpochDay
        if (start != null && end != null) {
            Text(
                stringResource(R.string.dac_rolling_year_dates, dacDate(start), dacDate(end)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            DacStat(
                stringResource(R.string.dac_rolling_year_accumulated),
                stringResource(R.string.dac_kwh_value, uiState.rollingWindowKwh),
                MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f),
            )
            DacStat(
                stringResource(R.string.dac_rolling_year_maximum),
                stringResource(R.string.dac_kwh_value, uiState.annualLimitKwh),
                MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f),
            )
        }
        val margin = uiState.annualMarginKwh
        Text(
            if (margin >= 0) {
                stringResource(R.string.dac_rolling_year_margin_left, margin)
            } else {
                stringResource(R.string.dac_rolling_year_margin_over, -margin)
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (margin >= 0) RzTheme.extendedColors.success else RzTheme.extendedColors.warningText,
            modifier = Modifier.padding(top = RzSpacing.lg),
        )
        if (kotlin.math.abs(uiState.annualComparableKwh - uiState.rollingWindowKwh) >= 1) {
            Text(
                stringResource(
                    R.string.dac_rolling_year_annualized,
                    uiState.risk.coveredDays,
                    uiState.annualComparableKwh,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        Text(
            stringResource(R.string.dac_rolling_year_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
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
        val isAlreadyDac = uiState.risk.level == DacRiskLevel.ALREADY_DAC
        val message = when {
            !uiState.risk.hasCompleteHistory -> stringResource(R.string.dac_next_bill_need_history)
            recommended == null -> stringResource(R.string.dac_next_bill_need_reading)
            isAlreadyDac && projected != null && projected > recommended ->
                stringResource(R.string.dac_exit_next_bill_over, projected, recommended, projected - recommended)
            isAlreadyDac && projected != null ->
                stringResource(R.string.dac_exit_next_bill_ok, projected, recommended)
            isAlreadyDac -> stringResource(R.string.dac_exit_next_bill_limit, recommended)
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
        val expiring = uiState.risk.expiringBeforeNextBillKwh?.roundToInt()
        val retained = uiState.risk.retainedBeforeNextBillKwh?.roundToInt()
        if (expiring != null && retained != null) {
            Text(
                stringResource(R.string.dac_next_bill_expiring, expiring, retained),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        uiState.risk.projectedRollingWindowKwh?.let { projectedRolling ->
            val projectedMargin = uiState.risk.projectedAnnualMarginKwh?.roundToInt() ?: 0
            Text(
                if (projectedMargin >= 0) {
                    stringResource(
                        R.string.dac_next_bill_rolling_ok,
                        projectedRolling.roundToInt(),
                        uiState.annualLimitKwh,
                        projectedMargin,
                    )
                } else {
                    stringResource(
                        R.string.dac_next_bill_rolling_over,
                        projectedRolling.roundToInt(),
                        uiState.annualLimitKwh,
                        -projectedMargin,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (projectedMargin >= 0) RzTheme.extendedColors.success else RzTheme.extendedColors.warningText,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
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
                stringResource(
                    if (uiState.usesLatestDacBillEffectiveRate) {
                        R.string.dac_tariff_effective_bill
                    } else {
                        R.string.dac_tariff_unavailable
                    },
                ),
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
private fun DacAlreadyClassifiedCard(
    uiState: DacUiState,
    onSelectReferenceTariff: (String) -> Unit,
) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_already_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.dac_already_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.dac_exit_reference_prompt),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = RzSpacing.lg),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            uiState.limitReferences.forEach { reference ->
                FilterChip(
                    selected = reference.isSelected,
                    onClick = { onSelectReferenceTariff(reference.tariffCode) },
                    label = { Text(reference.tariffCode) },
                )
            }
        }
        uiState.risk.referenceDomesticTariffCode?.let { reference ->
            if (!uiState.hasUsageData) {
                Text(
                    stringResource(R.string.dac_exit_need_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
                return@let
            }
            val average = uiState.rollingAverageKwh
            val limit = uiState.referenceLimitKwh
            Text(
                if (average < limit) {
                    stringResource(R.string.dac_exit_below_target, average, limit, reference)
                } else {
                    stringResource(R.string.dac_exit_above_target, average - limit, limit, reference)
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (average < limit) RzTheme.extendedColors.success else RzTheme.extendedColors.warningText,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
        }
    }
}

@Composable
private fun DacAggressiveActionsCard(uiState: DacUiState) {
    if (uiState.opportunities.isEmpty()) return
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_exit_actions_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.dac_exit_actions_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        uiState.opportunities.forEach { opportunity ->
            Text(
                opportunity.actionTitle,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
            Text(
                stringResource(R.string.dac_exit_device_load, opportunity.name, opportunity.monthlyKwh),
                style = MaterialTheme.typography.labelMedium,
                color = RzTheme.extendedColors.warningText,
            )
            Text(
                opportunity.actionBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            opportunity.estimatedSavingsKwh?.let { savings ->
                Text(
                    stringResource(R.string.dac_exit_measured_saving, savings),
                    style = MaterialTheme.typography.labelMedium,
                    color = RzTheme.extendedColors.success,
                )
            }
        }
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
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX")).apply { maximumFractionDigits = 0 }
private val dacNumberFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-MX"))

private fun dacDate(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val months = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    return "${date.dayOfMonth} ${months[date.monthValue - 1]} ${date.year}"
}

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
