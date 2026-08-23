package app.recibozen.android.feature.readings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzInlineBanner
import app.recibozen.android.core.components.RzBannerTone
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.core.calculation.MeterDelta
import app.recibozen.android.core.calculation.MeterProfile
import app.recibozen.android.core.calculation.ReadingInterval
import app.recibozen.android.core.model.MeterReplacement
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.rzViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The confirm screen always reflects the current dial/manual repository state
 * ([ReciboZenRepository.dialReading]), regardless of which input method the
 * user came from -- an accepted simplification for this handoff build.
 */
data class ReadingConfirmUiState(
    val readingKwh: Int = 0,
    val previousReadingKwh: Int = 0,
    /** Null when the readings do not establish a consumption; never 0 as a stand-in. */
    val consumptionSinceKwh: Int? = null,
    /** Null when there is no established consumption to divide. */
    val averagePerDay: Double? = null,
    /**
     * Days since the previous reading, or null when no stored reading carries a
     * timestamp. This row used to render a fixed "3 días y 4 horas" string left
     * over from the mockup -- a number the app never computed and could not have
     * known, printed next to figures the user was being asked to trust.
     */
    val elapsedDays: Double? = null,
    val isAbnormal: Boolean = false,
    val warningMessage: String? = null,
    val method: ReadingMethod = ReadingMethod.DIAL,
    /**
     * Readings of events the arithmetic allows, offered when it allows more than
     * one. Empty when the interval is unambiguous, and also when the app cannot
     * frame the question honestly: an unknown digit count leaves nothing to
     * enumerate, and inventing a capacity to fill the list would be worse than
     * saying nothing.
     */
    val wrapCandidates: List<MeterDelta.Candidate> = emptyList(),
    /** Which candidate the household picked, or null while they have not. */
    val attestedWraps: Int? = null,
    /** Why the consumption is undetermined, when it is. */
    val blockedReason: MeterDelta.Reason? = null,
    /** Digits the register shows, so the screen can name where it wraps. */
    val meterDigitCount: Int? = null,
    /** The meter swap the household declared on this screen, if any. */
    val meterReplacement: MeterReplacement? = null,
) {
    /** True when the figure shown rests on the household's testimony, not on arithmetic alone. */
    val restsOnAttestation: Boolean get() = (attestedWraps ?: 0) > 0 && consumptionSinceKwh != null

    /** A swap was declared but its boundary readings are still missing. */
    val replacementPending: Boolean get() = meterReplacement != null && !meterReplacement.isComplete

    /** A swap was declared and closed, so the consumption spans two registers. */
    val replacementResolved: Boolean get() = meterReplacement?.isComplete == true && consumptionSinceKwh != null
}

/** Above this daily rate the reading is questioned rather than accepted quietly. */
private const val IMPLAUSIBLE_DAILY_KWH = 20.0

/** Shown instead of a number the readings do not establish. */
private const val UNKNOWN_VALUE = "—"

class ReadingConfirmViewModel(private val repository: ReciboZenRepository) : ViewModel() {

    /**
     * How many wraps the household attested on this screen, before the reading
     * is saved. Held here and not in the repository so leaving the screen
     * discards it: an attestation only becomes a fact once the reading it
     * belongs to is confirmed.
     */
    private val attestedWraps = MutableStateFlow<Int?>(null)

    /**
     * The meter swap declared on this screen, held here for the same reason as
     * [attestedWraps]: it becomes a fact only when the reading it belongs to is
     * confirmed, so backing out discards it.
     */
    private val meterReplacement = MutableStateFlow<MeterReplacement?>(null)

