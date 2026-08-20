package app.recibozen.android.feature.home

import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.calculation.DacRiskSnapshot
import app.recibozen.android.core.calculation.ConsumptionPaceRelation

enum class HomeDataMode { EMPTY, BILL_REFERENCE, APPLIANCE_FORECAST, PROJECTION }

/** One row of "what is driving the bill", built from the user's own inventory. */
data class HomeApplianceShare(
    val id: String,
    val name: String,
    val costMxn: Int,
    val sharePercent: Int,
)

/**
 * Everything the "Inicio" tab needs to render, derived from
 * [app.recibozen.android.demo.repository.ReciboZenSessionState]. Immutable snapshot
 * consumed by the stateless [HomeScreen].
 */
data class HomeUiState(
    val userName: String = "",
    val householdName: String = "",
    val userInitial: String = "?",
    val profileConfigured: Boolean = false,
    val dataMode: HomeDataMode = HomeDataMode.EMPTY,
    /** Why this projection is not using the meter pace, when it is not. */
    val meterPaceBlockedBy: app.recibozen.android.core.calculation.MeterDelta.Reason? = null,

    val periodLabel: String = "",
    val currentPeriodLabel: String = "",
    val currentPeriodDateRange: String = "",
    /** The latest bill is old enough that whole periods have closed since. */
    val currentPeriodIsEstimated: Boolean = false,
    val projectedMxn: Int = 0,
    val rangeLowMxn: Int = 0,
    val rangeHighMxn: Int = 0,
    val isWithinGoal: Boolean = false,
    val goalMxn: Int = 0,
    val daysRemaining: Int = 0,
    val observedTodayKwh: Int = 0,
    val observedDays: Int = 0,
    val latestReadingDateLabel: String = "",
    val latestBillMxn: Int = 0,
    val latestBillKwh: Int = 0,
    val importedBillCount: Int = 0,
    val realBillHistory: List<BillPeriod> = emptyList(),
    val billingDays: Int = 0,
    val projectedKwh: Int = 0,
    val goalKwh: Int = 0,
    val applianceProjectedKwh: Int = 0,
    val applianceProjectedMxn: Int = 0,
    val applianceExpectedToDateKwh: Int = 0,
    val applianceDailyAverageKwh: Double = 0.0,
    val applianceCoveragePercent: Int = 0,
    val hasCurrentMeterProjection: Boolean = false,


    val dailyRateNow: Double = 0.0,
    val dailyRateRecommended: Double = 0.0,
    val remainingGoalKwh: Int = 0,
    val paceRelation: ConsumptionPaceRelation = ConsumptionPaceRelation.ON_RECOMMENDED,
    val hasAppliances: Boolean = false,
    val applianceCount: Int = 0,

    /** Highest-cost appliances the user actually registered, already ranked. */
    val topAppliances: List<HomeApplianceShare> = emptyList(),
    val unidentifiedCostMxn: Int = 0,
    val unidentifiedSharePercent: Int = 0,
    /** True only when a household total exists to subtract the inventory from. */
    val hasHouseholdTotal: Boolean = false,

    val confidenceLevel: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val previousBillsLoadedCount: Int = 0,
    val liveReadingCount: Int = 0,
    val missingApplianceSchedules: Int = 0,
    val dacRisk: DacRiskSnapshot = DacRiskSnapshot(),
)
