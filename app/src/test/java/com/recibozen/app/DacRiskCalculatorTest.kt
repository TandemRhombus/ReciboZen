package com.recibozen.app

import com.recibozen.app.core.calculation.DacRiskCalculator
import com.recibozen.app.core.calculation.DacRiskLevel
import com.recibozen.app.core.model.BillPeriod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun nextBillExplainsHowMuchOldConsumptionLeavesTheWindow() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 31,
        )

        assertEquals(200.0, result.expiringBeforeNextBillKwh ?: 0.0, 1.0)
        assertEquals(2_200.0, result.retainedBeforeNextBillKwh ?: 0.0, 1.0)
    }

    @Test
    fun dacAccountUsesItsPreviousDomesticTariffForAnExitPlan() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(300),
            tariffCode = "DAC",
            projectedNextBillKwh = 100,
            projectedBillingDays = 31,
            domesticReferenceTariffCode = "1B",
        )

        assertEquals(DacRiskLevel.ALREADY_DAC, result.level)
        assertEquals("1B", result.referenceDomesticTariffCode)
        assertEquals(400, result.monthlyLimitKwh)
        assertEquals(1_500, result.recommendedNextBillMaxKwh)
    }

    @Test
    fun duplicateImportedPeriodsDoNotDoubleTheRollingConsumption() {
        val history = yearOfMonthlyBills(200)
        val result = DacRiskCalculator.calculate(history + history, "1")

        assertEquals(2_400.0, result.rollingWindowKwh ?: 0.0, 1.0)
    }

    // --- Límite: justo antes, exactamente en, y justo después ---

    @Test
    fun justBelowTheLimitIsRiskAndNeverAboveLimit() {
        // 3,000 kWh is the annual equivalent of tarifa 1. One kWh short of it.
        val result = DacRiskCalculator.calculate(exactAnnualConsumption(2_999), "1")

        assertTrue(result.hasCompleteHistory)
        assertTrue((result.annualMarginKwh ?: 0.0) > 0.0)
        assertEquals(DacRiskLevel.RISK, result.level)
    }

    @Test
    fun exactlyAtTheLimitIsStillNotAboveIt() {
        // CFE reclassifies when the moving average EXCEEDS the limit, so the
        // exact boundary must not be reported as already over.
        val result = DacRiskCalculator.calculate(exactAnnualConsumption(3_000), "1")

        assertEquals(250.0, result.monthlyAverageKwh ?: 0.0, 0.01)
        assertEquals(0.0, result.annualMarginKwh ?: -1.0, 0.51)
        assertEquals(DacRiskLevel.RISK, result.level)
    }

    @Test
    fun oneKwhAboveTheLimitCrossesIntoAboveLimit() {
        val result = DacRiskCalculator.calculate(exactAnnualConsumption(3_012), "1")

        assertTrue((result.annualMarginKwh ?: 0.0) < 0.0)
        assertEquals(DacRiskLevel.ABOVE_LIMIT, result.level)
    }

    @Test
    fun theRecommendedNextBillIsTheLargestOneThatStillFitsUnderTheLimit() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(250),
            tariffCode = "1",
            projectedNextBillKwh = 250,
            projectedBillingDays = 31,
        )

        val recommended = result.recommendedNextBillMaxKwh ?: 0
        val retained = result.retainedBeforeNextBillKwh ?: 0.0
        assertTrue(retained + recommended <= 3_000.0)
        assertTrue(retained + recommended + 1 > 3_000.0)
    }

    @Test
    fun aProjectionAboveTheRecommendedMaximumIsFlaggedBeforeItHappens() {
        val result = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(250),
            tariffCode = "1",
            projectedNextBillKwh = 600,
            projectedBillingDays = 31,
        )

        assertTrue(result.wouldExceedWithProjection)
        assertTrue((result.projectedAnnualMarginKwh ?: 0.0) < 0.0)
    }

    @Test
    fun anUnsupportedTariffSaysSoInsteadOfBorrowingAnotherLimit() {
        val result = DacRiskCalculator.calculate(yearOfMonthlyBills(400), "PDBT")

        assertEquals(DacRiskLevel.UNSUPPORTED, result.level)
        assertNull(result.monthlyLimitKwh)
    }

    @Test
    fun aDacAccountWithoutAKnownDomesticReferenceStillReportsItsState() {
        val result = DacRiskCalculator.calculate(yearOfMonthlyBills(400), "DAC")

        assertEquals(DacRiskLevel.ALREADY_DAC, result.level)
        assertNull(result.referenceDomesticTariffCode)
    }

    @Test
    fun negativeOrInvertedPeriodsAreIgnoredInsteadOfCorruptingTheWindow() {
        val start = LocalDate.of(2025, 1, 1)
        val corrupt = listOf(
            bill(start.plusDays(30), start, 400),
            bill(start, start.plusDays(30), -50),
        )
        val result = DacRiskCalculator.calculate(yearOfMonthlyBills(200) + corrupt, "1")

        assertEquals(2_400.0, result.rollingWindowKwh ?: 0.0, 1.0)
    }

    /** Twelve equal monthly periods whose 365-day window totals [annualKwh]. */
    private fun exactAnnualConsumption(annualKwh: Int): List<BillPeriod> {
        val start = LocalDate.of(2025, 1, 1)
        val perMonth = annualKwh / 12
        return (0 until 12).map { index ->
            val periodStart = start.plusMonths(index.toLong())
            bill(periodStart, periodStart.plusMonths(1), perMonth)
        }
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
