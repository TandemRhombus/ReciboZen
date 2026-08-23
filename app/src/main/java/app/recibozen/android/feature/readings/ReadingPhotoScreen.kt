package app.recibozen.android.feature.readings

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.core.content.FileProvider
import app.recibozen.android.R
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzExtraShapes
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.core.calculation.MeterProfile
import app.recibozen.android.core.ocr.MeterReadingParser
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.rzViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import androidx.core.net.toUri

data class ReadingPhotoUiState(
    val detectedReadingKwh: Int? = null,
    val imageUri: String? = null,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val isPhotoKept: Boolean = false,
    val imageRevision: Int = 0,
)

class ReadingPhotoViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val _uiState = MutableStateFlow(ReadingPhotoUiState())
    val uiState: StateFlow<ReadingPhotoUiState> = _uiState.asStateFlow()
    private var captureFile: File? = null

    fun createCaptureUri(context: Context): Uri {
        val directory = File(context.cacheDir, "reading_photos").apply { mkdirs() }
        captureFile = File(directory, "medidor_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", captureFile!!)
        _uiState.value = ReadingPhotoUiState(imageUri = uri.toString())
        return uri
    }

    fun processCapture(context: Context) {
        val uri = _uiState.value.imageUri?.let(Uri::parse) ?: return
        _uiState.update { it.copy(isProcessing = true, errorMessage = null, imageRevision = it.imageRevision + 1) }
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrElse {
            _uiState.update { state -> state.copy(isProcessing = false, errorMessage = "capture") }
            return
        }
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val session = repository.session.value
                val reading = MeterReadingParser.parse(
                    text = text.text,
                    previousReadingKwh = session.lastConfirmedReadingKwh,
                    meterDigitCount = MeterProfile.digitCount(session.meterType),
                )
                if (reading != null) {
                    repository.prepareReading(reading, ReadingMethod.PHOTO)
                    _uiState.update { it.copy(detectedReadingKwh = reading, isProcessing = false, errorMessage = null) }
                } else {
                    _uiState.update { it.copy(isProcessing = false, errorMessage = "ocr") }
                }
            }
            .addOnFailureListener {
                _uiState.update { state -> state.copy(isProcessing = false, errorMessage = "ocr") }
            }
    }

    fun captureCancelled() {
        captureFile?.delete()
        _uiState.value = ReadingPhotoUiState(errorMessage = "capture")
    }

    fun keepPhoto(context: Context) {
        val source = captureFile ?: return
        val targetDir = File(context.filesDir, "reading_photos").apply { mkdirs() }
        val target = File(targetDir, source.name)
        runCatching { source.inputStream().use { input -> target.outputStream().use(input::copyTo) } }
            .onSuccess {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
                _uiState.update { it.copy(imageUri = uri.toString(), isPhotoKept = true) }
            }
    }

    fun discardPhoto() {
        captureFile?.delete()
        captureFile = null
        _uiState.update { it.copy(imageUri = null, isPhotoKept = false) }
    }

    override fun onCleared() {
        recognizer.close()
    }
}

@Composable
fun ReadingPhotoRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ReadingPhotoViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        if (captured) viewModel.processCapture(context) else viewModel.captureCancelled()
    }
    val takePhoto = { cameraLauncher.launch(viewModel.createCaptureUri(context)) }

    LaunchedEffect(Unit) { takePhoto() }

    ReadingPhotoScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onConfirm = { navController.navigate(Routes.ReadingConfirm) },
        onFixResult = { navController.navigate(Routes.ReadingPhotoUnclear) },
        onRetry = takePhoto,
        onKeepPhoto = { viewModel.keepPhoto(context) },
        onDiscardPhoto = viewModel::discardPhoto,
    )
}

@Composable
fun ReadingPhotoScreen(
    uiState: ReadingPhotoUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onFixResult: () -> Unit,
    onRetry: () -> Unit,
    onKeepPhoto: () -> Unit,
    onDiscardPhoto: () -> Unit,
) {
    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.reading_photo_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            CapturedPhotoOrViewfinder(uiState.imageUri, uiState.imageRevision)

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.reading_photo_detected_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.isProcessing) {
                    Text(
                        stringResource(R.string.reading_photo_processing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
                uiState.detectedReadingKwh?.let { detected ->
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        String.format(Locale("es", "MX"), "%,d", detected),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        " " + stringResource(R.string.reading_photo_unit_suffix),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                }
                uiState.errorMessage?.let { error ->
                    Text(
                        stringResource(if (error == "capture") R.string.reading_photo_capture_error else R.string.reading_photo_ocr_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RzTheme.extendedColors.warningText,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_photo_retry_button),
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                    )
                }
                if (uiState.detectedReadingKwh != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                    modifier = Modifier.padding(top = RzSpacing.sm),
                ) {
                    Icon(RzIcons.CheckCircle, contentDescription = null, tint = RzTheme.extendedColors.success)
                    Text(
                        stringResource(R.string.reading_photo_all_clear),
                        style = MaterialTheme.typography.bodySmall,
                        color = RzTheme.extendedColors.success,
                    )
                }
                Text(
                    stringResource(R.string.reading_photo_compare_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
                RzPrimaryButton(
                    text = stringResource(R.string.reading_photo_confirm_button),
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
                )
                RzSecondaryButton(
                    text = stringResource(R.string.reading_photo_fix_button),
                    onClick = onFixResult,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                )
                }
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.reading_photo_keep_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.reading_photo_keep_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                ) {
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_photo_keep_button),
                        onClick = onKeepPhoto,
                        modifier = Modifier.weight(1f),
                    )
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_photo_discard_button),
                        onClick = onDiscardPhoto,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Dark camera-scrim illustration. Uses literal colors (not theme tokens) on
 * purpose: this box represents an opaque camera preview overlay, which stays
 * visually identical regardless of the app's light/dark theme.
 */
@Composable
private fun CapturedPhotoOrViewfinder(uriString: String?, imageRevision: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(uriString, imageRevision) {
        uriString?.let { value ->
            runCatching {
                context.contentResolver.openInputStream(value.toUri())?.use(BitmapFactory::decodeStream)?.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.reading_photo_viewfinder_caption),
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth().height(240.dp),
        )
    } else {
        CameraViewfinderMock(modifier)
    }
}

@Composable
private fun CameraViewfinderMock(modifier: Modifier = Modifier) {
    val scrimColor = Color(0xFF2A3733)
    val limeAccent = Color(0xFFD9EE63)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(scrimColor, RzExtraShapes.cardLarge),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .height(120.dp)
                .border(3.dp, limeAccent, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.reading_photo_viewfinder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = limeAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = RzSpacing.md),
            )
        }
        Text(
            stringResource(R.string.reading_photo_viewfinder_caption),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFC9D2CD),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = RzSpacing.md),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReadingPhotoScreenPreview() {
    ReciboZenTheme {
        ReadingPhotoScreen(
            uiState = ReadingPhotoUiState(),
            onBack = {},
            onConfirm = {},
            onFixResult = {},
            onRetry = {},
            onKeepPhoto = {},
            onDiscardPhoto = {},
        )
    }
}
