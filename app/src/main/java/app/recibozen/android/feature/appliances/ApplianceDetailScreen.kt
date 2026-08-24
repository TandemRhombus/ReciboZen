package app.recibozen.android.feature.appliances

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.calculation.ApplianceAdvice
import app.recibozen.android.core.calculation.ApplianceAdviceEngine
import app.recibozen.android.core.calculation.ApplianceAdviceEvidence
import app.recibozen.android.core.calculation.ApplianceAdvicePriority
import app.recibozen.android.core.components.RzBarSegment
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.components.RzStackedBarRow
import app.recibozen.android.core.components.RzStatusChip
import app.recibozen.android.core.designsystem.RzExtraShapes
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.calculation.EvidenceResolution
import app.recibozen.android.core.calculation.EvidenceResolver
import app.recibozen.android.core.calculation.declaredEvidence
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.DeviceEvidence
import app.recibozen.android.core.model.EvidenceMethodology
import app.recibozen.android.core.model.EvidenceMetric
import app.recibozen.android.core.model.EvidenceSource
import app.recibozen.android.core.model.PlanAction
import app.recibozen.android.core.model.PlanActionStatus
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.rzViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ApplianceDetailUiState(
    val applianceId: String,
    val appliance: Appliance?,
    val recommendationAction: PlanAction?,
    val recommendations: List<ApplianceAdvice> = emptyList(),
)

/**
 * Looks up the appliance + (when applicable) its associated [PlanAction] on
 * every session change, so toggling the recommendation card's CTA (which
 * calls [ReciboZenRepository.togglePlanAction]) is reflected immediately.
 */
