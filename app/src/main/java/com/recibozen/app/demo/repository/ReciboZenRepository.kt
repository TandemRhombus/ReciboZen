package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.ReadingMethod
import kotlinx.coroutines.flow.StateFlow

/**
 * Single seam between the UI and "data". Every screen reads through here so
 * a future engineer can swap [DemoReciboZenRepository] for one backed by a
 * real database/tariff engine/backend without touching any Composable.
 */
interface ReciboZenRepository {
    val session: StateFlow<ReciboZenSessionState>

    fun appliances(state: ReciboZenSessionState = session.value): List<Appliance>
    fun appliance(id: String, state: ReciboZenSessionState = session.value): Appliance?
    fun billProjection(state: ReciboZenSessionState = session.value): BillProjection
    fun dialReading(state: ReciboZenSessionState = session.value): DialReadingResult

    fun setThemeMode(mode: AppThemeMode)
    fun setPs5HoursPerWeek(hours: Int)
    fun togglePlanAction(id: String)
    fun setDialAngle(index: Int, angleDegrees: Float)
    fun setDialSelectedIndex(index: Int)
    fun nudgeDial(index: Int, deltaDegrees: Float)
    fun setDialDigit(index: Int, digit: Int)
    fun toggleDialUnsure()
    fun resetDial()
    fun setSolarScenarioIndex(index: Int)
    fun setHistoryUnit(unit: HistoryUnit)
    fun setOnboardingStep(step: Int)
    fun setManualReadingDigits(digits: List<Int>)
    fun confirmReading(kwh: Int, method: ReadingMethod)
}
