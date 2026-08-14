package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.calculation.TariffEngine
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.core.model.ElectricServiceValidator
import com.recibozen.app.core.model.ElectricServiceIdentity
import com.recibozen.app.core.model.EntitlementPolicy
import com.recibozen.app.core.model.EntitlementTier
import com.recibozen.app.core.model.ServiceCreationResult
import com.recibozen.app.core.importer.ParsedCfeBill
import com.recibozen.app.core.importer.BillImportResult
import com.recibozen.app.core.components.MeterDialMath
import com.recibozen.app.core.calculation.TariffCatalog
import com.recibozen.app.demo.data.DemoReciboZenData
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reactive calculation implementation backed by one [MutableStateFlow]. It is
 * used directly by previews; [com.recibozen.app.data.repository.PersistentReciboZenRepository] subclasses it and
 * mirrors the state to DataStore in the running application.
 */
open class DemoReciboZenRepository : ReciboZenRepository {

    private val defaultTariffEngine = TariffEngine()

    protected val mutableTariffCalibration = MutableStateFlow<TariffCalibrationState?>(null)
    override val tariffCalibration: StateFlow<TariffCalibrationState?> = mutableTariffCalibration.asStateFlow()

    /**
     * The engine every estimate goes through: the user's own bill rates when a
     * bill has been imported, the generic progressive blocks otherwise.
     */
    protected fun activeTariffEngine(): TariffEngine =
        mutableTariffCalibration.value?.snapshot?.engine() ?: defaultTariffEngine

    protected val mutableSession = MutableStateFlow(ReciboZenSessionState())
    private val _session get() = mutableSession
    override val session: StateFlow<ReciboZenSessionState> = _session.asStateFlow()
    protected val mutableElectricServices = MutableStateFlow<List<ElectricServiceIdentity>>(emptyList())
    override val electricServices: StateFlow<List<ElectricServiceIdentity>> = mutableElectricServices.asStateFlow()
    protected val mutableImportedBillHistory = MutableStateFlow<List<BillPeriod>>(emptyList())
    override val importedBillHistory: StateFlow<List<BillPeriod>> = mutableImportedBillHistory.asStateFlow()

    override fun appliances(state: ReciboZenSessionState): List<Appliance> {
        val adjusted = state.customAppliances
            .distinctBy { it.id }
            .map { appliance ->
            val hours = state.applianceHoursPerWeek[appliance.id] ?: appliance.hoursPerWeek
            if (hours != null && appliance.hoursPerWeek != null) {
                val ratio = hours.toDouble() / appliance.hoursPerWeek.coerceAtLeast(1)
                appliance.copy(
                    hoursPerWeek = hours,
                    kwh = (appliance.kwh * ratio).roundToInt(),
                    costMxn = (appliance.costMxn * ratio).roundToInt(),
                )
            } else appliance
        }
        val energy = energySummary(state)
        val latestBill = importedBillHistory.value.lastOrNull()
        val effectiveRate = latestBill?.takeIf { it.consumptionKwh > 0 }
            ?.let { it.amountMxn.toDouble() / it.consumptionKwh }
        // Appliance kWh is per full billing period, so the share has to be
        // measured against the projected period too. Comparing it against a
        // mid-period reading would inflate every appliance to 100%.
        val shareBasisKwh = energy.projectedKwh.takeIf { it > 0 } ?: energy.observedKwh
        return adjusted.map { appliance ->
            appliance.copy(
                costMxn = effectiveRate?.let { (appliance.kwh * it).roundToInt() } ?: 0,
                sharePercent = if (energy.hasUsageEvidence && shareBasisKwh > 0) {
                    (appliance.kwh * 100f / shareBasisKwh).roundToInt().coerceIn(0, 100)
                } else 0,
            )
        }
    }

    override fun appliance(id: String, state: ReciboZenSessionState): Appliance? =
        appliances(state).find { it.id == id }

