package app.recibozen.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.core.calculation.DacRiskCalculator
import app.recibozen.android.core.calculation.ConsumptionPaceCalculator
import app.recibozen.android.demo.repository.EnergyForecastBasis
import app.recibozen.android.demo.repository.ReciboZenRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

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
            val hasReadingAfterBill = energy.hasCurrentMeterProjection
            val dataMode = when {
                latestBill != null && !hasReadingAfterBill -> HomeDataMode.BILL_REFERENCE
                energy.forecastBasis == EnergyForecastBasis.APPLIANCES -> HomeDataMode.APPLIANCE_FORECAST
                projection.hasUsageEvidence -> HomeDataMode.PROJECTION
                else -> HomeDataMode.EMPTY
            }
            val liveReadings = state.readings.count { it.method != ReadingMethod.PDF }
            // "Qué está influyendo más" comes from the household's own inventory.
            // A household total only exists when a meter pace or a closed bill
            // provides one; otherwise there is nothing left to call "sin identificar".
            val hasHouseholdTotal = energy.forecastBasis == EnergyForecastBasis.METER ||
                energy.forecastBasis == EnergyForecastBasis.CLOSED_BILL
            val rankedAppliances = appliances
                .filter { it.kwh > 0 }
                .sortedByDescending { it.costMxn }
            val topAppliances = rankedAppliances.take(3).map {
                HomeApplianceShare(
                    id = it.id,
                    name = it.name,
                    costMxn = it.costMxn,
                    sharePercent = it.sharePercent,
                )
            }
            val explainedSharePercent = rankedAppliances.sumOf { it.sharePercent }
            val explainedCostMxn = rankedAppliances.sumOf { it.costMxn }
            val currentPeriod = energy.currentPeriod
            val applianceExpectedToDate =
                (energy.applianceProjectedKwh * energy.observedWindowFraction).roundToInt().coerceAtLeast(0)
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
                currentPeriodIsEstimated = currentPeriod?.isBeyondNextPeriod == true,
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
                applianceExpectedToDateKwh = applianceExpectedToDate,
                applianceDailyAverageKwh = if (energy.billingDays > 0) {
                    energy.applianceProjectedKwh.toDouble() / energy.billingDays
                } else 0.0,
                // Not clamped at 100: when the declared inventory adds up to
                // more than the meter measured, that gap is the useful signal
                // (usually overstated hours), and hiding it helps nobody.
                applianceCoveragePercent = if (energy.observedKwh > 0) {
                    (applianceExpectedToDate * 100f / energy.observedKwh)
                        .toInt()
                        .coerceIn(0, 999)
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
                topAppliances = topAppliances,
                hasHouseholdTotal = hasHouseholdTotal && rankedAppliances.isNotEmpty(),
                unidentifiedSharePercent = if (hasHouseholdTotal) {
                    (100 - explainedSharePercent).coerceIn(0, 100)
                } else 0,
                unidentifiedCostMxn = if (hasHouseholdTotal) {
                    (energy.projectedCostMxn - explainedCostMxn).coerceAtLeast(0)
                } else 0,
                liveReadingCount = liveReadings,
                previousBillsLoadedCount = importedBills.size,
                missingApplianceSchedules = appliances.count { it.hoursPerWeek == null },
                dacRisk = DacRiskCalculator.calculate(
                    history = importedBills,
                    tariffCode = state.tariff,
                    projectedNextBillKwh = energy.projectedKwh.takeIf { hasReadingAfterBill && it > 0 },
                    projectedBillingDays = energy.billingDays.coerceAtLeast(0),
                    domesticReferenceTariffCode = state.dacReferenceTariffsByService[
                        state.activeServiceId.takeIf { it.isNotBlank() } ?: "current"
                    ],
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
}
