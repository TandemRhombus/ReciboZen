package app.recibozen.android.feature.appliances

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recibozen.android.core.ai.AiApplianceFill
import app.recibozen.android.core.ai.AiClient
import app.recibozen.android.core.ai.AiOutcome
import app.recibozen.android.core.ai.ApplianceEnergySuggestion
import app.recibozen.android.core.ai.aiApplianceFill
import app.recibozen.android.core.model.ApplianceCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state of asking the AI about one appliance.
 *
 * [Ready] exists because the answer is never written straight into the form. A
 * modelled figure that appears in a field looks exactly like a figure read off a
 * label, and telling those apart is the entire reason the evidence schema was
 * built before the AI was. The household sees what arrived, and what the model
 * admitted it did not know, before any of it becomes theirs.
 */
sealed interface AiSuggestionUiState {
    data object Idle : AiSuggestionUiState

    data object Loading : AiSuggestionUiState

    data class Ready(
        val suggestion: ApplianceEnergySuggestion,
        val fill: AiApplianceFill,
    ) : AiSuggestionUiState

    /** No lease: premium is not active. The screen sends them to the paywall. */
    data object NeedsPremium : AiSuggestionUiState

    data class Failed(val reason: AiFailureReason) : AiSuggestionUiState
}

/**
 * Why an attempt ended, kept apart because the household should do something
 * different in each case: wait, fix the model number, or stop for the month.
 */
sealed interface AiFailureReason {
    data class QuotaExhausted(val limit: Int, val resetsAtEpochSeconds: Long) : AiFailureReason

    data class TooFast(val retryAfterSeconds: Int) : AiFailureReason

    data object NotRecognized : AiFailureReason

    data object Unavailable : AiFailureReason
}

/**
 * Turns an outcome into what the screen shows. Pure on purpose: this is the part
 * worth testing, and a test for it should not need a dispatcher.
 */
fun aiSuggestionStateFor(
    outcome: AiOutcome<ApplianceEnergySuggestion>,
    billingDays: Int,
): AiSuggestionUiState = when (outcome) {
    is AiOutcome.Success -> {
        val fill = aiApplianceFill(outcome.value.evidence, billingDays)
        // An answer with nothing this form can hold is not a success to show
        // off. It is treated as "not recognised", which is what it means to the
        // household, and their allowance was already spent either way.
        if (fill.hasSomethingToApply) {
            AiSuggestionUiState.Ready(outcome.value, fill)
        } else {
            AiSuggestionUiState.Failed(AiFailureReason.NotRecognized)
        }
    }
    AiOutcome.NotEntitled -> AiSuggestionUiState.NeedsPremium
    is AiOutcome.QuotaExhausted ->
        AiSuggestionUiState.Failed(AiFailureReason.QuotaExhausted(outcome.limit, outcome.resetsAtEpochSeconds))
    is AiOutcome.TooFast -> AiSuggestionUiState.Failed(AiFailureReason.TooFast(outcome.retryAfterSeconds))
    AiOutcome.CouldNotEstimate -> AiSuggestionUiState.Failed(AiFailureReason.NotRecognized)
    // An invalid request is our bug, not theirs, and it cost them nothing.
    // There is nothing useful to say beyond "it did not work".
    is AiOutcome.InvalidRequest,
    AiOutcome.TemporarilyUnavailable,
    AiOutcome.Unavailable -> AiSuggestionUiState.Failed(AiFailureReason.Unavailable)
}

class ApplianceAiSuggestionViewModel(
    private val client: AiClient,
) : ViewModel() {

    private val mutableState = MutableStateFlow<AiSuggestionUiState>(AiSuggestionUiState.Idle)
    val state: StateFlow<AiSuggestionUiState> = mutableState.asStateFlow()

    /**
     * [billingDays] is passed per call rather than captured at construction:
     * the period length changes when a reading lands, and a stale value would
     * scale an annual figure into the wrong period.
     */
    fun request(brand: String, model: String, category: ApplianceCategory, billingDays: Int) {
        val cleanBrand = brand.trim()
        val cleanModel = model.trim()
        // Asking with an empty field would spend a unit of the household's
        // monthly allowance to be told what we already knew.
        if (cleanBrand.isEmpty() || cleanModel.isEmpty()) return
        if (mutableState.value is AiSuggestionUiState.Loading) return

        mutableState.value = AiSuggestionUiState.Loading
        viewModelScope.launch {
            val outcome = client.identifyAppliance(cleanBrand, cleanModel, category)
            mutableState.value = aiSuggestionStateFor(outcome, billingDays)
        }
    }

    fun dismiss() {
        mutableState.value = AiSuggestionUiState.Idle
    }
}
