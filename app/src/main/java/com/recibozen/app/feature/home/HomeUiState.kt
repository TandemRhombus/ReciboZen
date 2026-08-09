package com.recibozen.app.feature.home

import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.demo.data.DemoReciboZenData

/**
 * Everything the "Inicio" tab needs to render, derived from
 * [com.recibozen.app.demo.repository.ReciboZenSessionState] + the demo
 * fixtures. Immutable snapshot consumed by the stateless [HomeScreen].
 */
data class HomeUiState(
    val userName: String = DemoReciboZenData.USER_NAME,
    val householdName: String = DemoReciboZenData.HOUSEHOLD_NAME,
    val userInitial: String = DemoReciboZenData.USER_INITIAL,

    val periodLabel: String = DemoReciboZenData.PERIOD_LABEL,
    val projectedMxn: Int = DemoReciboZenData.BASE_PROJECTED_MXN,
    val rangeLowMxn: Int = DemoReciboZenData.BASE_PROJECTED_MXN - 36,
    val rangeHighMxn: Int = DemoReciboZenData.BASE_PROJECTED_MXN + 44,
    val isWithinGoal: Boolean = false,
    val goalMxn: Int = DemoReciboZenData.GOAL_MXN,
    val daysRemaining: Int = DemoReciboZenData.DAYS_REMAINING,
    val observedTodayKwh: Int = DemoReciboZenData.OBSERVED_KWH_TODAY,
    val observedDays: Int = DemoReciboZenData.OBSERVED_DAYS,

    val minisplitStatus: PlanActionStatus = PlanActionStatus.SUGGESTED,
    val minisplitSavingsLabel: String = "",

    val dailyRateNow: Double = DemoReciboZenData.DAILY_RATE_NOW,
    val dailyRateRecommended: Double = DemoReciboZenData.DAILY_RATE_RECOMMENDED,

    val minisplitCostMxn: Int = 0,
    val minisplitSharePercent: Int = 0,
    val refrigeradorCostMxn: Int = 0,
    val refrigeradorSharePercent: Int = 0,
    val ps5CostMxn: Int = 0,
    val ps5SharePercent: Int = 0,
    val unidentifiedCostMxn: Int = DemoReciboZenData.UNIDENTIFIED_COST_MXN,
    val unidentifiedSharePercent: Int = DemoReciboZenData.UNIDENTIFIED_SHARE_PERCENT,

    val confidenceLevel: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val previousBillsLoadedCount: Int = 9,
    val recentReadingsCountLabel: String = DemoReciboZenData.RECENT_READINGS_COUNT_LABEL,
    val missingApplianceSchedules: Int = 3,
)
