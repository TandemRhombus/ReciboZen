package com.recibozen.app.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzLinearProgress
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.components.RzStatusChip
import com.recibozen.app.core.components.RzTabHeader
import com.recibozen.app.core.components.rzTimeGreeting
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.EffortLevel
import com.recibozen.app.core.model.PlanAction
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenSessionState
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Bottom-nav "Plan" tab root: progress toward the period goal plus the plan
 * actions (suggestions, tracked, verified) and 3 link-out cards.
 */
data class PlanUiState(
    val userName: String = "",
    val householdName: String = "",
    val userInitial: String = "",
    val hasProjection: Boolean = false,
    val projectedMxn: Int = 0,
    val rangeHighMxn: Int = 0,
    val isWithinGoal: Boolean = false,
    val planProgressFraction: Float = 0f,
    val goalMxn: Int = 0,
    val baselineMxn: Int = 0,
    val kwhAboveGoal: Int = 0,
    val suggestionActions: List<PlanAction> = emptyList(),
    val verifiedAction: PlanAction? = null,
)

class PlanViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<PlanUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        repository.tariffCalibration,
    ) { state, imported, _ ->
            val latestEnd = imported.lastOrNull()?.periodEndEpochDay?.times(86_400_000L)
            val hasLiveReading = latestEnd == null || state.readings.any { reading ->
                reading.method != com.recibozen.app.core.model.ReadingMethod.PDF &&
                    (reading.timestampEpochMillis ?: Long.MIN_VALUE) > latestEnd
            }
            toUiState(state, hasLiveReading)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), toUiState(repository.session.value))

    private fun toUiState(state: ReciboZenSessionState, hasLiveReading: Boolean = false): PlanUiState {
        val projection = repository.billProjection(state)
        val energy = repository.energySummary(state)
        return PlanUiState(
            userName = state.userName,
            householdName = state.householdName,
            userInitial = state.userName.firstOrNull()?.uppercase() ?: "?",
            hasProjection = projection.hasUsageEvidence && hasLiveReading,
            projectedMxn = projection.projectedMxn,
            rangeHighMxn = projection.rangeHighMxn,
            isWithinGoal = projection.isWithinGoal,
            planProgressFraction = projection.planProgressFraction,
            goalMxn = state.goalMxn,
            baselineMxn = energy.projectedCostMxn,
            kwhAboveGoal = kwhReductionToGoal(state, energy.projectedKwh, energy.projectedCostMxn, projection.projectedMxn),
            suggestionActions = state.planActions.filter { it.status != PlanActionStatus.VERIFIED },
            verifiedAction = state.planActions.find { it.status == PlanActionStatus.VERIFIED },
        )
    }

    private fun kwhReductionToGoal(
        state: ReciboZenSessionState,
        projectedKwh: Int,
        projectedTariffCostMxn: Int,
        projectedAfterPlanMxn: Int,
    ): Int {
        if (state.goalMxn <= 0 || projectedKwh <= 0 || projectedAfterPlanMxn <= state.goalMxn) return 0
        val requiredCostCut = projectedAfterPlanMxn - state.goalMxn
        val targetTariffCost = (projectedTariffCostMxn - requiredCostCut).coerceAtLeast(0)
        var low = 0
        var high = projectedKwh
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (repository.estimateCostMxn(middle) <= targetTariffCost) low = middle else high = middle - 1
        }
        return (projectedKwh - low).coerceAtLeast(0)
    }

    fun toggleAction(id: String) {
        repository.togglePlanAction(id)
    }
}

@Composable
fun PlanRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { PlanViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PlanScreen(
        uiState = uiState,
        onProfileClick = { navController.navigate(Routes.Profile) },
        onHouseholdClick = { navController.navigate(Routes.Services) },
        onAddAction = viewModel::toggleAction,
        onRemoveAction = viewModel::toggleAction,
        onDacClick = { navController.navigate(Routes.Dac) },
        onSolarClick = { navController.navigate(Routes.Solar) },
        onBalanceClick = { navController.navigate(Routes.Balance) },
    )
}

@Composable
fun PlanScreen(
    uiState: PlanUiState,
    onProfileClick: () -> Unit,
    onAddAction: (String) -> Unit,
    onRemoveAction: (String) -> Unit,
    onDacClick: () -> Unit,
    onSolarClick: () -> Unit,
    onBalanceClick: () -> Unit,
    modifier: Modifier = Modifier,
    onHouseholdClick: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        RzTabHeader(
            greeting = rzTimeGreeting(uiState.userName),
            householdName = uiState.householdName.ifBlank { stringResource(R.string.home_household_unconfigured) },
            userInitial = uiState.userInitial,
            onHouseholdClick = onHouseholdClick,
            onProfileClick = onProfileClick,
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
                    stringResource(R.string.plan_headline),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.plan_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!uiState.hasProjection) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.plan_empty_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.plan_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
            } else {
            PlanSummaryCard(uiState)

            Text(
                stringResource(R.string.plan_suggestions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            uiState.suggestionActions.forEach { action ->
                PlanActionCard(
                    action = action,
                    onAdd = { onAddAction(action.id) },
                    onRemove = { onRemoveAction(action.id) },
                )
            }

            uiState.verifiedAction?.let { VerifiedActionCard(it) }
            }

            PlanLinkCard(
                icon = RzIcons.Monitoring,
                iconTint = RzTheme.extendedColors.warning,
                title = stringResource(R.string.plan_dac_link_title),
                description = stringResource(R.string.plan_dac_link_description),
                actionLabel = stringResource(R.string.plan_dac_link_action),
                onClick = onDacClick,
            )

            PlanLinkCard(
                icon = RzIcons.SolarPower,
                iconTint = RzTheme.extendedColors.solarText,
                title = stringResource(R.string.plan_solar_link_title),
                description = stringResource(R.string.plan_solar_link_description),
                actionLabel = stringResource(R.string.plan_solar_link_action),
                onClick = onSolarClick,
                containerColor = RzTheme.extendedColors.solarContainer,
                borderColor = RzTheme.extendedColors.solarBorder,
            )

            BalanceLinkRow(onClick = onBalanceClick)
        }
    }
}

