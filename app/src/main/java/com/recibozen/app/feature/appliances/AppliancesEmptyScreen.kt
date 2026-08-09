package com.recibozen.app.feature.appliances

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.calculation.ApplianceCalculator
import com.recibozen.app.core.calculation.ApplianceCatalog
import com.recibozen.app.core.calculation.ApplianceCatalogItem
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzDashedCard
import com.recibozen.app.core.components.RzFilterChip
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceCategory
import com.recibozen.app.core.model.ApplianceDataSource
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.OpportunityLevel
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AppliancesEmptyUiState(
    val catalog: List<ApplianceCatalogItem> = ApplianceCatalog.items,
    val billingDays: Int = 61,
    val effectiveRateMxnPerKwh: Double = 2.3,
)

class AppliancesEmptyViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<AppliancesEmptyUiState> = repository.session
        .map { state ->
            val energy = repository.energySummary(state)
            AppliancesEmptyUiState(
                billingDays = if (state.billingCycle.contains("bi", ignoreCase = true)) 61 else 30,
                effectiveRateMxnPerKwh = if (energy.projectedKwh > 0) {
                    energy.projectedCostMxn.toDouble() / energy.projectedKwh
                } else 2.3,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppliancesEmptyUiState())

    fun addCatalogItem(item: ApplianceCatalogItem, room: String, hoursPerWeek: Int?) {
        repository.addAppliance(
            ApplianceCatalog.toAppliance(
                item = item,
                instanceId = "catalog_${item.id}_${System.currentTimeMillis()}",
                room = room,
                hoursPerWeek = hoursPerWeek,
                billingDays = uiState.value.billingDays,
                effectiveRateMxnPerKwh = uiState.value.effectiveRateMxnPerKwh,
            ),
        )
    }

    fun addCustom(name: String, room: String, watts: Int, hoursPerDay: Double, daysPerWeek: Int) {
        val estimate = ApplianceCalculator.fromPower(
            watts = watts,
            hoursPerDay = hoursPerDay,
            daysPerWeek = daysPerWeek,
            billingDays = uiState.value.billingDays,
            effectiveRateMxnPerKwh = uiState.value.effectiveRateMxnPerKwh,
        )
        repository.addAppliance(
            Appliance(
                id = "custom_${System.currentTimeMillis()}",
                name = name.trim(),
                room = room.trim().ifEmpty { "Hogar" },
                costMxn = estimate.costMxn,
                kwh = estimate.kwh,
                sharePercent = 0,
                hoursPerWeek = (hoursPerDay * daysPerWeek).roundToInt(),
                confidence = ConfidenceLevel.LOW,
                opportunity = OpportunityLevel.MEDIUM,
                calculationNote = estimate.calculation,
                category = ApplianceCategory.OTHER,
                powerWatts = watts,
                dataSource = ApplianceDataSource.USER_ESTIMATE,
            ),
        )
    }
}

@Composable
fun AppliancesEmptyRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { AppliancesEmptyViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppliancesEmptyScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onAddCatalogItem = { item, room, hours ->
            viewModel.addCatalogItem(item, room, hours)
            navController.popBackStack()
        },
        onAddCustom = { name, room, watts, hoursPerDay, daysPerWeek ->
            viewModel.addCustom(name, room, watts, hoursPerDay, daysPerWeek)
            navController.popBackStack()
        },
    )
}

