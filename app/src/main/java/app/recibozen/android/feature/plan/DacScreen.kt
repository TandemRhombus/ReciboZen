package app.recibozen.android.feature.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import app.recibozen.android.core.designsystem.RzIcons
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
    /** Everything the main surface shows, already reduced to four answers. */
    val glance: DacGlance = risk.toGlance()

    /** Raw billed energy inside the window, only used by the "how" disclosure. */
    val rollingWindowKwh: Int get() = risk.rollingWindowKwh?.roundToInt() ?: 0
    val annualComparableKwh: Int get() = risk.annualizedRollingKwh?.roundToInt() ?: 0
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
    val glance = uiState.glance
    Scaffold(
        modifier = modifier,
        topBar = { RzFlowTopBar(title = stringResource(R.string.dac_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal).padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            when {
                uiState.risk.level == DacRiskLevel.UNSUPPORTED -> {
                    DacMissingTariffCard()
                    DacHowCard(uiState)
                }

                uiState.risk.level == DacRiskLevel.ALREADY_DAC -> {
                    // Without a reference tariff there is no limit to compare
                    // against, so the picker is the only thing worth showing.
                    if (glance.kind == DacGlanceKind.UNAVAILABLE) {
                        DacExitReferenceCard(uiState, onSelectDacReferenceTariff)
                        if (uiState.risk.referenceDomesticTariffCode != null) DacEmptyCard(hasTariff = true)
                    } else {
                        DacHeroCard(uiState, glance)
                        DacNextBillCard(glance)
                        DacExitActionsCard(uiState)
                        DacExitReferenceCard(uiState, onSelectDacReferenceTariff)
                    }
                    DacHowCard(uiState)
                    DacTiersCard(uiState)
                }

                glance.kind == DacGlanceKind.UNAVAILABLE -> {
                    DacEmptyCard(hasTariff = uiState.risk.monthlyLimitKwh != null)
                    DacHowCard(uiState)
                }

                else -> {
                    DacHeroCard(uiState, glance)
                    DacNextBillCard(glance)
                    DacOpportunitiesCard(uiState)
                    DacHowCard(uiState)
                    DacTiersCard(uiState)
                }
            }
        }
    }
}

/**
 * One number, one bar, and at most one sentence. Everything a reader needs to
 * answer "how much room do I have left" without scrolling.
 */