    override fun billProjection(state: ReciboZenSessionState): BillProjection {
        val energy = energySummary(state)
        if (!energy.hasUsageEvidence) {
            return BillProjection(0, 0, 0, false, 0f, hasUsageEvidence = false)
        }
        val cuts = state.planActions
            .filter { it.status == PlanActionStatus.TRACKING }
            .sumOf { it.estimatedCutMxn }
        val projected = (energy.projectedCostMxn - cuts).coerceAtLeast(0)
        val baseline = energy.projectedCostMxn.coerceAtLeast(1)
        val progress = if (state.goalMxn > 0) {
            ((baseline - projected).toFloat() / (baseline - state.goalMxn).coerceAtLeast(1)).coerceIn(0f, 1f)
        } else 0f
        val uncertainty = (projected * 0.08).roundToInt().coerceAtLeast(25)
        return BillProjection(
            projectedMxn = projected,
            rangeLowMxn = (projected - uncertainty).coerceAtLeast(0),
            rangeHighMxn = projected + uncertainty,
            isWithinGoal = state.goalMxn > 0 && projected <= state.goalMxn,
            planProgressFraction = progress,
            hasUsageEvidence = true,
        )
    }

    override fun energySummary(state: ReciboZenSessionState): EnergySummary {
        val newest = state.readings.getOrNull(0)
        val previous = state.readings.getOrNull(1)
        val elapsedMillis = if (newest?.timestampEpochMillis != null && previous?.timestampEpochMillis != null) {
            newest.timestampEpochMillis - previous.timestampEpochMillis
        } else 0L
        val measuredDays = (elapsedMillis / 86_400_000.0).coerceAtLeast(0.0)
        val measuredKwh = (newest?.kwh ?: 0) - (previous?.kwh ?: 0)
        // The two endpoints imported from a PDF describe a bill that already
        // closed; they must not be mistaken for a live reading pair. Once the
        // user records a new reading, comparing it with the PDF endpoint is
        // valid and starts the current-period projection.
        val hasReliablePair = newest?.method != ReadingMethod.PDF &&
            previous != null && measuredDays >= 0.5 && measuredKwh >= 0
        val latestBill = importedBillHistory.value.lastOrNull()
        val hasUsageEvidence = hasReliablePair || latestBill != null
        val billingDays = when {
            latestBill?.periodStartEpochDay != null && latestBill.periodEndEpochDay != null ->
                (latestBill.periodEndEpochDay - latestBill.periodStartEpochDay).toInt().coerceAtLeast(1)
            state.billingCycle.contains("bi", ignoreCase = true) -> 61
            state.billingCycle.contains("men", ignoreCase = true) -> 30
            else -> 0
        }
        val observedDays = when {
            hasReliablePair -> measuredDays.roundToInt().coerceAtLeast(1)
            latestBill != null -> billingDays
            else -> 0
        }
        val observedKwh = when {
            hasReliablePair -> measuredKwh
            latestBill != null -> latestBill.consumptionKwh
            else -> 0
        }
        val dailyAverage = observedKwh.toDouble() / observedDays.coerceAtLeast(1)
        val projectedKwh = if (hasReliablePair && billingDays > 0) {
            (dailyAverage * billingDays).roundToInt().coerceAtLeast(observedKwh)
        } else observedKwh
        val engine = activeTariffEngine()
        val costToDate = when {
            !hasUsageEvidence -> 0
            !hasReliablePair && latestBill != null -> latestBill.amountMxn
            else -> engine.estimate(observedKwh).totalMxn
        }
        val projectedCost = when {
            !hasUsageEvidence -> 0
            !hasReliablePair && latestBill != null -> latestBill.amountMxn
            else -> engine.estimate(projectedKwh).totalMxn
        }
        return EnergySummary(
            observedKwh = observedKwh,
            observedDays = observedDays,
            billingDays = billingDays,
            daysRemaining = if (hasReliablePair) (billingDays - observedDays).coerceAtLeast(0) else 0,
            dailyAverageKwh = dailyAverage,
            projectedKwh = projectedKwh,
            costToDateMxn = costToDate,
            projectedCostMxn = projectedCost,
            hasUsageEvidence = hasUsageEvidence,
        )
    }

