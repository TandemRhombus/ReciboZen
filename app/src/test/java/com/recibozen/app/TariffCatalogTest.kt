package com.recibozen.app

import com.recibozen.app.core.calculation.TariffCalculationSupport
import com.recibozen.app.core.calculation.TariffCatalog
import com.recibozen.app.core.model.ElectricServiceValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffCatalogTest {
    @Test
    fun normalizesReceiptStyleDomesticCode() {
        assertEquals("1", TariffCatalog.normalize("Tarifa 01"))
        assertEquals("1B", TariffCatalog.find("tarifa 1b")?.code)
    }

    @Test
    fun marksBusinessTariffAsReceiptCalibrated() {
        assertEquals(
            TariffCalculationSupport.RECEIPT_CALIBRATION_REQUIRED,
            TariffCatalog.find("PDBT")?.calculationSupport,
        )
    }

    @Test
    fun dacLimitsAreDefinedPerDomesticTariff() {
        val limits = TariffCatalog.supported.associate { it.code to it.highConsumptionLimitKwhPerMonth }

        assertEquals(250, limits["1"])
        assertEquals(300, limits["1A"])
        assertEquals(400, limits["1B"])
        assertEquals(850, limits["1C"])
        assertEquals(1_000, limits["1D"])
        assertEquals(2_000, limits["1E"])
        assertEquals(2_500, limits["1F"])
        assertEquals(null, limits["DAC"])
    }

    @Test
    fun validatesOptionalTwelveDigitServiceNumber() {
        assertTrue(ElectricServiceValidator.isValidServiceNumber(""))
        assertTrue(ElectricServiceValidator.isValidServiceNumber("5102 2110 5026"))
        assertFalse(ElectricServiceValidator.isValidServiceNumber("1234"))
    }
}
