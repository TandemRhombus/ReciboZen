package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.core.model.ElectricServiceIdentity
import com.recibozen.app.core.model.ServiceCreationResult
import com.recibozen.app.core.importer.ParsedCfeBill
import com.recibozen.app.core.importer.BillImportResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Single seam between the UI and "data". Every screen reads through here so
 * a future engineer can swap [DemoReciboZenRepository] for one backed by a
 * real database/tariff engine/backend without touching any Composable.
 */
interface ReciboZenRepository {
    val session: StateFlow<ReciboZenSessionState>
    val electricServices: StateFlow<List<ElectricServiceIdentity>>
    val importedBillHistory: StateFlow<List<BillPeriod>>

    fun appliances(state: ReciboZenSessionState = session.value): List<Appliance>
    fun appliance(id: String, state: ReciboZenSessionState = session.value): Appliance?
    fun billProjection(state: ReciboZenSessionState = session.value): BillProjection
    fun energySummary(state: ReciboZenSessionState = session.value): EnergySummary
    fun billHistory(state: ReciboZenSessionState = session.value): List<BillPeriod>
    fun dialReading(state: ReciboZenSessionState = session.value): DialReadingResult

    fun setThemeMode(mode: AppThemeMode)
    fun setApplianceHoursPerWeek(applianceId: String, hours: Int)
    fun addAppliance(appliance: Appliance)
    fun saveSimulatorScenario()
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
    fun completeOnboarding()
    fun setMeterType(meterType: String)
    fun updateProfile(userName: String, householdName: String, location: String, goalMxn: Int)
    fun updateElectricService(alias: String, serviceNumber: String, rmu: String, meterNumber: String, tariffCode: String)
    fun addElectricService(alias: String, serviceNumber: String, rmu: String, meterNumber: String, tariffCode: String): ServiceCreationResult
    fun selectElectricService(serviceId: String)
    suspend fun importCfeBill(bill: ParsedCfeBill, sourceFileHash: String): BillImportResult
    fun setManualReadingDigits(digits: List<Int>)
    fun prepareReading(kwh: Int, method: ReadingMethod)
    fun confirmReading(kwh: Int, method: ReadingMethod)
}
