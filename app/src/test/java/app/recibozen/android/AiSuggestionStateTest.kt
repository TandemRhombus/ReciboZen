package app.recibozen.android

import app.recibozen.android.core.ai.AiOutcome
import app.recibozen.android.core.ai.ApplianceEnergySuggestion
import app.recibozen.android.core.model.DeviceEvidence
import app.recibozen.android.core.model.EvidenceMethodology
import app.recibozen.android.core.model.EvidenceMetric
import app.recibozen.android.core.model.EvidenceSource
import app.recibozen.android.feature.appliances.AiFailureReason
import app.recibozen.android.feature.appliances.AiSuggestionUiState
import app.recibozen.android.feature.appliances.aiSuggestionStateFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun evidence(metric: EvidenceMetric, value: Double) = DeviceEvidence(
    metric = metric,
    value = value,
    source = EvidenceSource.AI_SUGGESTION,
    methodology = EvidenceMethodology.MODELED,
)

private fun success(vararg items: DeviceEvidence) = AiOutcome.Success(
    ApplianceEnergySuggestion(
        recognized = false,
        displayName = "Refrigerador Mabe",
        assumption = "Estimado por comparables.",
        marketMexicoVerified = true,
        evidence = items.toList(),
        quota = null,
    ),
)

class AiSuggestionStateTest {

    @Test
    fun aUsableAnswerWaitsForConfirmationInsteadOfWritingItself() {
        val state = aiSuggestionStateFor(success(evidence(EvidenceMetric.ACTIVE_WATTS, 150.0)), billingDays = 60)

        assertTrue(state is AiSuggestionUiState.Ready)
        val ready = state as AiSuggestionUiState.Ready
        assertEquals(150, ready.fill.powerWatts)
        assertEquals("Refrigerador Mabe", ready.suggestion.displayName)
    }

    @Test
    fun anAnswerThisFormCannotHoldReadsAsNotRecognized() {
        // A per-cycle figure alone cannot fill anything, so presenting it as a
        // successful lookup would be a dialog with nothing to accept.
        val state = aiSuggestionStateFor(success(evidence(EvidenceMetric.KWH_PER_CYCLE, 0.8)), billingDays = 60)

        assertEquals(AiSuggestionUiState.Failed(AiFailureReason.NotRecognized), state)
    }

    @Test
    fun anEmptyAnswerAlsoReadsAsNotRecognized() {
        assertEquals(
            AiSuggestionUiState.Failed(AiFailureReason.NotRecognized),
            aiSuggestionStateFor(success(), billingDays = 60),
        )
    }

    @Test
    fun noLeaseSendsThemToThePaywallRatherThanShowingAnError() {
        assertEquals(AiSuggestionUiState.NeedsPremium, aiSuggestionStateFor(AiOutcome.NotEntitled, 60))
    }

    @Test
    fun anExhaustedQuotaKeepsTheDateItComesBack() {
        val state = aiSuggestionStateFor(
            AiOutcome.QuotaExhausted(used = 150, limit = 150, resetsAtEpochSeconds = 1_788_220_800L),
            billingDays = 60,
        )
        val reason = (state as AiSuggestionUiState.Failed).reason as AiFailureReason.QuotaExhausted
        assertEquals(150, reason.limit)
        assertEquals(1_788_220_800L, reason.resetsAtEpochSeconds)
    }

    @Test
    fun goingTooFastKeepsTheSecondsToWait() {
        val state = aiSuggestionStateFor(AiOutcome.TooFast(retryAfterSeconds = 41), billingDays = 60)
        val reason = (state as AiSuggestionUiState.Failed).reason as AiFailureReason.TooFast
        assertEquals(41, reason.retryAfterSeconds)
    }

    @Test
    fun ourOwnBugIsNotDressedUpAsSomethingTheHouseholdDidWrong() {
        for (outcome in listOf(
            AiOutcome.InvalidRequest("brand"),
            AiOutcome.TemporarilyUnavailable,
            AiOutcome.Unavailable,
        )) {
            assertEquals(
                "$outcome",
                AiSuggestionUiState.Failed(AiFailureReason.Unavailable),
                aiSuggestionStateFor(outcome, billingDays = 60),
            )
        }
  }

    @Test
    fun theAnnualFigureIsScaledUsingTheRealPeriodBeforeItIsOffered() {
        val state = aiSuggestionStateFor(success(evidence(EvidenceMetric.ANNUAL_KWH, 730.0)), billingDays = 30)
        val ready = state as AiSuggestionUiState.Ready
        assertEquals(60.0, ready.fill.unitKwh!!, 0.001)
    }
}
