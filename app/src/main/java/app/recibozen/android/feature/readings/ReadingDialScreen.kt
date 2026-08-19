package app.recibozen.android.feature.readings

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.recibozen.android.core.designsystem.RzExtraShapes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.components.MeterDialMath
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzMeterDialCluster
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.floor

data class ReadingDialUiState(
    val angles: List<Float> = listOf(66.3f, 56.7f, 153.4f, 266.4f, 216f),
    val directions: List<Boolean> = listOf(true, false, true, false, true),
    val selectedIndex: Int = 0,
    val visitedFacesCount: Int = 1,
    val unsure: Boolean = false,
    val interpretedKwh: Int = 0,
    val unsureLowDigit: Int = 0,
    val unsureHighDigit: Int = 1,
    val faceIndices: List<Int> = listOf(0, 1, 2, 3, 4),
) {
    val allFacesVisited: Boolean get() = visitedFacesCount >= angles.size
}

class ReadingDialViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ReadingDialUiState> = repository.session
        .map { state ->
            val faceIndices = if (state.meterType.contains("4")) listOf(1, 2, 3, 4) else state.dialAngles.indices.toList()
            val selected = faceIndices.indexOf(state.dialSelectedIndex).takeIf { it >= 0 } ?: 0
            val backingSelected = faceIndices[selected]
            // The two candidate digits bracket the *raw* (fractional) value at the
            // selected face -- deliberately not the already-carried digit from
            // MeterDialMath.digits(), per the original prototype's dudaBajaVal/dudaAltaVal.
            val rawFloor = Math.floorMod(
                floor(MeterDialMath.rawValue(state.dialAngles[backingSelected], state.dialDirections[backingSelected])).toInt(),
                10,
            )
            ReadingDialUiState(
                angles = faceIndices.map(state.dialAngles::get),
                directions = faceIndices.map(state.dialDirections::get),
                selectedIndex = selected,
                visitedFacesCount = state.dialVisitedFaces.count { it in faceIndices },
                unsure = state.dialUnsure,
                interpretedKwh = repository.dialReading(state).valueKwh,
                unsureLowDigit = rawFloor,
                unsureHighDigit = (rawFloor + 1) % 10,
                faceIndices = faceIndices,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingDialUiState())

    private fun backingIndex(visibleIndex: Int): Int =
        uiState.value.faceIndices.getOrElse(visibleIndex) { uiState.value.faceIndices.first() }

    fun setSelectedIndex(index: Int) = repository.setDialSelectedIndex(backingIndex(index))
    fun setAngle(index: Int, angleDegrees: Float) = repository.setDialAngle(backingIndex(index), angleDegrees)
    fun toggleUnsure() = repository.toggleDialUnsure()
    fun setDigit(index: Int, digit: Int) = repository.setDialDigit(backingIndex(index), digit)
    fun prepare() = repository.prepareReading(uiState.value.interpretedKwh, ReadingMethod.DIAL)
}

@Composable
fun ReadingDialRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ReadingDialViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReadingDialScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelectedIndexChange = viewModel::setSelectedIndex,
        onAngleChange = viewModel::setAngle,
        onPrevious = { viewModel.setSelectedIndex((uiState.selectedIndex - 1).coerceAtLeast(0)) },
        onNextOrReview = {
            if (uiState.selectedIndex == uiState.angles.lastIndex) {
                viewModel.prepare()
                navController.navigate(Routes.ReadingConfirm)
            } else {
                viewModel.setSelectedIndex((uiState.selectedIndex + 1).coerceAtMost(uiState.angles.lastIndex))
            }
        },
        onToggleUnsure = viewModel::toggleUnsure,
        onPickLowDigit = { viewModel.setDigit(uiState.selectedIndex, uiState.unsureLowDigit) },
        onPickHighDigit = { viewModel.setDigit(uiState.selectedIndex, uiState.unsureHighDigit) },
        onSave = {
            viewModel.prepare()
            navController.navigate(Routes.ReadingConfirm)
        },
    )
}

@Composable
fun ReadingDialScreen(
    uiState: ReadingDialUiState,
    onBack: () -> Unit,
    onSelectedIndexChange: (Int) -> Unit,
    onAngleChange: (index: Int, angleDegrees: Float) -> Unit,
    onPrevious: () -> Unit,
    onNextOrReview: () -> Unit,
    onToggleUnsure: () -> Unit,
    onPickLowDigit: () -> Unit,
    onPickHighDigit: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.reading_method_dial_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Column {
                Text(
                    stringResource(R.string.reading_dial_headline),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.reading_dial_subtitle_dynamic, uiState.angles.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
            }

            RzMeterDialCluster(
                angles = uiState.angles,
                selectedIndex = uiState.selectedIndex,
                onSelectedIndexChange = onSelectedIndexChange,
                onAngleChange = onAngleChange,
                directions = uiState.directions,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                RzSecondaryButton(
                    text = stringResource(R.string.reading_dial_previous),
                    onClick = onPrevious,
                    modifier = Modifier.weight(1f),
                )
                RzPrimaryButton(
                    text = stringResource(
                        if (uiState.selectedIndex == uiState.angles.lastIndex) {
                            R.string.reading_dial_review
                        } else {
                            R.string.reading_dial_next
                        },
                    ),
                    onClick = onNextOrReview,
                    modifier = Modifier.weight(1.4f),
                )
            }

            RzSecondaryButton(
                text = stringResource(R.string.reading_dial_unsure_toggle),
                onClick = onToggleUnsure,
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState.unsure) {
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.reading_dial_unsure_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.reading_dial_unsure_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                        modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                    ) {
                        DisambiguationOption(
                            title = stringResource(R.string.reading_dial_unsure_low),
                            readsAs = stringResource(R.string.reading_dial_unsure_reads_as, uiState.unsureLowDigit),
                            onClick = onPickLowDigit,
                            modifier = Modifier.weight(1f),
                        )
                        DisambiguationOption(
                            title = stringResource(R.string.reading_dial_unsure_high),
                            readsAs = stringResource(R.string.reading_dial_unsure_reads_as, uiState.unsureHighDigit),
                            onClick = onPickHighDigit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.reading_dial_interpreted_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    Text(
                        String.format(Locale("es", "MX"), "%,d", uiState.interpretedKwh),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        " " + stringResource(R.string.reading_confirm_unit_suffix),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.reading_dial_interpreted_hint_dynamic, uiState.angles.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
            }

            if (!uiState.allFacesVisited) {
                Text(
                    stringResource(R.string.reading_dial_save_helper_dynamic, uiState.angles.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RzPrimaryButton(
                text = stringResource(R.string.reading_dial_save),
                onClick = onSave,
                enabled = uiState.allFacesVisited,
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DisambiguationOption(
    title: String,
    readsAs: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 56.dp),
        shape = RzExtraShapes.control,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            Text(readsAs, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReadingDialScreenPreview() {
    ReciboZenTheme {
        ReadingDialScreen(
            uiState = ReadingDialUiState(visitedFacesCount = 3, interpretedKwh = 18426),
            onBack = {},
            onSelectedIndexChange = {},
            onAngleChange = { _, _ -> },
            onPrevious = {},
            onNextOrReview = {},
            onToggleUnsure = {},
            onPickLowDigit = {},
            onPickHighDigit = {},
            onSave = {},
        )
    }
}
