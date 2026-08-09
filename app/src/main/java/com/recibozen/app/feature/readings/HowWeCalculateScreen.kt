package com.recibozen.app.feature.readings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Entirely static explanatory copy -- no repository state drives this screen. */
data class HowWeCalculateUiState(val placeholder: Boolean = true)

class HowWeCalculateViewModel(repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<HowWeCalculateUiState> = repository.session
        .map { HowWeCalculateUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HowWeCalculateUiState())
}

@Composable
fun HowWeCalculateRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { HowWeCalculateViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HowWeCalculateScreen(uiState = uiState, onBack = { navController.popBackStack() })
}

@Composable
fun HowWeCalculateScreen(uiState: HowWeCalculateUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.how_we_calculate_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.how_we_calculate_flow_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(modifier = Modifier.padding(top = RzSpacing.md), verticalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                    CalcStep(1, stringResource(R.string.how_we_calculate_step1_label), stringResource(R.string.how_we_calculate_step1_body))
                    CalcStep(2, stringResource(R.string.how_we_calculate_step2_label), stringResource(R.string.how_we_calculate_step2_body))
                    CalcStep(3, stringResource(R.string.how_we_calculate_step3_label), stringResource(R.string.how_we_calculate_step3_body))
                    CalcStep(4, stringResource(R.string.how_we_calculate_step4_label), stringResource(R.string.how_we_calculate_step4_body))
                }
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.how_we_calculate_assumptions_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.how_we_calculate_assumptions_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RzExtraShapes.cardSmall)
                    .padding(horizontal = RzSpacing.lg, vertical = RzSpacing.md),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
            ) {
                GlossaryLine("kWh", stringResource(R.string.how_we_calculate_glossary_kwh_body))
                GlossaryLine("kW", stringResource(R.string.how_we_calculate_glossary_kw_body))
                GlossaryLine("kWp", stringResource(R.string.how_we_calculate_glossary_kwp_body))
            }

            Text(
                stringResource(R.string.how_we_calculate_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun CalcStep(number: Int, label: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        val onSurface = MaterialTheme.colorScheme.onSurface
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append(label) }
            append(" ")
            withStyle(SpanStyle(color = onSurfaceVariant)) { append(body) }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GlossaryLine(term: String, body: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append(term) }
        append(" ")
        withStyle(SpanStyle(color = onSurfaceVariant)) { append(body) }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Preview(showBackground = true)
@Composable
private fun HowWeCalculateScreenPreview() {
    ReciboZenTheme {
        HowWeCalculateScreen(uiState = HowWeCalculateUiState(), onBack = {})
    }
}
