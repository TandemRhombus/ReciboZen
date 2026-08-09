package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.PlanAction
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.EntitlementTier
import com.recibozen.app.demo.data.DemoReciboZenData

/** User-owned state. The production repository persists this object with DataStore. */
data class ReciboZenSessionState(
    val isHydrated: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val userName: String = DemoReciboZenData.USER_NAME,
    val householdName: String = DemoReciboZenData.HOUSEHOLD_NAME,
    val serviceAlias: String = DemoReciboZenData.HOUSEHOLD_NAME,
    val serviceNumber: String = "",
    val rmu: String = "",
    val meterNumber: String = "",
    val activeServiceId: String = "legacy_service",
    val entitlementTier: EntitlementTier = EntitlementTier.FREE,
    val location: String = DemoReciboZenData.LOCATION,
    val tariff: String = DemoReciboZenData.TARIFF,
    val billingCycle: String = DemoReciboZenData.BILLING_CYCLE,
    val goalMxn: Int = DemoReciboZenData.GOAL_MXN,
    val meterType: String = DemoReciboZenData.METER_TYPE_SELECTED,
    val applianceHoursPerWeek: Map<String, Int> = DemoReciboZenData.appliances
        .mapNotNull { appliance -> appliance.hoursPerWeek?.let { appliance.id to it } }
        .toMap(),
    val customAppliances: List<Appliance> = emptyList(),
    val savedSimulatorScenario: Boolean = false,
    val planActions: List<PlanAction> = DemoReciboZenData.planActions,
    val dialAngles: List<Float> = listOf(66.3f, 56.7f, 153.4f, 266.4f, 216f),
    val dialDirections: List<Boolean> = listOf(true, false, true, false, true),
    val dialSelectedIndex: Int = 0,
    val dialVisitedFaces: Set<Int> = setOf(0),
    val dialUnsure: Boolean = false,
    val solarScenarioIndex: Int = DemoReciboZenData.DEFAULT_SOLAR_SCENARIO_INDEX,
    val historyUnit: HistoryUnit = HistoryUnit.MXN,
    val onboardingStep: Int = 0,
    val lastConfirmedReadingKwh: Int = DemoReciboZenData.LAST_READING_KWH,
    val readings: List<HistoryReadingEntry> = DemoReciboZenData.recentReadings,
    val manualReadingDigits: List<Int> = listOf(1, 8, 4, 2, 6),
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
