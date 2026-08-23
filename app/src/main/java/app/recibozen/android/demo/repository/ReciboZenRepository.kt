package app.recibozen.android.demo.repository

import app.recibozen.android.core.designsystem.AppThemeMode
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.PlanAction
import app.recibozen.android.core.model.HistoryUnit
import app.recibozen.android.core.model.MeterReplacement
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.core.model.ElectricServiceIdentity
import app.recibozen.android.core.model.ServiceCreationResult
import app.recibozen.android.core.importer.ParsedCfeBill
import app.recibozen.android.core.importer.BillImportResult
import app.recibozen.android.core.calculation.TariffBlock
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.HouseSpace
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
     * Savings actions for this household, ranked by the advice engine over
     * the appliances the user registered. Derived on every read: it is empty
     * until there is an inventory, and never shows preloaded suggestions.
     */
    fun planActions(state: ReciboZenSessionState = session.value): List<PlanAction>

    /**
     * Cost of [kwh] under the tariff currently in force. Exposed so screens can
     * ask marginal questions ("what would adding this appliance cost?") without
     * reimplementing the progressive blocks — adding 100 kWh is not a flat rate,
     * it depends on which tier you are already in.
     */
    fun estimateCostMxn(kwh: Int): Int
    fun activeTariffBlocks(): List<TariffBlock>

    /**
     * Which source priced the current estimate: the household's own bill, CFE's
     * synced catalog, or the built-in generic blocks. Screens read it so a
     * generic order of magnitude is never presented like a real rate.
     */
    fun tariffRateOrigin(): TariffRateOrigin
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
        standbyEnabled: Boolean = false,
        standbyWatts: Int? = null,
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
    fun setSolarDemandGrowthPercent(percent: Int)
    fun setSolarPlannedLoad(loadId: String, annualKwh: Int?)
    fun setHistoryUnit(unit: HistoryUnit)
    fun setOnboardingStep(step: Int)
    fun completeOnboarding()

    /** Marks the guided tour as seen, or queues it again from Perfil. */
    fun setTutorialCompleted(completed: Boolean)
    fun setMeterType(meterType: String)

    /**
     * Records how many digits the register shows, as counted by the household.
     * Null clears the declaration and hands the question back to the meter type
     * label, which for a digital meter means the app goes back to not knowing.
     */
    fun setMeterDigitCount(digitCount: Int?)

    /** Records the CFE constant printed on the meter. 1 when it has none. */
    fun setMeterMultiplier(multiplier: Int)
    fun updateProfile(userName: String, householdName: String, location: String, goalMxn: Int)
    fun setTariff(tariff: String)
    fun setDacReferenceTariff(tariff: String)
    fun updateElectricService(alias: String, serviceNumber: String, rmu: String, meterNumber: String, tariffCode: String)
    fun addElectricService(alias: String, serviceNumber: String, rmu: String, meterNumber: String, tariffCode: String): ServiceCreationResult
    fun selectElectricService(serviceId: String)
    fun premiumSubject(): String
    fun acceptPremiumLease(signedLease: String): EntitlementTier?
    suspend fun importCfeBill(bill: ParsedCfeBill, sourceFileHash: String): BillImportResult
    fun setManualReadingDigits(digits: List<Int>)
    fun prepareReading(kwh: Int, method: ReadingMethod)
    /**
     * Stores [kwh] as the household's confirmed reading.
     *
     * [wrapsSincePrevious] is how many times they attested the register wrapped
     * since the previous reading. Null means they were never asked -- either the
     * interval was unambiguous or the app could not frame the question -- and it
     * travels with the entry so no later screen has to re-derive it.
     */
    fun confirmReading(
        kwh: Int,
        method: ReadingMethod,
        wrapsSincePrevious: Int? = null,
        meterReplacement: MeterReplacement? = null,
    )
}
