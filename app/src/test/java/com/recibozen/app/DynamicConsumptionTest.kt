package com.recibozen.app

import com.recibozen.app.core.components.MeterDialMath
import com.recibozen.app.core.components.greetingResourceForHour
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.demo.repository.DemoReciboZenRepository
import com.recibozen.app.demo.repository.EnergyForecastBasis
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

    @Test
    fun multipleIntermediateReadingsStayAccumulatedFromTheRealBillClosingReading() {
        val repository = Repository()
        val periodStart = 20_584L
        val periodEnd = 20_646L
        repository.setBills(
            listOf(
                BillPeriod(
                    monthLabel = "May–Jul 26",
                    amountMxn = 2_274,
                    consumptionKwh = 646,
                    periodStartEpochDay = periodStart,
                    periodEndEpochDay = periodEnd,
                ),
            ),
        )
        val state = ReciboZenSessionState(
            readings = listOf(
                HistoryReadingEntry(9_974, "30 días", ReadingMethod.DIAL, "+74", (periodEnd + 30) * 86_400_000),
                HistoryReadingEntry(9_900, "17 días", ReadingMethod.MANUAL, "+97", (periodEnd + 17) * 86_400_000),
                HistoryReadingEntry(9_803, "Cierre", ReadingMethod.PDF, "PDF", periodEnd * 86_400_000),
                HistoryReadingEntry(9_157, "Inicio", ReadingMethod.PDF, "PDF", periodStart * 86_400_000),
            ),
        )

        val energy = repository.energySummary(state)

        assertEquals(171, energy.observedKwh)
        assertEquals(30, energy.observedDays)
        assertEquals(353, energy.projectedKwh)
        assertEquals(32, energy.daysRemaining)
    }

    @Test
    fun appliancesCanProduceAPreliminaryForecastWithoutMeterEvidence() {
        val repository = Repository()
        val state = ReciboZenSessionState(
            billingCycle = "Mensual",
            customAppliances = listOf(
                Appliance(
                    id = "equipo",
                    name = "Equipo",
                    room = "Hogar",
                    costMxn = 0,
                    kwh = 90,
                    sharePercent = 0,
                    hoursPerWeek = 21,
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
        )

        val energy = repository.energySummary(state)
        val bill = repository.billProjection(state)

        assertEquals(EnergyForecastBasis.APPLIANCES, energy.forecastBasis)
        assertEquals(90, energy.applianceProjectedKwh)
        assertEquals(90, energy.projectedKwh)
        assertEquals(0, energy.costToDateMxn)
        assertEquals(true, bill.hasUsageEvidence)
        assertEquals(true, bill.rangeHighMxn > bill.projectedMxn)
    }

    @Test
    fun meterAndApplianceForecastsAreComparedInsteadOfDoubleCounted() {
        val repository = Repository()
        val periodStart = 20_584L
        val periodEnd = 20_646L
        repository.setBills(
            listOf(
                BillPeriod(
                    monthLabel = "May–Jul 26",
                    amountMxn = 2_274,
                    consumptionKwh = 646,
                    periodStartEpochDay = periodStart,
                    periodEndEpochDay = periodEnd,
                ),
            ),
        )
        val state = ReciboZenSessionState(
            customAppliances = listOf(
                Appliance(
                    id = "equipo",
                    name = "Equipo",
                    room = "Hogar",
                    costMxn = 0,
                    kwh = 100,
                    sharePercent = 0,
                    hoursPerWeek = 10,
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            readings = listOf(
                HistoryReadingEntry(9_974, "30 días", ReadingMethod.MANUAL, "+171", (periodEnd + 30) * 86_400_000),
                HistoryReadingEntry(9_803, "Cierre", ReadingMethod.PDF, "PDF", periodEnd * 86_400_000),
            ),
        )

        val energy = repository.energySummary(state)

        assertEquals(EnergyForecastBasis.METER, energy.forecastBasis)
        assertEquals(353, energy.meterProjectedKwh)
        assertEquals(100, energy.applianceProjectedKwh)
        assertEquals(353, energy.projectedKwh)
    }

    @Test
    fun changingDeclaredHoursRecalculatesTheInventoryForecast() {
        val repository = Repository()
        repository.addAppliance(
            Appliance(
                id = "equipo",
                name = "Equipo",
                room = "Hogar",
                costMxn = 0,
                kwh = 40,
                sharePercent = 0,
                hoursPerWeek = 10,
                confidence = ConfidenceLevel.MEDIUM,
            ),
        )

        assertEquals(40, repository.energySummary().applianceProjectedKwh)

        repository.setApplianceHoursPerWeek("equipo", 25)

        assertEquals(100, repository.energySummary().applianceProjectedKwh)
        assertEquals(EnergyForecastBasis.APPLIANCES, repository.energySummary().forecastBasis)
    }
}