    val uiState: StateFlow<ReadingConfirmUiState> = combine(
        repository.session,
        attestedWraps,
        meterReplacement,
    ) { state, wraps, replacement ->
        val reading = state.pendingReadingKwh ?: repository.dialReading(state).valueKwh
        val method = state.pendingReadingMethod ?: ReadingMethod.DIAL
        val elapsedDays = ReadingInterval.daysSince(state.readings, System.currentTimeMillis())
        // The household's own pace is what lets MeterDelta notice an interval
        // long enough to hide a wrap. Zero means no pace is established yet, and
        // passing null disables that check rather than measuring against a rate
        // nobody has.
        val pace = repository.energySummary(state).dailyAverageKwh.takeIf { it > 0.0 }
        // The subtraction that used to live here produced a negative number
        // when the register wrapped, and the screen rendered it as a
        // measurement. MeterDelta either establishes the consumption or says
        // it cannot, and the screen has to show which of the two happened.
        val delta = MeterDelta.consumption(
            previousKwh = state.lastConfirmedReadingKwh,
            currentKwh = reading,
            elapsedDays = elapsedDays ?: 0.0,
            capacityKwh = MeterProfile.capacity(state.meterType, state.meterDigitCount),
            multiplier = state.meterMultiplier,
            attestedWraps = wraps,
            plausibleDailyKwh = pace,
            replacement = replacement,
        )
        val measured = (delta as? MeterDelta.Measured)?.kwh
        val average = measured?.let { kwh -> elapsedDays?.takeIf { it > 0.0 }?.let { kwh / it } }
        val blocked = (delta as? MeterDelta.Undecidable)?.reason
        val candidates = (delta as? MeterDelta.Undecidable)?.candidates.orEmpty()
        // An undetermined consumption the screen can ask about is not abnormal,
        // it is unresolved, and the chooser below is the answer to it. Flagging
        // it as abnormal too would stack a warning banner on top of the question
        // being asked, which is how the old screen ended up saying "no podemos
        // saber" and then offering nothing.
        val abnormal = measured == 0 || (average ?: 0.0) > IMPLAUSIBLE_DAILY_KWH ||
            (measured == null && candidates.isEmpty())
        val warning = when {
            candidates.isNotEmpty() -> null
            blocked != null -> when (blocked) {
                MeterDelta.Reason.WENT_BACKWARDS ->
                    "Esta lectura es menor que la anterior. Puede ser que tu medidor haya dado la vuelta al pasar de su número máximo, que la hayas capturado mal, o que hayan cambiado el medidor. No podemos saber cuál sin que nos digas."
                MeterDelta.Reason.CAPACITY_UNKNOWN ->
                    "Esta lectura es menor que la anterior. Para saber si tu medidor dio la vuelta necesitamos cuántos dígitos muestra. Dínoslo en Perfil, en “Cómo se lee tu medidor”, y volvemos a preguntarte qué pasó."
                MeterDelta.Reason.READING_EXCEEDS_CAPACITY ->
                    "Esta lectura tiene más dígitos de los que tiene tu medidor. Revisa el número, o corrige los dígitos en Perfil, en “Cómo se lee tu medidor”."
                MeterDelta.Reason.WRAP_POSSIBLE ->
                    "Pasó suficiente tiempo desde tu última lectura como para que el medidor haya dado la vuelta. Si lo hizo, el consumo real es mayor que el que muestra la resta."
                MeterDelta.Reason.RATE_IMPLAUSIBLE ->
                    "Esta lectura implicaría un consumo diario muy por encima de lo habitual en tu casa."
                MeterDelta.Reason.METER_REPLACED ->
                    "Nos dijiste que cambiaron tu medidor. Para cerrar el consumo de este tramo necesitamos la última lectura del medidor anterior y con cuánto arrancó el nuevo."
            }
            measured == 0 -> "Tu lectura no cambió desde el último registro. Puedes guardarla si es correcta."
            average != null && average > IMPLAUSIBLE_DAILY_KWH ->
                "Esta lectura implicaría ${average.roundToInt()} kWh diarios, muy por encima de tu consumo habitual."
            else -> null
        }
        ReadingConfirmUiState(
            readingKwh = reading,
            previousReadingKwh = state.lastConfirmedReadingKwh,
            consumptionSinceKwh = measured,
            averagePerDay = average,
            elapsedDays = elapsedDays,
            isAbnormal = abnormal,
            warningMessage = warning,
            method = method,
            wrapCandidates = candidates,
            attestedWraps = wraps,
            blockedReason = blocked,
            meterDigitCount = MeterProfile.digitCount(state.meterType, state.meterDigitCount),
            meterReplacement = replacement,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingConfirmUiState())

    fun attestWraps(wraps: Int) {
        attestedWraps.value = wraps
        // A wrap and a swap are different answers to the same question, so
        // picking one has to drop the other rather than leave both on record.
        meterReplacement.value = null
    }

    fun declareMeterReplacement(replacement: MeterReplacement) {
        meterReplacement.value = replacement
        attestedWraps.value = null
    }

    fun clearAttestation() {
        attestedWraps.value = null
        meterReplacement.value = null
    }

    fun confirm() {
        val current = uiState.value
        repository.confirmReading(
            kwh = current.readingKwh,
            method = current.method,
            wrapsSincePrevious = current.attestedWraps,
            meterReplacement = current.meterReplacement,
        )
    }
}

@Composable
fun ReadingConfirmRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ReadingConfirmViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ReadingConfirmScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onReviewDial = { navController.navigate(Routes.ReadingDial) },
        onConfirm = {
            viewModel.confirm()
            navController.popBackStack(Routes.Home, inclusive = false)
        },
        onFix = { navController.navigate(Routes.ReadingDial) },
        onAttestWraps = viewModel::attestWraps,
        onClearAttestation = viewModel::clearAttestation,
        onDeclareMeterReplacement = viewModel::declareMeterReplacement,
    )
}

