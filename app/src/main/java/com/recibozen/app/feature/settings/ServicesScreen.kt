package com.recibozen.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.calculation.TariffCatalog
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFilterChip
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzStatusChip
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.model.ElectricServiceIdentity
import com.recibozen.app.core.model.ElectricServiceValidator
import com.recibozen.app.core.model.EntitlementPolicy
import com.recibozen.app.core.model.EntitlementTier
import com.recibozen.app.core.model.ServiceCreationResult
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ServicesUiState(
    val services: List<ElectricServiceIdentity> = emptyList(),
    val activeServiceId: String = "",
    val tier: EntitlementTier = EntitlementTier.FREE,
    val serviceLimit: Int = 1,
)

class ServicesViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ServicesUiState> = combine(repository.session, repository.electricServices) { session, services ->
        ServicesUiState(
            services = services,
            activeServiceId = session.activeServiceId,
            tier = session.entitlementTier,
            serviceLimit = EntitlementPolicy.activeServiceLimit(session.entitlementTier),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServicesUiState())

    fun select(id: String) = repository.selectElectricService(id)

    fun add(alias: String, serviceNumber: String, rmu: String, meterNumber: String, tariff: String): ServiceCreationResult =
        repository.addElectricService(alias, serviceNumber, rmu, meterNumber, tariff)
}

@Composable
fun ServicesRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ServicesViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ServicesScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelect = viewModel::select,
        onAdd = viewModel::add,
    )
}

@Composable
fun ServicesScreen(
    uiState: ServicesUiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: (String, String, String, String, String) -> ServiceCreationResult,
) {
    var showAdd by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    Scaffold(topBar = { RzFlowTopBar(title = stringResource(R.string.services_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(
                stringResource(R.string.services_usage, uiState.services.count { it.isActive }, uiState.serviceLimit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.services.isEmpty()) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.services_empty_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.services_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
            }
            uiState.services.forEach { service ->
                RzCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(service.id) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(service.alias, style = MaterialTheme.typography.titleLarge)
                            Text(
                                serviceSubtitle(service),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (service.id == uiState.activeServiceId) {
                            RzStatusChip(text = stringResource(R.string.services_active))
                        }
                    }
                }
            }
            RzPrimaryButton(
                text = stringResource(R.string.services_add),
                onClick = {
                    if (uiState.services.count { it.isActive } >= uiState.serviceLimit) showPaywall = true else showAdd = true
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.services_meter_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    if (showAdd) {
        AddServiceDialog(
            onDismiss = { showAdd = false },
            onConfirm = { alias, number, rmu, meter, tariff ->
                val result = onAdd(alias, number, rmu, meter, tariff)
                showAdd = !result.accepted
                showPaywall = result.requiredTier != null
            },
        )
    }
    if (showPaywall) {
        AlertDialog(
            onDismissRequest = { showPaywall = false },
            title = { Text(stringResource(R.string.services_limit_title)) },
            text = { Text(stringResource(R.string.services_limit_body)) },
            confirmButton = {
                TextButton(onClick = { showPaywall = false }) { Text(stringResource(R.string.services_limit_ok)) }
            },
        )
    }
}

@Composable
private fun AddServiceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String) -> Unit,
) {
    var alias by remember { mutableStateOf("") }
    var serviceNumber by remember { mutableStateOf("") }
    var rmu by remember { mutableStateOf("") }
    var meter by remember { mutableStateOf("") }
    var tariff by remember { mutableStateOf("") }
    val numberValid = ElectricServiceValidator.isValidServiceNumber(serviceNumber)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.services_add_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                OutlinedTextField(alias, { alias = it.take(40) }, label = { Text(stringResource(R.string.profile_service_alias)) })
                OutlinedTextField(
                    serviceNumber,
                    { serviceNumber = ElectricServiceValidator.normalizeServiceNumber(it) },
                    label = { Text(stringResource(R.string.profile_service_number)) },
                    isError = !numberValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(rmu, { rmu = ElectricServiceValidator.normalizeRmu(it) }, label = { Text(stringResource(R.string.profile_service_rmu)) })
                OutlinedTextField(meter, { meter = ElectricServiceValidator.normalizeMeterNumber(it) }, label = { Text(stringResource(R.string.profile_service_meter_number)) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs), verticalArrangement = Arrangement.spacedBy(RzSpacing.xs)) {
                    TariffCatalog.supported.forEach { definition ->
                        RzFilterChip(definition.code, definition.code == tariff, onClick = { tariff = definition.code })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = alias.isNotBlank() && numberValid && tariff.isNotBlank(),
                onClick = { onConfirm(alias, serviceNumber, rmu, meter, tariff) },
            ) { Text(stringResource(R.string.services_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun serviceSubtitle(service: ElectricServiceIdentity): String {
    val masked = service.serviceNumber.takeIf { it.length == 12 }?.let { "•••• ${it.takeLast(4)}" }
    return listOfNotNull("Tarifa ${service.tariffCode}", masked, service.meterNumber.takeIf(String::isNotBlank)?.let { "Medidor $it" })
        .joinToString(" · ")
}
