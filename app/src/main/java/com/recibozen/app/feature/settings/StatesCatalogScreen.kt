package com.recibozen.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzBannerTone
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzInlineBanner
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.components.RzSkeletonBlock
import com.recibozen.app.core.components.RzSkeletonLine
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Static reference gallery of UI states ("Calculando", "Sin conexión", etc.) — not a live/interactive
 * state, just a design reference. Only reachable from [ProfileRoute]'s "Estados y errores" row.
 * Nothing here reads mutable session data; the [StatesCatalogViewModel] only exists to follow the
 * same Route/Screen/ViewModel pattern every other screen in the app uses.
 */
data class StatesCatalogUiState(val placeholder: Unit = Unit)

class StatesCatalogViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<StatesCatalogUiState> = repository.session
        .map { StatesCatalogUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatesCatalogUiState())
}

@Composable
fun StatesCatalogRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { StatesCatalogViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    StatesCatalogScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onRegisterReading = { navController.navigate(Routes.ReadingMethod) },
    )
}

@Composable
fun StatesCatalogScreen(
    uiState: StatesCatalogUiState,
    onBack: () -> Unit,
    onRegisterReading: () -> Unit,
) {
    Scaffold(topBar = { RzFlowTopBar(title = stringResource(R.string.states_catalog_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.states_catalog_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CalculatingStateCard()
            InsufficientInfoStateCard(onRegisterReading = onRegisterReading)
            OfflineStateCard()
            DubiousReadingStateCard()
            ReadingRegisteredStateCard()
            PlanEmptyStateCard()
            SolarLowConfidenceStateCard()
        }
    }
}

@Composable
private fun StateEyebrow(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun CalculatingStateCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        StateEyebrow(stringResource(R.string.states_catalog_eyebrow_loading))
        RzSkeletonLine(modifier = Modifier.fillMaxWidth(0.55f).padding(top = RzSpacing.md))
        RzSkeletonBlock(modifier = Modifier.fillMaxWidth(0.75f).height(34.dp).padding(top = RzSpacing.sm))
        RzSkeletonLine(modifier = Modifier.fillMaxWidth(0.9f).padding(top = RzSpacing.sm), height = 10.dp)
        Text(
            text = stringResource(R.string.states_catalog_loading_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
    }
}

@Composable
private fun InsufficientInfoStateCard(onRegisterReading: () -> Unit) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        StateEyebrow(stringResource(R.string.states_catalog_eyebrow_insufficient))
        Text(
            text = stringResource(R.string.states_catalog_insufficient_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            text = stringResource(R.string.states_catalog_insufficient_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        RzPrimaryButton(
            text = stringResource(R.string.states_catalog_insufficient_action),
            onClick = onRegisterReading,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
        )
    }
}

@Composable
private fun OfflineStateCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        StateEyebrow(stringResource(R.string.states_catalog_eyebrow_offline))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            modifier = Modifier.padding(top = RzSpacing.sm),
        ) {
            Icon(RzIcons.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(R.string.states_catalog_offline_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.states_catalog_offline_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
    }
}

@Composable
private fun DubiousReadingStateCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        StateEyebrow(stringResource(R.string.states_catalog_eyebrow_dubious))
        RzInlineBanner(
            title = stringResource(R.string.states_catalog_dubious_title),
            text = stringResource(R.string.states_catalog_dubious_body),
            tone = RzBannerTone.WARNING,
            icon = RzIcons.GppMaybe,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        )
        Text(
            stringResource(R.string.states_catalog_dubious_what_to_do),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Composable
private fun ReadingRegisteredStateCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        RzInlineBanner(
            title = stringResource(R.string.states_catalog_registered_title),
            text = stringResource(R.string.states_catalog_registered_body),
            tone = RzBannerTone.SUCCESS,
            icon = RzIcons.CheckCircle,
        )
    }
}

@Composable
private fun PlanEmptyStateCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        StateEyebrow(stringResource(R.string.states_catalog_eyebrow_plan_empty))
        Text(
            text = stringResource(R.string.states_catalog_plan_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            text = stringResource(R.string.states_catalog_plan_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
    }
}

@Composable
private fun SolarLowConfidenceStateCard() {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        StateEyebrow(stringResource(R.string.states_catalog_eyebrow_solar_low))
        Text(
            text = stringResource(R.string.states_catalog_solar_low_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        Text(
            text = stringResource(R.string.states_catalog_solar_low_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
    }
}

@Preview(showBackground = true, name = "Estados y errores")
@Composable
private fun StatesCatalogScreenPreview() {
    ReciboZenTheme {
        StatesCatalogScreen(
            uiState = StatesCatalogUiState(),
            onBack = {},
            onRegisterReading = {},
        )
    }
}
