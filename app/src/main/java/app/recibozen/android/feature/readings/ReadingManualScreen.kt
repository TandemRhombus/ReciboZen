package app.recibozen.android.feature.readings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFlowTopBar
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

data class ReadingManualUiState(
    val digits: List<Int> = listOf(1, 8, 4, 2, 6),
    val previousReadingKwh: Int = 0,
)

class ReadingManualViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ReadingManualUiState> = repository.session
        .map { state ->
            ReadingManualUiState(
                digits = state.manualReadingDigits,
                previousReadingKwh = state.lastConfirmedReadingKwh,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingManualUiState())

    fun setDigits(digits: List<Int>) = repository.setManualReadingDigits(digits)
    fun prepare() = repository.prepareReading(uiState.value.digits.joinToString("").toInt(), ReadingMethod.MANUAL)
}

@Composable
fun ReadingManualRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ReadingManualViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReadingManualScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onDigitsChange = viewModel::setDigits,
        onContinue = {
            viewModel.prepare()
            navController.navigate(Routes.ReadingConfirm)
        },
        onPreferDial = { navController.navigate(Routes.ReadingDial) },
    )
}

@Composable
fun ReadingManualScreen(
    uiState: ReadingManualUiState,
    onBack: () -> Unit,
    onDigitsChange: (List<Int>) -> Unit,
    onContinue: () -> Unit,
    onPreferDial: () -> Unit,
) {
    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.reading_manual_title), onBack = onBack) },
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
                stringResource(R.string.reading_manual_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RzCard(
                modifier = Modifier.fillMaxWidth(),
                borderWidth = 2.dp,
                borderColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    stringResource(R.string.reading_manual_field_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                ) {
                    uiState.digits.forEachIndexed { index, digit ->
                        DigitField(
                            digit = digit,
                            contentDescription = stringResource(R.string.reading_manual_digit_content_description, index + 1),
                            onDigitChange = { newDigit ->
                                val updated = uiState.digits.toMutableList().apply { this[index] = newDigit }
                                onDigitsChange(updated)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    stringResource(R.string.reading_manual_decimals_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.md),
                )
            }

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        R.string.reading_manual_previous_reading,
                        String.format(Locale("es", "MX"), "%,d", uiState.previousReadingKwh),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RzPrimaryButton(
                text = stringResource(R.string.reading_manual_continue),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
            RzSecondaryButton(
                text = stringResource(R.string.reading_manual_prefer_dial),
                onClick = onPreferDial,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DigitField(
    digit: Int,
    contentDescription: String,
    onDigitChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = digit.toString(),
        onValueChange = { text ->
            val onlyDigits = text.filter { it.isDigit() }
            if (onlyDigits.isNotEmpty()) {
                onDigitChange(onlyDigits.last().digitToInt())
            }
        },
        modifier = modifier
            .height(62.dp)
            .semantics { this.contentDescription = contentDescription },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, fontSize = 22.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun ReadingManualScreenPreview() {
    ReciboZenTheme {
        ReadingManualScreen(
            uiState = ReadingManualUiState(digits = listOf(1, 8, 4, 2, 6), previousReadingKwh = 18397),
            onBack = {},
            onDigitsChange = {},
            onContinue = {},
            onPreferDial = {},
        )
    }
}
