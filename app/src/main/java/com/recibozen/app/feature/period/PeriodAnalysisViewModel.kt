package com.recibozen.app.feature.period

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recibozen.app.demo.repository.ReciboZenRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

class PeriodAnalysisViewModel(repository: ReciboZenRepository) : ViewModel() {

    val uiState: StateFlow<PeriodAnalysisUiState> = combine(
        repository.session,
        repository.importedBillHistory,
    ) { state, _ ->
            val energy = repository.energySummary(state)
            val goalKwh = if (state.goalMxn > 0 && energy.projectedCostMxn > 0) {
                (energy.projectedKwh * state.goalMxn.toDouble() / energy.projectedCostMxn)
                    .roundToInt()
                    .coerceAtLeast(0)
            } else 0
            val scale = maxOf(energy.observedKwh, energy.projectedKwh, goalKwh, 1)
            PeriodAnalysisUiState(
                hasUsageData = energy.hasUsageEvidence,
                lastReadingKwh = state.readings.firstOrNull()?.kwh ?: 0,
                lastReadingDateLabel = state.readings.firstOrNull()?.dateLabel.orEmpty(),
                observedKwh = energy.observedKwh,
                observedDays = energy.observedDays,
                billingDays = energy.billingDays,
                costToDateMxn = energy.costToDateMxn,
                goalKwh = goalKwh,
                projectedKwh = energy.projectedKwh,
                surplusStartKwh = (scale * 1.12).roundToInt().coerceAtLeast(scale + 1),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PeriodAnalysisUiState())
}
