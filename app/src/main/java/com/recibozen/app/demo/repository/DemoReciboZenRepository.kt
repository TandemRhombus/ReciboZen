package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.calculation.TariffEngine
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceDataSource
import com.recibozen.app.core.model.ApplianceUsageModel
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
import com.recibozen.app.core.model.HouseSpace
import com.recibozen.app.core.model.HouseSpaceDefaults
import com.recibozen.app.core.importer.ParsedCfeBill
import com.recibozen.app.core.importer.BillImportResult
import com.recibozen.app.core.components.MeterDialMath
import com.recibozen.app.core.calculation.TariffCatalog
import com.recibozen.app.demo.data.DemoReciboZenData
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
     * Never price DAC with the generic 1C blocks. A legible DAC breakdown wins;
     * otherwise the latest DAC bill supplies an all-in effective rate that is
     * refreshed at every import. It is an estimate, but it preserves the actual
     * tariff jump instead of silently reverting to subsidized domestic blocks.
     */
    protected fun activeTariffEngine(): TariffEngine {
        val calibration = mutableTariffCalibration.value
        if (calibration?.isCalibrated == true) return calibration.snapshot.engine()
        if (TariffCatalog.normalize(mutableSession.value.tariff) == "DAC") {
            val latest = mutableImportedBillHistory.value.lastOrNull()
            val effectiveRate = latest
                ?.takeIf { it.consumptionKwh > 0 && it.amountMxn > 0 }
                ?.let { it.amountMxn.toDouble() / it.consumptionKwh }
            if (effectiveRate != null) {
                return TariffEngine(
                    blocks = listOf(com.recibozen.app.core.calculation.TariffBlock(null, effectiveRate)),
                    fixedChargeMxn = 0.0,
                    taxRate = 0.0,
                )
            }
        }
        return defaultTariffEngine
    }

    protected val mutableSession = MutableStateFlow(ReciboZenSessionState())
    private val _session get() = mutableSession
    override val session: StateFlow<ReciboZenSessionState> = _session.asStateFlow()
    protected val mutableElectricServices = MutableStateFlow<List<ElectricServiceIdentity>>(emptyList())
    override val electricServices: StateFlow<List<ElectricServiceIdentity>> = mutableElectricServices.asStateFlow()
    protected val mutableImportedBillHistory = MutableStateFlow<List<BillPeriod>>(emptyList())
    override val importedBillHistory: StateFlow<List<BillPeriod>> = mutableImportedBillHistory.asStateFlow()

    override fun appliances(state: ReciboZenSessionState): List<Appliance> {
        val energy = energySummary(state)
        val adjusted = adjustedAppliances(state, energy.billingDays)
        val shareBasisKwh = when {
            energy.hasCurrentMeterProjection && energy.meterProjectedKwh > 0 -> energy.meterProjectedKwh
            energy.forecastBasis == EnergyForecastBasis.CLOSED_BILL && energy.projectedKwh > 0 -> energy.projectedKwh
            energy.applianceProjectedKwh > 0 -> energy.applianceProjectedKwh
            energy.projectedKwh > 0 -> energy.projectedKwh
            else -> energy.observedKwh
        }
        val householdCost = when {
            energy.applianceProjectedKwh > 0 -> energy.applianceProjectedCostMxn
            else -> energy.projectedCostMxn
        }
        return adjusted.map { appliance ->
            val share = if (shareBasisKwh > 0) {
                (appliance.kwh * 100f / shareBasisKwh).roundToInt().coerceIn(0, 100)
            } else 0
            val allocatedCost = if (energy.applianceProjectedKwh > 0) {
                (householdCost * appliance.kwh.toDouble() / energy.applianceProjectedKwh)
                    .roundToInt()
                    .coerceAtLeast(0)
            } else 0
            val standbyCost = appliance.standbySharePercent?.let { percent ->
                (allocatedCost * percent / 100.0).roundToInt()
            }
            appliance.copy(
                costMxn = allocatedCost,
                sharePercent = share,
                activeCostMxn = standbyCost?.let { (allocatedCost - it).coerceAtLeast(0) },
                standbyCostMxn = standbyCost,
            )
        }
    }

    /** Applies persisted schedule and quantity data without consulting [energySummary], avoiding a forecast cycle. */
    private fun adjustedAppliances(state: ReciboZenSessionState, targetPeriodDays: Int = 0): List<Appliance> =
        state.customAppliances
            .distinctBy { it.id }
            .map { appliance ->
                val quantity = appliance.quantity.coerceIn(1, 999)
                val hours = state.applianceHoursPerWeek[appliance.id] ?: appliance.hoursPerWeek
                val isScheduled = appliance.usageModel == ApplianceUsageModel.POWER_SCHEDULE && hours != null
                val storedReferenceDays = appliance.referencePeriodDays.coerceAtLeast(1)
                val referenceDays = targetPeriodDays.takeIf { it > 0 } ?: storedReferenceDays
                val periodScale = referenceDays.toDouble() / storedReferenceDays
                val legacyUnitKwh = appliance.kwh.toDouble() / appliance.quantity.coerceAtLeast(1)
                val storedUnitKwh = appliance.unitKwh ?: legacyUnitKwh
                val unitActiveKwh: Double
                val unitStandbyKwh: Double
                if (isScheduled && appliance.powerWatts?.let { it > 0 } == true) {
                    unitActiveKwh = appliance.powerWatts / 1_000.0 * hours * referenceDays / 7.0
                    unitStandbyKwh = appliance.standbyWatts?.let { standbyWatts ->
                        standbyWatts / 1_000.0 * (168 - hours).coerceAtLeast(0) * referenceDays / 7.0
                    } ?: 0.0
                } else if (isScheduled && appliance.hoursPerWeek?.let { it > 0 } == true) {
                    val ratio = hours.toDouble() / appliance.hoursPerWeek.coerceAtLeast(1)
                    unitActiveKwh = storedUnitKwh * periodScale * ratio
                    unitStandbyKwh = 0.0
                } else {
                    unitActiveKwh = (storedUnitKwh * periodScale).coerceAtLeast(0.0)
                    unitStandbyKwh = 0.0
                }
                val unitTotalKwh = unitActiveKwh + unitStandbyKwh
                val totalKwh = (unitTotalKwh * quantity).roundToInt().coerceAtLeast(0)
                appliance.copy(
                    quantity = quantity,
                    hoursPerWeek = if (isScheduled) hours else null,
                    kwh = totalKwh,
                    unitKwh = unitTotalKwh,
                    referencePeriodDays = referenceDays,
                    standbySharePercent = if (unitTotalKwh > 0 && unitStandbyKwh > 0) {
                        (unitStandbyKwh * 100 / unitTotalKwh).roundToInt().coerceIn(0, 100)
                    } else null,
                )
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
        val uncertaintyFraction = if (energy.forecastBasis == EnergyForecastBasis.APPLIANCES) 0.20 else 0.08
        val uncertainty = (projected * uncertaintyFraction).roundToInt().coerceAtLeast(25)
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
        val latestBill = importedBillHistory.value.lastOrNull()
        val latestBillEndEpochDay = latestBill?.periodEndEpochDay
        val timedReadings = state.readings.filter { it.timestampEpochMillis != null }
        val newest = if (latestBillEndEpochDay != null) {
            timedReadings
                .filter {
                    it.method != ReadingMethod.PDF &&
                        (it.localEpochDay() ?: Long.MIN_VALUE) > latestBillEndEpochDay
                }
                .maxByOrNull { it.timestampEpochMillis!! }
        } else {
            timedReadings.filter { it.method != ReadingMethod.PDF }.maxByOrNull { it.timestampEpochMillis!! }
        }
        // Always measure the current period from its real opening reading. If
        // several intermediate readings exist, using only the last two would
        // incorrectly turn "consumed this period" into "consumed since the
        // previous check" and make the projection jump backwards.
        val baseline = if (latestBillEndEpochDay != null) {
            timedReadings
                .filter { (it.localEpochDay() ?: Long.MAX_VALUE) <= latestBillEndEpochDay }
                .maxByOrNull { it.timestampEpochMillis!! }
        } else {
            timedReadings
                .filter { it.method != ReadingMethod.PDF && it !== newest }
                .minByOrNull { it.timestampEpochMillis!! }
        }
        val elapsedMillis = if (newest?.timestampEpochMillis != null && baseline?.timestampEpochMillis != null) {
            newest.timestampEpochMillis - baseline.timestampEpochMillis
        } else 0L
        val newestEpochDay = newest?.localEpochDay()
        val measuredDays = when {
            latestBillEndEpochDay != null && newestEpochDay != null ->
                (newestEpochDay - latestBillEndEpochDay).toDouble().coerceAtLeast(0.0)
            else -> (elapsedMillis / MILLIS_PER_DAY.toDouble()).coerceAtLeast(0.0)
        }
        val measuredKwh = (newest?.kwh ?: 0) - (baseline?.kwh ?: 0)
        // The two endpoints imported from a PDF describe a bill that already
        // closed; they must not be mistaken for a live reading pair. Once the
        // user records a new reading, comparing it with the PDF endpoint is
        // valid and starts the current-period projection.
        val hasReliablePair = newest?.method != ReadingMethod.PDF &&
            baseline != null && measuredDays >= 0.5 && measuredKwh >= 0
        val hasUsageEvidence = hasReliablePair || latestBill != null
        val billingDays = when {
            latestBill?.periodStartEpochDay != null && latestBill.periodEndEpochDay != null ->
                (latestBill.periodEndEpochDay - latestBill.periodStartEpochDay).toInt().coerceAtLeast(1)
            state.billingCycle.contains("bi", ignoreCase = true) -> 61
            state.billingCycle.contains("men", ignoreCase = true) -> 30
            state.customAppliances.isNotEmpty() -> 30
            else -> 0
        }
        val observedDays = when {
            hasReliablePair -> measuredDays.toInt().coerceAtLeast(1)
            latestBill != null -> billingDays
            else -> 0
        }
        val observedKwh = when {
            hasReliablePair -> measuredKwh
            latestBill != null -> latestBill.consumptionKwh
            else -> 0
        }
        val dailyAverage = observedKwh.toDouble() / observedDays.coerceAtLeast(1)
        val meterProjectedKwh = if (hasReliablePair && billingDays > 0) {
            (dailyAverage * billingDays).roundToInt().coerceAtLeast(observedKwh)
        } else 0
        val engine = activeTariffEngine()
        val applianceProjectedKwh = adjustedAppliances(state, billingDays).sumOf { it.kwh }.coerceAtLeast(0)
        val applianceProjectedCost = if (applianceProjectedKwh > 0) {
            if (mutableTariffCalibration.value == null && latestBill?.consumptionKwh?.let { it > 0 } == true) {
                (applianceProjectedKwh * latestBill.amountMxn.toDouble() / latestBill.consumptionKwh)
                    .roundToInt()
            } else engine.estimate(applianceProjectedKwh).totalMxn
        } else 0
        // A meter already includes every appliance, so its projection and the
        // bottom-up inventory must never be added together. The inventory is a
        // second, independently visible estimate. It becomes the primary source
        // only while no current meter pace or closed bill is available.
        val forecastBasis = when {
            hasReliablePair -> EnergyForecastBasis.METER
            latestBill != null -> EnergyForecastBasis.CLOSED_BILL
            applianceProjectedKwh > 0 -> EnergyForecastBasis.APPLIANCES
            else -> EnergyForecastBasis.NONE
        }
        val projectedKwh = when (forecastBasis) {
            EnergyForecastBasis.METER -> meterProjectedKwh
            EnergyForecastBasis.CLOSED_BILL -> observedKwh
            EnergyForecastBasis.APPLIANCES -> applianceProjectedKwh
            EnergyForecastBasis.NONE -> 0
        }
        val costToDate = when {
            !hasUsageEvidence -> 0
            !hasReliablePair && latestBill != null -> latestBill.amountMxn
            else -> engine.estimate(observedKwh).totalMxn
        }
        val projectedCost = when {
            forecastBasis == EnergyForecastBasis.NONE -> 0
            !hasReliablePair && latestBill != null -> latestBill.amountMxn
            else -> engine.estimate(projectedKwh).totalMxn
        }
        return EnergySummary(
            observedKwh = observedKwh,
            observedDays = observedDays,
            billingDays = billingDays,
            daysRemaining = if (hasReliablePair) {
                val estimatedEndEpochDay = latestBillEndEpochDay?.plus(billingDays)
                if (estimatedEndEpochDay != null && newestEpochDay != null) {
                    val asOfEpochDay = maxOf(currentEpochDay(), newestEpochDay)
                    (estimatedEndEpochDay - asOfEpochDay).toInt().coerceAtLeast(0)
                } else {
                    (billingDays - observedDays).coerceAtLeast(0)
                }
            } else 0,
            dailyAverageKwh = dailyAverage,
            projectedKwh = projectedKwh,
            costToDateMxn = costToDate,
            projectedCostMxn = projectedCost,
            hasUsageEvidence = hasUsageEvidence || applianceProjectedKwh > 0,
            meterProjectedKwh = meterProjectedKwh,
            applianceProjectedKwh = applianceProjectedKwh,
            applianceProjectedCostMxn = applianceProjectedCost,
            forecastBasis = forecastBasis,
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }

    private fun HistoryReadingEntry.localEpochDay(): Long? {
        val timestamp = timestampEpochMillis ?: return null
        return if (method == ReadingMethod.PDF) {
            Math.floorDiv(timestamp, MILLIS_PER_DAY)
        } else {
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        }
    }

    /** Local calendar date keeps "days remaining" stable across DST and time-of-day changes. */
    protected open fun currentEpochDay(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

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

    override fun updateAppliance(
        applianceId: String,
        quantity: Int,
        spaceId: String?,
        room: String,
        brand: String?,
        model: String?,
        usageModel: ApplianceUsageModel,
        hoursPerWeek: Int?,
        powerWatts: Int?,
        unitKwh: Double?,
        dataSource: ApplianceDataSource,
    ) {
        _session.update { state ->
            val safeQuantity = quantity.coerceIn(1, 999)
            val safeHours = hoursPerWeek?.coerceIn(0, 168)
            val safePowerWatts = powerWatts?.coerceIn(1, 100_000)
            val safeUnitKwh = unitKwh?.coerceIn(0.0, 100_000.0)
            val safeRoom = room.trim().take(40).ifEmpty { "Toda la casa" }
            val updated = state.customAppliances.map { appliance ->
                if (appliance.id != applianceId) return@map appliance
                val effectivePower = safePowerWatts ?: appliance.powerWatts
                val referenceDays = appliance.referencePeriodDays.coerceIn(1, 366)
                val scheduledUnitKwh = if (
                    usageModel == ApplianceUsageModel.POWER_SCHEDULE && safeHours != null && effectivePower != null
                ) {
                    val active = effectivePower / 1_000.0 * safeHours * referenceDays / 7.0
                    val standby = appliance.standbyWatts?.let { standbyWatts ->
                        standbyWatts / 1_000.0 * (168 - safeHours).coerceAtLeast(0) * referenceDays / 7.0
                    } ?: 0.0
                    active + standby
                } else null
                val effectiveUnitKwh = scheduledUnitKwh ?: safeUnitKwh ?: appliance.unitKwh
                appliance.copy(
                    room = safeRoom,
                    spaceId = spaceId,
                    quantity = safeQuantity,
                    brand = brand?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
                    model = model?.trim()?.take(60)?.takeIf { it.isNotEmpty() },
                    usageModel = usageModel,
                    hoursPerWeek = if (usageModel == ApplianceUsageModel.POWER_SCHEDULE) safeHours else null,
                    powerWatts = effectivePower,
                    unitKwh = effectiveUnitKwh,
                    kwh = effectiveUnitKwh?.let { (it * safeQuantity).roundToInt() } ?: appliance.kwh,
                    calculationNote = when {
                        usageModel == ApplianceUsageModel.POWER_SCHEDULE && effectivePower != null && safeHours != null ->
                            "$effectivePower W promedio × $safeHours h/semana × $referenceDays días ÷ 7 ÷ 1,000"
                        usageModel == ApplianceUsageModel.PERIOD_KWH && safeUnitKwh != null ->
                            "$safeUnitKwh kWh por unidad en $referenceDays días"
                        else -> appliance.calculationNote
                    },
                    dataSource = dataSource,
                    confidence = when (dataSource) {
                        ApplianceDataSource.USER_MEASURED,
                        ApplianceDataSource.MANUFACTURER_LABEL -> com.recibozen.app.core.model.ConfidenceLevel.HIGH
                        ApplianceDataSource.CATALOG,
                        ApplianceDataSource.RECEIPT_INFERENCE -> com.recibozen.app.core.model.ConfidenceLevel.MEDIUM
                        ApplianceDataSource.USER_ESTIMATE -> com.recibozen.app.core.model.ConfidenceLevel.LOW
                    },
                    referenceAnnualKwh = if (dataSource == ApplianceDataSource.MANUFACTURER_LABEL) {
                        appliance.referenceAnnualKwh
                    } else null,
                    sourceLabel = when (dataSource) {
                        ApplianceDataSource.USER_MEASURED -> "Medición capturada por el usuario"
                        ApplianceDataSource.USER_ESTIMATE -> "Estimación ajustada por el usuario"
                        else -> appliance.sourceLabel
                    },
                )
            }
            val hoursMap = if (usageModel == ApplianceUsageModel.POWER_SCHEDULE && safeHours != null) {
                state.applianceHoursPerWeek + (applianceId to safeHours)
            } else state.applianceHoursPerWeek - applianceId
            state.copy(customAppliances = updated, applianceHoursPerWeek = hoursMap)
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
        mutableTariffCalibration.value?.takeIf { it.isCalibrated }?.snapshot?.blocks
            ?: if (TariffCatalog.normalize(mutableSession.value.tariff) == "DAC") emptyList()
            else TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS

    override fun premiumSubject(): String = "demo-install"

    override fun acceptPremiumLease(signedLease: String): EntitlementTier? = null

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

    override fun addHouseSpace(name: String): HouseSpace? {
        val cleanName = name.trim().replace(Regex("\\s+"), " ").take(40)
        if (cleanName.isBlank()) return null
        var created: HouseSpace? = null
        _session.update { state ->
            if (state.houseSpaces.any { it.name.equals(cleanName, ignoreCase = true) }) state
            else {
                created = HouseSpace("space_${UUID.randomUUID()}", cleanName)
                state.copy(houseSpaces = state.houseSpaces + created!!)
            }
        }
        return created
    }

    override fun renameHouseSpace(spaceId: String, name: String) {
        val cleanName = name.trim().replace(Regex("\\s+"), " ").take(40)
        if (cleanName.isBlank()) return
        _session.update { state ->
            if (state.houseSpaces.any { it.id != spaceId && it.name.equals(cleanName, ignoreCase = true) }) state
            else state.copy(
                houseSpaces = state.houseSpaces.map { if (it.id == spaceId) it.copy(name = cleanName) else it },
                customAppliances = state.customAppliances.map {
                    if (it.spaceId == spaceId) it.copy(room = cleanName) else it
                },
            )
        }
    }

    override fun removeHouseSpace(spaceId: String) {
        if (spaceId == HouseSpaceDefaults.HOME_ID) return
        _session.update { state ->
            if (state.houseSpaces.none { it.id == spaceId }) state
            else {
                val home = state.houseSpaces.firstOrNull { it.id == HouseSpaceDefaults.HOME_ID }
                    ?: HouseSpaceDefaults.items.first()
                state.copy(
                    houseSpaces = state.houseSpaces.filterNot { it.id == spaceId },
                    customAppliances = state.customAppliances.map {
                        if (it.spaceId == spaceId) it.copy(spaceId = home.id, room = home.name) else it
                    },
                )
            }
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
        _session.update { state ->
            val previousDomestic = TariffCatalog.normalize(state.tariff)
                .takeIf { TariffCatalog.find(it)?.highConsumptionLimitKwhPerMonth != null }
            val references = if (normalized == "DAC" && previousDomestic != null && state.activeServiceId.isNotBlank()) {
                state.dacReferenceTariffsByService + (state.activeServiceId to previousDomestic)
            } else state.dacReferenceTariffsByService
            state.copy(tariff = normalized, dacReferenceTariffsByService = references)
        }
        mutableElectricServices.update { services ->
            services.map { service ->
                if (service.id == _session.value.activeServiceId) service.copy(tariffCode = normalized) else service
            }
        }
    }

    override fun setDacReferenceTariff(tariff: String) {
        val normalized = TariffCatalog.normalize(tariff)
        if (TariffCatalog.find(normalized)?.highConsumptionLimitKwhPerMonth == null) return
        _session.update { state ->
            val serviceKey = state.activeServiceId.takeIf { it.isNotBlank() } ?: "current"
            state.copy(dacReferenceTariffsByService = state.dacReferenceTariffsByService + (serviceKey to normalized))
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
            val label = SimpleDateFormat("d MMM, h:mm a", Locale.forLanguageTag("es-MX")).format(Date(now))
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
