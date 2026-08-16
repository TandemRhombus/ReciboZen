package com.recibozen.app

import com.recibozen.app.core.calculation.DacRiskCalculator
import com.recibozen.app.core.calculation.DacRiskLevel
import com.recibozen.app.core.model.BillPeriod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DacRiskCalculatorTest {
    @Test
    fun tariffOneUsesThreeThousandKwhAsAnnualEquivalentOnly() {
        val result = DacRiskCalculator.calculate(yearOfMonthlyBills(240), "1")

        assertEquals(250, result.monthlyLimitKwh)
        assertEquals(3_000, result.annualEquivalentLimitKwh)
        assertEquals(240.0, result.monthlyAverageKwh ?: 0.0, 1.0)
        assertEquals(2_880.0, result.rollingWindowKwh ?: 0.0, 1.0)
        assertEquals(120.0, result.annualMarginKwh ?: 0.0, 1.0)
        assertEquals(DacRiskLevel.RISK, result.level)
    }

    @Test
    fun limitDependsOnAssignedDomesticTariff() {
        val history = yearOfMonthlyBills(500)
        assertEquals(DacRiskLevel.ABOVE_LIMIT, DacRiskCalculator.calculate(history, "1B").level)
        assertEquals(DacRiskLevel.NORMAL, DacRiskCalculator.calculate(history, "1C").level)
    }

    @Test
    fun incompleteHistoryNeverClaimsTheUserWillEnterDac() {
        val result = DacRiskCalculator.calculate(yearOfMonthlyBills(260).takeLast(3), "1")

        assertFalse(result.hasCompleteHistory)
        assertEquals(DacRiskLevel.UNKNOWN, result.level)
        assertFalse(result.wouldExceedWithProjection)
    }

    @Test
    fun irregularBillsAreProratedByTheirActualDays() {
        val start = LocalDate.of(2025, 1, 1)
        val history = listOf(
            bill(start, start.plusDays(45), 450),
            bill(start.plusDays(45), start.plusDays(120), 750),
            bill(start.plusDays(120), start.plusDays(365), 2_450),
        )
        val result = DacRiskCalculator.calculate(history, "1")

        assertTrue(result.hasCompleteHistory)
        assertEquals(3_650.0 / 12.0, result.monthlyAverageKwh ?: 0.0, 0.5)
    }

    @Test
    fun nextBillShowsTheFutureRollingTotalAndRemainingAnnualMargin() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 31,
        )

        assertEquals(800, result.recommendedNextBillMaxKwh)
        assertEquals(2_700.0, result.projectedRollingWindowKwh ?: 0.0, 1.0)
        assertEquals(300.0, result.projectedAnnualMarginKwh ?: 0.0, 1.0)
        assertFalse(result.wouldExceedWithProjection)
    }

    @Test
    fun duplicateImportedPeriodsDoNotDoubleTheRollingConsumption() {
        val history = yearOfMonthlyBills(200)
        val result = DacRiskCalculator.calculate(history + history, "1")

        assertEquals(2_400.0, result.rollingWindowKwh ?: 0.0, 1.0)
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
