package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.calculation.TariffEngine
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.core.components.MeterDialMath
import com.recibozen.app.demo.data.DemoReciboZenData
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val tariffEngine = TariffEngine()

    protected val mutableSession = MutableStateFlow(ReciboZenSessionState())
    private val _session get() = mutableSession
    override val session: StateFlow<ReciboZenSessionState> = _session.asStateFlow()

    override fun appliances(state: ReciboZenSessionState): List<Appliance> {
        val adjusted = (DemoReciboZenData.appliances + state.customAppliances)
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
        val observed = energySummary(state).observedKwh.coerceAtLeast(1)
        return adjusted.map { appliance ->
            appliance.copy(sharePercent = (appliance.kwh * 100f / observed).roundToInt().coerceIn(0, 100))
        }
    }

    override fun appliance(id: String, state: ReciboZenSessionState): Appliance? =
        appliances(state).find { it.id == id }

    override fun billProjection(state: ReciboZenSessionState): BillProjection {
        val energy = energySummary(state)
        val cuts = state.planActions
            .filter { it.status == PlanActionStatus.TRACKING }
            .sumOf { it.estimatedCutMxn }
        val projected = (energy.projectedCostMxn - cuts).coerceAtLeast(0)
        val baseline = energy.projectedCostMxn.coerceAtLeast(1)
        val progress = ((baseline - projected).toFloat() / (baseline - state.goalMxn).coerceAtLeast(1)).coerceIn(0f, 1f)
        val uncertainty = (projected * 0.08).roundToInt().coerceAtLeast(25)
        return BillProjection(
            projectedMxn = projected,
            rangeLowMxn = (projected - uncertainty).coerceAtLeast(0),
            rangeHighMxn = projected + uncertainty,
            isWithinGoal = projected <= state.goalMxn,
            planProgressFraction = progress,
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
        val hasReliablePair = measuredDays >= 0.5 && measuredKwh >= 0
        val observedDays = if (hasReliablePair) measuredDays.roundToInt().coerceAtLeast(1) else DemoReciboZenData.OBSERVED_DAYS
        val observedKwh = if (hasReliablePair) measuredKwh else DemoReciboZenData.OBSERVED_KWH_TODAY
        val billingDays = if (state.billingCycle.contains("bi", ignoreCase = true)) 61 else 30
        val dailyAverage = observedKwh.toDouble() / observedDays.coerceAtLeast(1)
        val projectedKwh = (dailyAverage * billingDays).roundToInt().coerceAtLeast(observedKwh)
        return EnergySummary(
            observedKwh = observedKwh,
            observedDays = observedDays,
            billingDays = billingDays,
            daysRemaining = (billingDays - observedDays).coerceAtLeast(0),
            dailyAverageKwh = dailyAverage,
            projectedKwh = projectedKwh,
            costToDateMxn = tariffEngine.estimate(observedKwh).totalMxn,
            projectedCostMxn = tariffEngine.estimate(projectedKwh).totalMxn,
        )
    }

    override fun billHistory(state: ReciboZenSessionState): List<BillPeriod> {
        val projection = billProjection(state)
        val energy = energySummary(state)
        return DemoReciboZenData.billHistory.filterNot { it.isProjected } + BillPeriod(
            monthLabel = "Actual",
            amountMxn = projection.projectedMxn,
            isProjected = true,
            consumptionKwh = energy.projectedKwh,
        )
    }

    override fun dialReading(state: ReciboZenSessionState): DialReadingResult {
        val digits = MeterDialMath.digits(state.dialAngles, state.dialDirections)
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

    override fun saveSimulatorScenario() {
        _session.update { it.copy(savedSimulatorScenario = true) }
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
        _session.update { it.copy(meterType = meterType) }
    }

    override fun updateProfile(userName: String, householdName: String, location: String, goalMxn: Int) {
        _session.update {
            it.copy(
                userName = userName.trim().ifEmpty { it.userName },
                householdName = householdName.trim().ifEmpty { it.householdName },
                location = location.trim().ifEmpty { it.location },
                goalMxn = goalMxn.coerceAtLeast(1),
            )
        }
    }

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
