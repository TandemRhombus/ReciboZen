package app.recibozen.android.demo.repository

import app.recibozen.android.core.designsystem.AppThemeMode
import app.recibozen.android.core.calculation.MeterDelta
import app.recibozen.android.core.calculation.MeterProfile
import app.recibozen.android.core.calculation.ReadingInterval
import app.recibozen.android.core.model.MeterReplacement
import app.recibozen.android.core.calculation.ApplianceAdviceEngine
import app.recibozen.android.core.calculation.ApplianceAdviceEvidence
import app.recibozen.android.core.calculation.ApplianceAdvicePriority
import app.recibozen.android.core.calculation.BillingPeriodTimeline
import app.recibozen.android.core.calculation.TariffEngine
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.HistoryUnit
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.EffortLevel
import app.recibozen.android.core.model.PlanAction
import app.recibozen.android.core.model.PlanActionStatus
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.core.model.ElectricServiceValidator
import app.recibozen.android.core.model.ElectricServiceIdentity
import app.recibozen.android.core.model.EntitlementPolicy
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.model.ServiceCreationResult
import app.recibozen.android.core.model.HouseSpace
import app.recibozen.android.core.model.HouseSpaceDefaults
import app.recibozen.android.core.importer.ParsedCfeBill
import app.recibozen.android.core.importer.BillImportResult
import app.recibozen.android.core.components.MeterDialMath
import app.recibozen.android.core.calculation.TariffCalibration
import app.recibozen.android.core.calculation.TariffCatalog
import app.recibozen.android.demo.data.DemoReciboZenData
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
 * used directly by previews; [app.recibozen.android.data.repository.PersistentReciboZenRepository] subclasses it and
 * mirrors the state to DataStore in the running application.
 */
open class DemoReciboZenRepository : ReciboZenRepository {

    private val defaultTariffEngine = TariffEngine()

    protected val mutableTariffCalibration = MutableStateFlow<TariffCalibrationState?>(null)
    override val tariffCalibration: StateFlow<TariffCalibrationState?> = mutableTariffCalibration.asStateFlow()

    /**
     * CFE's published rates for the active tariff, synced by the backend and
     * stored locally. Empty until the first successful sync; the app keeps
     * working from the bill or the generic blocks meanwhile.
     */
    protected val mutableRemoteTariffReference = MutableStateFlow<RemoteTariffReference?>(null)

    /**
     * Which of the three sources priced the current estimate. Screens read it
     * so a generic order of magnitude is never presented like a real rate.
     */
    override fun tariffRateOrigin(): TariffRateOrigin {
        val currentTariff = TariffCatalog.normalize(mutableSession.value.tariff)
        val calibration = mutableTariffCalibration.value
        return when {
            calibration?.isCalibrated == true &&
                TariffCatalog.normalize(calibration.snapshot.tariffCode) == currentTariff -> TariffRateOrigin.BILL
            usableRemoteReference(currentTariff) != null -> TariffRateOrigin.REMOTE_CATALOG
            else -> TariffRateOrigin.GENERIC
        }
    }

    /**
     * The synced catalog is only usable when it matches the active tariff and
     * the season is unambiguous. A seasonal tariff needs the locality's summer
     * start, which only the bill carries, so guessing a season here would bill
     * the wrong rate in silence.
     */
    private fun usableRemoteReference(currentTariff: String): RemoteTariffReference? =
        mutableRemoteTariffReference.value
            ?.takeIf { TariffCatalog.normalize(it.tariffCode) == currentTariff }
            ?.takeIf { it.blocks.isNotEmpty() && !it.isSeasonAmbiguous }