@Composable
private fun PlanSummaryCard(uiState: PlanUiState) {
    val diffMxn = uiState.projectedMxn - uiState.goalMxn
    val proyColor = if (uiState.isWithinGoal) RzTheme.extendedColors.success else MaterialTheme.colorScheme.onSurface
    val resumen = when {
        !uiState.isWithinGoal -> {
            stringResource(R.string.plan_summary_above_goal, diffMxn, uiState.kwhAboveGoal)
        }
        uiState.rangeHighMxn > uiState.goalMxn ->
            stringResource(R.string.plan_summary_within_goal_uncertain, uiState.rangeHighMxn)
        else -> stringResource(R.string.plan_summary_within_goal_confident)
    }
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.plan_summary_eyebrow),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.plan_summary_amount, uiState.projectedMxn),
            style = MaterialTheme.typography.displaySmall,
            color = proyColor,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        Text(
            resumen,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        RzLinearProgress(
            progress = uiState.planProgressFraction,
            height = 10.dp,
            modifier = Modifier.padding(top = RzSpacing.md),
            contentDescription = stringResource(R.string.plan_summary_progress_description),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = RzSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.plan_summary_goal_label, uiState.goalMxn),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                stringResource(R.string.plan_summary_no_change_label, uiState.baselineMxn),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PlanActionCard(
    action: PlanAction,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    var showManageDialog by remember { mutableStateOf(false) }
    val isTracking = action.status == PlanActionStatus.TRACKING
    val borderColor = if (isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    RzCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = borderColor,
    ) {
        RzStatusChip(
            text = stringResource(if (isTracking) R.string.plan_chip_tracking else R.string.plan_chip_suggested),
            icon = if (isTracking) RzIcons.Visibility else RzIcons.Lightbulb,
            containerColor = if (isTracking) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isTracking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            action.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(
                R.string.plan_action_meta,
                action.savingsRangeLabel,
                effortLabel(action.effort),
                confidenceLabel(action.confidence),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (isTracking && action.trackingNote != null) {
            RzCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = RzSpacing.md),
                containerColor = MaterialTheme.colorScheme.background,
                borderColor = null,
            ) {
                Text(
                    stringResource(R.string.plan_tracking_since, action.trackingSinceLabel.orEmpty()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    action.trackingNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (isTracking) {
            RzSecondaryButton(
                text = stringResource(R.string.plan_action_manage),
                onClick = { showManageDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = RzSpacing.md),
            )
        } else {
            RzPrimaryButton(
                text = stringResource(R.string.plan_action_add),
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = RzSpacing.md),
            )
        }
    }

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text(stringResource(R.string.plan_manage_dialog_title)) },
            text = { Text(stringResource(R.string.plan_manage_dialog_body, action.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove()
                    showManageDialog = false
                }) {
                    Text(stringResource(R.string.plan_manage_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showManageDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun VerifiedActionCard(action: PlanAction) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        RzStatusChip(
            text = stringResource(R.string.plan_chip_verified),
            icon = RzIcons.TaskAlt,
            containerColor = RzTheme.extendedColors.successContainer,
            contentColor = RzTheme.extendedColors.success,
        )
        Text(
            action.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.plan_verified_since, action.verifiedSinceLabel.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (action.verifiedNote != null) {
            RzCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = RzSpacing.md),
                containerColor = RzTheme.extendedColors.successContainer,
                borderColor = null,
            ) {
                Text(
                    action.verifiedNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PlanLinkCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    RzCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        containerColor = containerColor,
        borderColor = borderColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun BalanceLinkRow(onClick: () -> Unit) {
    RzCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentPadding = PaddingValues(horizontal = RzSpacing.xl, vertical = RzSpacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Icon(RzIcons.DonutSmall, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.plan_balance_link_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.plan_balance_link_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Icon(RzIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun effortLabel(effort: EffortLevel): String = stringResource(
    when (effort) {
        EffortLevel.LOW -> R.string.plan_effort_low
        EffortLevel.MEDIUM -> R.string.plan_effort_medium
        EffortLevel.HIGH -> R.string.plan_effort_high
    },
)

@Composable
private fun confidenceLabel(confidence: ConfidenceLevel): String = stringResource(
    when (confidence) {
        ConfidenceLevel.LOW -> R.string.plan_confidence_low
        ConfidenceLevel.MEDIUM -> R.string.plan_confidence_medium
        ConfidenceLevel.HIGH -> R.string.plan_confidence_high
    },
)

@Preview(showBackground = true)
@Composable
private fun PlanScreenPreview() {
    ReciboZenTheme {
        PlanScreen(
            uiState = PlanUiState(
                userName = "Emi",
                householdName = "Casa",
                userInitial = "E",
                projectedMxn = 846,
                rangeHighMxn = 890,
                isWithinGoal = false,
                planProgressFraction = 0.05f,
                suggestionActions = DemoReciboZenData.planActions.filter {
                    it.status != PlanActionStatus.VERIFIED
                },
                verifiedAction = DemoReciboZenData.planActions.find {
                    it.status == PlanActionStatus.VERIFIED
                },
            ),
            onProfileClick = {},
            onAddAction = {},
            onRemoveAction = {},
            onDacClick = {},
            onSolarClick = {},
            onBalanceClick = {},
        )
    }
}
