package com.recibozen.app.core.calculation

import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.MonthlyEnergyPoint
import com.recibozen.app.core.model.SolarScenario
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.roundToInt

data class SolarEstimate(
    val annualConsumptionKwh: Int,
    val coveredDays: Int,
    val annualYieldKwhPerKwp: Int,
    val scenarios: List<SolarScenario>,
    val monthlyEnergy: List<MonthlyEnergyPoint>,
)

object SolarEstimator {
    const val PANEL_WATTS = 550
    const val PANEL_AREA_M2 = 2.6
    const val PERFORMANCE_RATIO = 0.80
    private const val WINDOW_DAYS = 365L
    private val TARGETS = listOf(0.50, 0.80, 0.95)

    fun estimate(history: List<BillPeriod>, monthlyDailyIrradiance: List<Double>): SolarEstimate? {
        if (monthlyDailyIrradiance.size != 12 || monthlyDailyIrradiance.any { it <= 0.0 }) return null
        val periods = history.mapNotNull { bill ->
            val start = bill.periodStartEpochDay ?: return@mapNotNull null
            val end = bill.periodEndEpochDay ?: return@mapNotNull null
            if (end <= start || bill.consumptionKwh <= 0) null else Period(start, end, bill.consumptionKwh)
        }.distinctBy { it.start to it.end }
        if (periods.isEmpty()) return null
        val asOf = periods.maxOf { it.end }
        val start = asOf - WINDOW_DAYS
        val annualWindow = consumptionBetween(periods, start, asOf)
        if (annualWindow.coveredDays <= 0) return null
        val annualConsumption = (annualWindow.kwh * WINDOW_DAYS / annualWindow.coveredDays)
            .roundToInt().coerceAtLeast(1)
        val annualYield = monthlyDailyIrradiance.mapIndexed { index, daily ->
            daily * YearMonth.of(2024, index + 1).lengthOfMonth() * PERFORMANCE_RATIO
        }.sum().roundToInt().coerceAtLeast(1)

        val scenarios = TARGETS.mapIndexed { index, target ->
            val panels = ceil(annualConsumption * target / (annualYield * PANEL_WATTS / 1_000.0))
                .toInt().coerceAtLeast(1)
            val kwp = panels * PANEL_WATTS / 1_000.0
            val production = (kwp * annualYield).roundToInt()
            val coverage = (production * 100.0 / annualConsumption).roundToInt().coerceAtMost(100)
            SolarScenario(
                name = listOf("Escenario económico", "Escenario recomendado", "Escenario de mayor cobertura")[index],
                tabLabel = listOf("Económico", "Recomendado", "Mayor cobertura")[index],
                coverageLabel = "$coverage%",
                panels = panels.toString(),
                systemPower = String.format(java.util.Locale.US, "%.2f kWp", kwp),
                area = String.format(java.util.Locale.US, "%.1f m²", panels * PANEL_AREA_M2),
                production = "$production kWh/año",
                gridRemaining = "${(annualConsumption - production).coerceAtLeast(0)} kWh/año",
                note = "Estimación con recurso solar por ubicación, paneles de $PANEL_WATTS W y factor de desempeño del ${(PERFORMANCE_RATIO * 100).roundToInt()}%.",
            )
        }
        val selectedPanels = scenarios[1].panels.toInt()
        val selectedKwp = selectedPanels * PANEL_WATTS / 1_000.0
        val months = (0 until 12).map { offset -> YearMonth.from(LocalDate.ofEpochDay(asOf).minusMonths((11 - offset).toLong())) }
        val rawMonthly = months.map { month ->
            val monthStart = month.atDay(1).toEpochDay().coerceAtLeast(start)
            val monthEnd = month.plusMonths(1).atDay(1).toEpochDay().coerceAtMost(asOf)
            val consumption = if (monthEnd > monthStart) consumptionBetween(periods, monthStart, monthEnd).kwh else 0.0
            val production = selectedKwp * monthlyDailyIrradiance[month.monthValue - 1] * month.lengthOfMonth() * PERFORMANCE_RATIO
            Triple(month, consumption, production)
        }
        val scale = rawMonthly.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1.0)
        return SolarEstimate(
            annualConsumptionKwh = annualConsumption,
            coveredDays = annualWindow.coveredDays,
            annualYieldKwhPerKwp = annualYield,
            scenarios = scenarios,
            monthlyEnergy = rawMonthly.map { (month, consumption, production) ->
                MonthlyEnergyPoint(
                    monthLabel = MONTHS[month.monthValue - 1],
                    consumptionFraction = (consumption / scale).toFloat(),
                    productionFraction = (production / scale).toFloat(),
                    consumptionKwh = consumption.roundToInt(),
                    productionKwh = production.roundToInt(),
                )
            },
        )
    }

    private fun consumptionBetween(periods: List<Period>, start: Long, end: Long): Window {
        val overlaps = periods.mapNotNull { period ->
            val overlapStart = maxOf(start, period.start)
            val overlapEnd = minOf(end, period.end)
            val days = overlapEnd - overlapStart
            if (days <= 0) null else Overlap(
                overlapStart,
                overlapEnd,
                period.kwh * days.toDouble() / (period.end - period.start).coerceAtLeast(1),
            )
        }
        val covered = overlaps
            .sortedBy { it.start }
            .fold(mutableListOf<Pair<Long, Long>>()) { merged, overlap ->
                val last = merged.lastOrNull()
                if (last == null || overlap.start > last.second) merged += overlap.start to overlap.end
                else merged[merged.lastIndex] = last.first to maxOf(last.second, overlap.end)
                merged
            }.sumOf { (it.second - it.first).toInt() }
        return Window(overlaps.sumOf { it.kwh }, covered)
    }

    private data class Period(val start: Long, val end: Long, val kwh: Int)
    private data class Overlap(val start: Long, val end: Long, val kwh: Double)
    private data class Window(val kwh: Double, val coveredDays: Int)
    private val MONTHS = listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
}
