package com.recibozen.app.feature.appliances

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.recibozen.app.R
import com.recibozen.app.core.components.RzBarSegment
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.components.RzStackedBarRow
import com.recibozen.app.core.components.RzStatusChip
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceDataSource
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.EffortLevel
import com.recibozen.app.core.model.PlanAction
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ApplianceDetailUiState(
    val applianceId: String,
    val appliance: Appliance?,
    val recommendationAction: PlanAction?,
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
            val actionId = recommendationActionId(applianceId)
            ApplianceDetailUiState(
                applianceId = applianceId,
                appliance = appliance,
                recommendationAction = session.planActions.find { it.id == actionId },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ApplianceDetailUiState(
                applianceId = applianceId,
                appliance = repository.appliance(applianceId),
                recommendationAction = repository.session.value.planActions
                    .find { it.id == recommendationActionId(applianceId) },
            ),
        )

    fun onToggleRecommendation() {
        recommendationActionId(applianceId)?.let { repository.togglePlanAction(it) }
    }

    fun onRemove() = repository.removeAppliance(applianceId)

    private fun recommendationActionId(id: String): String? = when (id) {
        "minisplit" -> DemoReciboZenData.MINISPLIT_ACTION_ID
        "ps5" -> DemoReciboZenData.PS5_ACTION_ID
        else -> null
    }
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

            if (uiState.recommendationAction != null) {
                RecommendationCard(
                    applianceId = uiState.applianceId,
                    action = uiState.recommendationAction,
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
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
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
private fun RecommendationCard(applianceId: String, action: PlanAction, onToggle: () -> Unit) {
    val titleRes = if (applianceId == "ps5") {
        R.string.appliances_detail_recommendation_ps5_title
    } else {
        R.string.appliances_detail_recommendation_minisplit_title
    }
    val descriptionRes = if (applianceId == "ps5") {
        R.string.appliances_detail_recommendation_ps5_description
    } else {
        R.string.appliances_detail_recommendation_minisplit_description
    }
    val effortRes = when (action.effort) {
        EffortLevel.LOW -> R.string.appliances_detail_recommendation_effort_low
        EffortLevel.MEDIUM -> R.string.appliances_detail_recommendation_effort_medium
        EffortLevel.HIGH -> R.string.appliances_detail_recommendation_effort_high
    }
    val ext = RzTheme.extendedColors
    val (confidenceRes, confidenceColor) = when (action.confidence) {
        ConfidenceLevel.HIGH -> R.string.appliances_chip_confidence_high to ext.success
        ConfidenceLevel.MEDIUM -> R.string.appliances_chip_confidence_medium to ext.warningText
        ConfidenceLevel.LOW -> R.string.appliances_chip_confidence_low to ext.warningText
    }
    val isTracking = action.status == PlanActionStatus.TRACKING

    RzCard(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.appliances_detail_recommendation_badge),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(descriptionRes, action.savingsRangeLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        Row(
            modifier = Modifier.padding(top = RzSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            RzStatusChip(
                text = stringResource(effortRes),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RzStatusChip(
                text = stringResource(confidenceRes),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = confidenceColor,
            )
        }
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
                appliance = DemoReciboZenData.appliances.find { it.id == "ps5" },
                recommendationAction = DemoReciboZenData.planActions.find { it.id == DemoReciboZenData.PS5_ACTION_ID },
            ),
            onBack = {},
            onModifyUsage = {},
            onToggleRecommendation = {},
            onHowWeCalculate = {},
            onEditData = {},
        )
    }
}