    override fun billHistory(state: ReciboZenSessionState): List<BillPeriod> {
        val projection = billProjection(state)
        val energy = energySummary(state)
        if (!energy.hasUsageEvidence) return emptyList()
        return listOf(BillPeriod(
            monthLabel = "Actual",
            amountMxn = projection.projectedMxn,
            isProjected = true,
            consumptionKwh = energy.projectedKwh,
        ))
    }

    override fun dialReading(state: ReciboZenSessionState): DialReadingResult {
        val allDigits = MeterDialMath.digits(state.dialAngles, state.dialDirections)
        val digits = if (state.meterType.contains("4")) allDigits.takeLast(4) else allDigits
        val value = digits.joinToString("").toInt()
        val diff = value - state.lastConfirmedReadingKwh
        val rate = diff / 76.0 * 24.0
        val abnormal = diff < 0 || rate > DemoReciboZenData.ABNORMAL_RATE_THRESHOLD_KWH_PER_DAY || diff == 0
        val warning = when {
            diff < 0 -> "Esta lectura es menor que la anterior. ¿Ajustaste mal una manecilla, cambiaron tu medidor o reinició su contador?"
            diff == 0 -> "Tu lectura no cambió desde el último registro. Puedes guardarla si es correcta."
            rate > DemoReciboZenData.ABNORMAL_RATE_THRESHOLD_KWH_PER_DAY ->
                "Esta lectura implicaría ${rate.roundToInt()} kWh diarios, muy por encima de tu promedio de ${DemoReciboZenData.TYPICAL_DAILY_AVERAGE}."
            else -> null
        }
        return DialReadingResult(
            digits = digits,
            valueKwh = value,
            consumptionSinceLast = diff,
            averagePerDay = rate,
            isAbnormal = abnormal,
            warningMessage = warning,
        )
    }

    override fun setThemeMode(mode: AppThemeMode) {
        _session.update { it.copy(themeMode = mode) }
    }

    override fun setApplianceHoursPerWeek(applianceId: String, hours: Int) {
        _session.update {
            it.copy(applianceHoursPerWeek = it.applianceHoursPerWeek + (applianceId to hours.coerceIn(0, 168)))
        }
    }

    override fun addAppliance(appliance: Appliance) {
        _session.update { state ->
            if (appliances(state).any { it.id == appliance.id }) state
            else state.copy(
                customAppliances = state.customAppliances + appliance,
                applianceHoursPerWeek = appliance.hoursPerWeek?.let {
                    state.applianceHoursPerWeek + (appliance.id to it)
                } ?: state.applianceHoursPerWeek,
            )
        }
    }

    override fun estimateCostMxn(kwh: Int): Int =
        activeTariffEngine().estimate(kwh.coerceAtLeast(0)).totalMxn

    override fun activeTariffBlocks() =
        mutableTariffCalibration.value?.snapshot?.blocks ?: TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS

    override fun removeAppliance(applianceId: String) {
        _session.update { state ->
            state.copy(
                customAppliances = state.customAppliances.filterNot { it.id == applianceId },
                // Drop the override too, so re-adding the same appliance later
                // starts from its own defaults instead of a stale edit.
                applianceHoursPerWeek = state.applianceHoursPerWeek - applianceId,
                planActions = state.planActions.filterNot { it.id.startsWith(applianceId) },
            )
        }
    }

    override fun saveSimulatorScenario(name: String, watts: Int, hoursPerWeek: Double) {
        if (name.isBlank() || watts <= 0 || hoursPerWeek !in 0.01..168.0) return
        _session.update {
            it.copy(
                savedSimulatorScenario = true,
                simulatorScenarioName = name.trim(),
                simulatorScenarioWatts = watts,
                simulatorScenarioHoursPerWeek = hoursPerWeek,
            )
        }
    }

    override fun togglePlanAction(id: String) {
        _session.update { state ->
            state.copy(
                planActions = state.planActions.map { action ->
                    if (action.id == id && action.status != PlanActionStatus.VERIFIED) {
                        action.copy(
                            status = if (action.status == PlanActionStatus.TRACKING) {
                                PlanActionStatus.SUGGESTED
                            } else {
                                PlanActionStatus.TRACKING
                            },
                        )
                    } else action
                },
            )
        }
    }

