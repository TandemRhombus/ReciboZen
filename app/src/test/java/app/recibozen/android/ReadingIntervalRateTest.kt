package app.recibozen.android

import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.demo.repository.DemoReciboZenRepository
import app.recibozen.android.demo.repository.ReciboZenSessionState
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily rate must be divided by the days the two readings actually cover.
 * Dividing by the days since the bill closed, while the numerator starts at an
 * earlier reading, doubles the rate and produces a false "you are consuming
 * more than recommended".
 */
class ReadingIntervalRateTest {
    private class Repository : DemoReciboZenRepository() {
        var todayEpochDay: Long? = null

        fun setBills(bills: List<BillPeriod>) {
            mutableImportedBillHistory.value = bills
        }

        override fun currentEpochDay(): Long = todayEpochDay ?: super.currentEpochDay()
    }

    private val periodStart = 20_584L
    private val periodEnd = 20_646L

    private fun localMillis(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 12 * 3_600_000

    private fun repositoryWithBill(): Repository {
        val repository = Repository()
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
        return repository
    }

    @Test
    fun aBaselineTakenBeforeTheBillClosedDoesNotInflateTheDailyRate() {
        val repository = repositoryWithBill()
        repository.todayEpochDay = periodEnd + 10
        val state = ReciboZenSessionState(
            readings = listOf(
                // 200 kWh consumed over 20 real days: 10 before the bill closed
                // and 10 after it. The rate is 10 kWh/day, not 20.
                HistoryReadingEntry(9_200, "Después", ReadingMethod.MANUAL, "+200", localMillis(periodEnd + 10)),
                HistoryReadingEntry(9_000, "Antes", ReadingMethod.MANUAL, "", localMillis(periodEnd - 10)),
            ),
        )

        val energy = repository.energySummary(state)

        assertEquals(10.0, energy.dailyAverageKwh, 0.01)
        assertEquals(10, energy.observedDays)
        assertEquals(100, energy.observedKwh)
        assertEquals(620, energy.meterProjectedKwh)
    }

    @Test
    fun aBaselineExactlyOnTheClosingDateKeepsTheMeasuredConsumptionUntouched() {
        val repository = repositoryWithBill()
        repository.todayEpochDay = periodEnd + 20
        val state = ReciboZenSessionState(
            readings = listOf(
                HistoryReadingEntry(9_120, "Después", ReadingMethod.MANUAL, "+120", localMillis(periodEnd + 20)),
                HistoryReadingEntry(9_000, "Cierre", ReadingMethod.PDF, "PDF", periodEnd * 86_400_000),
            ),
        )

        val energy = repository.energySummary(state)

        assertEquals(20, energy.observedDays)
        assertEquals(120, energy.observedKwh)
        assertEquals(6.0, energy.dailyAverageKwh, 0.01)
    }

    @Test
    fun theCurrentPeriodFollowsTodayInsteadOfStayingOnAnOldReceipt() {
        val repository = repositoryWithBill()
        // Four months after the bill closed, with no new readings.
        repository.todayEpochDay = periodEnd + 130

        val energy = repository.energySummary(ReciboZenSessionState())

        assertNotNull(energy.currentPeriod)
        assertTrue(energy.currentPeriod!!.startEpochDay <= periodEnd + 130)
        assertTrue(energy.currentPeriod.endEpochDay > periodEnd + 130)
        assertTrue(energy.currentPeriod.isBeyondNextPeriod)
    }

    @Test
    fun withoutAnyReceiptThereIsNoPeriodToDisplay() {
        val repository = Repository()

        val energy = repository.energySummary(ReciboZenSessionState())

        assertNull(energy.currentPeriod)
        assertEquals(0, energy.daysRemaining)
    }

    @Test
    fun standbyIsCountedEvenWhenTheEntryHasNoRatedPower() {
        val repository = repositoryWithBill()
        val state = ReciboZenSessionState(
            customAppliances = listOf(
                Appliance(
                    id = "tv",
                    name = "Televisión",
                    room = "Sala",
                    costMxn = 0,
                    kwh = 0,
                    sharePercent = 0,
                    hoursPerWeek = 28,
                    confidence = ConfidenceLevel.MEDIUM,
                    usageModel = ApplianceUsageModel.POWER_SCHEDULE,
                    // No powerWatts: consumption comes from the stored kWh, but
                    // the idle draw the user configured must still be counted.
                    powerWatts = null,
                    unitKwh = 20.0,
                    referencePeriodDays = 30,
                    standbyEnabled = true,
                    standbyWatts = 10,
                ),
            ),
        )

        val appliance = repository.appliances(state).single()

        // 20 kWh/30 días escalados a 62 días = 41.33 kWh activos.
        // 10 W durante 140 h/semana en 62 días = 12.4 kWh en reposo.
        assertNotNull("el reposo configurado no puede desaparecer", appliance.standbySharePercent)
        assertEquals(23, appliance.standbySharePercent)
        assertEquals(53.73, appliance.unitKwh ?: 0.0, 0.05)
    }
}