@Composable
fun ReadingConfirmScreen(
    uiState: ReadingConfirmUiState,
    onBack: () -> Unit,
    onReviewDial: () -> Unit,
    onConfirm: () -> Unit,
    onFix: () -> Unit,
    onAttestWraps: (Int) -> Unit = {},
    onClearAttestation: () -> Unit = {},
    onDeclareMeterReplacement: (MeterReplacement) -> Unit = {},
) {
    var showReplacementDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.action_register_reading), onBack = onBack) },
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
                    stringResource(R.string.reading_confirm_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    Text(
                        String.format(Locale("es", "MX"), "%,d", uiState.readingKwh),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        " " + stringResource(R.string.reading_confirm_unit_suffix),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(modifier = Modifier.padding(top = RzSpacing.lg), verticalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
                    ConfirmRow(
                        label = stringResource(R.string.reading_confirm_previous_reading),
                        value = String.format(Locale("es", "MX"), "%,d", uiState.previousReadingKwh) + " " +
                            stringResource(R.string.reading_confirm_unit_suffix),
                    )
                    ConfirmRow(
                        label = stringResource(R.string.reading_confirm_consumption_since),
                        value = uiState.consumptionSinceKwh?.let { kwh ->
                            stringResource(
                                R.string.reading_confirm_consumption_value,
                                String.format(Locale("es", "MX"), "%,d", kwh),
                            )
                        } ?: UNKNOWN_VALUE,
                    )
                    ConfirmRow(
                        label = stringResource(R.string.reading_confirm_elapsed_time),
                        value = formatElapsed(uiState.elapsedDays),
                    )
                    ConfirmRow(
                        label = stringResource(R.string.reading_confirm_daily_average),
                        value = uiState.averagePerDay?.let { average ->
                            stringResource(
                                R.string.reading_confirm_daily_average_value,
                                formatOneDecimal(average),
                            )
                        } ?: UNKNOWN_VALUE,
                    )
                }
            }

            // The declared states have to keep the card on screen. Declaring a
            // meter swap empties wrapCandidates -- MeterDelta stops offering wrap
            // readings once it knows the registers differ -- so without these two
            // the card vanished the moment the household answered, taking with it
            // both the confirmation of what they said and the way to change it.
            if (uiState.wrapCandidates.isNotEmpty() ||
                uiState.restsOnAttestation ||
                uiState.replacementResolved ||
                uiState.replacementPending
            ) {
                WrapAttestationCard(
                    uiState = uiState,
                    onAttest = onAttestWraps,
                    onClear = onClearAttestation,
                    onFix = onFix,
                    onDeclareReplacement = { showReplacementDialog = true },
                )
            }

            if (uiState.isAbnormal && uiState.warningMessage != null) {
                RzInlineBanner(
                    text = uiState.warningMessage,
                    tone = RzBannerTone.WARNING,
                    icon = RzIcons.ErrorInfo,
                )
                Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_confirm_review_dial),
                        onClick = onReviewDial,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RzSecondaryButton(
                        text = stringResource(R.string.reading_confirm_save_anyway),
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            RzPrimaryButton(
                text = stringResource(R.string.reading_confirm_button),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
            RzSecondaryButton(
                text = stringResource(R.string.reading_confirm_fix_button),
                onClick = onFix,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.reading_confirm_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    if (showReplacementDialog) {
        MeterReplacementDialog(
            previousReadingKwh = uiState.previousReadingKwh,
            currentReadingKwh = uiState.readingKwh,
            onDismiss = { showReplacementDialog = false },
            onConfirm = { replacement ->
                onDeclareMeterReplacement(replacement)
                showReplacementDialog = false
            },
        )
    }
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatOneDecimal(value: Double): String = String.format(Locale("es", "MX"), "%.1f", value)

/** Grouped kWh magnitude, e.g. `10,022`. */
private fun formatKwh(kwh: Int): String = String.format(Locale("es", "MX"), "%,d", kwh)

/**
 * Elapsed time as the readings establish it, or the unknown marker.
 *
 * Under a day it is shown in hours, because "0.2 días" is a worse way of saying
 * "hace cinco horas" and the number is about to be divided into a rate.
 */
@Composable
private fun formatElapsed(days: Double?): String {
    if (days == null) return UNKNOWN_VALUE
    if (days >= 1.0) return stringResource(R.string.reading_confirm_elapsed_time_days, formatOneDecimal(days))
    val hours = (days * 24).roundToInt()
    // Rounding to zero hours is a real outcome -- two readings minutes apart --
    // and "0 horas" reads like a failure rather than like "just now".
    if (hours < 1) return stringResource(R.string.reading_confirm_elapsed_time_minutes)
    return pluralStringResource(R.plurals.reading_confirm_elapsed_time_hours, hours, hours)
}

/**
 * The question the app used to state and then refuse to resolve.
 *
 * [MeterDelta] already enumerates every reading of events the arithmetic allows
 * and what each would imply. All that was missing was showing them and letting
 * the household say which one happened -- their answer is the only thing that
 * can settle it, and it is recorded as testimony rather than folded into an
 * average of the possibilities.
 */
@Composable
private fun WrapAttestationCard(
    uiState: ReadingConfirmUiState,
    onAttest: (Int) -> Unit,
    onClear: () -> Unit,
    onFix: () -> Unit,
    onDeclareReplacement: () -> Unit,
) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.reading_confirm_wrap_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (uiState.replacementResolved || uiState.replacementPending) {
            Text(
                stringResource(
                    if (uiState.replacementResolved) R.string.reading_confirm_swap_chosen
                    else R.string.reading_confirm_swap_pending,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.replacementResolved) {
                    MaterialTheme.colorScheme.onSurface
                } else RzTheme.extendedColors.warningText,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            RzSecondaryButton(
                text = stringResource(R.string.reading_confirm_wrap_change),
                onClick = onClear,
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
            )
            return@RzCard
        }
        if (uiState.restsOnAttestation) {
            Text(
                stringResource(R.string.reading_confirm_wrap_chosen, wrapLabel(uiState.attestedWraps ?: 0)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            Text(
                stringResource(R.string.reading_confirm_wrap_attested_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            RzSecondaryButton(
                text = stringResource(R.string.reading_confirm_wrap_change),
                onClick = onClear,
                modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
            )
            return@RzCard
        }

        Text(
            text = if (uiState.blockedReason == MeterDelta.Reason.WRAP_POSSIBLE) {
                stringResource(R.string.reading_confirm_wrap_body_possible)
            } else {
                val digits = uiState.meterDigitCount ?: 0
                stringResource(
                    R.string.reading_confirm_wrap_body_backwards,
                    String.format(Locale("es", "MX"), "%,d", uiState.readingKwh),
                    String.format(Locale("es", "MX"), "%,d", uiState.previousReadingKwh),
                    digits,
                    "9".repeat(digits),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            modifier = Modifier.padding(top = RzSpacing.md),
        ) {
            uiState.wrapCandidates.forEach { candidate ->
                RzSecondaryButton(
                    text = wrapLabel(candidate.wraps) + " · " + candidateDetail(candidate),
                    onClick = { onAttest(candidate.wraps) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // The remaining two things a backwards reading can mean. Both are
            // answerable now: the number is wrong, or the meter is a different
            // physical device than the one the history was built on. That second
            // button used to be a sentence telling the household to report it
            // from Ayuda, which is a dead end dressed as an answer.
            RzSecondaryButton(
                text = stringResource(R.string.reading_confirm_wrap_misread),
                onClick = onFix,
                modifier = Modifier.fillMaxWidth(),
            )
            RzSecondaryButton(
                text = stringResource(R.string.reading_confirm_wrap_swap),
                onClick = onDeclareReplacement,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Collects the two readings that bridge a meter swap.
 *
 * Saving without them is deliberately allowed. A household told on the doorstep
 * that their meter is being changed frequently never sees the final reading of
 * the old one, and blocking until they do would leave them unable to record the
 * swap at all -- which is the state this whole change exists to end. Marking the
 * boundary alone already stops every later reading from being compared against a
 * register that no longer exists; only the one interval spanning the swap stays
 * undetermined, and it says so.
 */
@Composable
private fun MeterReplacementDialog(
    previousReadingKwh: Int,
    currentReadingKwh: Int,
    onDismiss: () -> Unit,
    onConfirm: (MeterReplacement) -> Unit,
) {
    var oldFinal by remember { mutableStateOf("") }
    var newStart by remember { mutableStateOf("") }
    val oldFinalValue = oldFinal.toIntOrNull()
    val newStartValue = newStart.toIntOrNull()
    val onRemoved = oldFinalValue?.minus(previousReadingKwh)
    val onInstalled = newStartValue?.let { currentReadingKwh - it }
    val bothGiven = onRemoved != null && onInstalled != null
    val negative = bothGiven && (onRemoved!! < 0 || onInstalled!! < 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reading_confirm_swap_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                Text(
                    stringResource(R.string.reading_confirm_swap_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = oldFinal,
                    onValueChange = { oldFinal = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.reading_confirm_swap_old_final)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.reading_confirm_swap_old_final_help,
                                formatKwh(previousReadingKwh),
                            ),
                        )
                    },
                    isError = onRemoved != null && onRemoved!! < 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = newStart,
                    onValueChange = { newStart = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.reading_confirm_swap_new_start)) },
                    supportingText = { Text(stringResource(R.string.reading_confirm_swap_new_start_help)) },
                    isError = onInstalled != null && onInstalled!! < 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                // The arithmetic shown before it is committed, with the two
                // halves named: a household that mistyped one of them can only
                // tell which by seeing which half came out wrong.
                if (negative) {
                    Text(
                        stringResource(R.string.reading_confirm_swap_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = RzTheme.extendedColors.warningText,
                    )
                } else if (bothGiven) {
                    Text(
                        stringResource(
                            R.string.reading_confirm_swap_preview,
                            formatKwh(onRemoved!! + onInstalled!!),
                            formatKwh(onRemoved!!),
                            formatKwh(onInstalled!!),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bothGiven && !negative,
                onClick = { onConfirm(MeterReplacement(oldFinalValue, newStartValue)) },
            ) { Text(stringResource(R.string.reading_confirm_swap_save)) }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(MeterReplacement()) }) {
                Text(stringResource(R.string.reading_confirm_swap_skip))
            }
        },
    )
}

@Composable
private fun wrapLabel(wraps: Int): String = when (wraps) {
    0 -> stringResource(R.string.reading_confirm_wrap_option_none)
    1 -> stringResource(R.string.reading_confirm_wrap_option_one)
    else -> pluralStringResource(R.plurals.reading_confirm_wrap_option_many, wraps, wraps)
}

/**
 * What a candidate would imply. The daily rate is dropped when the interval is
 * too short to establish one -- [MeterDelta] reports 0.0 there, and printing
 * "0.0 kWh/día" next to a real kWh figure would read as a measurement.
 */
@Composable
private fun candidateDetail(candidate: MeterDelta.Candidate): String {
    val kwh = String.format(Locale("es", "MX"), "%,d", candidate.kwh)
    return if (candidate.dailyKwh > 0.0) {
        stringResource(R.string.reading_confirm_wrap_option_detail, kwh, formatOneDecimal(candidate.dailyKwh))
    } else {
        stringResource(R.string.reading_confirm_wrap_option_detail_no_rate, kwh)
    }
}

@Preview(showBackground = true)
@Composable
private fun ReadingConfirmScreenPreview() {
    ReciboZenTheme {
        ReadingConfirmScreen(
            uiState = ReadingConfirmUiState(
                readingKwh = 18426,
                previousReadingKwh = 18397,
                consumptionSinceKwh = 29,
                averagePerDay = 9.1,
                isAbnormal = true,
                warningMessage = "Esta lectura implicaría 9 kWh diarios, muy por encima de tu promedio de 8.3.",
            ),
            onBack = {},
            onReviewDial = {},
            onConfirm = {},
            onFix = {},
        )
    }
}
