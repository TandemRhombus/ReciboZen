package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.PlanAction
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.EntitlementTier

/** User-owned state. The production repository persists this object with DataStore. */
data class ReciboZenSessionState(
    val isHydrated: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val userName: String = "",
    val householdName: String = "",
    val serviceAlias: String = "",
    val serviceNumber: String = "",
    val rmu: String = "",
    val meterNumber: String = "",
    val activeServiceId: String = "",
    val entitlementTier: EntitlementTier = EntitlementTier.FREE,
    val location: String = "",
    val tariff: String = "",
    val billingCycle: String = "",
    val goalMxn: Int = 0,
    val meterType: String = "",
    val applianceHoursPerWeek: Map<String, Int> = emptyMap(),
    val customAppliances: List<Appliance> = emptyList(),
    val savedSimulatorScenario: Boolean = false,
    val planActions: List<PlanAction> = emptyList(),
    val dialAngles: List<Float> = listOf(66.3f, 56.7f, 153.4f, 266.4f, 216f),
    val dialDirections: List<Boolean> = listOf(true, false, true, false, true),
    val dialSelectedIndex: Int = 0,
    val dialVisitedFaces: Set<Int> = setOf(0),
    val dialUnsure: Boolean = false,
    val solarScenarioIndex: Int = 0,
    val historyUnit: HistoryUnit = HistoryUnit.MXN,
    val onboardingStep: Int = 0,
    val lastConfirmedReadingKwh: Int = 0,
    val readings: List<HistoryReadingEntry> = emptyList(),
    val manualReadingDigits: List<Int> = listOf(0, 0, 0, 0, 0),
    val pendingReadingKwh: Int? = null,
    val pendingReadingMethod: com.recibozen.app.core.model.ReadingMethod? = null,
)

/** Result of the demo "846 − tracked-action cuts" projection, recomputed on every plan change. */
data class BillProjection(
    val projectedMxn: Int,
    val rangeLowMxn: Int,
    val rangeHighMxn: Int,
    val isWithinGoal: Boolean,
    val planProgressFraction: Float,
    val hasUsageEvidence: Boolean = false,
)

data class EnergySummary(
    val observedKwh: Int,
    val observedDays: Int,
    val billingDays: Int,
    val daysRemaining: Int,
    val dailyAverageKwh: Double,
    val projectedKwh: Int,
    val costToDateMxn: Int,
    val projectedCostMxn: Int,
    val hasUsageEvidence: Boolean = false,
)

/** Result of interpreting the 5 dial faces into a meter reading. */
data class DialReadingResult(
    val digits: List<Int>,
    val valueKwh: Int,
    val consumptionSinceLast: Int,
    val averagePerDay: Double,
    val isAbnormal: Boolean,
    val warningMessage: String?,
)
