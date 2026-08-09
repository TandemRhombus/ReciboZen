package com.recibozen.app.feature.history

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.importer.BillImportResult
import com.recibozen.app.core.importer.BillParseResult
import com.recibozen.app.core.importer.CfeBillTextParser
import com.recibozen.app.core.importer.CfePdfDocumentExtractor
import com.recibozen.app.core.importer.ParsedCfeBill
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BillImportUiState {
    data object Idle : BillImportUiState
    data object Processing : BillImportUiState
    data class Review(val bill: ParsedCfeBill, val usedOcr: Boolean) : BillImportUiState
    data class Error(val message: String) : BillImportUiState
    data class Complete(val result: BillImportResult) : BillImportUiState
}

class BillImportViewModel(context: Context, private val repository: ReciboZenRepository) : ViewModel() {
    private val extractor = CfePdfDocumentExtractor(context.applicationContext)
    private val parser = CfeBillTextParser()
    private val mutableUiState = MutableStateFlow<BillImportUiState>(BillImportUiState.Idle)
    val uiState: StateFlow<BillImportUiState> = mutableUiState.asStateFlow()
    private var sourceHash: String? = null

    fun process(uri: Uri) {
        mutableUiState.value = BillImportUiState.Processing
        viewModelScope.launch {
            extractor.extract(uri).fold(
                onSuccess = { document ->
                    sourceHash = document.sha256
                    when (val parsed = parser.parse(document.text)) {
                        is BillParseResult.Success -> mutableUiState.value = BillImportUiState.Review(parsed.bill, document.usedOcr)
                        is BillParseResult.Failure -> {
                            if (!document.usedOcr) retryWithOcr(uri, parsed.reasons)
                            else mutableUiState.value = BillImportUiState.Error(parsed.reasons.joinToString("\n"))
                        }
                    }
                },
                onFailure = { error -> mutableUiState.value = BillImportUiState.Error(error.message ?: "No se pudo leer el PDF.") },
            )
        }
    }

    private suspend fun retryWithOcr(uri: Uri, nativeReasons: List<String>) {
        extractor.extract(uri, forceOcr = true).fold(
            onSuccess = { document ->
                sourceHash = document.sha256
                mutableUiState.value = when (val parsed = parser.parse(document.text)) {
                    is BillParseResult.Success -> BillImportUiState.Review(parsed.bill, usedOcr = true)
                    is BillParseResult.Failure -> BillImportUiState.Error(parsed.reasons.joinToString("\n"))
                }
            },
            onFailure = { error ->
                mutableUiState.value = BillImportUiState.Error(
                    (nativeReasons + (error.message ?: "Falló el reconocimiento visual.")).joinToString("\n"),
                )
            },
        )
    }

    fun confirm() {
        val bill = (mutableUiState.value as? BillImportUiState.Review)?.bill ?: return
        val hash = sourceHash ?: return
        mutableUiState.value = BillImportUiState.Processing
        viewModelScope.launch {
            mutableUiState.value = BillImportUiState.Complete(repository.importCfeBill(bill, hash))
        }
    }

    fun reset() {
        sourceHash = null
        mutableUiState.value = BillImportUiState.Idle
    }
}

@Composable
fun BillImportRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val context = LocalContext.current
    val viewModel = rzViewModel { BillImportViewModel(context, repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::process)
    }
    BillImportScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onChoosePdf = { launcher.launch(arrayOf("application/pdf")) },
        onConfirm = viewModel::confirm,
        onRetry = viewModel::reset,
        onDone = { navController.popBackStack() },
    )
}

@Composable
fun BillImportScreen(
    uiState: BillImportUiState,
    onBack: () -> Unit,
    onChoosePdf: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(topBar = { RzFlowTopBar(title = stringResource(R.string.bill_import_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            when (uiState) {
                BillImportUiState.Idle -> {
                    Text(stringResource(R.string.bill_import_intro), style = MaterialTheme.typography.bodyLarge)
                    RzPrimaryButton(stringResource(R.string.bill_import_choose), onChoosePdf, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.bill_import_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                BillImportUiState.Processing -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = RzSpacing.xxl),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.bill_import_processing), modifier = Modifier.padding(start = RzSpacing.md))
                    }
                }
                is BillImportUiState.Review -> ReviewBillCard(uiState.bill, uiState.usedOcr, onConfirm, onRetry)
                is BillImportUiState.Error -> {
                    RzCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bill_import_error), style = MaterialTheme.typography.titleLarge)
                        Text(uiState.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = RzSpacing.sm))
                    }
                    RzPrimaryButton(stringResource(R.string.bill_import_retry), onRetry, Modifier.fillMaxWidth())
                }
                is BillImportUiState.Complete -> {
                    val result = uiState.result
                    RzCard(Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                result.accepted -> stringResource(R.string.bill_import_success)
                                result.duplicate -> stringResource(R.string.bill_import_duplicate)
                                result.requiredTier != null -> stringResource(R.string.bill_import_plan_limit)
                                else -> stringResource(R.string.bill_import_error)
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        result.message?.let { Text(it, modifier = Modifier.padding(top = RzSpacing.sm)) }
                    }
                    if (result.accepted || result.duplicate) {
                        RzPrimaryButton(stringResource(R.string.bill_import_done), onDone, Modifier.fillMaxWidth())
                    } else {
                        RzPrimaryButton(stringResource(R.string.bill_import_retry), onRetry, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewBillCard(bill: ParsedCfeBill, usedOcr: Boolean, onConfirm: () -> Unit, onRetry: () -> Unit) {
    val money = NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es", "MX"))
    Text(stringResource(R.string.bill_import_review_title), style = MaterialTheme.typography.headlineSmall)
    RzCard(Modifier.fillMaxWidth()) {
        ReviewRow(stringResource(R.string.bill_import_service), "•••• ${bill.serviceNumber.takeLast(4)}")
        ReviewRow(stringResource(R.string.bill_import_tariff), bill.tariffCode)
        ReviewRow(stringResource(R.string.bill_import_meter), bill.meterNumber)
        ReviewRow(stringResource(R.string.bill_import_period), "${date.format(bill.periodStart)} – ${date.format(bill.periodEnd)}")
        ReviewRow(stringResource(R.string.bill_import_consumption), "${bill.consumptionKwh} kWh")
        ReviewRow(stringResource(R.string.bill_import_total), money.format(bill.totalToPayMxn))
        ReviewRow(stringResource(R.string.bill_import_history_count), bill.history.size.toString())
    }
    if (usedOcr) Text(stringResource(R.string.bill_import_ocr_note), style = MaterialTheme.typography.bodySmall)
    bill.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val hasCriticalWarning = bill.warnings.any { it.contains("lecturas y el multiplicador", ignoreCase = true) }
    RzPrimaryButton(
        text = stringResource(R.string.bill_import_confirm),
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth(),
        enabled = !hasCriticalWarning,
    )
    RzSecondaryButton(stringResource(R.string.bill_import_other), onRetry, Modifier.fillMaxWidth())
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
