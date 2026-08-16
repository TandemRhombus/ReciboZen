package com.recibozen.app.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.components.RzStackedBarRow
import com.recibozen.app.core.calculation.OvernightBaselineCalculator
import com.recibozen.app.core.components.RzStatusChip
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Pushed flow screen "Balance de consumo": how much of the bill is explained vs unidentified. */
data class BalanceUiState(
    val hasUsageData: Boolean = false,
    val explainedSharePercent: Int = 0,
    val unidentifiedSharePercent: Int = 0,
    val explainedKwh: Int = 0,
    val unexplainedKwh: Int = 0,
    val observedKwh: Int = 0,
    val observedDays: Int = 0,
    val billingDays: Int = 0,
    /** True while the reading only covers part of the period, so figures are for those days. */
    val isPartialWindow: Boolean = false,
    /** Declared appliances exceed what the meter actually measured — hours are overstated. */
    val appliancesOverExplain: Boolean = false,
    val suspects: List<String> = emptyList(),
    val hasOvernightTest: Boolean = false,
    val overnightObservedKwh: Double = 0.0,
    val overnightExpectedKwh: Double = 0.0,
    val overnightUnexplainedKwh: Double = 0.0,
    val overnightHours: Int = 0,
    val overnightObservedWatts: Int = 0,
    val overnightExpectedWatts: Int = 0,
)

class BalanceViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<BalanceUiState> = combine(repository.session, repository.tariffCalibration) { state, _ ->
            val energy = repository.energySummary(state)
            val observed = energy.observedKwh
            // Appliance kWh is per full period. Against a mid-period reading it
            // has to be prorated to the measured window, otherwise the
            // appliances "explain" everything and nothing is ever unidentified.
            val periodApplianceKwh = repository.appliances(state).sumOf { it.kwh }
            val expectedInWindow = (periodApplianceKwh * energy.observedWindowFraction).roundToInt()
            val explained = expectedInWindow.coerceAtMost(observed)
            val unexplained = (observed - explained).coerceAtLeast(0)
            val explainedShare = if (observed == 0) 0 else (explained * 100f / observed).toInt().coerceIn(0, 100)
            // Phantom consumption: what the meter recorded overnight against
            // what the registered inventory can justify while nobody uses it.
            val overnight = OvernightBaselineCalculator.evaluate(state.readings, repository.appliances(state))
            BalanceUiState(
                hasUsageData = energy.hasUsageEvidence,
                explainedSharePercent = explainedShare,
                unidentifiedSharePercent = if (observed > 0) 100 - explainedShare else 0,
                explainedKwh = explained,
                unexplainedKwh = unexplained,
                observedKwh = observed,
                observedDays = energy.observedDays,
                billingDays = energy.billingDays,
                isPartialWindow = energy.observedWindowFraction < 0.99,
                appliancesOverExplain = expectedInWindow > observed && observed > 0,
                suspects = repository.appliances(state)
                    .filter { it.confidence != com.recibozen.app.core.model.ConfidenceLevel.HIGH }
                    .map { it.name },
                hasOvernightTest = overnight != null,
                overnightObservedKwh = overnight?.observedKwh ?: 0.0,
                overnightExpectedKwh = overnight?.expectedKwh ?: 0.0,
                overnightUnexplainedKwh = overnight?.unexplainedKwh ?: 0.0,
                overnightHours = overnight?.hours?.roundToInt() ?: 0,
                overnightObservedWatts = overnight?.observedWatts?.roundToInt() ?: 0,
                overnightExpectedWatts = overnight?.expectedWatts?.roundToInt() ?: 0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BalanceUiState())
}

@Composable
fun BalanceRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { BalanceViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BalanceScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onReviewRemaining = { navController.navigate(Routes.ApplianceEmpty) },
        onStartOvernightTest = { navController.navigate(Routes.ReadingMethod) },
    )
}

@Composable
fun BalanceScreen(
    uiState: BalanceUiState,
    onBack: () -> Unit,
    onReviewRemaining: () -> Unit,
    onStartOvernightTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { RzFlowTopBar(title = stringResource(R.string.balance_title), onBack = onBack) },
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
                    if (uiState.hasUsageData) stringResource(R.string.balance_explained_headline, uiState.explainedSharePercent)
                    else stringResource(R.string.balance_explained_headline_pending),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                RzStackedBarRow(
                    segments = if (uiState.hasUsageData) listOf(
                        RzBarSegment(
                            weight = uiState.explainedSharePercent.toFloat(),
                            color = MaterialTheme.colorScheme.primary,
                        ),
                        RzBarSegment(
                            weight = uiState.unidentifiedSharePercent.toFloat(),
                            color = MaterialTheme.colorScheme.outline,
                            striped = true,
                        ),
                    ) else listOf(RzBarSegment(weight = 100f, color = MaterialTheme.colorScheme.outlineVariant)),
                    height = 14.dp,
                    modifier = Modifier.padding(top = RzSpacing.lg),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.balance_explained_label, uiState.explainedKwh),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.balance_unidentified_label, uiState.unexplainedKwh),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (uiState.hasUsageData) stringResource(
                        R.string.balance_explained_body,
                        uiState.unidentifiedSharePercent,
                        uiState.observedKwh,
                    ) else stringResource(R.string.balance_explained_body_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
                if (uiState.hasUsageData && uiState.isPartialWindow) {
                    Text(
                        stringResource(
                            R.string.balance_partial_window_note,
                            uiState.observedDays,
                            uiState.billingDays,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
                if (uiState.appliancesOverExplain) {
                    Text(
                        stringResource(R.string.balance_appliances_over_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = RzTheme.extendedColors.warningText,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
                RzPrimaryButton(
                    text = stringResource(R.string.balance_review_remaining_action),
                    onClick = onReviewRemaining,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.lg),
                )
            }

            Text(
                stringResource(R.string.balance_suspects_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                uiState.suspects.forEach { suspect ->
                    RzStatusChip(
                        text = suspect,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (uiState.suspects.isEmpty()) {
                    Text(
                        stringResource(R.string.balance_suspects_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            RzCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            ) {
                Text(
                    stringResource(R.string.balance_overnight_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.balance_overnight_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
                if (uiState.hasOvernightTest) RzCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.md),
                    borderColor = null,
                ) {
                    Text(
                        stringResource(
                            R.string.balance_overnight_observed,
                            uiState.overnightObservedKwh,
                            uiState.overnightHours,
                            uiState.overnightObservedWatts,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.balance_overnight_expected,
                            uiState.overnightExpectedKwh,
                            uiState.overnightExpectedWatts,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        stringResource(
                            if (uiState.overnightUnexplainedKwh > 0.05) R.string.balance_overnight_unexplained
                            else R.string.balance_overnight_matches,
                            uiState.overnightUnexplainedKwh,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (uiState.overnightUnexplainedKwh > 0.05) {
                            RzTheme.extendedColors.warningText
                        } else RzTheme.extendedColors.success,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
                else Text(
                    stringResource(R.string.balance_overnight_no_result),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
                Text(
                    stringResource(R.string.balance_overnight_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
                RzSecondaryButton(
                    text = stringResource(R.string.balance_overnight_action),
                    onClick = onStartOvernightTest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.md),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BalanceScreenPreview() {
    ReciboZenTheme {
        BalanceScreen(
            uiState = BalanceUiState(),
            onBack = {},
            onReviewRemaining = {},
            onStartOvernightTest = {},
        )
    }
}
