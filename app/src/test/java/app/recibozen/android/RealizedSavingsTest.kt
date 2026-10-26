package app.recibozen.android

import app.recibozen.android.core.calculation.RealizedSavingsCalculator
import app.recibozen.android.core.calculation.TariffBlock
import app.recibozen.android.core.calculation.TariffEngine
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the household is told it saved.
 *
 * Every test here protects against a number that would look like a saving and
 * is not one: a season, a shorter period, a rate change, or a year that already
 * had the savings in it. Showing a household money it did not save is worse than
 * showing nothing.
 */
class RealizedSavingsTest {
    private val engine = TariffEngine(
        blocks = listOf(
            TariffBlock(limitKwh = 150, rateMxnPerKwh = 1.00),
            TariffBlock(limitKwh = 430, rateMxnPerKwh = 2.00),
            TariffBlock(limitKwh = null, rateMxnPerKwh = 6.00),
        ),
        fixedChargeMxn = 50.0,
        taxRate = 0.16,
    )
    private val costOf = { kwh: Int -> engine.estimate(kwh).exactTotalMxn }
    private val mexico = ZoneId.of("America/Mexico_City")

    private fun bill(
        label: String,
        start: LocalDate,
        days: Int,
        kwh: Int,
        amountMxn: Int = 999,
        projected: Boolean = false,
    ) = BillPeriod(
        monthLabel = label,
        amountMxn = amountMxn,
        isProjected = projected,
        consumptionKwh = kwh,
        periodStartEpochDay = start.toEpochDay(),
        periodEndEpochDay = start.plusDays(days.toLong()).toEpochDay(),
    )

    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toEpochDay()

    @Test
    fun eachBillIsComparedWithTheSamePeriodOfTheYearBeforeNotWithTheLastBill() {
        val bills = listOf(
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 400),
            // The neighbouring period, and the most recent bill before this one.
            // A heavy summer bill: choosing it would invent a large saving.
            bill("Ago–Sep 25", LocalDate.of(2025, 8, 1), days = 60, kwh = 900),
            bill("Jun–Ago 26", LocalDate.of(2026, 6, 2), days = 60, kwh = 350),
        )

        val savings = RealizedSavingsCalculator.compute(bills, day(2026, 5, 20), costOf)!!

