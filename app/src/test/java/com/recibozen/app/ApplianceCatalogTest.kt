package com.recibozen.app

import com.recibozen.app.core.calculation.ApplianceCalculator
import com.recibozen.app.core.calculation.ApplianceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplianceCatalogTest {
    @Test
    fun calculatesUsageFromLabelPowerAndSchedule() {
        val estimate = ApplianceCalculator.fromPower(
            watts = 1_000,
            hoursPerDay = 2.0,
            daysPerWeek = 7,
            billingDays = 30,
            effectiveRateMxnPerKwh = 2.5,
        )

        assertEquals(60, estimate.kwh)
        assertEquals(150, estimate.costMxn)
    }

    @Test
    fun catalogIsBroadAndHasStableUniqueIds() {
        assertTrue(ApplianceCatalog.items.size >= 30)
        assertEquals(ApplianceCatalog.items.size, ApplianceCatalog.items.map { it.id }.distinct().size)
    }
}
