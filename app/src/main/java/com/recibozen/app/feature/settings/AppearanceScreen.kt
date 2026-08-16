package com.recibozen.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzInlineBanner
import com.recibozen.app.core.components.RzBannerTone
import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * "Modo oscuro" screen — the one screen in this build with real behavior: picking an option calls
 * [ReciboZenRepository.setThemeMode], which [ReciboZenTheme] (mounted at the app root) reads to
 * flip the whole app's M3 [androidx.compose.material3.ColorScheme]. The preview card below just
 * renders normal themed Composables, so it always matches whatever theme is currently active
 * without any hardcoded dark colors here.
 */
data class AppearanceUiState(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    // Neutral defaults: the sample card exists to show the theme, and it must
    // not flash invented amounts before the real state arrives.
    val periodLabel: String = "",
    val projectedMxn: Int = 0,
    val projectedRangeLowMxn: Int = 0,
    val projectedRangeHighMxn: Int = 0,
    val goalMxn: Int = 0,
    val overGoalMxn: Int = 0,
    val daysRemaining: Int = 0,
)

class AppearanceViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<AppearanceUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        repository.tariffCalibration,
    ) { session, importedBills, _ ->
            val projection = repository.billProjection(session)
            val energy = repository.energySummary(session)
            AppearanceUiState(
                themeMode = session.themeMode,
                periodLabel = importedBills.lastOrNull()?.monthLabel.orEmpty(),
                projectedMxn = projection.projectedMxn,
                projectedRangeLowMxn = projection.rangeLowMxn,
                projectedRangeHighMxn = projection.rangeHighMxn,
                goalMxn = session.goalMxn,
                overGoalMxn = (projection.projectedMxn - session.goalMxn).coerceAtLeast(0),
                daysRemaining = energy.daysRemaining,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppearanceUiState())

    fun setThemeMode(mode: AppThemeMode) = repository.setThemeMode(mode)
}

@Composable
fun AppearanceRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { AppearanceViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppearanceScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelectThemeMode = viewModel::setThemeMode,
    )
}

@Composable
fun AppearanceScreen(
    uiState: AppearanceUiState,
    onBack: () -> Unit,
    onSelectThemeMode: (AppThemeMode) -> Unit,
) {
    Scaffold(topBar = { RzFlowTopBar(title = stringResource(R.string.appearance_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            Text(
                text = stringResource(R.string.appearance_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                ThemeOptionRow(
                    label = stringResource(R.string.appearance_option_light),
                    selected = uiState.themeMode == AppThemeMode.LIGHT,
                    onClick = { onSelectThemeMode(AppThemeMode.LIGHT) },
                )
                ThemeOptionRow(
                    label = stringResource(R.string.appearance_option_dark),
                    selected = uiState.themeMode == AppThemeMode.DARK,
                    onClick = { onSelectThemeMode(AppThemeMode.DARK) },
                )
                ThemeOptionRow(
                    label = stringResource(R.string.appearance_option_system),
                    selected = uiState.themeMode == AppThemeMode.SYSTEM,
                    onClick = { onSelectThemeMode(AppThemeMode.SYSTEM) },
                )
            }

            Text(
                text = stringResource(R.string.appearance_preview_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
            ProjectedBillPreviewCard(uiState)

            Text(
                text = stringResource(R.string.appearance_meaning_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ThemeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedDesc = stringResource(R.string.appearance_option_selected_desc)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    RzCard(
        shape = RzExtraShapes.control,
        borderColor = borderColor,
        borderWidth = if (selected) 2.dp else 1.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = RzSpacing.xl, vertical = RzSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Row(
            modifier = Modifier.padding(vertical = RzSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(RzIcons.CheckCircle, contentDescription = selectedDesc, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Non-interactive reproduction of the "Recibo proyectado" card, themed by whatever mode is active. */
@Composable
private fun ProjectedBillPreviewCard(uiState: AppearanceUiState) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.appearance_preview_label, uiState.periodLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, RzExtraShapes.pill)
                    .padding(horizontal = RzSpacing.md, vertical = RzSpacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.appearance_preview_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = stringResource(R.string.appearance_preview_price, uiState.projectedMxn),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            text = stringResource(R.string.appearance_preview_range, uiState.projectedRangeLowMxn, uiState.projectedRangeHighMxn),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        RzInlineBanner(
            text = stringResource(R.string.appearance_preview_warning, uiState.overGoalMxn, uiState.goalMxn, uiState.daysRemaining),
            tone = RzBannerTone.WARNING,
            icon = RzIcons.TrendingUp,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
        )
    }
}

@Preview(showBackground = true, name = "Modo oscuro")
@Composable
private fun AppearanceScreenPreview() {
    ReciboZenTheme {
        AppearanceScreen(uiState = AppearanceUiState(themeMode = AppThemeMode.SYSTEM), onBack = {}, onSelectThemeMode = {})
    }
}
