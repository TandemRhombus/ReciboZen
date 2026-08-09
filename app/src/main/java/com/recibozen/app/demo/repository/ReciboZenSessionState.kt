package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.PlanAction
import com.recibozen.app.demo.data.DemoReciboZenData

/** Everything a user interaction can change during a session. In-memory only. */
data class ReciboZenSessionState(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val ps5HoursPerWeek: Int = 17,
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
)

/** Result of the demo "846 − tracked-action cuts" projection, recomputed on every plan change. */
data class BillProjection(
    val projectedMxn: Int,
    val rangeLowMxn: Int,
    val rangeHighMxn: Int,
    val isWithinGoal: Boolean,
    val planProgressFraction: Float,
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
