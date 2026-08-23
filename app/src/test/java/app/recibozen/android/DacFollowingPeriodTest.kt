package app.recibozen.android

import app.recibozen.android.core.calculation.DacRiskCalculator
import app.recibozen.android.core.model.BillPeriod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ceiling for the period *after* the one in progress. It stands on two
 * assumptions — the current period closes at its projection, and the next one
 * lasts the same days — so these tests pin both when it may be stated and when
 * it must stay absent. [DacRiskCalculatorTest] covers the rest of the rule and
 * is deliberately left untouched.
 *
 * Every history here is twelve monthly bills from 2025-01-01, so `asOf` is
 * 2026-01-01. With a 31-day period the following window runs 2025-03-04 →
 * 2026-03-04: January and February have aged out entirely, March survives
 * prorated at 28 of its 31 days, and the projected period sits fully inside.
 */
class DacFollowingPeriodTest {

    @Test
    fun theFollowingCeilingCountsTheProjectedPeriodAsAlreadySpent() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 31,
        )

        // Retained: 200 * 28/31 + 9 * 200 + 500 = 2,480.6 of the 3,000 kWh
        // tarifa 1 allows in a year.
        assertEquals(800, result.recommendedNextBillMaxKwh)
        assertEquals(519, result.recommendedFollowingBillMaxKwh)
    }

    @Test
    fun everyExtraKwhProjectedNowIsOneFewerKwhAvailableLater() {
        val modest = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(250),
            tariffCode = "1",
            projectedNextBillKwh = 100,
            projectedBillingDays = 31,
        )
        val heavier = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(250),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 31,
        )

        // The current ceiling cannot see the projection at all; the following
        // one absorbs it kWh for kWh.
        assertEquals(250, modest.recommendedNextBillMaxKwh)
        assertEquals(250, heavier.recommendedNextBillMaxKwh)
        assertEquals(424, modest.recommendedFollowingBillMaxKwh)
        assertEquals(24, heavier.recommendedFollowingBillMaxKwh)
    }

    @Test
    fun aProjectionThatAlreadyBlowsTheLimitLeavesZeroRoomNotNegativeRoom() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(250),
            tariffCode = "1",
            projectedNextBillKwh = 2_000,
            projectedBillingDays = 31,
        )

        assertEquals(0, result.recommendedFollowingBillMaxKwh)
    }

    @Test
    fun withoutAProjectionTheFollowingCeilingIsNotInventedFromTheCurrentOne() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = null,
            projectedBillingDays = 31,
        )

        assertEquals(800, result.recommendedNextBillMaxKwh)
        assertNull(result.recommendedFollowingBillMaxKwh)
    }

    @Test
    fun withoutAnExpectedPeriodLengthThereIsNoFollowingCeiling() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 0,
        )

        assertNull(result.recommendedFollowingBillMaxKwh)
    }

    @Test
    fun incompleteHistoryHasNoCeilingForEitherPeriod() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200).takeLast(3),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 31,
        )

        assertNull(result.recommendedNextBillMaxKwh)
        assertNull(result.recommendedFollowingBillMaxKwh)
    }

    @Test
    fun anAlreadyDacAccountGetsBothCeilingsFromItsDomesticReference() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(300),
            tariffCode = "DAC",
            projectedNextBillKwh = 100,
            projectedBillingDays = 31,
            domesticReferenceTariffCode = "1B",
        )

        // 1B allows 400 kWh/month, i.e. 4,800 kWh a year.
        // Retained: 300 * 28/31 + 9 * 300 + 100 = 3,071.0
        assertEquals(1_500, result.recommendedNextBillMaxKwh)
        assertEquals(1_729, result.recommendedFollowingBillMaxKwh)
    }

    private fun yearOfMonthlyBills(kwh: Int): List<BillPeriod> {
        val start = LocalDate.of(2025, 1, 1)
        return (0 until 12).map { index ->
            val periodStart = start.plusMonths(index.toLong())
            bill(periodStart, periodStart.plusMonths(1), kwh)
        }
    }

    private fun bill(start: LocalDate, end: LocalDate, kwh: Int) = BillPeriod(
        monthLabel = "",
        amountMxn = 0,
        consumptionKwh = kwh,
        periodStartEpochDay = start.toEpochDay(),
        periodEndEpochDay = end.toEpochDay(),
    )
}
