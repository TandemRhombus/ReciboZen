package com.recibozen.app.feature.period

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.core.calculation.ConsumptionPaceCalculator
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.demo.repository.EnergySummary
import com.recibozen.app.demo.repository.ReciboZenSessionState
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
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
            val currentPeriod = energy.currentPeriod
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
                accumulatedKwhByDay = accumulatedByDay(state, energy),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PeriodAnalysisUiState())
}

/**
 * Accumulated consumption per elapsed day, interpolated between the readings
 * the user actually took. Between two readings the meter only tells us the
 * total, so the segment is drawn as a straight line: honest about the fact
 * that we do not know the shape inside that gap.
 */
private fun accumulatedByDay(state: ReciboZenSessionState, energy: EnergySummary): List<Int> {
    val period = energy.currentPeriod ?: return emptyList()
    if (energy.observedDays <= 0) return emptyList()
    val points = state.readings
        .filter { it.method != ReadingMethod.PDF }
        .mapNotNull { reading ->
            val timestamp = reading.timestampEpochMillis ?: return@mapNotNull null
            val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            if (day < period.startEpochDay) null else (day - period.startEpochDay).toInt() to reading.kwh
        }
        .sortedBy { it.first }
        .distinctBy { it.first }
    if (points.size < 2) {
        // A single reading still draws a straight line from the period's start.
        return (0..energy.observedDays).map { day ->
            (energy.observedKwh.toDouble() * day / energy.observedDays).roundToInt()
        }
    }
    val base = points.first().second
    return (0..energy.observedDays).map { day ->
        val after = points.firstOrNull { it.first >= day }
        val before = points.lastOrNull { it.first <= day }
        when {
            after != null && before != null && after.first == before.first -> after.second - base
            after != null && before != null -> {
                val span = (after.first - before.first).coerceAtLeast(1)
                val progress = (day - before.first).toDouble() / span
                (before.second + (after.second - before.second) * progress).roundToInt() - base
            }
            before != null -> before.second - base
            else -> 0
        }.coerceAtLeast(0)
    }
}