    override fun setDialAngle(index: Int, angleDegrees: Float) {
        _session.update { state ->
            val angles = state.dialAngles.toMutableList().apply { this[index] = angleDegrees }
            state.copy(dialAngles = angles, dialVisitedFaces = state.dialVisitedFaces + index)
        }
    }

    override fun setDialSelectedIndex(index: Int) {
        _session.update {
            it.copy(dialSelectedIndex = index, dialVisitedFaces = it.dialVisitedFaces + index, dialUnsure = false)
        }
    }

    override fun nudgeDial(index: Int, deltaDegrees: Float) {
        _session.update { state ->
            val clockwise = state.dialDirections[index]
            val newAngle = MeterDialMath.nudge(state.dialAngles[index], deltaDegrees, clockwise)
            val angles = state.dialAngles.toMutableList().apply { this[index] = newAngle }
            state.copy(dialAngles = angles, dialVisitedFaces = state.dialVisitedFaces + index)
        }
    }

    override fun setDialDigit(index: Int, digit: Int) {
        _session.update { state ->
            val clockwise = state.dialDirections[index]
            val angles = state.dialAngles.toMutableList().apply {
                this[index] = MeterDialMath.angleForDigit(digit, clockwise)
            }
            state.copy(dialAngles = angles, dialUnsure = false, dialVisitedFaces = state.dialVisitedFaces + index)
        }
    }

    override fun toggleDialUnsure() {
        _session.update { it.copy(dialUnsure = !it.dialUnsure) }
    }

    override fun resetDial() {
        _session.update {
            it.copy(
                dialAngles = listOf(66.3f, 56.7f, 153.4f, 266.4f, 216f),
                dialSelectedIndex = 0,
                dialVisitedFaces = setOf(0),
                dialUnsure = false,
            )
        }
    }

    override fun setSolarScenarioIndex(index: Int) {
        _session.update { it.copy(solarScenarioIndex = index) }
    }

    override fun setHistoryUnit(unit: HistoryUnit) {
        _session.update { it.copy(historyUnit = unit) }
    }

    override fun setOnboardingStep(step: Int) {
        _session.update { it.copy(onboardingStep = step.coerceIn(0, 3)) }
    }

    override fun completeOnboarding() {
        _session.update { it.copy(onboardingCompleted = true, onboardingStep = 3) }
    }

    override fun setMeterType(meterType: String) {
        _session.update { state ->
            if (state.meterType == meterType) state else {
                val firstFace = if (meterType.contains("4")) 1 else 0
                state.copy(
                    meterType = meterType,
                    dialSelectedIndex = firstFace,
                    dialVisitedFaces = setOf(firstFace),
                    dialUnsure = false,
                )
            }
        }
    }

    override fun updateProfile(userName: String, householdName: String, location: String, goalMxn: Int) {
        _session.update {
            it.copy(
                userName = userName.trim(),
                householdName = householdName.trim(),
                location = location.trim(),
                goalMxn = goalMxn.coerceAtLeast(0),
            )
        }
    }

    open override fun setTariff(tariff: String) {
        val normalized = TariffCatalog.find(tariff)?.code ?: return
        _session.update { it.copy(tariff = normalized) }
        mutableElectricServices.update { services ->
            services.map { service ->
                if (service.id == _session.value.activeServiceId) service.copy(tariffCode = normalized) else service
            }
        }
    }

