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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ReadingPhotoUnclearUiState(val placeholder: Boolean = true)

class ReadingPhotoUnclearViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ReadingPhotoUnclearUiState> = repository.session
        .map { ReadingPhotoUnclearUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingPhotoUnclearUiState())
}

@Composable
fun ReadingPhotoUnclearRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ReadingPhotoUnclearViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReadingPhotoUnclearScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onFixOnPhoto = { navController.navigate(Routes.ReadingDial) },
        onAdjustDial = { navController.navigate(Routes.ReadingDial) },
        onWriteManually = { navController.navigate(Routes.ReadingManual) },
        onRetryPhoto = { navController.navigate(Routes.ReadingPhoto) },
    )
}

@Composable
fun ReadingPhotoUnclearScreen(
    uiState: ReadingPhotoUnclearUiState,
    onBack: () -> Unit,
    onFixOnPhoto: () -> Unit,
    onAdjustDial: () -> Unit,
    onWriteManually: () -> Unit,
    onRetryPhoto: () -> Unit,
) {
    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.reading_photo_unclear_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            BlurredDigitsMock()

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
                    Icon(RzIcons.VisibilityOff, contentDescription = null, tint = RzTheme.extendedColors.warning)
                    Text(
                        stringResource(R.string.reading_photo_unclear_headline),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    stringResource(R.string.reading_photo_unclear_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )

                val warning = RzTheme.extendedColors.warning
                val onSurface = MaterialTheme.colorScheme.onSurface
                val rawPartialValue = stringResource(R.string.reading_photo_unclear_partial_value)
                val partialValue = buildAnnotatedString {
                    rawPartialValue.forEach { ch ->
                        if (ch == '?') {
                            withStyle(SpanStyle(color = warning, fontWeight = FontWeight.Bold)) { append(ch) }
                        } else {
                            withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.Bold)) { append(ch) }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RzSpacing.md)
                        .background(MaterialTheme.colorScheme.background, RzExtraShapes.cardSmall)
                        .padding(horizontal = RzSpacing.lg, vertical = RzSpacing.md),
                ) {
                    Text(
                        stringResource(R.string.reading_photo_unclear_partial_label) + " ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(partialValue, style = MaterialTheme.typography.bodyMedium)
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                ) {
                    RzPrimaryButton(
                        text = stringResource(R.string.reading_photo_unclear_fix_photo),
                        onClick = onFixOnPhoto,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_photo_unclear_dial),
                        onClick = onAdjustDial,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_photo_unclear_manual),
                        onClick = onWriteManually,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_photo_unclear_retry),
                        onClick = onRetryPhoto,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Blurred dark box with 5 per-digit outline rectangles, 2 highlighted amber for uncertainty. */
@Composable
private fun BlurredDigitsMock(modifier: Modifier = Modifier) {
    val scrimColor = Color(0xFF2A3733)
    val okColor = Color(0xFFD9EE63)
    val uncertainColor = Color(0xFFE99A32)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(scrimColor, RzExtraShapes.cardLarge),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.76f),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
        ) {
            val uncertainIndices = setOf(2, 3)
            repeat(5) { index ->
                val color = if (index in uncertainIndices) uncertainColor else okColor
                val background = if (index in uncertainIndices) uncertainColor.copy(alpha = 0.18f) else Color.Transparent
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .background(background, RoundedCornerShape(10.dp))
                        .border(2.dp, color, RoundedCornerShape(10.dp)),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReadingPhotoUnclearScreenPreview() {
    ReciboZenTheme {
        ReadingPhotoUnclearScreen(
            uiState = ReadingPhotoUnclearUiState(),
            onBack = {},
            onFixOnPhoto = {},
            onAdjustDial = {},
            onWriteManually = {},
            onRetryPhoto = {},
        )
    }
}
