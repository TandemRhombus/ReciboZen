package com.recibozen.app.feature.period

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.core.calculation.BillingPeriodTimeline
import com.recibozen.app.core.calculation.ConsumptionPaceCalculator
import com.recibozen.app.core.model.ReadingMethod
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PeriodAnalysisViewModel(repository: ReciboZenRepository) : ViewModel() {

    val uiState: StateFlow<PeriodAnalysisUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        repository.tariffCalibration,
    ) { state, importedBills, _ ->
            val energy = repository.energySummary(state)
            val latestBill = importedBills.lastOrNull()
            val currentPeriod = BillingPeriodTimeline.after(latestBill, energy.billingDays)
            val paceGoal = ConsumptionPaceCalculator.goal(
                goalMxn = state.goalMxn,
                observedKwh = energy.observedKwh,
                daysRemaining = energy.daysRemaining,
                estimateCostMxn = repository::estimateCostMxn,
            )
            val latestLiveReading = state.readings
                .filter { it.method != ReadingMethod.PDF }
                .maxByOrNull { it.timestampEpochMillis ?: Long.MIN_VALUE }
                ?: state.readings.maxByOrNull { it.timestampEpochMillis ?: Long.MIN_VALUE }
            PeriodAnalysisUiState(
                hasUsageData = energy.hasUsageEvidence,
                lastReadingKwh = latestLiveReading?.kwh ?: 0,
                lastReadingDateLabel = latestLiveReading?.dateLabel.orEmpty(),
                observedKwh = energy.observedKwh,
                observedDays = energy.observedDays,
                billingDays = energy.billingDays,
                daysRemaining = energy.daysRemaining,
                costToDateMxn = energy.costToDateMxn,
                goalKwh = paceGoal.goalKwh,
                projectedKwh = energy.projectedKwh,
                currentPeriodLabel = currentPeriod?.shortLabel.orEmpty(),
                currentPeriodDateRange = currentPeriod?.dateRangeLabel.orEmpty(),
                previousPeriodLabel = latestBill?.monthLabel.orEmpty(),
                previousBillKwh = latestBill?.consumptionKwh ?: 0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PeriodAnalysisUiState())
}
