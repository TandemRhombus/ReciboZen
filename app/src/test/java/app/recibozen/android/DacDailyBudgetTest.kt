package app.recibozen.android

import app.recibozen.android.core.calculation.DacDailyBudget
import app.recibozen.android.core.calculation.DacRiskCalculator
import app.recibozen.android.core.model.BillPeriod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DAC period ceiling turned into a daily rate.
 *
 * The ceiling itself is a bimester total, which nobody can act on: households
 * do not ration by the bimester. These tests pin the two things that make the
 * daily figure honest -- that it subtracts what the period already used, and
 * that it refuses to exist rather than guess when a fact is missing.
 */
class DacDailyBudgetTest {

    /**
     * Tariff 1 has a 250 kWh/month high-consumption limit, so the rolling year
     * allows 3,000 kWh. A household billed 150 kWh a month sits well under it.
     */
    private fun snapshot(
        monthlyKwh: Int,
        tariff: String = "1",
        projectedBillingDays: Int = 61,
        projectedNextBillKwh: Int? = null,
        months: Int = 12,
        referenceTariff: String? = null,
    ) = DacRiskCalculator.calculate(
        history = monthlyBills(monthlyKwh, months),
        tariffCode = tariff,
        projectedNextBillKwh = projectedNextBillKwh,
        projectedBillingDays = projectedBillingDays,
        domesticReferenceTariffCode = referenceTariff,
    )

    private fun monthlyBills(kwh: Int, months: Int): List<BillPeriod> {
        val start = LocalDate.of(2025, 1, 1)
        return (0 until months).map { index ->
            val periodStart = start.plusMonths(index.toLong())
            BillPeriod(
                monthLabel = "",
                amountMxn = 0,
                consumptionKwh = kwh,
                periodStartEpochDay = periodStart.toEpochDay(),
                periodEndEpochDay = periodStart.plusMonths(1).toEpochDay(),
            )
        }
    }

    // --- The figure the household asked for ---

    @Test
    fun theDailyAllowanceIsWhatIsLeftOfTheCeilingSpreadOverTheDaysThatRemain() {
        val risk = snapshot(monthlyKwh = 150)
        val ceiling = requireNotNull(risk.recommendedNextBillMaxKwh)

        val budget = DacDailyBudget.of(
            snapshot = risk,
            consumedKwh = 200,
            daysRemaining = 40,
            consumptionIsMeasured = true,
        )

        val available = budget as DacDailyBudget.Available
        assertEquals(ceiling - 200, available.remainingKwh)
        assertEquals((ceiling - 200) / 40.0, available.dailyKwh, 0.0001)
        assertEquals(ceiling, available.periodCeilingKwh)
        assertFalse(available.isExitBudget)
    }

    @Test
    fun spendingMoreOfThePeriodTightensTheRemainingPace() {
        val risk = snapshot(monthlyKwh = 150)

        val thrifty = DacDailyBudget.of(risk, consumedKwh = 100, daysRemaining = 30, consumptionIsMeasured = true)
        val spent = DacDailyBudget.of(risk, consumedKwh = 900, daysRemaining = 30, consumptionIsMeasured = true)

        assertTrue(
            "con los mismos días restantes, haber gastado más aprieta el ritmo",
            (thrifty as DacDailyBudget.Available).dailyKwh > (spent as DacDailyBudget.Available).dailyKwh,
        )
    }

    @Test
    fun theSameEnergyOverFewerDaysIsALooserDailyPaceNotATighterOne() {
        // Counter-intuitive, correct, and pinned here so nobody "fixes" it into
        // the opposite. The allowance is what is left divided by the days left,
        // so a household holding 600 kWh with ten days to go may spend 60 a day;
        // the same 600 kWh with fifty days to go is 12 a day. Being late in the
        // period is not by itself a reason to ration harder -- having spent the
        // budget is, and that is the test above.
        val risk = snapshot(monthlyKwh = 150)

        val early = DacDailyBudget.of(risk, consumedKwh = 400, daysRemaining = 50, consumptionIsMeasured = true)
        val late = DacDailyBudget.of(risk, consumedKwh = 400, daysRemaining = 10, consumptionIsMeasured = true)

        assertTrue(
            (late as DacDailyBudget.Available).dailyKwh > (early as DacDailyBudget.Available).dailyKwh,
        )
        assertEquals(early.remainingKwh, late.remainingKwh)
    }

    // --- A household already in DAC gets the same arithmetic, other wording ---

    @Test
    fun aHouseholdAlreadyInDacGetsTheCeilingThatTakesItOut() {
        // In DAC, with tariff 1 as the domestic reference the exit limit applies to.
        val risk = snapshot(monthlyKwh = 150, tariff = "DAC", referenceTariff = "1")

        val budget = DacDailyBudget.of(risk, consumedKwh = 200, daysRemaining = 40, consumptionIsMeasured = true)

        assertTrue(budget is DacDailyBudget.Available)
        assertTrue("la pantalla tiene que poder decir 'para salir'", budget.isExitBudget)
    }

