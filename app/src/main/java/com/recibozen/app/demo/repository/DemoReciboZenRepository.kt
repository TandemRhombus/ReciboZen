package com.recibozen.app.demo.repository

import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.core.components.MeterDialMath
import com.recibozen.app.demo.data.DemoReciboZenData
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-only implementation: everything lives in a single [MutableStateFlow]
 * and resets when the process dies. No persistence, no network — exactly what
 * this handoff phase asks for. Swap this class (not the interface) once real
 * storage/tariff logic exists.
 */
class DemoReciboZenRepository : ReciboZenRepository {

    private val _session = MutableStateFlow(ReciboZenSessionState())
    override val session: StateFlow<ReciboZenSessionState> = _session.asStateFlow()

    override fun appliances(state: ReciboZenSessionState): List<Appliance> =
        DemoReciboZenData.appliances.map { appliance ->
            if (appliance.id == "ps5") {
                val kwh = (state.ps5HoursPerWeek * 2.2).roundToInt()
                val cost = (state.ps5HoursPerWeek * 5.47).roundToInt()
                appliance.copy(hoursPerWeek = state.ps5HoursPerWeek, kwh = kwh, costMxn = cost)
            } else appliance
        }

    override fun appliance(id: String, state: ReciboZenSessionState): Appliance? =
        appliances(state).find { it.id == id }

    override fun billProjection(state: ReciboZenSessionState): BillProjection {
        val cuts = state.planActions
            .filter { it.status == PlanActionStatus.TRACKING }
            .sumOf { it.estimatedCutMxn }
        val projected = DemoReciboZenData.BASE_PROJECTED_MXN - cuts
        val progress = ((DemoReciboZenData.BASE_PROJECTED_MXN - projected) / 66f * 100f).coerceIn(5f, 100f)
        return BillProjection(
            projectedMxn = projected,
            rangeLowMxn = projected - 36,
            rangeHighMxn = projected + 44,
            isWithinGoal = projected <= DemoReciboZenData.GOAL_MXN,
            planProgressFraction = progress / 100f,
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

    override fun setPs5HoursPerWeek(hours: Int) {
        _session.update { it.copy(ps5HoursPerWeek = hours) }
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

    override fun setManualReadingDigits(digits: List<Int>) {
        _session.update { it.copy(manualReadingDigits = digits) }
    }

    override fun confirmReading(kwh: Int, method: ReadingMethod) {
        _session.update { state ->
            state.copy(
                lastConfirmedReadingKwh = kwh,
                readings = listOf(
                    HistoryReadingEntry(kwh, "Hoy", method, "registrada en esta sesión"),
                ) + state.readings,
            )
        }
    }
}
