package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.BillPeriod
import java.time.LocalDate

data class CurrentBillingPeriod(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val billingDays: Int,
    /** 1 when this is the period right after the latest bill, 2 for the one after that, and so on. */
    val periodsAfterLatestBill: Int,
    val shortLabel: String,
    val dateRangeLabel: String,
) {
    /** True when at least one whole period closed between the latest bill and today. */
    val isBeyondNextPeriod: Boolean get() = periodsAfterLatestBill > 1

    fun daysRemaining(asOfEpochDay: Long): Int = (endEpochDay - asOfEpochDay).toInt().coerceAtLeast(0)

    fun daysElapsed(asOfEpochDay: Long): Int =
        (asOfEpochDay - startEpochDay).toInt().coerceIn(0, billingDays)
}

object BillingPeriodTimeline {
    private val shortMonths = arrayOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
    private val lowerMonths = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /**
     * A bill that closed long ago no longer describes the period the household
     * is living now. Chaining further than this would be guesswork, so the
     * timeline reports nothing instead of inventing dates.
     */
    private const val MAX_PERIODS_AHEAD = 12

    /**
     * CFE periods share their boundary date: a bill ending on 14 July is
     * followed by a period beginning on 14 July. We preserve the real length
     * of the latest bill instead of assuming every bimonthly period has 60 days.
     *
     * When [asOfEpochDay] is provided, consecutive periods are chained until one
     * contains that date. Without it an old imported bill keeps being presented
     * as "the current period" months after it closed.
     */
    fun after(
        latestBill: BillPeriod?,
        fallbackBillingDays: Int = 0,
        asOfEpochDay: Long? = null,
    ): CurrentBillingPeriod? {
        val firstStart = latestBill?.periodEndEpochDay ?: return null
        val realLength = if (latestBill.periodStartEpochDay != null) {
            (firstStart - latestBill.periodStartEpochDay).toInt()
        } else fallbackBillingDays
        val billingDays = realLength.takeIf { it > 0 } ?: fallbackBillingDays.takeIf { it > 0 } ?: return null

        var startEpochDay = firstStart
        var periodsAhead = 1
        if (asOfEpochDay != null) {
            while (startEpochDay + billingDays <= asOfEpochDay) {
                if (periodsAhead >= MAX_PERIODS_AHEAD) return null
                startEpochDay += billingDays
                periodsAhead++
            }
        }
        val endEpochDay = startEpochDay + billingDays
        val start = LocalDate.ofEpochDay(startEpochDay)
        val end = LocalDate.ofEpochDay(endEpochDay)
        val sameMonth = start.monthValue == end.monthValue && start.year == end.year
        return CurrentBillingPeriod(
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            billingDays = billingDays,
            periodsAfterLatestBill = periodsAhead,
            shortLabel = if (sameMonth) {
                "${shortMonths[start.monthValue - 1]} ${end.year.toString().takeLast(2)}"
            } else {
                "${shortMonths[start.monthValue - 1]}–${shortMonths[end.monthValue - 1]} ${end.year.toString().takeLast(2)}"
            },
            dateRangeLabel = if (start.year == end.year) {
                "${start.dayOfMonth} ${lowerMonths[start.monthValue - 1]}–${end.dayOfMonth} ${lowerMonths[end.monthValue - 1]} ${end.year}"
            } else {
                "${start.dayOfMonth} ${lowerMonths[start.monthValue - 1]} ${start.year}–${end.dayOfMonth} ${lowerMonths[end.monthValue - 1]} ${end.year}"
            },
        )
    }
}
