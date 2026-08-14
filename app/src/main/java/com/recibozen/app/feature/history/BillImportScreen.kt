package com.recibozen.app.feature.history

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.importer.BillImportResult
import com.recibozen.app.core.importer.BillParseResult
import com.recibozen.app.core.importer.CfeBillTextParser
import com.recibozen.app.core.importer.CfePdfDocumentExtractor
import com.recibozen.app.core.importer.CfePhotoDocumentExtractor
import com.recibozen.app.core.importer.ParsedCfeBill
import com.recibozen.app.core.importer.ParsedBillHistoryRow
import com.recibozen.app.core.calculation.TariffCatalog
import com.recibozen.app.core.model.ElectricServiceValidator
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFilterChip
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import java.io.File
import java.time.LocalDate
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
    private val photoExtractor = CfePhotoDocumentExtractor(context.applicationContext)
    private val parser = CfeBillTextParser()
    private val mutableUiState = MutableStateFlow<BillImportUiState>(BillImportUiState.Idle)
    val uiState: StateFlow<BillImportUiState> = mutableUiState.asStateFlow()
    private var sourceHash: String? = null

    /**
     * Photographed paper bill. Same parser and same editable review as the PDF
     * path; only the text source differs. Always flagged as OCR so the review
     * screen tells the user to double-check.
     */
    fun processPhoto(uri: Uri) {
        mutableUiState.value = BillImportUiState.Processing
        viewModelScope.launch {
            photoExtractor.extract(uri).fold(
                onSuccess = { document ->
                    sourceHash = document.sha256
                    mutableUiState.value = when (val parsed = parser.parse(document.text)) {
                        is BillParseResult.Success -> BillImportUiState.Review(parsed.bill, usedOcr = true)
                        is BillParseResult.Failure -> BillImportUiState.Error(parsed.reasons.joinToString("\n"))
                    }
                },
                onFailure = { error ->
                    mutableUiState.value = BillImportUiState.Error(
                        error.message ?: "No se pudo leer la fotografía del recibo.",
                    )
                },
            )
        }
    }

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

    /** [bill] is what the user confirmed on screen, which may differ from what was parsed. */
    fun confirm(bill: ParsedCfeBill) {
        if (mutableUiState.value !is BillImportUiState.Review) return
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

    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        if (captured) pendingPhotoUri?.let(viewModel::processPhoto) else pendingPhotoUri = null
    }

    BillImportScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onChoosePdf = { launcher.launch(arrayOf("application/pdf")) },
        onPhotographBill = {
            val directory = File(context.cacheDir, "bill_photos").apply { mkdirs() }
            val target = File(directory, "bill_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            pendingPhotoUri = uri
            cameraLauncher.launch(uri)
        },
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
    onConfirm: (ParsedCfeBill) -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onPhotographBill: () -> Unit = {},
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
                    RzSecondaryButton(stringResource(R.string.bill_import_photograph), onPhotographBill, Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.bill_import_photograph_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

/**
 * Editable review step. Text recognition — especially from a photo of a paper
 * bill — routinely confuses O/0, 1/7 and S/5, so every value that feeds the
 * estimate has to be correctable here. Confirming an uncorrectable misread
 * would persist a wrong number wearing the face of verified data.
 */
@Composable
private fun ReviewBillCard(
    bill: ParsedCfeBill,
    usedOcr: Boolean,
    onConfirm: (ParsedCfeBill) -> Unit,
    onRetry: () -> Unit,
) {
    var serviceNumber by rememberSaveable(bill) { mutableStateOf(bill.serviceNumber) }
    var rmu by rememberSaveable(bill) { mutableStateOf(bill.rmu.orEmpty()) }
    var meterNumber by rememberSaveable(bill) { mutableStateOf(bill.meterNumber) }
    var tariffCode by rememberSaveable(bill) { mutableStateOf(bill.tariffCode) }
    var multiplier by rememberSaveable(bill) { mutableStateOf(bill.multiplier.toString()) }
    var periodStart by rememberSaveable(bill) { mutableStateOf(bill.periodStart.toString()) }
    var periodEnd by rememberSaveable(bill) { mutableStateOf(bill.periodEnd.toString()) }
    var previousReading by rememberSaveable(bill) { mutableStateOf(bill.previousReadingKwh.toString()) }
    var currentReading by rememberSaveable(bill) { mutableStateOf(bill.currentReadingKwh.toString()) }
    var consumption by rememberSaveable(bill) { mutableStateOf(bill.consumptionKwh.toString()) }
    var total by rememberSaveable(bill) { mutableStateOf(bill.totalToPayMxn.toString()) }
    var charges by remember(bill) { mutableStateOf(bill.charges) }
    var historyDrafts by remember(bill) { mutableStateOf(bill.history.map(HistoryRowDraft::from)) }

    val previousValue = previousReading.toIntOrNull()
    val currentValue = currentReading.toIntOrNull()
    val consumptionValue = consumption.toIntOrNull()
    val totalValue = total.toDoubleOrNull()
    val multiplierValue = multiplier.toIntOrNull()
    val periodStartValue = periodStart.isoDateOrNull()
    val periodEndValue = periodEnd.isoDateOrNull()

    val readingsInconsistent = previousValue != null && currentValue != null &&
        consumptionValue != null &&
        (currentValue - previousValue) * (multiplierValue ?: 0) != consumptionValue
    val tierCharges = charges.filter { it.type.uppercase() in setOf("BASICO", "INTERMEDIO", "EXCEDENTE") }
    val chargesValid = tierCharges.all { (it.quantityKwh ?: 0.0) > 0.0 && (it.unitRateMxn ?: 0.0) > 0.0 }
    val history = historyDrafts.mapNotNull(HistoryRowDraft::parsed)
    val historyValid = history.size == historyDrafts.size &&
        history.all { it.periodEnd > it.periodStart && it.consumptionKwh >= 0 && it.amountMxn >= 0.0 }
    val canConfirm = ElectricServiceValidator.isValidServiceNumber(serviceNumber) && serviceNumber.isNotBlank() &&
        TariffCatalog.find(tariffCode) != null &&
        multiplierValue != null && multiplierValue > 0 &&
        periodStartValue != null && periodEndValue != null && periodEndValue > periodStartValue &&
        previousValue != null && currentValue != null &&
        consumptionValue != null && consumptionValue > 0 &&
        totalValue != null && totalValue > 0.0 && !readingsInconsistent && chargesValid && historyValid

    Text(stringResource(R.string.bill_import_review_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.bill_import_review_editable_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    RzCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bill_import_period), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm), modifier = Modifier.padding(top = RzSpacing.sm)) {
            OutlinedTextField(
                value = periodStart,
                onValueChange = { periodStart = it.take(10) },
                label = { Text(stringResource(R.string.bill_import_period_start)) },
                singleLine = true,
                isError = periodStartValue == null,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = periodEnd,
                onValueChange = { periodEnd = it.take(10) },
                label = { Text(stringResource(R.string.bill_import_period_end)) },
                singleLine = true,
                isError = periodEndValue == null || (periodStartValue != null && periodEndValue <= periodStartValue),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = multiplier,
            onValueChange = { multiplier = it.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.bill_import_multiplier)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = multiplierValue == null || multiplierValue <= 0,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        )
        ReviewRow(stringResource(R.string.bill_import_history_count), history.size.toString())
    }

    RzCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bill_import_tariff), style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
            modifier = Modifier.padding(top = RzSpacing.sm),
        ) {
            TariffCatalog.supported.forEach { definition ->
                RzFilterChip(
                    text = definition.code,
                    selected = definition.code == TariffCatalog.normalize(tariffCode),
                    onClick = { tariffCode = definition.code },
                )
            }
        }

        OutlinedTextField(
            value = serviceNumber,
            onValueChange = { serviceNumber = it.filter(Char::isDigit).take(12) },
            label = { Text(stringResource(R.string.bill_import_service)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = !ElectricServiceValidator.isValidServiceNumber(serviceNumber) || serviceNumber.isBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
        )
        OutlinedTextField(
            value = rmu,
            onValueChange = { rmu = it.uppercase().filter(Char::isLetterOrDigit).take(27) },
            label = { Text(stringResource(R.string.bill_import_rmu)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        )
        OutlinedTextField(
            value = meterNumber,
            onValueChange = { meterNumber = it.take(20) },
            label = { Text(stringResource(R.string.bill_import_meter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            modifier = Modifier.padding(top = RzSpacing.sm),
        ) {
            OutlinedTextField(
                value = previousReading,
                onValueChange = { previousReading = it.filter(Char::isDigit).take(9) },
                label = { Text(stringResource(R.string.bill_import_previous_reading)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = previousValue == null,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = currentReading,
                onValueChange = { currentReading = it.filter(Char::isDigit).take(9) },
                label = { Text(stringResource(R.string.bill_import_current_reading)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = currentValue == null,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = consumption,
            onValueChange = { consumption = it.filter(Char::isDigit).take(7) },
            label = { Text(stringResource(R.string.bill_import_consumption)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = consumptionValue == null || consumptionValue <= 0,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        )
        OutlinedTextField(
            value = total,
            onValueChange = { total = it.filter { char -> char.isDigit() || char == '.' }.take(10) },
            label = { Text(stringResource(R.string.bill_import_total)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = totalValue == null || totalValue <= 0.0,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        )

        if (tierCharges.isNotEmpty()) {
            Text(
                stringResource(R.string.bill_import_charge_breakdown_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
            Text(
                stringResource(R.string.bill_import_charge_breakdown_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            charges.forEachIndexed { index, charge ->
                if (charge.type.uppercase() in setOf("BASICO", "INTERMEDIO", "EXCEDENTE")) {
                    Text(
                        listOf(charge.documentLabel, charge.season.takeUnless { it == "NONE" })
                            .filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = RzSpacing.md),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
                        OutlinedTextField(
                            value = editableNumber(charge.quantityKwh),
                            onValueChange = { raw ->
                                charges = charges.toMutableList().also { list ->
                                    list[index] = charge.copy(quantityKwh = raw.decimalOrNull())
                                }
                            },
                            label = { Text(stringResource(R.string.bill_import_charge_kwh)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = (charge.quantityKwh ?: 0.0) <= 0.0,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = editableNumber(charge.unitRateMxn),
                            onValueChange = { raw ->
                                charges = charges.toMutableList().also { list ->
                                    list[index] = charge.copy(unitRateMxn = raw.decimalOrNull())
                                }
                            },
                            label = { Text(stringResource(R.string.bill_import_charge_rate)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = (charge.unitRateMxn ?: 0.0) <= 0.0,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (historyDrafts.isNotEmpty()) {
        RzCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bill_import_history_edit_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.bill_import_history_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            historyDrafts.forEachIndexed { index, row ->
                Text(
                    stringResource(R.string.bill_import_history_period_number, index + 1),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
                    OutlinedTextField(
                        value = row.periodStart,
                        onValueChange = { raw ->
                            historyDrafts = historyDrafts.toMutableList().also { it[index] = row.copy(periodStart = raw.take(10)) }
                        },
                        label = { Text(stringResource(R.string.bill_import_period_start)) },
                        singleLine = true,
                        isError = row.periodStart.isoDateOrNull() == null,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = row.periodEnd,
                        onValueChange = { raw ->
                            historyDrafts = historyDrafts.toMutableList().also { it[index] = row.copy(periodEnd = raw.take(10)) }
                        },
                        label = { Text(stringResource(R.string.bill_import_period_end)) },
                        singleLine = true,
                        isError = row.periodEnd.isoDateOrNull() == null,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm), modifier = Modifier.padding(top = RzSpacing.xs)) {
                    OutlinedTextField(
                        value = row.consumptionKwh,
                        onValueChange = { raw ->
                            historyDrafts = historyDrafts.toMutableList().also {
                                it[index] = row.copy(consumptionKwh = raw.filter(Char::isDigit).take(7))
                            }
                        },
                        label = { Text(stringResource(R.string.bill_import_consumption)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = row.consumptionKwh.toIntOrNull() == null,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = row.amountMxn,
                        onValueChange = { raw ->
                            historyDrafts = historyDrafts.toMutableList().also {
                                it[index] = row.copy(amountMxn = raw.filter { char -> char.isDigit() || char == '.' }.take(10))
                            }
                        },
                        label = { Text(stringResource(R.string.bill_import_total)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = row.amountMxn.toDoubleOrNull() == null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (usedOcr) Text(stringResource(R.string.bill_import_ocr_note), style = MaterialTheme.typography.bodySmall)
    if (readingsInconsistent) {
        Text(
            stringResource(
                R.string.bill_import_readings_mismatch,
                ((currentValue ?: 0) - (previousValue ?: 0)) * (multiplierValue ?: 0),
                consumptionValue ?: 0,
            ),
            color = RzTheme.extendedColors.warningText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    bill.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    RzPrimaryButton(
        text = stringResource(R.string.bill_import_confirm),
        onClick = {
            onConfirm(
                bill.copy(
                    serviceNumber = serviceNumber,
                    rmu = rmu.ifBlank { null },
                    meterNumber = meterNumber,
                    multiplier = multiplierValue ?: bill.multiplier,
                    tariffCode = TariffCatalog.normalize(tariffCode),
                    periodStart = periodStartValue ?: bill.periodStart,
                    periodEnd = periodEndValue ?: bill.periodEnd,
                    previousReadingKwh = previousValue ?: bill.previousReadingKwh,
                    currentReadingKwh = currentValue ?: bill.currentReadingKwh,
                    consumptionKwh = consumptionValue ?: bill.consumptionKwh,
                    totalToPayMxn = totalValue ?: bill.totalToPayMxn,
                    charges = charges,
                    history = history,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = canConfirm,
    )
    RzSecondaryButton(stringResource(R.string.bill_import_other), onRetry, Modifier.fillMaxWidth())
}

private fun String.decimalOrNull(): Double? =
    filter { it.isDigit() || it == '.' }.take(10).toDoubleOrNull()

private fun editableNumber(value: Double?): String = when {
    value == null -> ""
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> value.toString()
}

private fun String.isoDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private data class HistoryRowDraft(
    val periodStart: String,
    val periodEnd: String,
    val consumptionKwh: String,
    val amountMxn: String,
    val paidMxn: Double?,
) {
    fun parsed(): ParsedBillHistoryRow? {
        val start = periodStart.isoDateOrNull() ?: return null
        val end = periodEnd.isoDateOrNull() ?: return null
        val consumption = consumptionKwh.toIntOrNull() ?: return null
        val amount = amountMxn.toDoubleOrNull() ?: return null
        return ParsedBillHistoryRow(start, end, consumption, amount, paidMxn)
    }

    companion object {
        fun from(row: ParsedBillHistoryRow) = HistoryRowDraft(
            periodStart = row.periodStart.toString(),
            periodEnd = row.periodEnd.toString(),
            consumptionKwh = row.consumptionKwh.toString(),
            amountMxn = editableNumber(row.amountMxn),
            paidMxn = row.paidMxn,
        )
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
