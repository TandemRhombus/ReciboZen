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
    fun validatesOptionalTwelveDigitServiceNumber() {
        assertTrue(ElectricServiceValidator.isValidServiceNumber(""))
        assertTrue(ElectricServiceValidator.isValidServiceNumber("5102 2110 5026"))
        assertFalse(ElectricServiceValidator.isValidServiceNumber("1234"))
    }
}
