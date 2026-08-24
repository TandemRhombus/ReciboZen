package app.recibozen.android.feature.period

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.core.calculation.BillChangeExplainer
import app.recibozen.android.core.calculation.BillChangeExplanation
import app.recibozen.android.core.calculation.ConsumptionPaceCalculator
import app.recibozen.android.core.calculation.TariffBlock
import app.recibozen.android.core.calculation.TariffEngine
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.core.calculation.AccumulatedConsumption
import app.recibozen.android.core.calculation.MeterProfile
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
                accumulatedKwhByDay = AccumulatedConsumption.byDay(
                    readings = state.readings,
                    register = MeterProfile.register(state.meterType, state.meterDigitCount, state.meterMultiplier),
                    periodStartEpochDay = currentPeriod?.startEpochDay ?: 0L,
                    observedDays = if (currentPeriod == null) 0 else energy.observedDays,
                    observedKwh = energy.observedKwh,
                ),
                billChange = explainLastChange(importedBills, repository.activeTariffBlocks()),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PeriodAnalysisUiState())
}

/**
 * Breaks down the difference between the last two imported bills.
 *
 * Both bills are priced with the tariff in force today, because ReciboZen does
 * not keep dated rate tables per past period. That is deliberate: any peso the
 * current rates cannot reproduce shows up under "no lo podemos explicar"
 * instead of being blamed on the household's consumption.
 */
private fun explainLastChange(bills: List<BillPeriod>, blocks: List<TariffBlock>): BillChangeExplanation? {
    val real = bills.filterNot { it.isProjected }
    if (real.size < 2) return null
    val engine = TariffEngine(blocks)
    return BillChangeExplainer.explain(
        previous = real[real.lastIndex - 1],
        current = real.last(),
        previousEngine = engine,
    )
}
