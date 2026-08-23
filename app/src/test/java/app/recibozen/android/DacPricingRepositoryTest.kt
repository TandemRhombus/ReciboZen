package app.recibozen.android

import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.demo.repository.DemoReciboZenRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class DacPricingRepositoryTest {
    private class Repository : DemoReciboZenRepository() {
        fun useBills(bills: List<BillPeriod>) {
            mutableImportedBillHistory.value = bills
        }
    }

    @Test
    fun dacWithoutDetailedRatesUsesLatestBillsEffectivePriceNotGenericOneC() {
        val repository = Repository()
        repository.useBills(listOf(BillPeriod("", amountMxn = 1_000, consumptionKwh = 200, tariffCode = "DAC")))
        repository.setTariff("DAC")

        assertEquals(500, repository.estimateCostMxn(100))
        assertEquals(emptyList<Any>(), repository.activeTariffBlocks())
    }

    @Test
    fun changingAConfiguredDomesticServiceToDacKeepsItsExitReference() {
        val repository = Repository()
        val service = repository.addElectricService("Casa", "123456789012", "", "ABC123", "1C")
        repository.setTariff("DAC")

        assertEquals("1C", repository.session.value.dacReferenceTariffsByService[service.serviceId])
    }
}
