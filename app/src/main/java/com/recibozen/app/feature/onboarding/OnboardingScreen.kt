package com.recibozen.app.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzDashedCard
import com.recibozen.app.core.components.RzInlineBanner
import com.recibozen.app.core.components.RzBannerTone
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.DemoReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Immutable UI state for [OnboardingScreen]. `step` mirrors
 * [com.recibozen.app.demo.repository.ReciboZenSessionState.onboardingStep] (0..3); everything else
 * is fixture copy from [DemoReciboZenData] plus the projection range computed by the repository so
 * step 3 always matches the same math the rest of the app uses.
 */
data class OnboardingUiState(
    val step: Int = 0,
    val householdName: String = DemoReciboZenData.HOUSEHOLD_NAME,
    val location: String = DemoReciboZenData.LOCATION,
    val billingCycle: String = DemoReciboZenData.BILLING_CYCLE,
    val goalMxn: Int = DemoReciboZenData.GOAL_MXN,
    val projectedMxn: Int = DemoReciboZenData.BASE_PROJECTED_MXN,
    val projectedRangeLowMxn: Int = DemoReciboZenData.BASE_PROJECTED_MXN - 36,
    val projectedRangeHighMxn: Int = DemoReciboZenData.BASE_PROJECTED_MXN + 44,
)

class OnboardingViewModel(private val repository: ReciboZenRepository) : ViewModel() {

    val uiState: StateFlow<OnboardingUiState> = repository.session
        .map { session ->
            val projection = repository.billProjection(session)
            OnboardingUiState(
                step = session.onboardingStep,
                householdName = DemoReciboZenData.HOUSEHOLD_NAME,
                location = DemoReciboZenData.LOCATION,
                billingCycle = DemoReciboZenData.BILLING_CYCLE,
                goalMxn = DemoReciboZenData.GOAL_MXN,
                projectedMxn = projection.projectedMxn,
                projectedRangeLowMxn = projection.rangeLowMxn,
                projectedRangeHighMxn = projection.rangeHighMxn,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OnboardingUiState())

    fun advanceStep() {
        val current = repository.session.value.onboardingStep
        repository.setOnboardingStep(current + 1)
    }

    fun previousStep() {
        val current = repository.session.value.onboardingStep
        repository.setOnboardingStep(current - 1)
    }
}

/**
 * Start destination of the app: no back arrow, no bottom nav. Four internal steps driven by
 * [ReciboZenSessionState.onboardingStep] rather than four separate nav routes.
 */
@Composable
fun OnboardingRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { OnboardingViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = uiState.step > 0) { viewModel.previousStep() }

    fun goHome() {
        navController.navigate(Routes.Home) {
            popUpTo(Routes.Onboarding) { inclusive = true }
        }
    }

    OnboardingScreen(
        uiState = uiState,
        onSkip = { goHome() },
        onPrimaryClick = {
            if (uiState.step >= 3) goHome() else viewModel.advanceStep()
        },
    )
}

@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onSkip: () -> Unit,
    onPrimaryClick: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_label, uiState.step + 1),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OnboardingProgressBar(step = uiState.step)

            when (uiState.step) {
                0 -> OnboardingStep0()
                1 -> OnboardingStep1(uiState = uiState, onNext = onPrimaryClick)
                2 -> OnboardingStep2()
                else -> OnboardingStep3(uiState = uiState)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                RzSecondaryButton(
                    text = stringResource(R.string.onboarding_action_skip),
                    onClick = onSkip,
                    modifier = Modifier.weight(1f).height(52.dp),
                )
                RzPrimaryButton(
                    text = stringResource(onboardingPrimaryLabelRes(uiState.step)),
                    onClick = onPrimaryClick,
                    modifier = Modifier.weight(1.6f).height(52.dp),
                )
            }
        }
    }
}

private fun onboardingPrimaryLabelRes(step: Int): Int = when (step) {
    0 -> R.string.onboarding_primary_step0
    1 -> R.string.onboarding_primary_step1
    2 -> R.string.onboarding_primary_step2
    else -> R.string.onboarding_primary_step3
}

/** 4-segment step indicator, fills left-to-right as the user advances. */
@Composable
private fun OnboardingProgressBar(step: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
    ) {
        repeat(4) { index ->
            val color = if (step >= index) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(color),
            )
        }
    }
}