class ApplianceDetailViewModel(
    private val repository: ReciboZenRepository,
    private val applianceId: String,
) : ViewModel() {

    val uiState: StateFlow<ApplianceDetailUiState> = combine(repository.session, repository.tariffCalibration) { session, _ ->
            val appliance = repository.appliance(applianceId, session)
            ApplianceDetailUiState(
                applianceId = applianceId,
                appliance = appliance,
                recommendationAction = repository.planActions(session)
                    .find { it.applianceId == applianceId },
                recommendations = appliance?.let(ApplianceAdviceEngine::recommendations).orEmpty(),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ApplianceDetailUiState(
                applianceId = applianceId,
                appliance = repository.appliance(applianceId),
                recommendationAction = repository.planActions()
                    .find { it.applianceId == applianceId },
                recommendations = repository.appliance(applianceId)
                    ?.let(ApplianceAdviceEngine::recommendations)
                    .orEmpty(),
            ),
        )

    /** Adds or removes this appliance's top action from the plan. */
    fun onToggleRecommendation() {
        repository.planActions()
            .find { it.applianceId == applianceId }
            ?.let { repository.togglePlanAction(it.id) }
    }

    fun onRemove() = repository.removeAppliance(applianceId)

}

@Composable
fun ApplianceDetailRoute(
    navController: NavHostController,
    repository: ReciboZenRepository,
    applianceId: String,
) {
    val viewModel = rzViewModel { ApplianceDetailViewModel(repository, applianceId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ApplianceDetailScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onModifyUsage = { navController.navigate(Routes.applianceUsage(applianceId)) },
        onToggleRecommendation = viewModel::onToggleRecommendation,
        onHowWeCalculate = { navController.navigate(Routes.HowWeCalculate) },
        onEditData = { navController.navigate(Routes.applianceUsage(applianceId)) },
        onRemove = {
            viewModel.onRemove()
            navController.popBackStack()
        },
    )
}

@Composable
fun ApplianceDetailScreen(
    uiState: ApplianceDetailUiState,
    onBack: () -> Unit,
    onModifyUsage: () -> Unit,
    onToggleRecommendation: () -> Unit,
    onHowWeCalculate: () -> Unit,
    onEditData: () -> Unit,
    onRemove: () -> Unit = {},
) {
    val appliance = uiState.appliance
    val uriHandler = LocalUriHandler.current
    var showRemoveDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        RzFlowTopBar(title = appliance?.name.orEmpty(), onBack = onBack)

        if (appliance == null) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(RzSpacing.screenHorizontal)) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.appliances_detail_fallback_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.appliances_detail_fallback_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                }
            }
            return
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            HeaderCard(appliance = appliance, onModifyUsage = onModifyUsage)

            if (appliance.activeCostMxn != null && appliance.standbyCostMxn != null) {
                ActiveStandbyCard(appliance)
            }

            if (uiState.recommendations.isNotEmpty()) {
                SavingsAdviceSection(
                    recommendations = uiState.recommendations,
                    trackedAction = uiState.recommendationAction,
                    onToggle = onToggleRecommendation,
                )
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.appliances_detail_source_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    appliance.sourceLabel ?: when (appliance.dataSource) {
                        ApplianceDataSource.MANUFACTURER_LABEL -> stringResource(R.string.appliances_source_label)
                        ApplianceDataSource.USER_MEASURED -> stringResource(R.string.appliances_source_measured)
                        ApplianceDataSource.CATALOG -> stringResource(R.string.appliances_detail_source_catalog)
                        ApplianceDataSource.RECEIPT_INFERENCE -> stringResource(R.string.appliances_detail_source_inferred)
                        ApplianceDataSource.USER_ESTIMATE -> stringResource(R.string.appliances_source_estimated)
                        ApplianceDataSource.AI_SUGGESTION -> stringResource(R.string.appliances_source_ai)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                EvidenceProvenance(appliance)
                if (appliance.marketMexicoVerified == false) {
                    Text(
                        stringResource(R.string.appliances_detail_market_unverified),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                }
                if (appliance.isModelFamily) {
                    Text(
                        stringResource(R.string.appliances_detail_model_family),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                }
                if (
                    appliance.referenceAnnualKwhMin != null &&
                    appliance.referenceAnnualKwhMax != null &&
                    appliance.referenceAnnualKwhMax - appliance.referenceAnnualKwhMin >= 0.1
                ) {
                    Text(
                        stringResource(
                            R.string.appliances_detail_annual_range,
                            appliance.referenceAnnualKwhMin,
                            appliance.referenceAnnualKwhMax,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                }
                appliance.sourceUrl?.let { sourceUrl ->
                    Text(
                        stringResource(R.string.appliances_detail_open_source),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = RzSpacing.sm)
                            .clickable { runCatching { uriHandler.openUri(sourceUrl) } },
                    )
                }
            }

            if (appliance.calculationNote != null) {
                RzCard(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onHowWeCalculate),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                        Icon(RzIcons.Functions, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.appliances_detail_calc_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                appliance.calculationNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Icon(RzIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            RzSecondaryButton(
                text = stringResource(R.string.appliances_detail_edit_data),
                onClick = onEditData,
                modifier = Modifier.fillMaxWidth(),
            )

            RzSecondaryButton(
                text = stringResource(R.string.appliances_detail_remove),
                onClick = { showRemoveDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showRemoveDialog && appliance != null) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.appliances_detail_remove_title, appliance.name)) },
            text = { Text(stringResource(R.string.appliances_detail_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        onRemove()
                    },
                ) { Text(stringResource(R.string.appliances_detail_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HeaderCard(appliance: Appliance, onModifyUsage: () -> Unit) {
    val ext = RzTheme.extendedColors
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
            Text(appliance.room, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val (labelRes, icon, container, content) = when (appliance.confidence) {
                ConfidenceLevel.HIGH -> ConfidenceStyle(R.string.appliances_chip_confidence_high, RzIcons.CheckCircle, ext.successContainer, ext.success)
                ConfidenceLevel.MEDIUM -> ConfidenceStyle(R.string.appliances_chip_confidence_medium, RzIcons.GppMaybe, ext.warningContainer, ext.warningText)
                ConfidenceLevel.LOW -> ConfidenceStyle(R.string.appliances_chip_confidence_low, RzIcons.Help, ext.warningContainer, ext.warningText)
            }
            RzStatusChip(text = stringResource(labelRes), icon = icon, containerColor = container, contentColor = content)
        }
        Text(
            if (appliance.quantity > 1) {
                stringResource(R.string.appliances_name_with_quantity, appliance.name, appliance.quantity)
            } else appliance.name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        if (appliance.brand != null || appliance.model != null) {
            Text(
                listOfNotNull(appliance.brand, appliance.model).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
        }
        Text(
            stringResource(R.string.appliances_detail_cost_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.lg),
        )
        Text(
            stringResource(R.string.appliances_cost_format, appliance.costMxn),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = RzSpacing.lg)
                .background(MaterialTheme.colorScheme.background, RzExtraShapes.cardMedium)
                .padding(horizontal = RzSpacing.lg, vertical = RzSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.appliances_detail_usage_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    appliance.hoursPerWeek?.let {
                        stringResource(R.string.appliances_detail_usage_value, it, appliance.kwh)
                    } ?: stringResource(
                        R.string.appliances_detail_usage_fixed_value,
                        appliance.unitKwh ?: appliance.kwh.toDouble() / appliance.quantity.coerceAtLeast(1),
                        appliance.kwh,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            RzSecondaryButton(text = stringResource(R.string.appliances_detail_modify), onClick = onModifyUsage)
        }

        if (appliance.previousPeriodDeltaPercent != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = RzSpacing.md)
                    .background(ext.warningContainer, RzExtraShapes.control)
                    .padding(horizontal = RzSpacing.md, vertical = RzSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                Icon(RzIcons.TrendingUp, contentDescription = null, tint = ext.warning)
                Text(
                    stringResource(R.string.appliances_detail_delta_banner, appliance.previousPeriodDeltaPercent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ActiveStandbyCard(appliance: Appliance) {
    val primary = MaterialTheme.colorScheme.primary
    // Guaranteed non-null by the caller's guard (both fields checked together
    // before this composable is invoked), but Kotlin smart-casts don't cross
    // function boundaries, so pin them to local non-null vals once.
    val activeCostMxn = appliance.activeCostMxn ?: 0
    val standbyCostMxn = appliance.standbyCostMxn ?: 0
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.appliances_detail_active_standby_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        RzStackedBarRow(
            segments = listOf(
                RzBarSegment(weight = activeCostMxn.toFloat(), color = primary, striped = false),
                RzBarSegment(weight = standbyCostMxn.toFloat(), color = primary, striped = true),
            ),
            modifier = Modifier.padding(top = RzSpacing.md),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.appliances_detail_active_label, activeCostMxn),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.appliances_detail_standby_label, standbyCostMxn),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.appliances_detail_active_standby_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun SavingsAdviceSection(
    recommendations: List<ApplianceAdvice>,
    trackedAction: PlanAction?,
    onToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
        Text(
            stringResource(R.string.appliances_advice_section_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.appliances_advice_section_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        recommendations.forEachIndexed { index, advice ->
            SavingsAdviceCard(
                advice = advice,
                trackedAction = trackedAction.takeIf { index == 0 },
                onToggle = onToggle,
                highlighted = index == 0,
            )
        }
    }
}

@Composable
private fun SavingsAdviceCard(
    advice: ApplianceAdvice,
    trackedAction: PlanAction?,
    onToggle: () -> Unit,
    highlighted: Boolean,
) {
    val ext = RzTheme.extendedColors
    val uriHandler = LocalUriHandler.current
    val evidenceLabel = when (advice.evidence) {
        ApplianceAdviceEvidence.MODEL -> stringResource(R.string.appliances_advice_evidence_model)
        ApplianceAdviceEvidence.BRAND -> stringResource(R.string.appliances_advice_evidence_brand)
        ApplianceAdviceEvidence.DEVICE_DATA -> stringResource(R.string.appliances_advice_evidence_data)
        ApplianceAdviceEvidence.CATEGORY -> stringResource(R.string.appliances_advice_evidence_category)
    }
    val priorityLabel = when (advice.priority) {
        ApplianceAdvicePriority.HIGH -> stringResource(R.string.appliances_advice_priority_high)
        ApplianceAdvicePriority.MEDIUM -> stringResource(R.string.appliances_advice_priority_medium)
        ApplianceAdvicePriority.LOW -> stringResource(R.string.appliances_advice_priority_low)
    }
    val isTracking = trackedAction?.status == PlanActionStatus.TRACKING

    RzCard(
        containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        borderColor = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            RzStatusChip(
                text = priorityLabel,
                containerColor = if (advice.priority == ApplianceAdvicePriority.HIGH) ext.successContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (advice.priority == ApplianceAdvicePriority.HIGH) ext.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RzStatusChip(
                text = evidenceLabel,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            advice.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            advice.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        advice.estimatedSavingsKwh?.let { kwh ->
            Text(
                if (advice.estimatedSavingsMxn != null) {
                    stringResource(R.string.appliances_advice_estimated_savings_with_cost, kwh, advice.estimatedSavingsMxn)
                } else {
                    stringResource(R.string.appliances_advice_estimated_savings, kwh)
                },
                style = MaterialTheme.typography.titleSmall,
                color = ext.success,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        advice.sourceUrl?.let { sourceUrl ->
            Text(
                advice.sourceLabel ?: stringResource(R.string.appliances_advice_open_source),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = RzSpacing.sm)
                    .clickable { runCatching { uriHandler.openUri(sourceUrl) } },
            )
        }
        if (trackedAction != null) {
            Text(
                stringResource(R.string.appliances_advice_plan_savings, trackedAction.savingsRangeLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
            if (isTracking) {
                RzSecondaryButton(
                    text = stringResource(R.string.appliances_detail_recommendation_cta_remove),
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                )
            } else {
                RzPrimaryButton(
                    text = stringResource(R.string.appliances_detail_recommendation_cta_add),
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                )
            }
        }
    }
}

private data class ConfidenceStyle(
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val containerColor: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color,
)

@Preview(showBackground = true)
@Composable
private fun ApplianceDetailScreenPreview() {
    ReciboZenTheme {
        ApplianceDetailScreen(
            uiState = ApplianceDetailUiState(
                applianceId = "ps5",
                appliance = null,
                recommendationAction = null,
            ),
            onBack = {},
            onModifyUsage = {},
            onToggleRecommendation = {},
            onHowWeCalculate = {},
            onEditData = {},
        )
    }
}

/**
 * How the reference figure was obtained, and whether anything contradicts it.
 *
 * Reads the resolved evidence rather than the flat fields on [Appliance], so the
 * day a NOM label or a model suggestion becomes a second source for the same
 * appliance, the disagreement surfaces here instead of being silently overwritten.
 */
@Composable
private fun EvidenceProvenance(appliance: Appliance) {
    // Nothing the app stores today carries a retrieval date, so there is nothing
    // to age; a clock gets threaded in when the NOM label capture starts
    // recording when the user read it.
    val resolution = remember(appliance) {
        EvidenceResolver.resolve(
            appliance.declaredEvidence(),
            EvidenceMetric.ANNUAL_KWH,
            asOfEpochDay = null,
        )
    }
    val winner: DeviceEvidence = when (resolution) {
        is EvidenceResolution.Settled -> resolution.winner
        is EvidenceResolution.Conflicted -> resolution.winner
        EvidenceResolution.None -> return
    }
    Text(
        stringResource(
            when (winner.methodology) {
                EvidenceMethodology.METERED -> R.string.appliances_evidence_method_metered
                EvidenceMethodology.STANDARD_TEST -> R.string.appliances_evidence_method_standard_test
                EvidenceMethodology.NAMEPLATE -> R.string.appliances_evidence_method_nameplate
                EvidenceMethodology.MODELED -> R.string.appliances_evidence_method_modeled
                EvidenceMethodology.DECLARED -> R.string.appliances_evidence_method_declared
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = RzSpacing.xs),
    )
    if (winner.source == EvidenceSource.AI_SUGGESTION) {
        Text(
            stringResource(R.string.appliances_evidence_ai_unconfirmed),
            style = MaterialTheme.typography.bodySmall,
            color = RzTheme.extendedColors.warningText,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
    }
    if (resolution is EvidenceResolution.Conflicted) {
        // The user's own schedule disagreeing with the model's published figure is
        // a specific, actionable situation, so it gets its own wording instead of
        // the generic "two sources disagree".
        val configured = resolution.dissenting
            .firstOrNull { it.source == EvidenceSource.USER_CONFIGURED }
        Text(
            if (configured != null) {
                stringResource(
                    R.string.appliances_evidence_config_mismatch,
                    configured.value.roundToInt(),
                    winner.value.roundToInt(),
                )
            } else {
                stringResource(
                    R.string.appliances_evidence_conflict,
                    (resolution.disagreementFraction * 100).roundToInt(),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = RzTheme.extendedColors.warningText,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
    }
}
