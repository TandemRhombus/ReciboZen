package app.recibozen.android.feature.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.core.ai.AiClient
import app.recibozen.android.core.calculation.DacRiskCalculator
import app.recibozen.android.core.ai.AiOutcome
import app.recibozen.android.core.ai.aiSavingsRequest
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.security.PremiumAccessStore
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.rzViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.recibozen.android.R
import app.recibozen.android.core.ai.SavingsAction
import app.recibozen.android.core.ai.SavingsActionKind
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.feature.appliances.AiFailureReason
import kotlin.math.roundToInt

/**
 * The savings plan, as things the household can change on the appliances it
 * already owns.
 *
 * Every peso figure on this screen is computed here from the app's own kWh, not
 * received: the server answers in percentages of one appliance's consumption and
 * never sees a tariff. That division is deliberate — a model doing arithmetic on
 * CFE's tiers is wrong in a way that sounds right.
 */
sealed interface SavingsAdviceUiState {
    data object Idle : SavingsAdviceUiState

    data object Loading : SavingsAdviceUiState

    data class Ready(
        val summary: String,
        val items: List<SavingsAdviceItem>,
    ) : SavingsAdviceUiState

    data object NeedsPremium : SavingsAdviceUiState

    data class Failed(val reason: AiFailureReason) : SavingsAdviceUiState

    /** Nothing measured yet, so there is nothing honest to advise about. */
    data object NothingToAdviseOn : SavingsAdviceUiState
}

/** One action, already resolved against the appliance it belongs to. */
data class SavingsAdviceItem(
    val action: SavingsAction,
    val applianceName: String,
    val applianceKwh: Int,
    /** kWh this action would save over the period, from our own figure. */
    val savedKwh: Double,
)

/**
 * Resolves each action against the appliance it points at and computes what it
 * saves in kWh.
 *
 * An action whose index does not resolve is dropped rather than guessed at:
 * attaching advice to the wrong appliance is worse than showing one less tip.
 */
fun savingsAdviceItems(actions: List<SavingsAction>, sent: List<Appliance>): List<SavingsAdviceItem> =
    actions.mapNotNull { action ->
        val appliance = sent.getOrNull(action.applianceIndex) ?: return@mapNotNull null
        SavingsAdviceItem(
            action = action,
            applianceName = appliance.name,
            applianceKwh = appliance.kwh,
            savedKwh = appliance.kwh * action.estimatedReductionPercent / 100.0,
        )
    }

