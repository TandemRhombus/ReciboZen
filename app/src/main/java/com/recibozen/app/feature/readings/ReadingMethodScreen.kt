package com.recibozen.app.feature.readings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzDashedCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * This screen has no dynamic session state today, but still follows the
 * mandated Route/ViewModel/UiState/Screen split so it's consistent with
 * every other screen in the flow and ready for future state.
 */
data class ReadingMethodUiState(val placeholder: Boolean = true)

class ReadingMethodViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ReadingMethodUiState> = repository.session
        .map { ReadingMethodUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingMethodUiState())
}

@Composable
fun ReadingMethodRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ReadingMethodViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReadingMethodScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelectPhoto = { navController.navigate(Routes.ReadingPhoto) },
        onSelectDial = { navController.navigate(Routes.ReadingDial) },
        onSelectManual = { navController.navigate(Routes.ReadingManual) },
    )
}

@Composable
fun ReadingMethodScreen(
    uiState: ReadingMethodUiState,
    onBack: () -> Unit,
    onSelectPhoto: () -> Unit,
    onSelectDial: () -> Unit,
    onSelectManual: () -> Unit,
) {
    Scaffold(
        topBar = {
            RzFlowTopBar(title = stringResource(R.string.action_register_reading), onBack = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(
                stringResource(R.string.reading_method_headline),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.reading_method_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MethodOptionCard(
                icon = RzIcons.PhotoCamera,
                title = stringResource(R.string.reading_method_photo_title),
                subtitle = stringResource(R.string.reading_method_photo_subtitle),
                highlighted = false,
                onClick = onSelectPhoto,
            )
            MethodOptionCard(
                icon = RzIcons.Schedule,
                title = stringResource(R.string.reading_method_dial_title),
                subtitle = stringResource(R.string.reading_method_dial_subtitle),
                badgeText = stringResource(R.string.reading_method_dial_badge),
                highlighted = true,
                onClick = onSelectDial,
            )
            MethodOptionCard(
                icon = RzIcons.Keyboard,
                title = stringResource(R.string.reading_method_manual_title),
                subtitle = stringResource(R.string.reading_method_manual_subtitle),
                highlighted = false,
                onClick = onSelectManual,
            )

            RzDashedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.reading_method_footnote),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A tappable card styled like [com.recibozen.app.core.components.RzCard], but using [Surface]
 * (instead of RzCard, which has no onClick slot) so the ripple/clip behavior is correct out of
 * the box for a selectable-looking row.
 */
@Composable
private fun MethodOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RzSpacing.minTouchTarget),
        shape = RzExtraShapes.cardLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (highlighted) 2.dp else 1.dp,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(RzSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (badgeText != null) {
                        Surface(
                            shape = RzExtraShapes.pill,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                badgeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = RzSpacing.md, vertical = 3.dp),
                            )
                        }
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReadingMethodScreenPreview() {
    ReciboZenTheme {
        ReadingMethodScreen(
            uiState = ReadingMethodUiState(),
            onBack = {},
            onSelectPhoto = {},
            onSelectDial = {},
            onSelectManual = {},
        )
    }
}
