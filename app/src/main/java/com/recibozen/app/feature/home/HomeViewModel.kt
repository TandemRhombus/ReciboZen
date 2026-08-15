package com.recibozen.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.core.calculation.DacRiskCalculator
import com.recibozen.app.core.calculation.BillingPeriodTimeline
import com.recibozen.app.core.calculation.ConsumptionPaceCalculator
import com.recibozen.app.demo.repository.EnergyForecastBasis
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(private val repository: ReciboZenRepository) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        repository.tariffCalibration,
    ) { state, importedBills, _ ->
            val projection = repository.billProjection(state)
            val energy = repository.energySummary(state)
            val appliances = repository.appliances(state)
            val latestBill = importedBills.lastOrNull()
            val latestBillEndMillis = latestBill?.periodEndEpochDay?.times(86_400_000L)
            val hasReadingAfterBill = latestBillEndMillis != null && state.readings.any { reading ->
                reading.method != com.recibozen.app.core.model.ReadingMethod.PDF &&
                    (reading.timestampEpochMillis ?: Long.MIN_VALUE) > latestBillEndMillis
            }
            val dataMode = when {
                latestBill != null && !hasReadingAfterBill -> HomeDataMode.BILL_REFERENCE
                energy.forecastBasis == EnergyForecastBasis.APPLIANCES -> HomeDataMode.APPLIANCE_FORECAST
                projection.hasUsageEvidence -> HomeDataMode.PROJECTION
                else -> HomeDataMode.EMPTY
            }
            val minisplit = appliances.find { it.id == "minisplit" }
            val refrigerador = appliances.find { it.id == "refrigerador" }
            val ps5 = appliances.find { it.id == "ps5" }
            val minisplitAction = state.planActions.find { it.id == DemoReciboZenData.MINISPLIT_ACTION_ID }
            val liveReadings = state.readings.count { it.method != ReadingMethod.PDF }
            val currentPeriod = BillingPeriodTimeline.after(latestBill, energy.billingDays)
            val paceGoal = ConsumptionPaceCalculator.goal(
                goalMxn = state.goalMxn,
                observedKwh = energy.observedKwh,
                daysRemaining = energy.daysRemaining,
                estimateCostMxn = repository::estimateCostMxn,
            )

            HomeUiState(
                userName = state.userName,
                householdName = state.householdName,
                userInitial = state.userName.firstOrNull()?.uppercase() ?: "?",
                profileConfigured = state.userName.isNotBlank() && state.householdName.isNotBlank(),
                dataMode = dataMode,
                periodLabel = latestBill?.monthLabel.orEmpty(),
                currentPeriodLabel = currentPeriod?.shortLabel.orEmpty(),
                currentPeriodDateRange = currentPeriod?.dateRangeLabel.orEmpty(),
                projectedMxn = projection.projectedMxn,
                rangeLowMxn = projection.rangeLowMxn,
                rangeHighMxn = projection.rangeHighMxn,
                isWithinGoal = projection.isWithinGoal,
                goalMxn = state.goalMxn,
                daysRemaining = energy.daysRemaining,
                observedTodayKwh = energy.observedKwh,
                observedDays = energy.observedDays,
                latestReadingDateLabel = state.readings
                    .filter { it.method != ReadingMethod.PDF }
                    .maxByOrNull { it.timestampEpochMillis ?: Long.MIN_VALUE }
                    ?.dateLabel.orEmpty(),
                latestBillMxn = latestBill?.amountMxn ?: 0,
                latestBillKwh = latestBill?.consumptionKwh ?: 0,
                importedBillCount = importedBills.size,
                realBillHistory = importedBills.takeLast(12),
                billingDays = energy.billingDays,
                projectedKwh = energy.projectedKwh,
                goalKwh = paceGoal.goalKwh,
                applianceProjectedKwh = energy.applianceProjectedKwh,
                applianceProjectedMxn = energy.applianceProjectedCostMxn,
                applianceCoveragePercent = if (energy.meterProjectedKwh > 0) {
                    (energy.applianceProjectedKwh * 100f / energy.meterProjectedKwh)
                        .toInt()
                        .coerceIn(0, 100)
                } else 0,
                hasCurrentMeterProjection = energy.hasCurrentMeterProjection,
                dailyRateNow = energy.dailyAverageKwh,
                dailyRateRecommended = paceGoal.recommendedDailyKwh,
                remainingGoalKwh = paceGoal.remainingKwh,
                paceRelation = ConsumptionPaceCalculator.relation(
                    energy.dailyAverageKwh,
                    paceGoal.recommendedDailyKwh,
                ),
                hasAppliances = appliances.isNotEmpty(),
                applianceCount = appliances.size,
                confidenceLevel = when {
                    dataMode == HomeDataMode.BILL_REFERENCE && importedBills.size >= 6 -> ConfidenceLevel.HIGH
                    dataMode == HomeDataMode.PROJECTION && liveReadings >= 2 -> ConfidenceLevel.HIGH
                    dataMode == HomeDataMode.APPLIANCE_FORECAST && appliances.all { it.hoursPerWeek != null || it.kwh > 0 } -> ConfidenceLevel.MEDIUM
                    importedBills.isNotEmpty() || liveReadings > 0 -> ConfidenceLevel.MEDIUM
                    else -> ConfidenceLevel.LOW
                },
                minisplitStatus = minisplitAction?.status ?: PlanActionStatus.SUGGESTED,
                minisplitSavingsLabel = minisplitAction?.savingsRangeLabel.orEmpty(),
                minisplitCostMxn = minisplit?.costMxn ?: 0,
                minisplitSharePercent = minisplit?.sharePercent ?: 0,
                refrigeradorCostMxn = refrigerador?.costMxn ?: 0,
                refrigeradorSharePercent = refrigerador?.sharePercent ?: 0,
                ps5CostMxn = ps5?.costMxn ?: 0,
                ps5SharePercent = ps5?.sharePercent ?: 0,
                recentReadingsCountLabel = "$liveReadings lecturas posteriores al recibo",
                previousBillsLoadedCount = importedBills.size,
                missingApplianceSchedules = appliances.count { it.hoursPerWeek == null },
                dacRisk = DacRiskCalculator.calculate(
                    history = importedBills,
                    tariffCode = state.tariff,
                    projectedNextBillKwh = energy.projectedKwh.takeIf { hasReadingAfterBill && it > 0 },
                    projectedBillingDays = energy.billingDays.takeIf { hasReadingAfterBill && it > 0 } ?: 0,
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun onToggleMinisplitAction() {
        repository.togglePlanAction(DemoReciboZenData.MINISPLIT_ACTION_ID)
    }
}
