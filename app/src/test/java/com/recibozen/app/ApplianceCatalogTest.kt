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

    @Test
    fun catalogForecastIncludesStandbyConsumptionWhenReported() {
        val television = ApplianceCatalog.find("television_led")!!
        val estimate = ApplianceCalculator.fromCatalog(
            item = television,
            hoursPerWeek = 28,
            billingDays = 30,
            effectiveRateMxnPerKwh = 2.5,
            includeStandby = true,
        )

        assertTrue(estimate.standbyKwh > 0.0)
        assertEquals(13, estimate.kwh)
        assertTrue(estimate.costMxn > 0)
    }

    @Test
    fun standbyIsOnlyCountedAfterUserConfirmsIt() {
        val console = ApplianceCatalog.find("model_xbox_series_x")!!
        val off = ApplianceCalculator.fromCatalog(console, 14, 60, 2.5, includeStandby = false)
        val instantOn = ApplianceCalculator.fromCatalog(console, 14, 60, 2.5, includeStandby = true)

        assertEquals(0.0, off.standbyKwh, 0.001)
        assertTrue(instantOn.standbyKwh > 10.0)
        assertTrue(instantOn.kwh > off.kwh)
    }

    @Test
    fun standbyConfigurationExistsOnlyForApplicableCatalogDevices() {
        assertEquals(0, ApplianceCatalog.find("foco_led_9")?.standbyWatts)
        assertEquals(0, ApplianceCatalog.find("licuadora")?.standbyWatts)
        assertEquals(3, ApplianceCatalog.find("pc_oficina")?.standbyWatts)
        assertEquals(4, ApplianceCatalog.find("pc_gaming")?.standbyWatts)
        assertTrue((ApplianceCatalog.find("model_sony_ps5_cfi2016")?.standbyWatts ?: 0) > 0)
    }
}
