package app.recibozen.android.feature.appliances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFilterChip
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzDropdownField
import app.recibozen.android.core.components.RzDropdownOption
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.model.Appliance
import androidx.compose.ui.platform.LocalContext
import app.recibozen.android.core.ai.AiClient
import app.recibozen.android.core.security.PremiumAccessStore
import app.recibozen.android.navigation.Routes
import app.recibozen.android.core.model.ApplianceCategory
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.HouseSpace
import app.recibozen.android.core.model.HouseSpaceDefaults
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.rzViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HourOption(val labelRes: Int, val representativeHours: Int)

val ApplianceHourOptions = listOf(
    HourOption(R.string.appliances_usage_option_lt5, 3),
    HourOption(R.string.appliances_usage_option_5_10, 8),
    HourOption(R.string.appliances_usage_option_10_20, 17),
    HourOption(R.string.appliances_usage_option_gt20, 25),
)

data class ApplianceUsageUiState(
    val applianceId: String,
    val applianceName: String?,
    val hoursPerWeek: Int,
    val costMxn: Int,
    val kwh: Int,
    val isSupported: Boolean,
    val powerWatts: Int? = null,
    val standbyWatts: Int? = null,
    val standbyEnabled: Boolean = false,
    val billingDays: Int = 30,
    val homeApplianceKwh: Int = 0,
    val meterProjectedKwh: Int = 0,
    val hasCurrentMeterProjection: Boolean = false,
    val quantity: Int = 1,
    val room: String = "Toda la casa",
    val spaceId: String? = null,
    val spaces: List<HouseSpace> = HouseSpaceDefaults.items,
    val brand: String? = null,
    val model: String? = null,
    val usageModel: ApplianceUsageModel = ApplianceUsageModel.POWER_SCHEDULE,
    val unitKwh: Double = 0.0,
    val dataSource: ApplianceDataSource = ApplianceDataSource.USER_ESTIMATE,
    /** Needed to ask the AI about this appliance; the model answers per category. */
    val category: ApplianceCategory = ApplianceCategory.OTHER,
)

data class ApplianceUsageDraft(
    val quantity: Int,
    val space: HouseSpace,
    val brand: String?,
    val model: String?,
    val usageModel: ApplianceUsageModel,
    val hoursPerWeek: Int?,
    val powerWatts: Int?,
    val unitKwh: Double?,
    val dataSource: ApplianceDataSource,
    val standbyEnabled: Boolean,
    val standbyWatts: Int?,
)

class ApplianceUsageViewModel(
    private val repository: ReciboZenRepository,
    private val applianceId: String,
) : ViewModel() {
    val uiState: StateFlow<ApplianceUsageUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        repository.tariffCalibration,
    ) { session, _, _ ->
        toUiState(repository.appliance(applianceId, session), session)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = toUiState(repository.appliance(applianceId), repository.session.value),
    )

    private fun toUiState(
        appliance: Appliance?,
        session: app.recibozen.android.demo.repository.ReciboZenSessionState,
    ): ApplianceUsageUiState {
        val energy = repository.energySummary(session)
        val inferredUsageModel = appliance?.usageModel ?: if (appliance?.hoursPerWeek == null) {
            ApplianceUsageModel.PERIOD_KWH
        } else ApplianceUsageModel.POWER_SCHEDULE
        return ApplianceUsageUiState(
            applianceId = applianceId,
            applianceName = appliance?.name,
            hoursPerWeek = appliance?.hoursPerWeek ?: 0,
            costMxn = appliance?.costMxn ?: 0,
            kwh = appliance?.kwh ?: 0,
            isSupported = appliance != null,
            powerWatts = appliance?.powerWatts,
            standbyWatts = appliance?.standbyWatts,
            standbyEnabled = appliance?.standbyEnabled == true,
            billingDays = energy.billingDays.takeIf { it > 0 } ?: 30,
            homeApplianceKwh = energy.applianceProjectedKwh,
            meterProjectedKwh = energy.meterProjectedKwh,
            hasCurrentMeterProjection = energy.hasCurrentMeterProjection,
            quantity = appliance?.quantity ?: 1,
            category = appliance?.category ?: ApplianceCategory.OTHER,
            room = appliance?.room ?: "Toda la casa",
            spaceId = appliance?.spaceId,
            spaces = session.houseSpaces,
            brand = appliance?.brand,
            model = appliance?.model,
            usageModel = inferredUsageModel,
            unitKwh = appliance?.unitKwh
                ?: appliance?.let { it.kwh.toDouble() / it.quantity.coerceAtLeast(1) }
                ?: 0.0,
            dataSource = appliance?.dataSource ?: ApplianceDataSource.USER_ESTIMATE,
        )
    }

    fun save(draft: ApplianceUsageDraft) {
        repository.updateAppliance(
            applianceId = applianceId,
            quantity = draft.quantity,
            spaceId = draft.space.id,
            room = draft.space.name,
            brand = draft.brand,
            model = draft.model,
            usageModel = draft.usageModel,
            hoursPerWeek = draft.hoursPerWeek,
            powerWatts = draft.powerWatts,
            unitKwh = draft.unitKwh,
            dataSource = draft.dataSource,
            standbyEnabled = draft.standbyEnabled,
            standbyWatts = draft.standbyWatts,
        )
    }
}