@Composable
private fun DacHeroCard(uiState: DacUiState, glance: DacGlance) {
    val tone = glance.toneColor()
    RzCard(modifier = Modifier.fillMaxWidth()) {
        if (glance.kind == DacGlanceKind.EXIT) {
            Text(
                stringResource(R.string.dac_already_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = RzSpacing.md),
            )
        }
        Text(
            stringResource(
                when {
                    glance.kind == DacGlanceKind.NO_VERDICT -> R.string.dac_hero_label_unknown
                    glance.isOverLimit -> R.string.dac_hero_label_over
                    else -> R.string.dac_hero_label_left
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(
            modifier = Modifier.padding(top = RzSpacing.xs),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            Text(dacKwh(glance.heroKwh), style = MaterialTheme.typography.displaySmall, color = tone)
            Text(
                stringResource(R.string.dac_kwh_unit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = RzSpacing.xs),
            )
        }
        RzMarkedProgress(
            progress = glance.fillFraction,
            markerAt = glance.limitMarkerFraction,
            progressColor = tone,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        if (glance.kind != DacGlanceKind.NO_VERDICT) {
            Text(
                stringResource(
                    R.string.dac_hero_window,
                    dacKwh(glance.accumulatedKwh),
                    dacKwh(glance.annualLimitKwh),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        when (glance.kind) {
            DacGlanceKind.NO_VERDICT -> Text(
                stringResource(R.string.dac_hero_unknown_body, glance.coveredMonths),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
            // The question that frustrates most: over the limit, still not DAC.
            DacGlanceKind.OVER_LIMIT -> Text(
                stringResource(R.string.dac_over_reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
            DacGlanceKind.EXIT -> {
                val reference = uiState.risk.referenceDomesticTariffCode ?: uiState.tariff
                Text(
                    if (glance.monthlyMarginKwh >= 0) {
                        stringResource(R.string.dac_exit_below_target, glance.monthlyMarginKwh, reference)
                    } else {
                        stringResource(R.string.dac_exit_above_target, -glance.monthlyMarginKwh, reference)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = tone,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
            }
            DacGlanceKind.HEADROOM, DacGlanceKind.UNAVAILABLE -> Unit
        }
        Text(
            stringResource(R.string.dac_prediction_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
    }
}

/** Three figures, no prose: what leaves, what enters, what is left. */
@Composable
private fun DacNextBillCard(glance: DacGlance) {
    if (glance.kind == DacGlanceKind.NO_VERDICT) return
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_next_bill_title), style = MaterialTheme.typography.titleMedium)
        val ledger = glance.ledger
        if (ledger == null) {
            Text(
                stringResource(R.string.dac_next_bill_need_reading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        } else {
            DacFigureRow(
                stringResource(R.string.dac_ledger_expiring),
                dacSignedKwh(-ledger.expiringKwh),
                MaterialTheme.colorScheme.onSurface,
            )
            DacFigureRow(
                stringResource(R.string.dac_ledger_projected),
                dacSignedKwh(ledger.projectedKwh),
                MaterialTheme.colorScheme.onSurface,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = RzSpacing.sm),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            DacFigureRow(
                stringResource(R.string.dac_ledger_result),
                dacSignedKwh(ledger.resultingMarginKwh),
                if (ledger.exceeds || ledger.resultingMarginKwh < 0) {
                    RzTheme.extendedColors.warningText
                } else {
                    RzTheme.extendedColors.success
                },
            )
        }
        DacRecommendedMaxima(glance)
    }
}

/**
 * The two ceilings: the period in progress, and the one after it. The second
 * only exists on top of the projection, so it carries that condition in writing.
 */
@Composable
private fun DacRecommendedMaxima(glance: DacGlance) {
    val current = glance.recommendedMaxKwh ?: return
    val following = glance.recommendedFollowingMaxKwh
    if (following == null) {
        Text(
            stringResource(R.string.dac_ledger_recommended, dacKwh(current)),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        return
    }
    Text(
        stringResource(R.string.dac_max_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = RzSpacing.lg),
    )
    DacFigureRow(
        stringResource(R.string.dac_max_current),
        dacKwh(current),
        MaterialTheme.colorScheme.onSurface,
    )
    DacFigureRow(
        stringResource(R.string.dac_max_following),
        dacKwh(following),
        MaterialTheme.colorScheme.onSurface,
    )
    DacNote(stringResource(R.string.dac_max_following_note))
}

@Composable
private fun DacFigureRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.dac_kwh_value, value),
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun DacOpportunitiesCard(uiState: DacUiState) {
    if (uiState.opportunities.isEmpty()) return
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_opportunities_title), style = MaterialTheme.typography.titleMedium)
        uiState.opportunities.forEach { opportunity ->
            Text(
                stringResource(R.string.dac_exit_device_load, opportunity.name, opportunity.monthlyKwh),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
    }
}

/**
 * The audit trail: rolling window, annualisation, per-tariff limits and history
 * coverage. Correct, but not what the reader opens the screen for — so it stays
 * collapsed instead of being deleted.
 */
@Composable
private fun DacHowCard(uiState: DacUiState) {
    val risk = uiState.risk
    DacDisclosure(title = stringResource(R.string.dac_how_title)) {
        val start = risk.rollingWindowStartEpochDay
        val end = risk.rollingWindowEndEpochDay
        if (start != null && end != null) {
            Text(stringResource(R.string.dac_rolling_year_title), style = MaterialTheme.typography.titleSmall)
            DacNote(stringResource(R.string.dac_rolling_year_dates, dacDate(start), dacDate(end)))
        }
        DacNote(stringResource(R.string.dac_rolling_year_explanation))
        if (kotlin.math.abs(uiState.annualComparableKwh - uiState.rollingWindowKwh) >= 1) {
            DacNote(
                stringResource(
                    R.string.dac_rolling_year_annualized,
                    risk.coveredDays,
                    uiState.annualComparableKwh,
                ),
            )
        }
        if (risk.coveredDays > 0) {
            DacNote(
                if (risk.hasCompleteHistory) {
                    stringResource(R.string.dac_history_complete, risk.coveredMonths)
                } else {
                    stringResource(R.string.dac_history_incomplete, risk.coveredMonths)
                },
            )
        }
        if (uiState.limitReferences.isNotEmpty()) {
            Text(
                stringResource(R.string.dac_limits_by_tariff_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
            DacNote(stringResource(R.string.dac_limits_by_tariff_body))
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                uiState.limitReferences.forEach { reference -> DacLimitRow(reference) }
            }
            DacNote(stringResource(R.string.dac_limits_strictly_above))
        }
    }
}

@Composable
private fun DacLimitRow(reference: DacLimitReference) {
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
                dacKwh(reference.monthlyLimitKwh),
                dacKwh(reference.annualEquivalentKwh),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (reference.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Cost tiers inside the current period — a different question, also collapsed. */
@Composable
private fun DacTiersCard(uiState: DacUiState) {
    DacDisclosure(title = stringResource(R.string.dac_tariff_title, uiState.tariff.ifBlank { "—" })) {
        if (!uiState.tariffCalibrated || uiState.tariffTiers.isEmpty()) {
            DacNote(
                stringResource(
                    if (uiState.usesLatestDacBillEffectiveRate) {
                        R.string.dac_tariff_effective_bill
                    } else {
                        R.string.dac_tariff_unavailable
                    },
                ),
            )
            return@DacDisclosure
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
            modifier = Modifier.padding(top = RzSpacing.md),
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
        DacNote(stringResource(R.string.dac_tariff_footnote))
    }
}

@Composable
private fun DacExitReferenceCard(uiState: DacUiState, onSelectReferenceTariff: (String) -> Unit) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dac_exit_reference_prompt), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
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
    }
}

@Composable
private fun DacExitActionsCard(uiState: DacUiState) {
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

/** Collapsible card: the detail stays available without owning the first screen. */
@Composable
private fun DacDisclosure(title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                RzIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = RzSpacing.md)) { content() }
        }
    }
}

@Composable
private fun DacNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = RzSpacing.xs),
    )
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

@Composable
private fun DacGlance.toneColor(): Color = when (tone) {
    DacGlanceTone.SAFE -> RzTheme.extendedColors.success
    DacGlanceTone.CAUTION -> RzTheme.extendedColors.warning
    DacGlanceTone.OVER -> RzTheme.extendedColors.warningText
    DacGlanceTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}

private val dacMoneyFormat: NumberFormat =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX")).apply { maximumFractionDigits = 0 }

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
                    rollingWindowKwh = 9_480.0,
                    annualizedRollingKwh = 9_480.0,
                    annualMarginKwh = 720.0,
                    utilizationFraction = 0.93,
                    coveredDays = 365,
                    hasCompleteHistory = true,
                    level = DacRiskLevel.RISK,
                    projectedNextBillKwh = 1_500,
                    recommendedNextBillMaxKwh = 1_380,
                    recommendedFollowingBillMaxKwh = 1_140,
                    expiringBeforeNextBillKwh = 1_260.0,
                    retainedBeforeNextBillKwh = 8_220.0,
                    projectedRollingWindowKwh = 9_720.0,
                    projectedAnnualMarginKwh = 480.0,
                    wouldExceedWithProjection = true,
                ),
                tariff = "1C",
                opportunities = listOf(DacOpportunity("Minisplit", 120)),
            ),
            onBack = {},
        )
    }
}