    /**
     * Never price DAC with the generic 1C blocks. A legible DAC breakdown wins;
     * otherwise the latest DAC bill supplies an all-in effective rate that is
     * refreshed at every import. It is an estimate, but it preserves the actual
     * tariff jump instead of silently reverting to subsidized domestic blocks.
     */
    protected fun activeTariffEngine(): TariffEngine {
        val currentTariff = TariffCatalog.normalize(mutableSession.value.tariff)
        val calibration = mutableTariffCalibration.value
        if (calibration?.isCalibrated == true &&
            TariffCatalog.normalize(calibration.snapshot.tariffCode) == currentTariff
        ) return calibration.snapshot.engine()
        usableRemoteReference(currentTariff)?.let { reference ->
            return TariffEngine(
                blocks = reference.blocks,
                fixedChargeMxn = reference.fixedChargeMxn ?: TariffCalibration.DEFAULT_FIXED_CHARGE_MXN,
                taxRate = TariffCalibration.DEFAULT_TAX_RATE,
            )
        }
        if (currentTariff == "DAC") {
            val latest = mutableImportedBillHistory.value
                .filter { TariffCatalog.normalize(it.tariffCode.orEmpty()) == "DAC" }
                .maxByOrNull { it.periodEndEpochDay ?: Long.MIN_VALUE }
            val effectiveRate = latest
                ?.takeIf { it.consumptionKwh > 0 && it.amountMxn > 0 }
                ?.let { it.amountMxn.toDouble() / it.consumptionKwh }
            if (effectiveRate != null) {
                return TariffEngine(
                    blocks = listOf(app.recibozen.android.core.calculation.TariffBlock(null, effectiveRate)),
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
                    unitStandbyKwh = appliance.standbyWatts?.takeIf { appliance.standbyEnabled }?.let { standbyWatts ->
                        standbyWatts / 1_000.0 * (168 - hours).coerceAtLeast(0) * referenceDays / 7.0
                    } ?: 0.0
                } else if (isScheduled && appliance.hoursPerWeek?.let { it > 0 } == true) {
                    val ratio = hours.toDouble() / appliance.hoursPerWeek.coerceAtLeast(1)
                    unitActiveKwh = storedUnitKwh * periodScale * ratio
                    // Standby was being dropped whenever the entry had a
                    // schedule but no rated power, so a TV or console with idle
                    // watts configured lost that consumption without saying so.
                    unitStandbyKwh = appliance.standbyWatts?.takeIf { appliance.standbyEnabled }?.let { standbyWatts ->
                        standbyWatts / 1_000.0 * (168 - hours).coerceAtLeast(0) * referenceDays / 7.0
                    } ?: 0.0
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
        val cuts = planActions(state)
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
        val baselineEpochDay = baseline?.localEpochDay()
        // The rate must be divided by the days the two readings actually cover.
        // Using the days since the bill closed while the numerator starts at an
        // earlier reading inflates the daily average and triggers a false
        // "you are consuming more than recommended".
        val intervalDays = when {
            newestEpochDay != null && baselineEpochDay != null ->
                (newestEpochDay - baselineEpochDay).toDouble().coerceAtLeast(0.0)
            else -> (elapsedMillis / MILLIS_PER_DAY.toDouble()).coerceAtLeast(0.0)
        }
        // Days of the current period the newest reading already covers. When the
        // baseline sits before the bill closed, only the part after it counts.
        val measuredDays = when {
            latestBillEndEpochDay != null && newestEpochDay != null ->
                (newestEpochDay - maxOf(latestBillEndEpochDay, baselineEpochDay ?: latestBillEndEpochDay))
                    .toDouble().coerceAtLeast(0.0)
            else -> intervalDays
        }
        // Plain subtraction here produced a negative number whenever the register
        // wrapped past its capacity, which then failed the sign guard below and
        // dropped the whole pair without telling anyone. MeterDelta either
        // establishes the consumption or names what it cannot tell apart, and
        // that name travels to the screen so the fallback stops being silent.
        val meterDelta = if (newest != null && baseline != null) {
            MeterDelta.consumption(
                previousKwh = baseline.kwh,
                currentKwh = newest.kwh,
                elapsedDays = intervalDays,
                capacityKwh = MeterProfile.capacity(state.meterType, state.meterDigitCount),
                multiplier = state.meterMultiplier,
                // Baseline and newest are routinely not adjacent, so the wraps
                // across the span are the sum of what was attested for each
                // interval inside it, not the newest entry's own value.
                attestedWraps = ReadingInterval.attestedWrapsBetween(timedReadings, baseline, newest),
                // A swap inside the span makes the two endpoints readings of
                // different registers, so it can never be ignored here.
                replacement = ReadingInterval.replacementForDelta(timedReadings, baseline, newest),
            )
        } else null
        val measuredKwh = (meterDelta as? MeterDelta.Measured)?.kwh ?: 0
        val meterPaceBlockedBy = (meterDelta as? MeterDelta.Undecidable)?.reason
        val measuredDailyKwh = if (intervalDays > 0.0) measuredKwh / intervalDays else 0.0
        // The two endpoints imported from a PDF describe a bill that already
        // closed; they must not be mistaken for a live reading pair. Once the
        // user records a new reading, comparing it with the PDF endpoint is
        // valid and starts the current-period projection.
        val hasReliablePair = newest?.method != ReadingMethod.PDF &&
            baseline != null && measuredDays >= 0.5 && meterDelta is MeterDelta.Measured
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
            // Only the share of the interval that falls inside the current
            // period. Identical to measuredKwh whenever the baseline reading
            // sits exactly on the bill's closing date, which is the usual case.
            hasReliablePair -> (measuredDailyKwh * observedDays).roundToInt().coerceAtLeast(0)
            latestBill != null -> latestBill.consumptionKwh
            else -> 0
        }
        val dailyAverage = when {
            hasReliablePair -> measuredDailyKwh
            else -> observedKwh.toDouble() / observedDays.coerceAtLeast(1)
        }
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
        val asOfEpochDay = maxOf(currentEpochDay(), newestEpochDay ?: Long.MIN_VALUE)
        // One timeline for every screen. It chains whole periods forward from
        // the latest bill, so a bill imported months ago is never displayed as
        // the period in progress.
        val currentPeriod = BillingPeriodTimeline.after(
            latestBill = latestBill,
            fallbackBillingDays = billingDays,
            asOfEpochDay = asOfEpochDay,
        )
        return EnergySummary(
            observedKwh = observedKwh,
            observedDays = observedDays,
            billingDays = billingDays,
            daysRemaining = if (hasReliablePair) {
                currentPeriod?.daysRemaining(asOfEpochDay) ?: (billingDays - observedDays).coerceAtLeast(0)
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
            meterPaceBlockedBy = meterPaceBlockedBy,
            currentPeriod = currentPeriod,
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        /** Enough to act on without turning the plan into a wall of cards. */
        const val MAX_PLAN_ACTIONS = 8

        /**
         * Advice ids repeat across appliances (two TVs share the same standby
         * tip), so the tracked id has to carry the appliance too.
         */
        fun planActionId(applianceId: String, adviceId: String): String = "$applianceId::$adviceId"
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

    /**
     * Interprets the dial faces into a reading. Nothing more.
     *
     * It used to also return a consumption, a daily rate and a warning, all
     * built from `value - lastConfirmedReadingKwh` divided by a hard-coded
     * `76.0 / 24.0` -- an interval nobody measured, left over from the demo
     * fixture. Every one of those four fields was dead: the only thing any
     * screen ever read was [DialReadingResult.valueKwh]. They are gone rather
     * than repaired, because [MeterDelta] on the confirm screen already answers
     * the same question correctly, and a second, wrong implementation kept for
     * later is worse than none.
     */
    override fun dialReading(state: ReciboZenSessionState): DialReadingResult {
        val allDigits = MeterDialMath.digits(state.dialAngles, state.dialDirections)
        val digits = MeterProfile.trimDigits(state.meterType, allDigits, state.meterDigitCount)
        return DialReadingResult(digits = digits, valueKwh = digits.joinToString("").toInt())
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
        standbyEnabled: Boolean,
        standbyWatts: Int?,
    ) {
        _session.update { state ->
            val safeQuantity = quantity.coerceIn(1, 999)
            val safeHours = hoursPerWeek?.coerceIn(0, 168)
            val safePowerWatts = powerWatts?.coerceIn(1, 100_000)
            val safeUnitKwh = unitKwh?.coerceIn(0.0, 100_000.0)
            val safeStandbyWatts = standbyWatts?.coerceIn(1, 10_000)
            val safeRoom = room.trim().take(40).ifEmpty { "Toda la casa" }
            val updated = state.customAppliances.map { appliance ->
                if (appliance.id != applianceId) return@map appliance
                val effectivePower = safePowerWatts ?: appliance.powerWatts
                val referenceDays = appliance.referencePeriodDays.coerceIn(1, 366)
                val scheduledUnitKwh = if (
                    usageModel == ApplianceUsageModel.POWER_SCHEDULE && safeHours != null && effectivePower != null
                ) {
                    val active = effectivePower / 1_000.0 * safeHours * referenceDays / 7.0
                    val standby = (safeStandbyWatts ?: appliance.standbyWatts)
                        ?.takeIf { standbyEnabled }
                        ?.let { standbyPower ->
                        standbyPower / 1_000.0 * (168 - safeHours).coerceAtLeast(0) * referenceDays / 7.0
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
                    standbyWatts = safeStandbyWatts ?: appliance.standbyWatts,
                    standbyEnabled = standbyEnabled && (safeStandbyWatts ?: appliance.standbyWatts) != null,
                    unitKwh = effectiveUnitKwh,
                    kwh = effectiveUnitKwh?.let { (it * safeQuantity).roundToInt() } ?: appliance.kwh,
                    calculationNote = when {
                        usageModel == ApplianceUsageModel.POWER_SCHEDULE && effectivePower != null && safeHours != null ->
                            buildString {
                                append("$effectivePower W promedio × $safeHours h/semana × $referenceDays días ÷ 7 ÷ 1,000")
                                (safeStandbyWatts ?: appliance.standbyWatts)?.takeIf { standbyEnabled }?.let {
                                    append(" + $it W en espera")
                                }
                            }
                        usageModel == ApplianceUsageModel.PERIOD_KWH && safeUnitKwh != null ->
                            "$safeUnitKwh kWh por unidad en $referenceDays días"
                        else -> appliance.calculationNote
                    },
                    dataSource = dataSource,
                    confidence = when (dataSource) {
                        ApplianceDataSource.USER_MEASURED,
                        ApplianceDataSource.MANUFACTURER_LABEL -> app.recibozen.android.core.model.ConfidenceLevel.HIGH
                        ApplianceDataSource.CATALOG,
                        ApplianceDataSource.RECEIPT_INFERENCE -> app.recibozen.android.core.model.ConfidenceLevel.MEDIUM
                        ApplianceDataSource.USER_ESTIMATE -> app.recibozen.android.core.model.ConfidenceLevel.LOW
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

    override fun activeTariffBlocks(): List<app.recibozen.android.core.calculation.TariffBlock> {
        val currentTariff = TariffCatalog.normalize(mutableSession.value.tariff)
        mutableTariffCalibration.value
            ?.takeIf { it.isCalibrated && TariffCatalog.normalize(it.snapshot.tariffCode) == currentTariff }
            ?.let { return it.snapshot.blocks }
        usableRemoteReference(currentTariff)?.let { return it.blocks }
        return if (currentTariff == "DAC") emptyList() else TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS
    }

    override fun premiumSubject(): String = "demo-install"

    override fun acceptPremiumLease(signedLease: String): EntitlementTier? = null

    override fun removeAppliance(applianceId: String) {
        _session.update { state ->
            state.copy(
                customAppliances = state.customAppliances.filterNot { it.id == applianceId },
                // Drop the override too, so re-adding the same appliance later
                // starts from its own defaults instead of a stale edit.
                applianceHoursPerWeek = state.applianceHoursPerWeek - applianceId,
                // Its tracked actions go with it; keeping them would subtract a
                // saving from an appliance that no longer exists.
                trackedActionIds = state.trackedActionIds.filterNot { it.startsWith("$applianceId::") }.toSet(),
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
                trackedActionIds = if (id in state.trackedActionIds) {
                    state.trackedActionIds - id
                } else {
                    state.trackedActionIds + id
                },
            )
        }
    }

    /**
     * The savings plan, derived from the household's own inventory on every
     * read. Persisting the generated list would freeze advice that must change
     * when an appliance, its hours or the tariff change; only the user's
     * decision to follow an action is durable.
     */
    override fun planActions(state: ReciboZenSessionState): List<PlanAction> {
        val energy = energySummary(state)
        val billingDays = energy.billingDays.coerceAtLeast(30)
        val inventory = appliances(state)
        if (inventory.isEmpty()) return emptyList()
        val effectiveRate = if (energy.projectedKwh > 0 && energy.projectedCostMxn > 0) {
            energy.projectedCostMxn.toDouble() / energy.projectedKwh
        } else 0.0
        return ApplianceAdviceEngine.rankInventory(inventory, billingDays, limit = MAX_PLAN_ACTIONS)
            .map { ranked ->
                val advice = ranked.advice
                val id = planActionId(ranked.applianceId, advice.id)
                val savingsMxn = advice.estimatedSavingsMxn
                    ?: advice.estimatedSavingsKwh?.let { (it * effectiveRate).roundToInt() }
                PlanAction(
                    id = id,
                    title = "${ranked.applianceName} · ${advice.title}",
                    savingsRangeLabel = savingsMxn?.takeIf { it > 0 }?.let { "$$it" }.orEmpty(),
                    effort = when (advice.priority) {
                        ApplianceAdvicePriority.HIGH -> EffortLevel.LOW
                        ApplianceAdvicePriority.MEDIUM -> EffortLevel.MEDIUM
                        ApplianceAdvicePriority.LOW -> EffortLevel.HIGH
                    },
                    // How solid the advice is: an official model guide is not the
                    // same thing as a generic tip for the category.
                    confidence = when (advice.evidence) {
                        ApplianceAdviceEvidence.MODEL -> ConfidenceLevel.HIGH
                        ApplianceAdviceEvidence.BRAND -> ConfidenceLevel.HIGH
                        ApplianceAdviceEvidence.DEVICE_DATA -> ConfidenceLevel.MEDIUM
                        ApplianceAdviceEvidence.CATEGORY -> ConfidenceLevel.LOW
                    },
                    status = if (id in state.trackedActionIds) {
                        PlanActionStatus.TRACKING
                    } else {
                        PlanActionStatus.SUGGESTED
                    },
                    estimatedCutMxn = savingsMxn?.coerceAtLeast(0) ?: 0,
                    applianceId = ranked.applianceId,
                    description = advice.description,
                    sourceLabel = advice.sourceLabel,
                    sourceUrl = advice.sourceUrl,
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

    override fun setSolarDemandGrowthPercent(percent: Int) {
        _session.update { it.copy(solarDemandGrowthPercent = percent.coerceIn(0, 100)) }
    }

    override fun setSolarPlannedLoad(loadId: String, annualKwh: Int?) {
        val safeId = loadId.trim().take(40)
        if (safeId.isEmpty()) return
        _session.update { state ->
            state.copy(
                solarPlannedLoadsKwh = if (annualKwh == null || annualKwh <= 0) {
                    state.solarPlannedLoadsKwh - safeId
                } else {
                    state.solarPlannedLoadsKwh + (safeId to annualKwh.coerceIn(1, 50_000))
                },
            )
        }
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

    override fun setTutorialCompleted(completed: Boolean) {
        mutableSession.update { it.copy(tutorialCompleted = completed) }
    }

    override fun setMeterType(meterType: String) {
        _session.update { state ->
            if (state.meterType == meterType) state else {
                // A digit count declared for the previous meter is not evidence
                // about this one. Dropping it costs one question; keeping it
                // would silently do rollover arithmetic against the capacity of
                // a meter the household just told us they no longer have.
                val firstFace = MeterProfile.firstFaceIndex(meterType)
                state.copy(
                    meterType = meterType,
                    meterTypeByService = state.withActiveService(state.meterTypeByService, meterType),
                    meterDigitCount = null,
                    meterDigitCountByService = state.withoutActiveService(state.meterDigitCountByService),
                    dialSelectedIndex = firstFace,
                    dialVisitedFaces = setOf(firstFace),
                    dialUnsure = false,
                )
            }
        }
    }

    override fun setMeterDigitCount(digitCount: Int?) {
        val declared = digitCount?.takeIf { it in MeterProfile.DECLARABLE_DIGITS }
        _session.update { state ->
            // The dial component shows one face per digit, so a declaration
            // changes which faces exist and the selection has to land inside
            // the new set rather than pointing past its end.
            val firstFace = MeterProfile.firstFaceIndex(state.meterType, declared)
            state.copy(
                meterDigitCount = declared,
                meterDigitCountByService = declared
                    ?.let { state.withActiveService(state.meterDigitCountByService, it) }
                    ?: state.withoutActiveService(state.meterDigitCountByService),
                dialSelectedIndex = firstFace,
                dialVisitedFaces = setOf(firstFace),
                dialUnsure = false,
            )
        }
    }

    override fun setMeterMultiplier(multiplier: Int) {
        val safe = multiplier.coerceAtLeast(1)
        _session.update { state ->
            state.copy(
                meterMultiplier = safe,
                meterMultiplierByService = state.withActiveService(state.meterMultiplierByService, safe),
            )
        }
    }

    /** Stores [value] for the active supply, or leaves the map alone if there is none yet. */
    private fun <T> ReciboZenSessionState.withActiveService(map: Map<String, T>, value: T): Map<String, T> =
        activeServiceId.takeIf { it.isNotBlank() }?.let { map + (it to value) } ?: map

    /**
     * Drops the active supply's entry. Absence is how these maps say "never
     * declared", so a cleared declaration has to remove the key rather than
     * store a placeholder that later reads as an answer.
     */
    private fun <T> ReciboZenSessionState.withoutActiveService(map: Map<String, T>): Map<String, T> =
        activeServiceId.takeIf { it.isNotBlank() }?.let { map - it } ?: map

    override fun updateProfile(userName: String, householdName: String, location: String, goalMxn: Int) {
        _session.update { state ->
            val cleanLocation = location.trim()
            val safeGoal = goalMxn.coerceAtLeast(0)
            state.copy(
                userName = userName.trim(),
                householdName = householdName.trim(),
                location = cleanLocation,
                goalMxn = safeGoal,
                // Goal and city follow the supply: switching services must not
                // carry over the goal of the other house.
                goalMxnByService = state.withActiveService(state.goalMxnByService, safeGoal),
                locationByService = state.withActiveService(state.locationByService, cleanLocation),
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
        _session.update { state ->
            val previousDomestic = TariffCatalog.normalize(state.tariff)
                .takeIf { TariffCatalog.find(it)?.highConsumptionLimitKwhPerMonth != null }
            val references = if (normalizedTariff == "DAC" && previousDomestic != null && state.activeServiceId.isNotBlank()) {
                state.dacReferenceTariffsByService + (state.activeServiceId to previousDomestic)
            } else state.dacReferenceTariffsByService
            state.copy(
                serviceAlias = alias.trim().ifEmpty { state.serviceAlias },
                serviceNumber = ElectricServiceValidator.normalizeServiceNumber(serviceNumber),
                rmu = ElectricServiceValidator.normalizeRmu(rmu),
                meterNumber = ElectricServiceValidator.normalizeMeterNumber(meterNumber),
                tariff = normalizedTariff,
                dacReferenceTariffsByService = references,
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
        _session.update { state ->
            // The first switch adopts whatever was configured before the supply
            // existed, so an upgrade to Plus never wipes the original setup.
            val goal = state.goalMxnByService[service.id] ?: state.goalMxn
            val meterType = state.meterTypeByService[service.id] ?: state.meterType
            val location = state.locationByService[service.id] ?: state.location
            // Absence here means "this supply never declared one", which is a
            // different thing from "the previous supply's value applies". Only
            // the first switch, before any per-service entry exists, adopts the
            // scalar; after that an unset supply stays unset.
            val digitCount = state.meterDigitCountByService[service.id]
                ?: state.meterDigitCount.takeIf { state.meterDigitCountByService.isEmpty() }
            val multiplier = state.meterMultiplierByService[service.id]
                ?: state.meterMultiplier.takeIf { state.meterMultiplierByService.isEmpty() }
                ?: 1
            state.copy(
                activeServiceId = service.id,
                serviceAlias = service.alias,
                serviceNumber = service.serviceNumber,
                rmu = service.rmu,
                meterNumber = service.meterNumber,
                tariff = service.tariffCode,
                billingCycle = service.billingCycle,
                goalMxn = goal,
                meterType = meterType,
                location = location,
                meterDigitCount = digitCount,
                meterMultiplier = multiplier,
                goalMxnByService = state.goalMxnByService + (service.id to goal),
                meterTypeByService = state.meterTypeByService + (service.id to meterType),
                locationByService = state.locationByService + (service.id to location),
                meterDigitCountByService = digitCount
                    ?.let { state.meterDigitCountByService + (service.id to it) }
                    ?: state.meterDigitCountByService,
                meterMultiplierByService = state.meterMultiplierByService + (service.id to multiplier),
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

    /**
     * Days since the household's latest timestamped reading. Zero when none
     * carries a timestamp: [MeterDelta] treats anything under half a day as "no
     * rate can be derived", which is exactly right for an unknown interval.
     */
    private fun elapsedDaysSinceLastReading(state: ReciboZenSessionState, nowMillis: Long): Double =
        ReadingInterval.daysSince(state.readings, nowMillis) ?: 0.0

    override fun confirmReading(
        kwh: Int,
        method: ReadingMethod,
        wrapsSincePrevious: Int?,
        meterReplacement: MeterReplacement?,
    ) {
        _session.update { state ->
            val now = System.currentTimeMillis()
            val label = SimpleDateFormat("d MMM, h:mm a", Locale.forLanguageTag("es-MX")).format(Date(now))
            // The note used to come from `kwh - lastConfirmedReadingKwh`, the
            // fourth raw subtraction in the app and the one that wrote "lectura
            // menor a la anterior" into history for every register that wrapped.
            // The attestation the user just gave answers that, so the note has
            // to be built from the same arithmetic the screen showed them.
            val delta = MeterDelta.consumption(
                previousKwh = state.lastConfirmedReadingKwh,
                currentKwh = kwh,
                elapsedDays = elapsedDaysSinceLastReading(state, now),
                capacityKwh = MeterProfile.capacity(state.meterType, state.meterDigitCount),
                multiplier = state.meterMultiplier,
                attestedWraps = wrapsSincePrevious,
                replacement = meterReplacement,
            )
            val note = when {
                delta is MeterDelta.Measured && delta.acrossMeterReplacement ->
                    "+${delta.kwh} kWh, contando el medidor anterior y el nuevo"
                meterReplacement != null && delta !is MeterDelta.Measured ->
                    "cambiaron el medidor; falta la lectura que cierra el tramo"
                delta is MeterDelta.Measured && delta.wraps > 0 ->
                    "+${delta.kwh} kWh, con ${delta.wraps} vuelta${if (delta.wraps == 1) "" else "s"} del medidor"
                delta is MeterDelta.Measured -> "+${delta.kwh} kWh desde la anterior"
                else -> "consumo sin determinar desde la anterior"
            }
            state.copy(
                lastConfirmedReadingKwh = kwh,
                readings = listOf(
                    HistoryReadingEntry(
                        kwh = kwh,
                        dateLabel = label,
                        method = method,
                        note = note,
                        timestampEpochMillis = now,
                        wrapsSincePrevious = wrapsSincePrevious,
                        meterReplacement = meterReplacement,
                    ),
                ) + state.readings,
                pendingReadingKwh = null,
                pendingReadingMethod = null,
            )
        }
    }
}