@Composable
fun SavingsAdviceContent(state: SavingsAdviceUiState, modifier: Modifier = Modifier) {
    when (state) {
        SavingsAdviceUiState.Idle, SavingsAdviceUiState.Loading ->
            CircularProgressIndicator(modifier = modifier)

        SavingsAdviceUiState.NeedsPremium ->
            Text(stringResource(R.string.savings_ai_needs_premium), modifier = modifier)

        SavingsAdviceUiState.NothingToAdviseOn ->
            Text(stringResource(R.string.savings_ai_nothing_measured), modifier = modifier)

        is SavingsAdviceUiState.Failed ->
            Text(failureText(state.reason), modifier = modifier)

        is SavingsAdviceUiState.Ready -> LazyColumn(modifier = modifier.fillMaxWidth()) {
            item {
                Text(
                    state.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = RzSpacing.sm),
                )
            }
            items(state.items) { item -> SavingsAdviceCard(item) }
            item {
                Text(
                    stringResource(R.string.savings_ai_provenance_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
            }
        }
    }
}

@Composable
private fun SavingsAdviceCard(item: SavingsAdviceItem) {
    RzCard(modifier = Modifier.fillMaxWidth().padding(bottom = RzSpacing.sm)) {
        Column {
            Text(
                stringResource(kindLabel(item.action.kind)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(item.action.title, style = MaterialTheme.typography.titleSmall)
            Text(
                item.applianceName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                item.action.howTo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
            // The figure comes from the server's own verified table, never from
            // the model, and it is flagged when it is a health limit.
            item.action.targetSetting?.let { target ->
                Text(
                    stringResource(
                        if (target.isSafetyLimit) R.string.savings_ai_target_safety
                        else R.string.savings_ai_target,
                        target.text,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (target.isSafetyLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
            }
            Text(
                stringResource(
                    R.string.savings_ai_saving,
                    item.savedKwh.roundToInt(),
                    item.action.estimatedReductionPercent.roundToInt(),
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
        }
    }
}

private fun kindLabel(kind: SavingsActionKind): Int = when (kind) {
    SavingsActionKind.SETTING -> R.string.savings_ai_kind_setting
    SavingsActionKind.MAINTENANCE -> R.string.savings_ai_kind_maintenance
    SavingsActionKind.HABIT -> R.string.savings_ai_kind_habit
}

@Composable
private fun failureText(reason: AiFailureReason): String = when (reason) {
    is AiFailureReason.QuotaExhausted -> stringResource(R.string.savings_ai_error_quota, reason.limit)
    is AiFailureReason.TooFast -> stringResource(R.string.savings_ai_error_too_fast, reason.retryAfterSeconds)
    AiFailureReason.NotRecognized -> stringResource(R.string.savings_ai_error_no_plan)
    AiFailureReason.Unavailable -> stringResource(R.string.savings_ai_error_unavailable)
}

/**
 * Builds the request from the household's own numbers, asks once, and resolves
 * the answer back onto the appliances that were sent.
 */
class SavingsAdviceViewModel(
    private val repository: ReciboZenRepository,
    private val client: AiClient,
) : ViewModel() {

    private val mutableState = MutableStateFlow<SavingsAdviceUiState>(SavingsAdviceUiState.Idle)
    val state: StateFlow<SavingsAdviceUiState> = mutableState.asStateFlow()

    fun load() {
        if (mutableState.value is SavingsAdviceUiState.Loading) return
        val session = repository.session.value
        val energy = repository.energySummary(session)
        val request = aiSavingsRequest(
            appliances = repository.appliances(session),
            periodDays = energy.billingDays,
            kwhProjected = energy.applianceProjectedKwh.takeIf { it > 0 } ?: energy.projectedKwh,
            dacRisk = DacRiskCalculator.calculate(
                repository.importedBillHistory.value,
                session.tariff,
                energy.projectedKwh.takeIf { it > 0 },
                energy.billingDays.coerceAtLeast(0),
                domesticReferenceTariffCode = session.dacReferenceTariffsByService[
                    session.activeServiceId.takeIf { it.isNotBlank() } ?: "current",
                ],
            ).level,
        )
        // Nothing measured means nothing honest to advise about, and asking
        // would spend a unit of the allowance to be told so.
        if (!request.isUsable) {
            mutableState.value = SavingsAdviceUiState.NothingToAdviseOn
            return
        }

        mutableState.value = SavingsAdviceUiState.Loading
        viewModelScope.launch {
            val outcome = client.suggestSavings(
                appliances = request.appliances,
                periodDays = request.periodDays,
                kwhProjected = request.kwhProjected,
                dacRisk = request.dacRisk,
            )
            mutableState.value = when (outcome) {
                is AiOutcome.Success -> {
                    val items = savingsAdviceItems(outcome.value.actions, request.sent)
                    if (items.isEmpty()) {
                        SavingsAdviceUiState.Failed(AiFailureReason.NotRecognized)
                    } else {
                        SavingsAdviceUiState.Ready(outcome.value.summary, items)
                    }
                }
                AiOutcome.NotEntitled -> SavingsAdviceUiState.NeedsPremium
                is AiOutcome.QuotaExhausted -> SavingsAdviceUiState.Failed(
                    AiFailureReason.QuotaExhausted(outcome.limit, outcome.resetsAtEpochSeconds),
                )
                is AiOutcome.TooFast ->
                    SavingsAdviceUiState.Failed(AiFailureReason.TooFast(outcome.retryAfterSeconds))
                AiOutcome.CouldNotEstimate -> SavingsAdviceUiState.Failed(AiFailureReason.NotRecognized)
                is AiOutcome.InvalidRequest,
                AiOutcome.TemporarilyUnavailable,
                AiOutcome.Unavailable -> SavingsAdviceUiState.Failed(AiFailureReason.Unavailable)
            }
        }
    }
}

@Composable
fun SavingsAdviceRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val context = LocalContext.current
    val viewModel = rzViewModel {
        val accessStore = PremiumAccessStore(context)
        SavingsAdviceViewModel(repository, AiClient(signedLease = { accessStore.signedLease() }))
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // One call per visit. A plan does not change between two taps, and every
    // repeat spends a unit of a thirty-a-month allowance.
    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = { RzFlowTopBar(title = stringResource(R.string.savings_ai_title), onBack = { navController.popBackStack() }) },
    ) { padding ->
        SavingsAdviceContent(
            state = state,
            modifier = Modifier.fillMaxSize().padding(padding).padding(RzSpacing.md),
        )
    }
}