@Composable
fun ApplianceUsageRoute(
    navController: NavHostController,
    repository: ReciboZenRepository,
    applianceId: String,
) {
    val viewModel = rzViewModel { ApplianceUsageViewModel(repository, applianceId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The lease is read at call time, not cached: it expires, and a stale one
    // would send the household to a 403 instead of the paywall.
    val context = LocalContext.current
    val aiViewModel = rzViewModel {
        val accessStore = PremiumAccessStore(context)
        ApplianceAiSuggestionViewModel(AiClient(signedLease = { accessStore.signedLease() }))
    }
    val aiState by aiViewModel.state.collectAsStateWithLifecycle()

    ApplianceUsageScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSave = { draft ->
            viewModel.save(draft)
            navController.popBackStack()
        },
        aiState = aiState,
        onAskAi = { brand, model ->
            aiViewModel.request(brand, model, uiState.category, uiState.billingDays)
        },
        onDismissAi = aiViewModel::dismiss,
        onGoPremium = {
            aiViewModel.dismiss()
            navController.navigate(Routes.Premium)
        },
    )
}

@Composable
fun ApplianceUsageScreen(
    uiState: ApplianceUsageUiState,
    onBack: () -> Unit,
    onSave: (ApplianceUsageDraft) -> Unit,
    aiState: AiSuggestionUiState = AiSuggestionUiState.Idle,
    onAskAi: (brand: String, model: String) -> Unit = { _, _ -> },
    onDismissAi: () -> Unit = {},
    onGoPremium: () -> Unit = {},
) {
    val safeSpaces = uiState.spaces.ifEmpty { HouseSpaceDefaults.items }
    var selectedSpaceId by remember(uiState.applianceId, uiState.spaceId, uiState.room) {
        mutableStateOf(
            uiState.spaceId
                ?: safeSpaces.firstOrNull { it.name.equals(uiState.room, ignoreCase = true) }?.id
                ?: safeSpaces.first().id,
        )
    }
    var quantity by remember(uiState.applianceId, uiState.quantity) { mutableStateOf(uiState.quantity.toString()) }
    var brand by remember(uiState.applianceId, uiState.brand) { mutableStateOf(uiState.brand.orEmpty()) }
    var model by remember(uiState.applianceId, uiState.model) { mutableStateOf(uiState.model.orEmpty()) }
    var draftMode by remember(uiState.applianceId, uiState.usageModel) { mutableStateOf(uiState.usageModel) }
    var draftHours by remember(uiState.applianceId, uiState.hoursPerWeek) { mutableIntStateOf(uiState.hoursPerWeek) }
    var activeWatts by remember(uiState.applianceId, uiState.powerWatts) {
        mutableStateOf(uiState.powerWatts?.toString().orEmpty())
    }
    var standbyEnabled by remember(uiState.applianceId, uiState.standbyEnabled) {
        mutableStateOf(uiState.standbyEnabled)
    }
    var standbyWatts by remember(uiState.applianceId, uiState.standbyWatts) {
        mutableStateOf(uiState.standbyWatts?.toString().orEmpty())
    }
    var unitKwh by remember(uiState.applianceId, uiState.unitKwh) {
        mutableStateOf("%.1f".format(java.util.Locale.US, uiState.unitKwh))
    }
    var dataSource by remember(uiState.applianceId, uiState.dataSource) { mutableStateOf(uiState.dataSource) }

    val selectedSpace = safeSpaces.firstOrNull { it.id == selectedSpaceId } ?: safeSpaces.first()
    val parsedQuantity = quantity.toIntOrNull()?.takeIf { it in 1..999 }
    val parsedUnitKwh = unitKwh.toDoubleOrNull()?.takeIf { it in 0.0..100_000.0 }
    val parsedActiveWatts = activeWatts.toIntOrNull()?.takeIf { it in 1..100_000 }
    val parsedStandbyWatts = standbyWatts.toIntOrNull()?.takeIf { it in 1..10_000 }
    val canUseSchedule = uiState.powerWatts?.let { it > 0 } == true
    val supportsStandby = uiState.standbyWatts != null || uiState.standbyEnabled
    val effectiveMode = if (!canUseSchedule && draftMode == ApplianceUsageModel.POWER_SCHEDULE) {
        ApplianceUsageModel.PERIOD_KWH
    } else draftMode
    val valid = uiState.isSupported && parsedQuantity != null &&
        (if (effectiveMode == ApplianceUsageModel.POWER_SCHEDULE) {
            parsedActiveWatts != null && (!supportsStandby || !standbyEnabled || parsedStandbyWatts != null)
        } else parsedUnitKwh != null)
    val draftUnitKwh = if (effectiveMode == ApplianceUsageModel.POWER_SCHEDULE && canUseSchedule) {
        val active = (parsedActiveWatts ?: uiState.powerWatts!!) / 1_000.0 * draftHours * uiState.billingDays / 7.0
        val standby = parsedStandbyWatts?.takeIf { supportsStandby && standbyEnabled }?.let {
            it / 1_000.0 * (168 - draftHours).coerceAtLeast(0) * uiState.billingDays / 7.0
        } ?: 0.0
        active + standby
    } else parsedUnitKwh ?: 0.0
    val draftTotalKwh = (draftUnitKwh * (parsedQuantity ?: 1)).roundToInt()
    val draftHomeKwh = (uiState.homeApplianceKwh - uiState.kwh + draftTotalKwh).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        RzFlowTopBar(title = stringResource(R.string.appliances_usage_top_bar_title), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            if (!uiState.isSupported) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appliances_usage_not_found))
                }
            } else {
                Text(uiState.applianceName.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appliances_usage_identity_title), style = MaterialTheme.typography.titleMedium)
                    RzDropdownField(
                        label = stringResource(R.string.appliances_space_select),
                        selectedLabel = selectedSpace.name,
                        options = safeSpaces.map { RzDropdownOption(it, it.name) },
                        onSelected = { selectedSpaceId = it.id },
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                        label = { Text(stringResource(R.string.appliances_quantity)) },
                        supportingText = { Text(stringResource(R.string.appliances_quantity_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = brand,
                        onValueChange = { brand = it.take(40) },
                        modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                        label = { Text(stringResource(R.string.appliances_brand)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.take(60) },
                        modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                        label = { Text(stringResource(R.string.appliances_model)) },
                        singleLine = true,
                    )
                    // Offered to everyone, on purpose: a household on the free
                    // tier gets the paywall rather than a hidden feature. The
                    // label says Premium before they spend a tap finding out.
                    TextButton(
                        onClick = { onAskAi(brand, model) },
                        enabled = brand.isNotBlank() && model.isNotBlank() &&
                            aiState !is AiSuggestionUiState.Loading,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    ) {
                        Text(stringResource(R.string.appliances_ai_button))
                    }
                    Text(
                        stringResource(
                            if (brand.isBlank() || model.isBlank()) R.string.appliances_ai_needs_brand
                            else R.string.appliances_ai_button_premium,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appliances_consumption_method), style = MaterialTheme.typography.titleMedium)
                    RzDropdownField(
                        label = stringResource(R.string.appliances_consumption_method),
                        selectedLabel = stringResource(
                            if (effectiveMode == ApplianceUsageModel.PERIOD_KWH) {
                                R.string.appliances_consumption_by_kwh
                            } else R.string.appliances_consumption_by_average_power,
                        ),
                        options = buildList {
                            if (canUseSchedule) {
                                add(
                                    RzDropdownOption(
                                        ApplianceUsageModel.POWER_SCHEDULE,
                                        stringResource(R.string.appliances_consumption_by_average_power),
                                    ),
                                )
                            }
                            add(
                                RzDropdownOption(
                                    ApplianceUsageModel.PERIOD_KWH,
                                    stringResource(R.string.appliances_consumption_by_kwh),
                                ),
                            )
                        },
                        onSelected = { draftMode = it },
                        supportingText = stringResource(R.string.appliances_consumption_variable_hint),
                        modifier = Modifier.padding(top = RzSpacing.md),
                    )

                    if (effectiveMode == ApplianceUsageModel.POWER_SCHEDULE) {
                        OutlinedTextField(
                            value = activeWatts,
                            onValueChange = { activeWatts = it.filter(Char::isDigit).take(6) },
                            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                            label = { Text(stringResource(R.string.appliances_average_active_watts)) },
                            supportingText = { Text(stringResource(R.string.appliances_average_active_watts_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        Text(
                            stringResource(R.string.appliances_usage_hours_value, draftHours),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = RzSpacing.lg),
                        )
                        Slider(
                            value = draftHours.toFloat(),
                            onValueChange = { draftHours = it.toInt().coerceIn(0, 168) },
                            valueRange = 0f..168f,
                            steps = 167,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.appliances_usage_zero_hours), style = MaterialTheme.typography.labelSmall)
                            Text(stringResource(R.string.appliances_usage_full_week), style = MaterialTheme.typography.labelSmall)
                        }
                        FlowRow(
                            modifier = Modifier.padding(top = RzSpacing.md),
                            horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                        ) {
                            ApplianceHourOptions.forEach { option ->
                                RzFilterChip(
                                    text = stringResource(option.labelRes),
                                    selected = draftHours == option.representativeHours,
                                    onClick = { draftHours = option.representativeHours },
                                )
                            }
                        }
                        if (supportsStandby) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.appliances_standby_question), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.appliances_standby_hint), style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = standbyEnabled, onCheckedChange = { standbyEnabled = it })
                            }
                            if (standbyEnabled) {
                                OutlinedTextField(
                                    value = standbyWatts,
                                    onValueChange = { standbyWatts = it.filter(Char::isDigit).take(5) },
                                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                                    label = { Text(stringResource(R.string.appliances_standby_watts)) },
                                    supportingText = { Text(stringResource(R.string.appliances_standby_watts_hint)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = unitKwh,
                            onValueChange = { unitKwh = it.filter { char -> char.isDigit() || char == '.' }.take(8) },
                            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                            label = { Text(stringResource(R.string.appliances_period_kwh_per_unit, uiState.billingDays)) },
                            supportingText = { Text(stringResource(R.string.appliances_usage_kwh_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        val sourceOptions = listOf(
                            RzDropdownOption(
                                ApplianceDataSource.USER_MEASURED,
                                stringResource(R.string.appliances_source_measured),
                            ),
                            RzDropdownOption(
                                ApplianceDataSource.MANUFACTURER_LABEL,
                                stringResource(R.string.appliances_source_label),
                            ),
                            RzDropdownOption(
                                ApplianceDataSource.USER_ESTIMATE,
                                stringResource(R.string.appliances_source_estimated),
                            ),
                        )
                        val selectedSourceLabel = when (dataSource) {
                            ApplianceDataSource.USER_MEASURED -> stringResource(R.string.appliances_source_measured)
                            ApplianceDataSource.MANUFACTURER_LABEL -> stringResource(R.string.appliances_source_label)
                            // Named explicitly: the else below would have shown
                            // "Estimado" for a figure the household never estimated.
                            ApplianceDataSource.AI_SUGGESTION -> stringResource(R.string.appliances_source_ai)
                            else -> stringResource(R.string.appliances_source_estimated)
                        }
                        RzDropdownField(
                            label = stringResource(R.string.appliances_usage_source_title),
                            selectedLabel = selectedSourceLabel,
                            options = sourceOptions,
                            onSelected = { dataSource = it },
                            modifier = Modifier.padding(top = RzSpacing.xs),
                        )
                    }

                    Text(
                        stringResource(R.string.appliances_usage_draft_total, draftTotalKwh, uiState.billingDays),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = RzSpacing.lg),
                    )
                    Text(
                        stringResource(R.string.appliances_usage_per_unit_note, draftUnitKwh),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appliances_usage_detail_title), style = MaterialTheme.typography.titleMedium)
                    DetailRow(
                        stringResource(R.string.appliances_usage_detail_power_label),
                        parsedActiveWatts?.let { stringResource(R.string.appliances_usage_detail_power_value, it) }
                            ?: stringResource(R.string.appliances_usage_detail_unknown),
                    )
                    DetailRow(
                        stringResource(R.string.appliances_usage_detail_period_label),
                        stringResource(R.string.appliances_usage_detail_period_value, uiState.billingDays),
                    )
                    Text(
                        if (uiState.hasCurrentMeterProjection) {
                            stringResource(
                                R.string.appliances_usage_forecast_comparison,
                                draftHomeKwh,
                                uiState.meterProjectedKwh,
                            )
                        } else stringResource(R.string.appliances_usage_forecast_inventory, draftHomeKwh),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.md),
                    )
                }

                RzPrimaryButton(
                    text = stringResource(R.string.appliances_usage_save),
                    enabled = valid,
                    onClick = {
                        onSave(
                            ApplianceUsageDraft(
                                quantity = parsedQuantity ?: 1,
                                space = selectedSpace,
                                brand = brand,
                                model = model,
                                usageModel = effectiveMode,
                                hoursPerWeek = draftHours.takeIf { effectiveMode == ApplianceUsageModel.POWER_SCHEDULE },
                                powerWatts = parsedActiveWatts,
                                unitKwh = if (effectiveMode == ApplianceUsageModel.PERIOD_KWH) parsedUnitKwh else draftUnitKwh,
                                dataSource = when {
                                    // An AI suggestion keeps its provenance in
                                    // either model. Overwriting it with
                                    // USER_ESTIMATE would tell the household
                                    // they typed a number a model produced.
                                    dataSource == ApplianceDataSource.AI_SUGGESTION -> ApplianceDataSource.AI_SUGGESTION
                                    effectiveMode == ApplianceUsageModel.POWER_SCHEDULE -> ApplianceDataSource.USER_ESTIMATE
                                    else -> dataSource
                                },
                                standbyEnabled = supportsStandby && standbyEnabled &&
                                    effectiveMode == ApplianceUsageModel.POWER_SCHEDULE,
                                standbyWatts = parsedStandbyWatts.takeIf {
                                    standbyEnabled && effectiveMode == ApplianceUsageModel.POWER_SCHEDULE
                                },
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.appliances_usage_save_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    // Applying writes into the very fields the household edits, so anything the
    // model got wrong can still be corrected before saving.
    ApplianceAiSuggestionDialog(
        state = aiState,
        onApply = { fill ->
            fill.powerWatts?.let { activeWatts = it.toString() }
            fill.unitKwh?.let { unitKwh = "%.1f".format(java.util.Locale.US, it) }
            fill.standbyWatts?.let {
                standbyWatts = it.toString()
                standbyEnabled = true
            }
            fill.usageModel?.let { draftMode = it }
            dataSource = ApplianceDataSource.AI_SUGGESTION
            onDismissAi()
        },
        onDismiss = onDismissAi,
        onGoPremium = onGoPremium,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Preview(showBackground = true)
@Composable
private fun ApplianceUsageScreenPreview() {
    ReciboZenTheme {
        ApplianceUsageScreen(
            uiState = ApplianceUsageUiState(
                applianceId = "focos",
                applianceName = "Foco LED 9 W",
                hoursPerWeek = 28,
                costMxn = 25,
                kwh = 15,
                isSupported = true,
                powerWatts = 9,
                quantity = 20,
            ),
            onBack = {},
            onSave = {},
        )
    }
}
