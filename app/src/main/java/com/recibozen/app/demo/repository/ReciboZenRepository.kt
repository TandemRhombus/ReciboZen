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
import com.recibozen.app.core.calculation.TariffBlock
import com.recibozen.app.core.model.EntitlementTier
import com.recibozen.app.core.model.ApplianceDataSource
import com.recibozen.app.core.model.ApplianceUsageModel
import com.recibozen.app.core.model.HouseSpace
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

    /**
     * Tariff rates derived from the user's own imported bill, or null while no
     * bill has been imported (the estimate then falls back to generic blocks).
     * Screens read this to say whether a number came from real rates.
     */
    val tariffCalibration: StateFlow<TariffCalibrationState?>

    fun appliances(state: ReciboZenSessionState = session.value): List<Appliance>
    fun appliance(id: String, state: ReciboZenSessionState = session.value): Appliance?
    fun billProjection(state: ReciboZenSessionState = session.value): BillProjection
    fun energySummary(state: ReciboZenSessionState = session.value): EnergySummary
    fun billHistory(state: ReciboZenSessionState = session.value): List<BillPeriod>

    /**
     * Cost of [kwh] under the tariff currently in force. Exposed so screens can
     * ask marginal questions ("what would adding this appliance cost?") without
     * reimplementing the progressive blocks — adding 100 kWh is not a flat rate,
     * it depends on which tier you are already in.
     */
    fun estimateCostMxn(kwh: Int): Int
    fun activeTariffBlocks(): List<TariffBlock>
    fun dialReading(state: ReciboZenSessionState = session.value): DialReadingResult

    fun setThemeMode(mode: AppThemeMode)
    fun setApplianceHoursPerWeek(applianceId: String, hours: Int)
    fun updateAppliance(
        applianceId: String,
        quantity: Int,
        spaceId: String?,
        room: String,
        brand: String?,
        model: String?,
        usageModel: ApplianceUsageModel,
        hoursPerWeek: Int?,
        powerWatts: Int? = null,
        unitKwh: Double?,
        dataSource: ApplianceDataSource,
    )
    fun addAppliance(appliance: Appliance)
    fun removeAppliance(applianceId: String)
    fun addHouseSpace(name: String): HouseSpace?
    fun renameHouseSpace(spaceId: String, name: String)
    fun removeHouseSpace(spaceId: String)
    fun saveSimulatorScenario(name: String, watts: Int, hoursPerWeek: Double)
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
    fun setTariff(tariff: String)
    fun updateElectricService(alias: String, serviceNumber: String, rmu: String, meterNumber: String, tariffCode: String)
    fun addElectricService(alias: String, serviceNumber: String, rmu: String, meterNumber: String, tariffCode: String): ServiceCreationResult
    fun selectElectricService(serviceId: String)
    fun premiumSubject(): String
    fun acceptPremiumLease(signedLease: String): EntitlementTier?
    suspend fun importCfeBill(bill: ParsedCfeBill, sourceFileHash: String): BillImportResult
    fun setManualReadingDigits(digits: List<Int>)
    fun prepareReading(kwh: Int, method: ReadingMethod)
    fun confirmReading(kwh: Int, method: ReadingMethod)
}