        val only = savings.periods.single()
        assertEquals("Jun–Jul 25", only.baselineLabel)
        assertEquals(50, only.savedKwh)
        // 50 kWh inside the 2.00 block, with 16 % VAT.
        assertEquals(116, only.savedMxn)
    }

    @Test
    fun aLongerPeriodIsNotCountedAsSpendingMore() {
        val bills = listOf(
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 300),
            // Same 5 kWh a day, ten days longer.
            bill("Jun–Ago 26", LocalDate.of(2026, 6, 1), days = 70, kwh = 350),
        )

        val period = RealizedSavingsCalculator.compute(bills, day(2026, 5, 20), costOf)!!.periods.single()

        assertEquals(350, period.baselineKwh)
        assertEquals(0, period.savedKwh)
        assertEquals(0, period.savedMxn)
    }

    @Test
    fun whatTheBillsCostIsIgnoredSoARateChangeCannotPoseAsASaving() {
        // Identical consumption. Last year's bill was far more expensive -- a
        // summer rate, a lost subsidy, anything but the household's doing.
        val expensiveLastYear = listOf(
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 400, amountMxn = 1_500),
            bill("Jun–Jul 26", LocalDate.of(2026, 6, 1), days = 60, kwh = 400, amountMxn = 900),
        )
        val cheapLastYear = listOf(
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 400, amountMxn = 300),
            bill("Jun–Jul 26", LocalDate.of(2026, 6, 1), days = 60, kwh = 400, amountMxn = 900),
        )

        val a = RealizedSavingsCalculator.compute(expensiveLastYear, day(2026, 5, 20), costOf)!!
        val b = RealizedSavingsCalculator.compute(cheapLastYear, day(2026, 5, 20), costOf)!!

        assertEquals(0, a.totalSavedMxn)
        assertEquals(0, b.totalSavedMxn)
    }

    @Test
    fun aSavingIsPricedAtTheBlockItAvoidedNotAtAnAverageRate() {
        val bills = listOf(
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 480),
            bill("Jun–Jul 26", LocalDate.of(2026, 6, 1), days = 60, kwh = 420),
        )

        val period = RealizedSavingsCalculator.compute(bills, day(2026, 5, 20), costOf)!!.periods.single()

        // The 60 kWh avoided were 50 at 6.00 and 10 at 2.00: 320 × 1.16 = 371.2.
        assertEquals(60, period.savedKwh)
        assertEquals(371, period.savedMxn)
    }

    @Test
    fun usingMoreIsReportedAndSubtractedInsteadOfLeftOut() {
        val bills = listOf(
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 300),
            bill("Ago–Sep 25", LocalDate.of(2025, 7, 31), days = 60, kwh = 300),
            bill("Jun–Jul 26", LocalDate.of(2026, 6, 1), days = 60, kwh = 250),
            bill("Ago–Sep 26", LocalDate.of(2026, 7, 31), days = 60, kwh = 330),
        )

        val savings = RealizedSavingsCalculator.compute(bills, day(2026, 5, 20), costOf)!!

        assertEquals(listOf(116, -70), savings.periods.map { it.savedMxn })
        assertEquals(46, savings.totalSavedMxn)
        assertEquals(savings.periods.sumOf { it.savedMxn }, savings.totalSavedMxn)
        assertEquals(20, savings.totalSavedKwh)
    }

    @Test
    fun billsFromBeforeTheHouseholdUsedTheAppAreNotItsSavings() {
        // A clear year-over-year saving, entirely before the first import.
        val bills = listOf(
            bill("Jun–Jul 24", LocalDate.of(2024, 6, 1), days = 60, kwh = 500),
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 300),
        )

        val savings = RealizedSavingsCalculator.compute(bills, day(2026, 5, 20), costOf)!!

        assertTrue(savings.periods.isEmpty())
        assertEquals(0, savings.periodsWithoutBaseline)
    }

    @Test
    fun afterAYearOfUseTheBaselineIsTheYearBeforeTheAppNotTheAppsOwnFirstYear() {
        val bills = listOf(
            bill("Jun–Jul 24", LocalDate.of(2024, 6, 1), days = 60, kwh = 500),
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 400),
            bill("Jun–Ago 26", LocalDate.of(2026, 6, 2), days = 60, kwh = 380),
        )

        val savings = RealizedSavingsCalculator.compute(bills, day(2025, 5, 20), costOf)!!

        // Against 2025 the second year would show 20 kWh and hide the first
        // year's 100. Both are measured against the year before the app.
        assertEquals(listOf("Jun–Jul 24", "Jun–Jul 24"), savings.periods.map { it.baselineLabel })
        assertEquals(listOf(100, 120), savings.periods.map { it.savedKwh })
    }

    @Test
    fun aMissingSamePeriodIsNotReplacedByTheNeighbouringSeason() {
        val bills = listOf(
            // Only last year's following period is on record, 60 days away from
            // the one that would be comparable.
            bill("Ago–Sep 25", LocalDate.of(2025, 8, 1), days = 60, kwh = 900),
            bill("Jun–Jul 26", LocalDate.of(2026, 6, 1), days = 60, kwh = 350),
        )

        val savings = RealizedSavingsCalculator.compute(bills, day(2026, 5, 20), costOf)!!

        assertTrue(savings.periods.isEmpty())
        assertEquals(1, savings.periodsWithoutBaseline)
    }

    @Test
    fun aProjectionIsNeverASaving() {
        val bills = listOf(
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 400),
            bill("Jun–Jul 26", LocalDate.of(2026, 6, 1), days = 60, kwh = 200, projected = true),
        )

        val savings = RealizedSavingsCalculator.compute(bills, day(2026, 5, 20), costOf)!!

        assertTrue(savings.periods.isEmpty())
        assertEquals(0, savings.periodsWithoutBaseline)
    }

    @Test
    fun withoutAStartDateThereIsNothingToClaim() {
        val bills = listOf(
            bill("Jun–Jul 25", LocalDate.of(2025, 6, 1), days = 60, kwh = 400),
            bill("Jun–Jul 26", LocalDate.of(2026, 6, 1), days = 60, kwh = 200),
        )

        assertNull(RealizedSavingsCalculator.compute(bills, trackingStartEpochDay = null, costOf))
    }

    @Test
    fun theCountStartsAtTheFirstImportOrHandTakenReadingNotAtDatesPrintedOnABill() {
        val millisOf = { date: LocalDate -> date.atTime(12, 0).atZone(mexico).toInstant().toEpochMilli() }
        val fromPdf = HistoryReadingEntry(
            kwh = 12_000,
            dateLabel = "",
            method = ReadingMethod.PDF,
            note = "",
            timestampEpochMillis = millisOf(LocalDate.of(2025, 1, 10)),
        )
        val byHand = fromPdf.copy(
            method = ReadingMethod.MANUAL,
            timestampEpochMillis = millisOf(LocalDate.of(2026, 5, 20)),
        )

        assertEquals(
            day(2026, 5, 20),
            RealizedSavingsCalculator.trackingStart(day(2026, 5, 25), listOf(fromPdf, byHand), mexico),
        )
        assertEquals(
            day(2026, 5, 25),
            RealizedSavingsCalculator.trackingStart(day(2026, 5, 25), listOf(fromPdf), mexico),
        )
        assertNull(RealizedSavingsCalculator.trackingStart(null, listOf(fromPdf), mexico))
    }
}