@Composable
private fun OnboardingStep0() {
    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
        RzCard(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RzExtraShapes.cardMedium)
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    RzIcons.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = stringResource(R.string.onboarding_step0_headline),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
            Text(
                text = stringResource(R.string.onboarding_step0_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        RzCard(modifier = Modifier.fillMaxWidth(), shape = RzExtraShapes.cardMedium) {
            Text(
                text = stringResource(R.string.onboarding_step0_bullets),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnboardingStep1(uiState: OnboardingUiState, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
        Text(
            text = stringResource(R.string.onboarding_step1_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.onboarding_step1_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RzCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RzExtraShapes.cardMedium,
            contentPadding = PaddingValues(horizontal = RzSpacing.xl),
        ) {
            OnboardingSummaryRow(
                label = stringResource(R.string.onboarding_row_household_name),
                value = uiState.householdName,
                showDivider = true,
            )
            OnboardingSummaryRow(
                label = stringResource(R.string.onboarding_row_location),
                value = uiState.location,
                showDivider = true,
            )
            OnboardingSummaryRow(
                label = stringResource(R.string.onboarding_row_billing_cycle),
                value = uiState.billingCycle,
                showDivider = true,
            )
            OnboardingSummaryRow(
                label = stringResource(R.string.onboarding_row_goal),
                value = stringResource(R.string.onboarding_goal_value, uiState.goalMxn),
                valueColor = MaterialTheme.colorScheme.primary,
                showDivider = false,
            )
        }
        RzCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RzExtraShapes.cardMedium,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ) {
            Text(
                text = stringResource(R.string.onboarding_receipt_card_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onboarding_receipt_card_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                RzPrimaryButton(
                    text = stringResource(R.string.onboarding_action_take_photo),
                    onClick = onNext,
                    modifier = Modifier.weight(1f).height(48.dp),
                )
                RzSecondaryButton(
                    text = stringResource(R.string.onboarding_action_type_manually),
                    onClick = onNext,
                    modifier = Modifier.weight(1f).height(48.dp),
                )
            }
        }
    }
}

@Composable
private fun OnboardingSummaryRow(
    label: String,
    value: String,
    showDivider: Boolean,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RzSpacing.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(text = value, style = MaterialTheme.typography.titleSmall, color = valueColor)
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private data class MeterOption(val labelRes: Int, val icon: ImageVector)

private val meterOptions = listOf(
    MeterOption(R.string.onboarding_meter_digital, RzIcons.Keyboard),
    MeterOption(R.string.onboarding_meter_5_dials, RzIcons.Schedule),
    MeterOption(R.string.onboarding_meter_4_dials, RzIcons.Schedule),
    MeterOption(R.string.onboarding_meter_unsure, RzIcons.Help),
)

@Composable
private fun OnboardingStep2() {
    // Selection here is local UI state only per the handoff spec — it does not persist to the
    // repository. Pre-selected index 1 matches DemoReciboZenData.METER_TYPE_SELECTED ("De
    // manecillas, 5 carátulas").
    var selectedIndex by rememberSaveable { mutableIntStateOf(1) }
    val selectedDesc = stringResource(R.string.onboarding_meter_selected_desc)

    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
        Text(
            text = stringResource(R.string.onboarding_step2_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.onboarding_step2_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            meterOptions.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                RzCard(
                    shape = RzExtraShapes.control,
                    borderColor = borderColor,
                    borderWidth = if (selected) 2.dp else 1.dp,
                    contentPadding = PaddingValues(horizontal = RzSpacing.xl, vertical = RzSpacing.md),
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = { selectedIndex = index },
                            role = Role.RadioButton,
                        ),
                ) {
                    Row(
                        modifier = Modifier.height(RzSpacing.minTouchTarget - RzSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RzSpacing.md),
                    ) {
                        Icon(
                            option.icon,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                RzIcons.CheckCircle,
                                contentDescription = selectedDesc,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        RzDashedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.onboarding_meter_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnboardingStep3(uiState: OnboardingUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
        RzCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.onboarding_step3_eyebrow),
                style = MaterialTheme.typography.labelMedium,
                color = RzTheme.extendedColors.success,
            )
            Text(
                text = stringResource(R.string.onboarding_step3_price, uiState.projectedMxn),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
            Text(
                text = stringResource(
                    R.string.onboarding_step3_range,
                    uiState.projectedRangeLowMxn,
                    uiState.projectedRangeHighMxn,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            RzInlineBanner(
                text = stringResource(R.string.onboarding_step3_confidence_warning),
                tone = RzBannerTone.WARNING,
                icon = RzIcons.GppMaybe,
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
            )
            Text(
                text = stringResource(R.string.onboarding_step3_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
        }
        RzDashedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.onboarding_step3_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, name = "Onboarding · Step 0")
@Composable
private fun OnboardingStep0Preview() {
    ReciboZenTheme {
        OnboardingScreen(uiState = OnboardingUiState(step = 0), onSkip = {}, onPrimaryClick = {})
    }
}

@Preview(showBackground = true, name = "Onboarding · Step 1")
@Composable
private fun OnboardingStep1Preview() {
    ReciboZenTheme {
        OnboardingScreen(uiState = OnboardingUiState(step = 1), onSkip = {}, onPrimaryClick = {})
    }
}

@Preview(showBackground = true, name = "Onboarding · Step 2")
@Composable
private fun OnboardingStep2Preview() {
    ReciboZenTheme {
        OnboardingScreen(uiState = OnboardingUiState(step = 2), onSkip = {}, onPrimaryClick = {})
    }
}

@Preview(showBackground = true, name = "Onboarding · Step 3")
@Composable
private fun OnboardingStep3Preview() {
    ReciboZenTheme {
        OnboardingScreen(uiState = OnboardingUiState(step = 3), onSkip = {}, onPrimaryClick = {})
    }
}

@Preview(showBackground = true, name = "Onboarding · Route (fake repo)")
@Composable
private fun OnboardingRoutePreview() {
    ReciboZenTheme {
        OnboardingRoute(navController = NavHostController(androidx.compose.ui.platform.LocalContext.current), repository = DemoReciboZenRepository())
    }
}