    open override fun updateElectricService(
        alias: String,
        serviceNumber: String,
        rmu: String,
        meterNumber: String,
        tariffCode: String,
    ) {
        val normalizedTariff = TariffCatalog.find(tariffCode)?.code ?: return
        if (!ElectricServiceValidator.isValidServiceNumber(serviceNumber)) return
        _session.update {
            it.copy(
                serviceAlias = alias.trim().ifEmpty { it.serviceAlias },
                serviceNumber = ElectricServiceValidator.normalizeServiceNumber(serviceNumber),
                rmu = ElectricServiceValidator.normalizeRmu(rmu),
                meterNumber = ElectricServiceValidator.normalizeMeterNumber(meterNumber),
                tariff = normalizedTariff,
            )
        }
        mutableElectricServices.update { services ->
            services.map { service ->
                if (service.id == _session.value.activeServiceId) {
                    service.copy(
                        alias = _session.value.serviceAlias,
                        serviceNumber = _session.value.serviceNumber,
                        rmu = _session.value.rmu,
                        meterNumber = _session.value.meterNumber,
                        tariffCode = normalizedTariff,
                    )
                } else service
            }
        }
    }

    open override fun addElectricService(
        alias: String,
        serviceNumber: String,
        rmu: String,
        meterNumber: String,
        tariffCode: String,
    ): ServiceCreationResult {
        val state = _session.value
        val activeCount = electricServices.value.count { it.isActive }
        val limit = EntitlementPolicy.activeServiceLimit(state.entitlementTier)
        if (activeCount >= limit) {
            val required = if (activeCount < EntitlementPolicy.activeServiceLimit(EntitlementTier.PLUS)) {
                EntitlementTier.PLUS
            } else EntitlementTier.FAMILY
            return ServiceCreationResult(accepted = false, requiredTier = required)
        }
        if (alias.isBlank() || !ElectricServiceValidator.isValidServiceNumber(serviceNumber)) {
            return ServiceCreationResult(accepted = false)
        }
        val normalizedTariff = TariffCatalog.find(tariffCode)?.code ?: return ServiceCreationResult(false)
        val id = UUID.randomUUID().toString()
        val service = ElectricServiceIdentity(
            id = id,
            alias = alias.trim(),
            serviceNumber = ElectricServiceValidator.normalizeServiceNumber(serviceNumber),
            rmu = ElectricServiceValidator.normalizeRmu(rmu),
            meterNumber = ElectricServiceValidator.normalizeMeterNumber(meterNumber),
            tariffCode = normalizedTariff,
            billingCycle = state.billingCycle,
        )
        mutableElectricServices.update { it + service }
        selectElectricService(id)
        return ServiceCreationResult(accepted = true, serviceId = id)
    }

    override fun selectElectricService(serviceId: String) {
        val service = electricServices.value.firstOrNull { it.id == serviceId && it.isActive } ?: return
        _session.update {
            it.copy(
                activeServiceId = service.id,
                serviceAlias = service.alias,
                serviceNumber = service.serviceNumber,
                rmu = service.rmu,
                meterNumber = service.meterNumber,
                tariff = service.tariffCode,
                billingCycle = service.billingCycle,
            )
        }
    }

    override suspend fun importCfeBill(bill: ParsedCfeBill, sourceFileHash: String): BillImportResult =
        BillImportResult(accepted = false, message = "La importación durable solo está disponible en el repositorio local.")

    override fun setManualReadingDigits(digits: List<Int>) {
        _session.update { it.copy(manualReadingDigits = digits) }
    }

    override fun prepareReading(kwh: Int, method: ReadingMethod) {
        _session.update { it.copy(pendingReadingKwh = kwh.coerceAtLeast(0), pendingReadingMethod = method) }
    }

    override fun confirmReading(kwh: Int, method: ReadingMethod) {
        _session.update { state ->
            val now = System.currentTimeMillis()
            val difference = kwh - state.lastConfirmedReadingKwh
            val label = SimpleDateFormat("d MMM, h:mm a", Locale("es", "MX")).format(Date(now))
            state.copy(
                lastConfirmedReadingKwh = kwh,
                readings = listOf(
                    HistoryReadingEntry(
                        kwh = kwh,
                        dateLabel = label,
                        method = method,
                        note = if (difference >= 0) "+$difference kWh desde la anterior" else "lectura menor a la anterior",
                        timestampEpochMillis = now,
                    ),
                ) + state.readings,
                pendingReadingKwh = null,
                pendingReadingMethod = null,
            )
        }
    }
}
