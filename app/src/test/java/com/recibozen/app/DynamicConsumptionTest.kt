package com.recibozen.app

import com.recibozen.app.core.components.MeterDialMath
import com.recibozen.app.core.components.greetingResourceForHour
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.demo.repository.DemoReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicConsumptionTest {
    private class Repository : DemoReciboZenRepository() {
        fun setBills(bills: List<BillPeriod>) {
            mutableImportedBillHistory.value = bills
        }
    }

    @Test
    fun greetingUsesThePhoneHourBoundaries() {
        assertEquals(R.string.greeting_evening, greetingResourceForHour(4))
        assertEquals(R.string.greeting_morning, greetingResourceForHour(5))
        assertEquals(R.string.greeting_morning, greetingResourceForHour(11))
        assertEquals(R.string.greeting_afternoon, greetingResourceForHour(12))
        assertEquals(R.string.greeting_afternoon, greetingResourceForHour(18))
        assertEquals(R.string.greeting_evening, greetingResourceForHour(19))
        assertEquals(R.string.greeting_evening, greetingResourceForHour(23))
    }

    @Test
    fun fourDialMeterUsesExactlyFourVisibleDigits() {
        val repository = Repository()
        val directions = listOf(true, false, true, false, true)
        val angles = listOf(1, 2, 3, 4, 5).mapIndexed { index, digit ->
            MeterDialMath.angleForDigit(digit, directions[index])
        }

        val reading = repository.dialReading(
            ReciboZenSessionState(
                meterType = "De manecillas, 4 carátulas",
                dialAngles = angles,
                dialDirections = directions,
            ),
        )

        assertEquals(listOf(2, 3, 4, 5), reading.digits)
        assertEquals(2_345, reading.valueKwh)
    }

    @Test
    fun fiveDialMeterKeepsAllFiveDigits() {
        val repository = Repository()
        val directions = listOf(true, false, true, false, true)
        val angles = listOf(1, 2, 3, 4, 5).mapIndexed { index, digit ->
            MeterDialMath.angleForDigit(digit, directions[index])
        }

        val reading = repository.dialReading(
            ReciboZenSessionState(
                meterType = "De manecillas, 5 carátulas",
                dialAngles = angles,
                dialDirections = directions,
            ),
        )

        assertEquals(listOf(1, 2, 3, 4, 5), reading.digits)
        assertEquals(12_345, reading.valueKwh)
    }

    @Test
    fun applianceCostAndShareUseTheImportedBillInsteadOfFixtureValues() {
        val repository = Repository()
        repository.setBills(
            listOf(BillPeriod("Abr–Jun", amountMxn = 1_000, consumptionKwh = 500)),
        )
        val state = ReciboZenSessionState(
            customAppliances = listOf(
                Appliance(
                    id = "custom",
                    name = "Equipo",
                    room = "Taller",
                    costMxn = 999,
                    kwh = 100,
                    sharePercent = 99,
                    hoursPerWeek = null,
                    confidence = ConfidenceLevel.LOW,
                ),
            ),
        )

        val appliance = repository.appliances(state).single()

        assertEquals(200, appliance.costMxn)
        assertEquals(20, appliance.sharePercent)
    }

    @Test
    fun pdfEndpointsKeepTheRealBillCostUntilAUserAddsANewReading() {
        val repository = Repository()
        repository.setBills(
            listOf(
                BillPeriod(
                    monthLabel = "Abr–Jun",
                    amountMxn = 4_396,
                    consumptionKwh = 858,
                    periodStartEpochDay = 20_550,
                    periodEndEpochDay = 20_612,
                ),
            ),
        )
        val state = ReciboZenSessionState(
            readings = listOf(
                HistoryReadingEntry(18_426, "Fin", ReadingMethod.PDF, "PDF", 20_612L * 86_400_000),
                HistoryReadingEntry(17_568, "Inicio", ReadingMethod.PDF, "PDF", 20_550L * 86_400_000),
            ),
        )

        val energy = repository.energySummary(state)

        assertEquals(858, energy.observedKwh)
        assertEquals(62, energy.billingDays)
        assertEquals(4_396, energy.costToDateMxn)
        assertEquals(4_396, energy.projectedCostMxn)
    }
}
