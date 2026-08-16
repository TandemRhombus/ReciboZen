package com.recibozen.app.core.calculation

import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.MonthlyEnergyPoint
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.core.model.SolarScenario
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

data class SolarEstimate(
    /** Annual demand used to size the scenarios, including the user's growth reserve. */
    val annualConsumptionKwh: Int,
    val rollingAnnualConsumptionKwh: Int,
    val historicalPeakAnnualConsumptionKwh: Int,
    val designGrowthPercent: Int,
    val plannedAnnualKwh: Int,
    val coveredDays: Int,
    val historySpanDays: Int,
    val manualEvidenceDays: Int,
    val annualYieldKwhPerKwp: Int,
    val scenarios: List<SolarScenario>,
    val monthlyEnergy: List<MonthlyEnergyPoint>,
)

object SolarEstimator {
    const val PANEL_WATTS = 550
    const val PANEL_AREA_M2 = 2.6
    const val PERFORMANCE_RATIO = 0.80
    private const val WINDOW_DAYS = 365L
    // Do not extrapolate a partial season into a "historical peak year": that
    // would exaggerate homes whose high-consumption months happen to be the
    // only older evidence available. Peak comparisons require a complete year.
    private const val MIN_PEAK_WINDOW_DAYS = 365
    private val TARGETS = listOf(0.50, 0.80, 0.95)

    fun estimate(
        history: List<BillPeriod>,
        monthlyDailyIrradiance: List<Double>,
        readings: List<HistoryReadingEntry> = emptyList(),
        demandGrowthPercent: Int = 0,
        plannedAnnualKwh: Int = 0,
    ): SolarEstimate? {
        if (monthlyDailyIrradiance.size != 12 || monthlyDailyIrradiance.any { it <= 0.0 }) return null
        val billedPeriods = history.mapNotNull { bill ->
            val start = bill.periodStartEpochDay ?: return@mapNotNull null
            val end = bill.periodEndEpochDay ?: return@mapNotNull null
            if (end <= start || bill.consumptionKwh <= 0) null else Period(start, end, bill.consumptionKwh)
        }.distinctBy { it.start to it.end }
        val manualPeriods = readingPeriods(readings).filter { candidate ->
            billedPeriods.none { billed -> candidate.start < billed.end && candidate.end > billed.start }
        }
        val periods = (billedPeriods + manualPeriods).sortedBy { it.start }
        if (periods.isEmpty()) return null
        val asOf = periods.maxOf { it.end }
        val start = asOf - WINDOW_DAYS
        val annualWindow = consumptionBetween(periods, start, asOf)
        if (annualWindow.coveredDays <= 0) return null
        val rollingAnnualConsumption = annualized(annualWindow)
        val historicalPeak = periods.asSequence()
            .map { it.end }
            .distinct()
            .map { end -> consumptionBetween(periods, end - WINDOW_DAYS, end) }
            .filter { it.coveredDays >= MIN_PEAK_WINDOW_DAYS }
            .map(::annualized)
            .maxOrNull()
            ?.coerceAtLeast(rollingAnnualConsumption)
            ?: rollingAnnualConsumption
        val safeGrowthPercent = demandGrowthPercent.coerceIn(0, 100)
        val safePlannedAnnualKwh = plannedAnnualKwh.coerceIn(0, 100_000)
        val designBaseline = maxOf(rollingAnnualConsumption, historicalPeak)
        val annualConsumption = ((designBaseline * (1.0 + safeGrowthPercent / 100.0)).roundToInt() +
            safePlannedAnnualKwh).coerceAtLeast(1)
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
                note = "Dimensionado para $annualConsumption kWh/año: el mayor año móvil registrado" +
                    (if (safeGrowthPercent > 0) " más $safeGrowthPercent% de reserva" else "") +
                    (if (safePlannedAnnualKwh > 0) " y $safePlannedAnnualKwh kWh/año de cargas futuras" else "") +
                    ", recurso solar por ubicación y desempeño del ${(PERFORMANCE_RATIO * 100).roundToInt()}%.",
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
            rollingAnnualConsumptionKwh = rollingAnnualConsumption,
            historicalPeakAnnualConsumptionKwh = historicalPeak,
            designGrowthPercent = safeGrowthPercent,
            plannedAnnualKwh = safePlannedAnnualKwh,
            coveredDays = annualWindow.coveredDays,
            historySpanDays = (periods.maxOf { it.end } - periods.minOf { it.start }).toInt().coerceAtLeast(0),
            manualEvidenceDays = manualPeriods.sumOf { (it.end - it.start).toInt() },
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

    private fun annualized(window: Window): Int =
        (window.kwh * WINDOW_DAYS / window.coveredDays.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)

    private fun readingPeriods(readings: List<HistoryReadingEntry>): List<Period> {
        val points = readings.mapNotNull { reading ->
            val timestamp = reading.timestampEpochMillis ?: return@mapNotNull null
            val day = if (reading.method == ReadingMethod.PDF) {
                Math.floorDiv(timestamp, MILLIS_PER_DAY)
            } else {
                Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            }
            ReadingPoint(day, reading.kwh)
        }.filter { it.kwh >= 0 }
            .sortedBy { it.day }
            .distinctBy { it.day to it.kwh }
        return points.zipWithNext().mapNotNull { (from, to) ->
            val days = to.day - from.day
            val consumed = to.kwh - from.kwh
            if (days !in 1..183 || consumed <= 0) null else Period(from.day, to.day, consumed)
        }
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
    private data class ReadingPoint(val day: Long, val kwh: Int)
    private const val MILLIS_PER_DAY = 86_400_000L
    private val MONTHS = listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
}
