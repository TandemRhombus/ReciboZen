package com.recibozen.app.feature.appliances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzDashedCard
import com.recibozen.app.core.components.RzEmptyState
import com.recibozen.app.core.components.RzFilterChip
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AppliancesEmptyUiState(val hasSuggestions: Boolean = true)

/**
 * Trivial state (this screen has no session-derived data), but still routed
 * through [ReciboZenRepository.session] so the Route/ViewModel/UiState/Screen
 * shape matches every other feature screen.
 */
class AppliancesEmptyViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<AppliancesEmptyUiState> = repository.session
        .map { AppliancesEmptyUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppliancesEmptyUiState())
}

@Composable
fun AppliancesEmptyRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { AppliancesEmptyViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppliancesEmptyScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onAddFirstAppliance = { navController.popBackStack() },
        onSuggestionClick = {},
    )
}

@Composable
fun AppliancesEmptyScreen(
    uiState: AppliancesEmptyUiState,
    onBack: () -> Unit,
    onAddFirstAppliance: () -> Unit,
    onSuggestionClick: (String) -> Unit,
) {
    val suggestions = stringArrayResource(R.array.appliances_empty_suggestion_items)

    Column(modifier = Modifier.fillMaxWidth()) {
        RzFlowTopBar(title = stringResource(R.string.appliances_empty_top_bar_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            RzEmptyState(
                icon = RzIcons.AddHome,
                title = stringResource(R.string.appliances_empty_title),
                description = stringResource(R.string.appliances_empty_description),
                primaryActionLabel = stringResource(R.string.appliances_empty_cta),
                onPrimaryAction = onAddFirstAppliance,
            )

            Text(
                stringResource(R.string.appliances_empty_suggestions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                if (uiState.hasSuggestions) {
                    suggestions.forEach { suggestion ->
                        RzFilterChip(
                            text = suggestion,
                            selected = false,
                            onClick = { onSuggestionClick(suggestion) },
                        )
                    }
                }
            }

            RzDashedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.appliances_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppliancesEmptyScreenPreview() {
    ReciboZenTheme {
        AppliancesEmptyScreen(
            uiState = AppliancesEmptyUiState(),
            onBack = {},
            onAddFirstAppliance = {},
            onSuggestionClick = {},
        )
    }
}