    @Test
    fun aDacHouseholdWithoutAReferenceTariffHasNoLimitToPaceAgainst() {
        // Without a domestic reference there is no limit, so there is nothing to
        // ration towards and the app must not invent one.
        val risk = snapshot(monthlyKwh = 150, tariff = "DAC")

        val budget = DacDailyBudget.of(risk, consumedKwh = 0, daysRemaining = 40, consumptionIsMeasured = true)

        assertEquals(DacDailyBudget.Unavailable(DacDailyBudget.Reason.NO_LIMIT, isExitBudget = true), budget)
    }

    // --- What it refuses to answer ---

    @Test
    fun aCeilingAlreadySpentIsNotReportedAsZeroPerDay() {
        val risk = snapshot(monthlyKwh = 150)
        val ceiling = requireNotNull(risk.recommendedNextBillMaxKwh)

        val budget = DacDailyBudget.of(
            snapshot = risk,
            consumedKwh = ceiling + 300,
            daysRemaining = 20,
            consumptionIsMeasured = true,
        )

        // Zero per day would read as "use nothing and you are fine". The
        // overshoot has already happened and no future rate undoes it.
        val exhausted = budget as DacDailyBudget.Exhausted
        assertEquals(300, exhausted.overshootKwh)
        assertEquals(20, exhausted.daysRemaining)
    }

    @Test
    fun anExhaustedCeilingStaysExhaustedEvenOnTheLastDay() {
        val risk = snapshot(monthlyKwh = 150)
        val ceiling = requireNotNull(risk.recommendedNextBillMaxKwh)

        val budget = DacDailyBudget.of(risk, consumedKwh = ceiling, daysRemaining = 0, consumptionIsMeasured = true)

        // Exactly at the ceiling with no days left: still nothing left to spend,
        // and PERIOD_CLOSED would hide that they are right on the edge.
        assertTrue(budget is DacDailyBudget.Exhausted)
        assertEquals(0, (budget as DacDailyBudget.Exhausted).overshootKwh)
    }

    @Test
    fun withoutACompleteBilledYearThereIsNoCeilingAndSoNoDailyFigure() {
        val risk = snapshot(monthlyKwh = 150, months = 3)

        val budget = DacDailyBudget.of(risk, consumedKwh = 100, daysRemaining = 40, consumptionIsMeasured = true)

        assertEquals(DacDailyBudget.Unavailable(DacDailyBudget.Reason.INCOMPLETE_HISTORY), budget)
    }

    @Test
    fun aTariffWithNoHighConsumptionLimitHasNothingToPaceAgainst() {
        val risk = snapshot(monthlyKwh = 150, tariff = "PDBT")

        val budget = DacDailyBudget.of(risk, consumedKwh = 100, daysRemaining = 40, consumptionIsMeasured = true)

        assertEquals(DacDailyBudget.Unavailable(DacDailyBudget.Reason.NO_LIMIT), budget)
    }

    @Test
    fun anUnknownPeriodLengthHasNoCeilingToDivide() {
        val risk = snapshot(monthlyKwh = 150, projectedBillingDays = 0)

        val budget = DacDailyBudget.of(risk, consumedKwh = 100, daysRemaining = 40, consumptionIsMeasured = true)

        assertEquals(DacDailyBudget.Unavailable(DacDailyBudget.Reason.PERIOD_LENGTH_UNKNOWN), budget)
    }

    @Test
    fun aClosedPeriodWithBudgetLeftReportsThatRatherThanADivisionByZero() {
        val risk = snapshot(monthlyKwh = 150)

        val budget = DacDailyBudget.of(risk, consumedKwh = 100, daysRemaining = 0, consumptionIsMeasured = true)

        assertEquals(DacDailyBudget.Unavailable(DacDailyBudget.Reason.PERIOD_CLOSED), budget)
    }

    // --- Where the consumed figure came from travels with the answer ---

    @Test
    fun anAllowanceBuiltOnAnUnmeasuredPeriodSaysSo() {
        val risk = snapshot(monthlyKwh = 150)

        val budget = DacDailyBudget.of(risk, consumedKwh = 200, daysRemaining = 40, consumptionIsMeasured = false)

        // Without readings from this period the consumed figure is an assumption,
        // so the allowance is an upper bound on an unknown and the screen warns.
        assertFalse((budget as DacDailyBudget.Available).basedOnMeasuredConsumption)
    }

    @Test
    fun aNegativeConsumedFigureIsNotAllowedToInflateTheAllowance() {
        val risk = snapshot(monthlyKwh = 150)
        val ceiling = requireNotNull(risk.recommendedNextBillMaxKwh)

        val budget = DacDailyBudget.of(risk, consumedKwh = -500, daysRemaining = 50, consumptionIsMeasured = true)

        assertEquals(ceiling, (budget as DacDailyBudget.Available).remainingKwh)
    }
}
