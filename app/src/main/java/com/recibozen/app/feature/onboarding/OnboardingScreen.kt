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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzBannerTone
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzDashedCard
import com.recibozen.app.core.components.RzInlineBanner
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class OnboardingUiState(
    val step: Int = 0,
    val userName: String = "",
    val householdName: String = "",
    val location: String = "",
    val goalMxn: Int = 0,
    val meterType: String = "",
    val hasImportedBill: Boolean = false,
    val latestBillAmountMxn: Int = 0,
    val latestBillConsumptionKwh: Int = 0,
    val latestBillPeriod: String = "",
    val importedPeriods: Int = 0,
)

class OnboardingViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<OnboardingUiState> = combine(
        repository.session,
        repository.importedBillHistory,
    ) { session, importedBills ->
        val latestBill = importedBills.lastOrNull()
        OnboardingUiState(
            step = session.onboardingStep,
            userName = session.userName,
            householdName = session.householdName,
            location = session.location,
            goalMxn = session.goalMxn,
            meterType = session.meterType,
            hasImportedBill = latestBill != null,
            latestBillAmountMxn = latestBill?.amountMxn ?: 0,
            latestBillConsumptionKwh = latestBill?.consumptionKwh ?: 0,
            latestBillPeriod = latestBill?.monthLabel.orEmpty(),
            importedPeriods = importedBills.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    fun advanceStep() = repository.setOnboardingStep(repository.session.value.onboardingStep + 1)
    fun previousStep() = repository.setOnboardingStep(repository.session.value.onboardingStep - 1)
    fun complete() = repository.completeOnboarding()
    fun setMeterType(meterType: String) = repository.setMeterType(meterType)
    fun updateProfile(userName: String, householdName: String, location: String, goalMxn: Int) =
        repository.updateProfile(userName, householdName, location, goalMxn)
    fun updateGoal(goalMxn: Int) {
        val state = repository.session.value
        repository.updateProfile(state.userName, state.householdName, state.location, goalMxn)
    }
}

@Composable
fun OnboardingRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { OnboardingViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = uiState.step > 0) { viewModel.previousStep() }

    fun goHome() {
        viewModel.complete()
        navController.navigate(Routes.Home) {
            popUpTo(Routes.Onboarding) { inclusive = true }
        }
    }

    OnboardingScreen(
        uiState = uiState,
        onSkip = ::goHome,
        onPrevious = viewModel::previousStep,
        onImportBill = { navController.navigate(Routes.BillImport) },
        onPrimaryClick = { if (uiState.step >= 3) goHome() else viewModel.advanceStep() },
        onMeterTypeSelected = viewModel::setMeterType,
        onProfileChanged = viewModel::updateProfile,
        onGoalChanged = viewModel::updateGoal,
    )
}

@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onSkip: () -> Unit,
    onPrevious: () -> Unit,
    onImportBill: () -> Unit,
    onPrimaryClick: () -> Unit,
    onMeterTypeSelected: (String) -> Unit = {},
    onProfileChanged: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    onGoalChanged: (Int) -> Unit = {},
) {
    val profileComplete = uiState.userName.isNotBlank() &&
        uiState.householdName.isNotBlank() && uiState.location.isNotBlank()
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = RzSpacing.screenHorizontal,
                        end = RzSpacing.screenHorizontal,
                        top = RzSpacing.xxl,
                        bottom = RzSpacing.lg,
                    ),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_step_label, uiState.step + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OnboardingProgressBar(uiState.step)
                when (uiState.step) {
                    0 -> ProfileStep(uiState, onProfileChanged)
                    1 -> ReceiptStep(uiState, onImportBill)
                    2 -> MeterStep(uiState.meterType, onMeterTypeSelected)
                    else -> ResultStep(uiState, onGoalChanged)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .imePadding()
                    .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                RzSecondaryButton(
                    text = stringResource(
                        if (uiState.step > 0) R.string.onboarding_action_back
                        else R.string.onboarding_action_configure_later,
                    ),
                    onClick = if (uiState.step > 0) onPrevious else onSkip,
                    modifier = Modifier.weight(1f).height(52.dp),
                )
                RzPrimaryButton(
                    text = stringResource(primaryLabel(uiState.step, uiState.hasImportedBill)),
                    onClick = onPrimaryClick,
                    enabled = when (uiState.step) {
                        0 -> profileComplete
                        2 -> uiState.meterType.isNotBlank()
                        else -> true
                    },
                    modifier = Modifier.weight(1.6f).height(52.dp),
                )
            }
        }
    }
}

private fun primaryLabel(step: Int, hasBill: Boolean): Int = when (step) {
    0 -> R.string.onboarding_primary_step0
    1 -> if (hasBill) R.string.onboarding_primary_step1 else R.string.onboarding_continue_without_bill
    2 -> R.string.onboarding_primary_step2
    else -> R.string.onboarding_primary_step3
}