@Composable
fun AppliancesEmptyScreen(
    uiState: AppliancesEmptyUiState,
    onBack: () -> Unit,
    onAddCatalogItem: (ApplianceCatalogItem, String, Int?) -> Unit,
    onAddCustom: (String, String, Int, Double, Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ApplianceCategory?>(null) }
    var selectedItem by remember { mutableStateOf<ApplianceCatalogItem?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    val filtered = uiState.catalog.filter { item ->
        (selectedCategory == null || item.category == selectedCategory) &&
            (query.isBlank() || item.name.contains(query, ignoreCase = true))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        RzFlowTopBar(title = stringResource(R.string.appliances_catalog_top_bar_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(
                stringResource(R.string.appliances_catalog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.appliances_catalog_description, uiState.catalog.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(50) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.appliances_catalog_search)) },
                singleLine = true,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
            ) {
                RzFilterChip(
                    text = stringResource(R.string.appliances_catalog_all),
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                )
                ApplianceCategory.entries.filterNot { it == ApplianceCategory.OTHER }.forEach { category ->
                    RzFilterChip(
                        text = categoryLabel(category),
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
            }
            filtered.forEach { item ->
                RzCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedItem = item },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                catalogUsageLabel(item),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${item.typicalWatts} W", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (filtered.isEmpty()) {
                RzDashedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appliances_catalog_no_results))
                }
            }
            RzDashedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCustom = true },
            ) {
                Text(stringResource(R.string.appliances_custom_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.appliances_custom_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    selectedItem?.let { item ->
        CatalogItemDialog(
            item = item,
            billingDays = uiState.billingDays,
            effectiveRateMxnPerKwh = uiState.effectiveRateMxnPerKwh,
            onDismiss = { selectedItem = null },
            onConfirm = { room, hours -> onAddCatalogItem(item, room, hours) },
        )
    }
    if (showCustom) {
        CustomApplianceDialog(
            billingDays = uiState.billingDays,
            effectiveRateMxnPerKwh = uiState.effectiveRateMxnPerKwh,
            onDismiss = { showCustom = false },
            onConfirm = onAddCustom,
        )
    }
}

@Composable
private fun CatalogItemDialog(
    item: ApplianceCatalogItem,
    billingDays: Int,
    effectiveRateMxnPerKwh: Double,
    onDismiss: () -> Unit,
    onConfirm: (String, Int?) -> Unit,
) {
    var room by remember { mutableStateOf("Hogar") }
    var hours by remember(item.id) { mutableStateOf(item.defaultHoursPerWeek?.toString().orEmpty()) }
    val parsedHours = hours.toIntOrNull()?.coerceIn(0, 168)
    val estimate = ApplianceCalculator.fromCatalog(item, parsedHours, billingDays, effectiveRateMxnPerKwh)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
                OutlinedTextField(room, { room = it.take(40) }, label = { Text(stringResource(R.string.appliances_catalog_room)) })
                if (item.defaultHoursPerWeek != null) {
                    OutlinedTextField(
                        hours,
                        { hours = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.appliances_catalog_hours)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                } else {
                    Text(stringResource(R.string.appliances_catalog_cycle_based), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.appliances_catalog_estimate, estimate.kwh, estimate.costMxn))
                Text(estimate.calculation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = room.isNotBlank() && (item.defaultHoursPerWeek == null || parsedHours != null),
                onClick = { onConfirm(room, if (item.defaultHoursPerWeek == null) null else parsedHours) },
            ) { Text(stringResource(R.string.appliances_catalog_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CustomApplianceDialog(
    billingDays: Int,
    effectiveRateMxnPerKwh: Double,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, Double, Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("Hogar") }
    var watts by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("7") }
    val wattsValue = watts.toIntOrNull()
    val hoursValue = hours.toDoubleOrNull()
    val daysValue = days.toIntOrNull()
    val valid = name.isNotBlank() && room.isNotBlank() && wattsValue != null && wattsValue in 1..100_000 &&
        hoursValue != null && hoursValue in 0.0..24.0 && daysValue != null && daysValue in 1..7
    val estimate = if (valid) {
        ApplianceCalculator.fromPower(wattsValue!!, hoursValue!!, daysValue!!, billingDays, effectiveRateMxnPerKwh)
    } else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appliances_custom_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                Text(stringResource(R.string.appliances_custom_dialog_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(50) }, label = { Text(stringResource(R.string.appliances_custom_name)) })
                OutlinedTextField(room, { room = it.take(40) }, label = { Text(stringResource(R.string.appliances_catalog_room)) })
                OutlinedTextField(
                    watts,
                    { watts = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.appliances_custom_watts)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    hours,
                    { hours = it.filter { char -> char.isDigit() || char == '.' }.take(4) },
                    label = { Text(stringResource(R.string.appliances_custom_hours_day)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    days,
                    { days = it.filter(Char::isDigit).take(1) },
                    label = { Text(stringResource(R.string.appliances_custom_days_week)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                estimate?.let {
                    Text(stringResource(R.string.appliances_catalog_estimate, it.kwh, it.costMxn))
                    Text(it.calculation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(name, room, wattsValue!!, hoursValue!!, daysValue!!) },
            ) { Text(stringResource(R.string.appliances_catalog_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun categoryLabel(category: ApplianceCategory): String = when (category) {
    ApplianceCategory.CLIMATE -> stringResource(R.string.appliances_category_climate)
    ApplianceCategory.KITCHEN -> stringResource(R.string.appliances_category_kitchen)
    ApplianceCategory.LAUNDRY -> stringResource(R.string.appliances_category_laundry)
    ApplianceCategory.ENTERTAINMENT -> stringResource(R.string.appliances_category_entertainment)
    ApplianceCategory.COMPUTING -> stringResource(R.string.appliances_category_computing)
    ApplianceCategory.LIGHTING -> stringResource(R.string.appliances_category_lighting)
    ApplianceCategory.WATER -> stringResource(R.string.appliances_category_water)
    ApplianceCategory.PERSONAL_CARE -> stringResource(R.string.appliances_category_personal_care)
    ApplianceCategory.OTHER -> stringResource(R.string.appliances_category_other)
}

@Composable
private fun catalogUsageLabel(item: ApplianceCatalogItem): String = item.typicalKwhPer30Days?.let {
    stringResource(R.string.appliances_catalog_monthly_reference, it)
} ?: stringResource(R.string.appliances_catalog_weekly_reference, item.defaultHoursPerWeek ?: 0)

@Preview(showBackground = true)
@Composable
private fun AppliancesEmptyScreenPreview() {
    ReciboZenTheme {
        AppliancesEmptyScreen(
            uiState = AppliancesEmptyUiState(),
            onBack = {},
            onAddCatalogItem = { _, _, _ -> },
            onAddCustom = { _, _, _, _, _ -> },
        )
    }
}
