package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One real bill set against the same period from before the household used
 * ReciboZen.
 */
data class PeriodSaving(
    val label: String,
    val baselineLabel: String,
    val periodEndEpochDay: Long,
    val kwh: Int,
    /** The baseline's consumption stretched or shrunk to this period's days. */
    val baselineKwh: Int,
    /** Positive when this period used less than its baseline. Negative is kept. */
    val savedKwh: Int,
    val savedMxn: Int,
)

data class RealizedSavings(
    val trackingStartEpochDay: Long,
    val periods: List<PeriodSaving>,
    /** Bills since the start that have no comparable period before it. */
    val periodsWithoutBaseline: Int,
) {
    /** The sum of the rows the household sees, so the card always adds up. */
    val totalSavedMxn: Int get() = periods.sumOf { it.savedMxn }
    val totalSavedKwh: Int get() = periods.sumOf { it.savedKwh }

    /** Too small a difference to call a saving or an increase on a CFE bill. */
    val isEven: Boolean get() = abs(totalSavedMxn) < RealizedSavingsCalculator.EVEN_THRESHOLD_MXN
}

/**
 * How much less a household paid for consuming less, since it started using
 * ReciboZen.
 *
 * "Last bill minus this bill" is the obvious answer and it is wrong in three
 * ways that each hand the household a saving it did not make:
 *
 * - **Season.** A bimonthly bill in December is not comparable with one in June;
 *   in the north the summer one can be triple. Every bill is therefore compared
 *   with the SAME period of an earlier year, which CFE prints on every receipt
 *   (eleven past periods), so the comparison exists from the first import.
 * - **Period length.** A period five days shorter uses less without anyone doing
 *   anything. The baseline is stretched to the same number of days first.
 * - **Rates.** A tariff update, a subsidy change or a summer rate ending moves
 *   the amount charged without a single kWh changing. What the bills cost is
 *   never read here: both consumptions are priced with the tariff in force
 *   today, so the only thing that can produce a saving is consuming less.
 *
 * The baseline is the most recent same period that ended before the household
 * started using the app, not simply last year's. After a year of use, last
 * year's bill already carries the savings being measured, and comparing
 * against it would report them as gone.
 *
 * What this cannot know is the cause. Less consumption may be the app, the
 * weather, or a trip. The screen says "consumiste menos", never "ReciboZen te
 * ahorró".
 */
object RealizedSavingsCalculator {
    /**
     * CFE period boundaries drift by a few days from one year to the next. Wide
     * enough for that, and far narrower than the 30 days to the neighbouring
     * period of a monthly service, so a missing comparison is never quietly
     * replaced by the wrong season.
     */
    const val SAME_PERIOD_TOLERANCE_DAYS = 15

    const val EVEN_THRESHOLD_MXN = 5

    private const val DAYS_PER_YEAR = 365L
    private const val MAX_YEARS_BACK = 5

    /**
     * When the household started using ReciboZen: its first imported bill or its
     * first reading taken by hand, whichever came first.
     *
     * Readings with method PDF are excluded on purpose. They are extracted from a
     * bill and carry that bill's dates, which can be a year before the app was
     * installed; counting them would start the clock before the household ever
     * opened it and claim a year of savings it had nothing to do with.
     */
    fun trackingStart(
        firstBillImportEpochDay: Long?,
        readings: List<HistoryReadingEntry>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val firstLiveReading = readings.asSequence()
            .filter { it.method != ReadingMethod.PDF }
            .mapNotNull { it.timestampEpochMillis }
            .minOrNull()
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
        return listOfNotNull(firstBillImportEpochDay, firstLiveReading).minOrNull()
    }

    /**
     * @param costOf price of a consumption under today's tariff, unrounded.
     * @return null while there is no start date, because without one there is no
     * "since" to measure from.
     */
    fun compute(
        bills: List<BillPeriod>,
        trackingStartEpochDay: Long?,
        costOf: (Int) -> Double,
    ): RealizedSavings? {
        val start = trackingStartEpochDay ?: return null
        val comparable = bills.filter { it.isComparable() }
        val beforeStart = comparable.filter { it.periodEndEpochDay!! <= start }

        var withoutBaseline = 0
        val periods = comparable
            .filter { it.periodEndEpochDay!! > start }
            .sortedBy { it.periodEndEpochDay }
            .mapNotNull { bill ->
                val baseline = samePeriodBefore(bill, beforeStart)
                if (baseline == null) {
                    withoutBaseline++
                    return@mapNotNull null
                }
                saving(bill, baseline, costOf)
            }

        return RealizedSavings(
            trackingStartEpochDay = start,
            periods = periods,
            periodsWithoutBaseline = withoutBaseline,
        )
    }

    /** The most recent earlier year whose same period is on record. */
    private fun samePeriodBefore(bill: BillPeriod, candidates: List<BillPeriod>): BillPeriod? {
        val end = bill.periodEndEpochDay!!
        for (years in 1..MAX_YEARS_BACK) {
            val target = end - years * DAYS_PER_YEAR
            val match = candidates
                .filter { abs(it.periodEndEpochDay!! - target) <= SAME_PERIOD_TOLERANCE_DAYS }
                .minByOrNull { abs(it.periodEndEpochDay!! - target) }
            if (match != null) return match
        }
        return null
    }

    private fun saving(bill: BillPeriod, baseline: BillPeriod, costOf: (Int) -> Double): PeriodSaving {
        val baselineAtSameDays = (baseline.consumptionKwh.toDouble() * bill.days() / baseline.days()).roundToInt()
        return PeriodSaving(
            label = bill.monthLabel,
            baselineLabel = baseline.monthLabel,
            periodEndEpochDay = bill.periodEndEpochDay!!,
            kwh = bill.consumptionKwh,
            baselineKwh = baselineAtSameDays,
            savedKwh = baselineAtSameDays - bill.consumptionKwh,
            // Priced at today's tariff on both sides. A saving that crosses back
            // out of the expensive block is worth the expensive block, which an
            // average rate would understate.
            savedMxn = (costOf(baselineAtSameDays) - costOf(bill.consumptionKwh)).roundToInt(),
        )
    }

    /** Real, measured, and dated. The amount charged is deliberately not required. */
    private fun BillPeriod.isComparable(): Boolean =
        !isProjected && consumptionKwh > 0 && periodStartEpochDay != null &&
            periodEndEpochDay != null && (periodEndEpochDay - periodStartEpochDay) in 1..400

    private fun BillPeriod.days(): Long = periodEndEpochDay!! - periodStartEpochDay!!
}