@Composable
private fun OnboardingProgressBar(step: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (step >= index) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@Composable
private fun ProfileStep(
    uiState: OnboardingUiState,
    onChanged: (String, String, String, Int) -> Unit,
) {
    var userName by rememberSaveable(uiState.userName) { mutableStateOf(uiState.userName) }
    var householdName by rememberSaveable(uiState.householdName) { mutableStateOf(uiState.householdName) }
    var location by rememberSaveable(uiState.location) { mutableStateOf(uiState.location) }
    fun publish() = onChanged(userName, householdName, location, uiState.goalMxn)

    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
        RzCard(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(60.dp).clip(RzExtraShapes.cardMedium)
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(RzIcons.ReceiptLong, null, tint = MaterialTheme.colorScheme.onTertiary)
            }
            Text(
                stringResource(R.string.onboarding_profile_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
            Text(
                stringResource(R.string.onboarding_profile_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        OutlinedTextField(
            userName,
            { userName = it.take(60); publish() },
            label = { Text(stringResource(R.string.profile_edit_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            householdName,
            { householdName = it.take(60); publish() },
            label = { Text(stringResource(R.string.profile_edit_household)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            location,
            { location = it.take(80); publish() },
            label = { Text(stringResource(R.string.profile_edit_location)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.onboarding_profile_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReceiptStep(uiState: OnboardingUiState, onImportBill: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
        Text(stringResource(R.string.onboarding_receipt_step_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.onboarding_receipt_step_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RzCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RzExtraShapes.cardMedium,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        ) {
            Text(
                stringResource(
                    if (uiState.hasImportedBill) R.string.onboarding_receipt_loaded_title
                    else R.string.onboarding_receipt_card_title,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (uiState.hasImportedBill) {
                    stringResource(R.string.onboarding_receipt_loaded_body, uiState.importedPeriods)
                } else stringResource(R.string.onboarding_receipt_card_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            if (uiState.hasImportedBill) {
                SummaryRow(stringResource(R.string.bill_import_period), uiState.latestBillPeriod, true)
                SummaryRow(stringResource(R.string.bill_import_consumption), "${uiState.latestBillConsumptionKwh} kWh", true)
                SummaryRow(stringResource(R.string.bill_import_total), "$${uiState.latestBillAmountMxn}", false)
            }
            RzPrimaryButton(
                text = stringResource(
                    if (uiState.hasImportedBill) R.string.onboarding_action_change_pdf
                    else R.string.onboarding_action_upload_pdf,
                ),
                onClick = onImportBill,
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md).height(48.dp),
            )
        }
        RzDashedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.onboarding_receipt_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, divider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(RzSpacing.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
private fun MeterStep(selectedMeterType: String, onMeterTypeSelected: (String) -> Unit) {
    val labels = meterOptions.map { stringResource(it.labelRes) }
    var selectedIndex by rememberSaveable(selectedMeterType) {
        mutableIntStateOf(labels.indexOfFirst { it == selectedMeterType })
    }
    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
        Text(stringResource(R.string.onboarding_step2_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.onboarding_step2_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
            meterOptions.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                RzCard(
                    shape = RzExtraShapes.control,
                    borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    borderWidth = if (selected) 2.dp else 1.dp,
                    contentPadding = PaddingValues(horizontal = RzSpacing.xl, vertical = RzSpacing.md),
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = selected,
                        onClick = { selectedIndex = index; onMeterTypeSelected(labels[index]) },
                        role = Role.RadioButton,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                        Icon(option.icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Text(stringResource(option.labelRes), modifier = Modifier.weight(1f))
                        if (selected) Icon(RzIcons.CheckCircle, stringResource(R.string.onboarding_meter_selected_desc), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        RzDashedCard(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_meter_footnote), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResultStep(uiState: OnboardingUiState, onGoalChanged: (Int) -> Unit) {
    var goal by rememberSaveable(uiState.goalMxn) {
        mutableStateOf(uiState.goalMxn.takeIf { it > 0 }?.toString().orEmpty())
    }
    Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
        RzCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (uiState.hasImportedBill) R.string.onboarding_step3_bill_eyebrow
                    else R.string.onboarding_step3_empty_eyebrow,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = RzTheme.extendedColors.success,
            )
            if (uiState.hasImportedBill) {
                Text(
                    "$${uiState.latestBillAmountMxn}",
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
                Text(
                    stringResource(
                        R.string.onboarding_step3_bill_detail,
                        uiState.latestBillPeriod,
                        uiState.latestBillConsumptionKwh,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RzInlineBanner(
                text = stringResource(
                    if (uiState.hasImportedBill) R.string.onboarding_step3_bill_explanation
                    else R.string.onboarding_step3_empty_explanation,
                ),
                tone = if (uiState.hasImportedBill) RzBannerTone.SUCCESS else RzBannerTone.WARNING,
                icon = if (uiState.hasImportedBill) RzIcons.CheckCircle else RzIcons.GppMaybe,
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
            )
            Text(
                stringResource(R.string.onboarding_step3_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.md),
            )
        }
        if (uiState.hasImportedBill) {
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.onboarding_goal_after_bill_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.onboarding_goal_after_bill_body, uiState.latestBillAmountMxn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                OutlinedTextField(
                    value = goal,
                    onValueChange = {
                        goal = it.filter(Char::isDigit).take(7)
                        onGoalChanged(goal.toIntOrNull() ?: 0)
                    },
                    label = { Text(stringResource(R.string.onboarding_goal_after_bill_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                )
            }
        }
    }
}
