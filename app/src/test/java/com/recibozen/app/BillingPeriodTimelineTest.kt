package com.recibozen.app

import com.recibozen.app.core.calculation.BillingPeriodTimeline
import com.recibozen.app.core.model.BillPeriod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BillingPeriodTimelineTest {
    @Test
    fun `real May to July receipt projects the following July to September period`() {
        val previous = BillPeriod(
            monthLabel = "May–Jul 26",
            amountMxn = 2_274,
            consumptionKwh = 646,
            periodStartEpochDay = LocalDate.of(2026, 5, 13).toEpochDay(),
            periodEndEpochDay = LocalDate.of(2026, 7, 14).toEpochDay(),
        )

        val current = BillingPeriodTimeline.after(previous)

        assertNotNull(current)
        assertEquals("Jul–Sep 26", current?.shortLabel)
        assertEquals("14 jul–14 sep 2026", current?.dateRangeLabel)
        assertEquals(LocalDate.of(2026, 9, 14).toEpochDay(), current?.endEpochDay)
    }
}
