package com.recibozen.app.feature.readings

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

/**
 * Static camera-viewfinder mockup — this build does no real capture/OCR. The
 * "detected reading" is the same fixed value used by [DemoReciboZenData.recentReadings]'s
 * most recent dial entry, so it lines up with what Historial later shows.
 */
data class ReadingPhotoUiState(val detectedReadingKwh: Int = DemoReciboZenData.recentReadings.first().kwh)

class ReadingPhotoViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ReadingPhotoUiState> = repository.session
        .map { ReadingPhotoUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingPhotoUiState())
}

@Composable
fun ReadingPhotoRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ReadingPhotoViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReadingPhotoScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onConfirm = { navController.navigate(Routes.ReadingConfirm) },
        onFixResult = { navController.navigate(Routes.ReadingPhotoUnclear) },
    )
}

@Composable
fun ReadingPhotoScreen(
    uiState: ReadingPhotoUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onFixResult: () -> Unit,
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
            CameraViewfinderMock()

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.reading_photo_detected_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        String.format(Locale("es", "MX"), "%,d", uiState.detectedReadingKwh),
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
                        onClick = { /* no-op: demo build has no real photo storage */ },
                        modifier = Modifier.weight(1f),
                    )
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_photo_discard_button),
                        onClick = { /* no-op: demo build has no real photo storage */ },
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
        )
    }
}
