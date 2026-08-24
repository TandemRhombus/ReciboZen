package app.recibozen.android.feature.onboarding

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.calculation.MeterProfile
import app.recibozen.android.core.designsystem.rzPrefersStackedLayout
import app.recibozen.android.core.calculation.TariffCatalog
import app.recibozen.android.core.components.RzBannerTone
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzDashedCard
import app.recibozen.android.core.components.RzFilterChip
import app.recibozen.android.core.components.RzInlineBanner
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.designsystem.RzExtraShapes
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.core.location.CityLocator
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val step: Int = 0,
    val userName: String = "",
    val householdName: String = "",
    val location: String = "",
    val goalMxn: Int = 0,
    val meterType: String = "",
    /** Digits the household declared, or null while nothing establishes them. */
    val meterDigitCount: Int? = null,
    /** What the chosen meter type states on its own; null means the question is still open. */
    val meterInferredDigitCount: Int? = null,
    val tariff: String = "",
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
            meterDigitCount = session.meterDigitCount,
            meterInferredDigitCount = MeterProfile.inferredDigitCount(session.meterType),
            tariff = session.tariff,
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
    fun setMeterDigitCount(digitCount: Int?) = repository.setMeterDigitCount(digitCount)
    fun updateProfile(userName: String, householdName: String, location: String, goalMxn: Int) =
        repository.updateProfile(userName, householdName, location, goalMxn)
    fun updateGoal(goalMxn: Int) {
        val state = repository.session.value
        repository.updateProfile(state.userName, state.householdName, state.location, goalMxn)
    }
    fun updateTariff(tariff: String) = repository.setTariff(tariff)
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
        onMeterDigitCountChanged = viewModel::setMeterDigitCount,
        onProfileChanged = viewModel::updateProfile,
        onGoalChanged = viewModel::updateGoal,
        onTariffChanged = viewModel::updateTariff,
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
    onMeterDigitCountChanged: (Int?) -> Unit = {},
    onProfileChanged: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    onGoalChanged: (Int) -> Unit = {},
    onTariffChanged: (String) -> Unit = {},
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
                    2 -> MeterStep(uiState, onMeterTypeSelected, onMeterDigitCountChanged)
                    else -> ResultStep(uiState, onGoalChanged, onTariffChanged)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Neither button carries a fixed height any more -- that would
            // override the floor each one sets for itself -- and past a large
            // font size they stop sharing a row at all. Side by side at 2x,
            // "Configurar después" broke mid-word into "Configur / ar / después"
            // inside its half, and the two buttons ended up visibly different
            // heights next to each other.
            val backLabel = stringResource(
                if (uiState.step > 0) R.string.onboarding_action_back
                else R.string.onboarding_action_configure_later,
            )
            val primaryEnabled = when (uiState.step) {
                0 -> profileComplete
                2 -> uiState.meterType.isNotBlank()
                3 -> uiState.goalMxn > 0 && TariffCatalog.find(uiState.tariff) != null
                else -> true
            }
            val back: @Composable (Modifier) -> Unit = { buttonModifier ->
                RzSecondaryButton(
                    text = backLabel,
                    onClick = if (uiState.step > 0) onPrevious else onSkip,
                    modifier = buttonModifier,
                )
            }
            val forward: @Composable (Modifier) -> Unit = { buttonModifier ->
                RzPrimaryButton(
                    text = stringResource(primaryLabel(uiState.step, uiState.hasImportedBill)),
                    onClick = onPrimaryClick,
                    enabled = primaryEnabled,
                    modifier = buttonModifier,
                )
            }
            val footerModifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .imePadding()
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.md)
            if (rzPrefersStackedLayout()) {
                // Primary on top when stacked: it is the action the step expects.
                Column(
                    modifier = footerModifier,
                    verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                ) {
                    forward(Modifier.fillMaxWidth())
                    back(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = footerModifier,
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                ) {
                    back(Modifier.weight(1f))
                    forward(Modifier.weight(1.6f))
                }
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
        CityAutofillAction(
            onCityDetected = { detected ->
                location = detected
                publish()
            },
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
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
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
private fun MeterStep(
    uiState: OnboardingUiState,
    onMeterTypeSelected: (String) -> Unit,
    onMeterDigitCountChanged: (Int?) -> Unit,
) {
    val selectedMeterType = uiState.meterType
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
        // Only asked when the chosen type leaves it open. "4 carátulas" already
        // states four, and asking again would invite a contradiction between two
        // answers the household gave one screen apart.
        if (selectedMeterType.isNotBlank() && uiState.meterInferredDigitCount == null) {
            var digits by rememberSaveable(selectedMeterType) {
                mutableStateOf(uiState.meterDigitCount?.toString().orEmpty())
            }
            val digitsValue = digits.toIntOrNull()?.takeIf { it in MeterProfile.DECLARABLE_DIGITS }
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.onboarding_meter_digits_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.onboarding_meter_digits_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                OutlinedTextField(
                    value = digits,
                    onValueChange = {
                        digits = it.filter(Char::isDigit).take(1)
                        onMeterDigitCountChanged(digits.toIntOrNull()?.takeIf { d -> d in MeterProfile.DECLARABLE_DIGITS })
                    },
                    label = { Text(stringResource(R.string.onboarding_meter_digits_label)) },
                    isError = digits.isNotBlank() && digitsValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                )
                digitsValue?.let { count ->
                    Text(
                        stringResource(R.string.onboarding_meter_digits_capacity, "9".repeat(count), "0".repeat(count)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                }
            }
        }
        RzDashedCard(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_meter_footnote), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * "Usar mi ubicación" for the city field. Location is optional and one-shot:
 * it only prefills a field the user can overwrite, so a denial or a device
 * without a recent fix just leaves the form as it was.
 */
@Composable
private fun CityAutofillAction(onCityDetected: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locator = remember(context) { CityLocator(context) }
    var status by remember { mutableStateOf<CityAutofillStatus>(CityAutofillStatus.Idle) }

    fun detect() {
        status = CityAutofillStatus.Detecting
        scope.launch {
            val city = locator.detectCity()
            status = if (city != null) {
                onCityDetected(city)
                CityAutofillStatus.Idle
            } else {
                CityAutofillStatus.Unavailable
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) detect() else status = CityAutofillStatus.Denied
    }

    Column {
        RzSecondaryButton(
            text = stringResource(
                if (status == CityAutofillStatus.Detecting) R.string.onboarding_city_detecting
                else R.string.onboarding_city_use_location,
            ),
            onClick = {
                if (locator.hasPermission()) detect()
                else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
            enabled = status != CityAutofillStatus.Detecting,
            modifier = Modifier.fillMaxWidth(),
        )
        val message = when (status) {
            CityAutofillStatus.Denied -> R.string.onboarding_city_permission_denied
            CityAutofillStatus.Unavailable -> R.string.onboarding_city_unavailable
            else -> null
        }
        message?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
        }
    }
}

private enum class CityAutofillStatus { Idle, Detecting, Denied, Unavailable }

@Composable
private fun ResultStep(
    uiState: OnboardingUiState,
    onGoalChanged: (Int) -> Unit,
    onTariffChanged: (String) -> Unit,
) {
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
        RzCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (uiState.hasImportedBill) R.string.onboarding_goal_after_bill_title
                    else R.string.onboarding_goal_no_bill_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (uiState.hasImportedBill) {
                    stringResource(R.string.onboarding_goal_after_bill_body, uiState.latestBillAmountMxn)
                } else {
                    stringResource(R.string.onboarding_goal_no_bill_body)
                },
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
        RzCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.onboarding_tariff_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.onboarding_tariff_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                modifier = Modifier.padding(top = RzSpacing.md),
            ) {
                TariffCatalog.supported.forEach { definition ->
                    RzFilterChip(
                        text = definition.code,
                        selected = definition.code == uiState.tariff,
                        onClick = { onTariffChanged(definition.code) },
                    )
                }
            }
            Text(
                stringResource(R.string.onboarding_goal_tariff_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
    }
}
