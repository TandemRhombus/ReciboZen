package com.recibozen.app.feature.home

import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.core.calculation.DacRiskSnapshot

enum class HomeDataMode { EMPTY, BILL_REFERENCE, PROJECTION }

/**
 * Everything the "Inicio" tab needs to render, derived from
 * [com.recibozen.app.demo.repository.ReciboZenSessionState] + the demo
 * fixtures. Immutable snapshot consumed by the stateless [HomeScreen].
 */
data class HomeUiState(
    val userName: String = "",
    val householdName: String = "",
    val userInitial: String = "?",
    val profileConfigured: Boolean = false,
    val dataMode: HomeDataMode = HomeDataMode.EMPTY,

    val periodLabel: String = "",
    val projectedMxn: Int = 0,
    val rangeLowMxn: Int = 0,
    val rangeHighMxn: Int = 0,
    val isWithinGoal: Boolean = false,
    val goalMxn: Int = 0,
    val daysRemaining: Int = 0,
    val observedTodayKwh: Int = 0,
    val observedDays: Int = 0,
    val latestBillMxn: Int = 0,
    val latestBillKwh: Int = 0,
    val importedBillCount: Int = 0,
    val realBillHistory: List<BillPeriod> = emptyList(),
    val billingDays: Int = 0,
    val projectedKwh: Int = 0,
    val goalKwh: Int = 0,

    val minisplitStatus: PlanActionStatus = PlanActionStatus.SUGGESTED,
    val minisplitSavingsLabel: String = "",

    val dailyRateNow: Double = 0.0,
    val dailyRateRecommended: Double = 0.0,
    val hasAppliances: Boolean = false,
    val applianceCount: Int = 0,

    val minisplitCostMxn: Int = 0,
    val minisplitSharePercent: Int = 0,
    val refrigeradorCostMxn: Int = 0,
    val refrigeradorSharePercent: Int = 0,
    val ps5CostMxn: Int = 0,
    val ps5SharePercent: Int = 0,
    val unidentifiedCostMxn: Int = 0,
    val unidentifiedSharePercent: Int = 0,

    val confidenceLevel: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val previousBillsLoadedCount: Int = 0,
    val recentReadingsCountLabel: String = "0 lecturas recientes",
    val missingApplianceSchedules: Int = 0,
    val dacRisk: DacRiskSnapshot = DacRiskSnapshot(),
)
