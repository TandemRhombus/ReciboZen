package app.recibozen.android.feature.appliances

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.recibozen.android.R
import app.recibozen.android.core.ai.AiApplianceFill
import app.recibozen.android.core.ai.UnappliedReason
import app.recibozen.android.core.designsystem.RzSpacing
import java.util.Locale

/**
 * Shows what the AI answered and waits.
 *
 * The values are never written straight into the form. A modelled figure sitting
 * in a field is indistinguishable from one read off a label, and telling those
 * two apart is the reason the evidence schema exists. So the household sees the
 * numbers, sees what the model admitted it did not know, and decides.
 */
@Composable
fun ApplianceAiSuggestionDialog(
    state: AiSuggestionUiState,
    onApply: (AiApplianceFill) -> Unit,
    onDismiss: () -> Unit,
    onGoPremium: () -> Unit,
) {
    when (state) {
        AiSuggestionUiState.Idle -> Unit

        AiSuggestionUiState.Loading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.appliances_ai_loading)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )

        AiSuggestionUiState.NeedsPremium -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.appliances_ai_title)) },
            text = { Text(stringResource(R.string.appliances_ai_needs_premium)) },
            confirmButton = {
                TextButton(onClick = onGoPremium) { Text(stringResource(R.string.appliances_ai_go_premium)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.appliances_ai_dismiss)) }
            },
        )

        is AiSuggestionUiState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.appliances_ai_title)) },
            text = { Text(failureMessage(state.reason)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.appliances_ai_dismiss)) }
            },
        )

        is AiSuggestionUiState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(state.suggestion.displayName) },
            text = { ReadyBody(state) },
            confirmButton = {
                TextButton(onClick = { onApply(state.fill) }) {
                    Text(stringResource(R.string.appliances_ai_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.appliances_ai_dismiss)) }
            },
        )
    }
}

@Composable
private fun ReadyBody(state: AiSuggestionUiState.Ready) {
    val suggestion = state.suggestion
    val fill = state.fill
    Column(modifier = Modifier.fillMaxWidth()) {
        // What it did not know goes first, before the numbers. Reading "I did
        // not recognise this model" after already seeing a figure is reading it
        // too late.
        Text(
            stringResource(
                if (suggestion.recognized) R.string.appliances_ai_recognized
                else R.string.appliances_ai_not_recognized,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (suggestion.marketMexicoVerified == false) {
            Text(
                stringResource(R.string.appliances_ai_market_unverified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
        }
        if (suggestion.assumption.isNotBlank()) {
            Text(
                suggestion.assumption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = RzSpacing.sm))

        fill.powerWatts?.let {
            Text(
                stringResource(R.string.appliances_ai_value_watts, it),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        fill.unitKwh?.let {
            Text(
                stringResource(R.string.appliances_ai_value_kwh, String.format(Locale.US, "%.1f", it)),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        fill.standbyWatts?.let {
            Text(
                stringResource(R.string.appliances_ai_value_standby, it),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (fill.unapplied.isNotEmpty()) {
            Text(
                stringResource(R.string.appliances_ai_unapplied_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
            for (item in fill.unapplied) {
                Text(
                    stringResource(
                        when (item.reason) {
                            UnappliedReason.NAMEPLATE_IS_NOT_TYPICAL_USE -> R.string.appliances_ai_unapplied_nameplate
                            UnappliedReason.NEEDS_CYCLES_PER_PERIOD -> R.string.appliances_ai_unapplied_cycle
                            UnappliedReason.ENERGY_TOTAL_IS_MORE_RELIABLE -> R.string.appliances_ai_unapplied_total
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
            }
        }

        Text(
            stringResource(R.string.appliances_ai_provenance_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
        suggestion.quota?.let { quota ->
            Text(
                stringResource(
                    R.string.appliances_ai_quota_left,
                    (quota.limit - quota.used).coerceAtLeast(0),
                    quota.limit,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.xs),
            )
        }
    }
}

@Composable
private fun failureMessage(reason: AiFailureReason): String = when (reason) {
    is AiFailureReason.QuotaExhausted -> stringResource(R.string.appliances_ai_error_quota, reason.limit)
    is AiFailureReason.TooFast -> stringResource(R.string.appliances_ai_error_too_fast, reason.retryAfterSeconds)
    AiFailureReason.NotRecognized -> stringResource(R.string.appliances_ai_error_not_recognized)
    AiFailureReason.Unavailable -> stringResource(R.string.appliances_ai_error_unavailable)
}
