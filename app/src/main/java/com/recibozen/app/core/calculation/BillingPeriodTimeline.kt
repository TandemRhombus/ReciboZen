package com.recibozen.app.core.calculation

import com.recibozen.app.core.model.BillPeriod
import java.time.LocalDate

data class CurrentBillingPeriod(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val shortLabel: String,
    val dateRangeLabel: String,
)

object BillingPeriodTimeline {
    private val shortMonths = arrayOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
    private val lowerMonths = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /**
     * CFE periods share their boundary date: a bill ending on 14 July is
     * followed by a period beginning on 14 July. We preserve the real length
     * of the latest bill instead of assuming every bimonthly period has 60 days.
     */
    fun after(latestBill: BillPeriod?, fallbackBillingDays: Int = 0): CurrentBillingPeriod? {
        val startEpochDay = latestBill?.periodEndEpochDay ?: return null
        val realLength = if (latestBill.periodStartEpochDay != null) {
            (startEpochDay - latestBill.periodStartEpochDay).toInt()
        } else fallbackBillingDays
        val billingDays = realLength.takeIf { it > 0 } ?: fallbackBillingDays.takeIf { it > 0 } ?: return null
        val endEpochDay = startEpochDay + billingDays
        val start = LocalDate.ofEpochDay(startEpochDay)
        val end = LocalDate.ofEpochDay(endEpochDay)
        return CurrentBillingPeriod(
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            shortLabel = "${shortMonths[start.monthValue - 1]}–${shortMonths[end.monthValue - 1]} ${end.year.toString().takeLast(2)}",
            dateRangeLabel = if (start.year == end.year) {
                "${start.dayOfMonth} ${lowerMonths[start.monthValue - 1]}–${end.dayOfMonth} ${lowerMonths[end.monthValue - 1]} ${end.year}"
            } else {
                "${start.dayOfMonth} ${lowerMonths[start.monthValue - 1]} ${start.year}–${end.dayOfMonth} ${lowerMonths[end.monthValue - 1]} ${end.year}"
            },
        )
    }
}
