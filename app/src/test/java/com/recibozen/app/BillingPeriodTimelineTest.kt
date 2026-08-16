package com.recibozen.app

import com.recibozen.app.core.calculation.BillingPeriodTimeline
import com.recibozen.app.core.model.BillPeriod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingPeriodTimelineTest {
    private fun bill(start: LocalDate, end: LocalDate, kwh: Int = 646) = BillPeriod(
        monthLabel = "",
        amountMxn = 2_274,
        consumptionKwh = kwh,
        periodStartEpochDay = start.toEpochDay(),
        periodEndEpochDay = end.toEpochDay(),
    )

    @Test
    fun `real May to July receipt projects the following July to September period`() {
        val previous = bill(LocalDate.of(2026, 5, 13), LocalDate.of(2026, 7, 14))

        val current = BillingPeriodTimeline.after(previous)

        assertNotNull(current)
        assertEquals("Jul–Sep 26", current?.shortLabel)
        assertEquals("14 jul–14 sep 2026", current?.dateRangeLabel)
        assertEquals(LocalDate.of(2026, 9, 14).toEpochDay(), current?.endEpochDay)
        assertEquals(1, current?.periodsAfterLatestBill)
        assertFalse(current?.isBeyondNextPeriod ?: true)
    }

    @Test
    fun `a receipt whose next period already closed rolls forward to the period containing today`() {
        // The bill closed 14 Jul 2026 and today is 20 Jan 2027: several whole
        // periods later. Presenting Jul-Sep as "the current period" would be wrong.
        val previous = bill(LocalDate.of(2026, 5, 13), LocalDate.of(2026, 7, 14))
        val today = LocalDate.of(2027, 1, 20).toEpochDay()

        val current = BillingPeriodTimeline.after(latestBill = previous, asOfEpochDay = today)

        assertNotNull(current)
        assertTrue(current!!.startEpochDay <= today)
        assertTrue(current.endEpochDay > today)
        assertTrue(current.isBeyondNextPeriod)
        assertEquals(62, current.billingDays)
    }

    @Test
    fun `today inside the first period after the receipt is not rolled forward`() {
        val previous = bill(LocalDate.of(2026, 5, 13), LocalDate.of(2026, 7, 14))
        val today = LocalDate.of(2026, 8, 1).toEpochDay()

        val current = BillingPeriodTimeline.after(latestBill = previous, asOfEpochDay = today)

        assertEquals(1, current?.periodsAfterLatestBill)
        assertEquals(LocalDate.of(2026, 7, 14).toEpochDay(), current?.startEpochDay)
        assertEquals(44, current?.daysRemaining(today))
        assertEquals(18, current?.daysElapsed(today))
    }

    @Test
    fun `a receipt older than the roll forward limit reports no current period`() {
        val previous = bill(LocalDate.of(2020, 1, 10), LocalDate.of(2020, 3, 10))

        val current = BillingPeriodTimeline.after(
            latestBill = previous,
            asOfEpochDay = LocalDate.of(2026, 8, 16).toEpochDay(),
        )

        assertNull(current)
    }

    @Test
    fun `a leap day period keeps its real length and does not shift`() {
        val previous = bill(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 15))

        val current = BillingPeriodTimeline.after(previous)

        assertEquals(60, current?.billingDays)
        assertEquals(LocalDate.of(2024, 5, 14).toEpochDay(), current?.endEpochDay)
    }

    @Test
    fun `a period that crosses new year keeps both years in the range label`() {
        val previous = bill(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 11, 10))

        val current = BillingPeriodTimeline.after(previous)

        assertEquals("10 nov 2026–10 ene 2027", current?.dateRangeLabel)
    }

    @Test
    fun `a receipt without dates cannot produce a period`() {
        val undated = BillPeriod(monthLabel = "", amountMxn = 800, consumptionKwh = 200)

        assertNull(BillingPeriodTimeline.after(undated))
        assertNull(BillingPeriodTimeline.after(null))
    }
}
