package app.recibozen.android

import app.recibozen.android.core.calculation.DacRiskCalculator
import app.recibozen.android.core.calculation.DacRiskSnapshot
import app.recibozen.android.core.calculation.TariffBlock
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.feature.plan.DacGlanceKind
import app.recibozen.android.feature.plan.DacGlanceTone
import app.recibozen.android.feature.plan.buildTariffTiersForTest
import app.recibozen.android.feature.plan.dacKwh
import app.recibozen.android.feature.plan.dacSignedKwh
import app.recibozen.android.feature.plan.toGlance
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DAC screen compresses the snapshot into four answers. These tests pin the
 * compression: what it may claim, what it must refuse to claim, and how the
 * numbers are signed on screen.
 */
class DacGlanceTest {

    // --- Which question the hero answers ---

    @Test
    fun healthyConsumptionShowsTheRemainingCushion() {
        val glance = DacRiskCalculator.calculate(yearOfMonthlyBills(150), "1").toGlance()

        assertEquals(DacGlanceKind.HEADROOM, glance.kind)
        assertEquals(DacGlanceTone.SAFE, glance.tone)
        assertTrue(glance.marginKwh > 0)
        assertFalse(glance.isOverLimit)
        assertEquals(glance.marginKwh, glance.heroKwh)
    }

    @Test
    fun approachingTheLimitIsCautionWithoutClaimingItWasCrossed() {
        val glance = DacRiskCalculator.calculate(yearOfMonthlyBills(240), "1").toGlance()

        assertEquals(DacGlanceKind.HEADROOM, glance.kind)
        assertEquals(DacGlanceTone.CAUTION, glance.tone)
        assertFalse(glance.isOverLimit)
    }

    @Test
    fun aboveTheLimitKeepsItsOwnKindSoTheScreenCanExplainWhyItIsNotDacYet() {
        val glance = DacRiskCalculator.calculate(yearOfMonthlyBills(300), "1").toGlance()

        assertEquals(DacGlanceKind.OVER_LIMIT, glance.kind)
        assertEquals(DacGlanceTone.OVER, glance.tone)
        assertTrue(glance.isOverLimit)
        // The hero number is unsigned; its label carries the direction.
        assertEquals(-glance.marginKwh, glance.heroKwh)
    }

    // --- What the screen must refuse to claim ---

    @Test
    fun incompleteHistoryNeverBecomesAVerdictEvenWhenTheTrendIsOverTheLimit() {
        val glance = DacRiskCalculator.calculate(yearOfMonthlyBills(400).takeLast(3), "1").toGlance()

        assertEquals(DacGlanceKind.NO_VERDICT, glance.kind)
        assertEquals(DacGlanceTone.NEUTRAL, glance.tone)
        assertNull(glance.ledger)
        assertNull(glance.recommendedMaxKwh)
        // With no verdict the hero shows what was actually billed, not a margin.
        assertEquals(glance.accumulatedKwh, glance.heroKwh)
    }

    @Test
    fun withoutAVerdictTheBarPlotsBilledEnergyNotAnAnnualisedExtrapolation() {
        val history = yearOfMonthlyBills(400).takeLast(3)
        val snapshot = DacRiskCalculator.calculate(history, "1")
        val glance = snapshot.toGlance()

        // The calculator still annualises for its own comparison; the hero must
        // not borrow that number under an "accumulated" label.
        assertTrue((snapshot.annualizedRollingKwh ?: 0.0) > (snapshot.rollingWindowKwh ?: 0.0))
        assertEquals(snapshot.rollingWindowKwh?.toInt(), glance.accumulatedKwh)
        assertEquals(1_200, glance.accumulatedKwh)
    }

    @Test
    fun anAlreadyDacAccountWithIncompleteHistoryDoesNotGetAnExitTarget() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(600).takeLast(4),
            tariffCode = "DAC",
            domesticReferenceTariffCode = "1B",
        ).toGlance()

        assertEquals(DacGlanceKind.NO_VERDICT, glance.kind)
    }

    @Test
    fun anUnsupportedTariffHasNothingToShow() {
        val glance = DacRiskCalculator.calculate(yearOfMonthlyBills(400), "PDBT").toGlance()

        assertEquals(DacGlanceKind.UNAVAILABLE, glance.kind)
        assertNull(glance.ledger)
    }

    @Test
    fun aDacAccountWithoutADomesticReferenceHasNoLimitToCompareAgainst() {
        val glance = DacRiskCalculator.calculate(yearOfMonthlyBills(400), "DAC").toGlance()

        assertEquals(DacGlanceKind.UNAVAILABLE, glance.kind)
    }

    @Test
    fun aTariffWithALimitButNoBilledHistoryStaysUnavailable() {
        val glance = DacRiskCalculator.calculate(emptyList(), "1").toGlance()

        assertEquals(DacGlanceKind.UNAVAILABLE, glance.kind)
        assertEquals(0, glance.accumulatedKwh)
    }

    // --- Already DAC: the question is how much has to come off ---

    @Test
    fun anAlreadyDacAccountAboveItsReferenceLimitShowsTheMonthlyGapToClose() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(600),
            tariffCode = "DAC",
            domesticReferenceTariffCode = "1B",
        ).toGlance()

        assertEquals(DacGlanceKind.EXIT, glance.kind)
        assertEquals(DacGlanceTone.OVER, glance.tone)
        // 1B allows 400 kWh/month; the account is averaging about 600.
        assertEquals(-200, glance.monthlyMarginKwh)
    }

    @Test
    fun anAlreadyDacAccountBackUnderTheLimitReadsAsProgressNotAsAlarm() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(300),
            tariffCode = "DAC",
            domesticReferenceTariffCode = "1B",
        ).toGlance()

        assertEquals(DacGlanceKind.EXIT, glance.kind)
        assertEquals(DacGlanceTone.SAFE, glance.tone)
        assertEquals(100, glance.monthlyMarginKwh)
    }

    // --- The next-bill ledger ---

    @Test
    fun theLedgerCarriesWhatLeavesWhatEntersAndWhatIsLeft() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 31,
        ).toGlance()

        val ledger = glance.ledger
        assertNotNull(ledger)
        assertEquals(200, ledger!!.expiringKwh)
        assertEquals(500, ledger.projectedKwh)
        assertEquals(300, ledger.resultingMarginKwh)
        assertFalse(ledger.exceeds)
        assertEquals(800, glance.recommendedMaxKwh)
    }

    @Test
    fun aProjectionThatBlowsTheCushionIsFlaggedInTheLedger() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(250),
            tariffCode = "1",
            projectedNextBillKwh = 600,
            projectedBillingDays = 31,
        ).toGlance()

        val ledger = glance.ledger
        assertNotNull(ledger)
        assertTrue(ledger!!.exceeds)
        assertTrue(ledger.resultingMarginKwh < 0)
    }

    @Test
    fun bothCeilingsReachTheScreenSoTheReaderCanSeeTwoPeriodsAhead() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 31,
        ).toGlance()

        assertEquals(800, glance.recommendedMaxKwh)
        assertEquals(519, glance.recommendedFollowingMaxKwh)
    }

    @Test
    fun withoutAMeterProjectionThereIsNoLedgerButStillARecommendedMaximum() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = null,
            projectedBillingDays = 31,
        ).toGlance()

        assertNull(glance.ledger)
        assertEquals(800, glance.recommendedMaxKwh)
        // The second ceiling would need a projection to stand on.
        assertNull(glance.recommendedFollowingMaxKwh)
    }

    @Test
    fun withoutAVerdictNeitherCeilingIsShown() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200).takeLast(3),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 31,
        ).toGlance()

        assertEquals(DacGlanceKind.NO_VERDICT, glance.kind)
        assertNull(glance.recommendedMaxKwh)
        assertNull(glance.recommendedFollowingMaxKwh)
    }

    @Test
    fun withoutAnExpectedPeriodLengthNothingAboutTheNextBillIsClaimed() {
        val glance = DacRiskCalculator.calculate(
            history = yearOfMonthlyBills(200),
            tariffCode = "1",
            projectedNextBillKwh = 500,
            projectedBillingDays = 0,
        ).toGlance()

        assertNull(glance.ledger)
        assertNull(glance.recommendedMaxKwh)
    }

    // --- The bar ---

    @Test
    fun theBarFillsCompletelyAndPushesTheLimitMarkerBackWhenTheLimitIsExceeded() {
        val glance = DacRiskCalculator.calculate(yearOfMonthlyBills(300), "1").toGlance()

        assertEquals(1f, glance.fillFraction, 0.0001f)
        assertTrue(glance.limitMarkerFraction < 1f)
        // 3,000 of 3,600 kWh.
        assertEquals(0.833f, glance.limitMarkerFraction, 0.01f)
    }

    @Test
    fun belowTheLimitTheMarkerSitsAtTheEndOfTheBar() {
        val glance = DacRiskCalculator.calculate(yearOfMonthlyBills(150), "1").toGlance()

        assertEquals(1f, glance.limitMarkerFraction, 0.0001f)
        assertEquals(0.6f, glance.fillFraction, 0.01f)
    }

    @Test
    fun anEmptySnapshotNeverDividesByZero() {
        val glance = DacRiskSnapshot().toGlance()

        assertEquals(DacGlanceKind.UNAVAILABLE, glance.kind)
        assertEquals(0f, glance.fillFraction, 0.0001f)
        assertEquals(0f, glance.limitMarkerFraction, 0.0001f)
    }

    // --- Signed formatting ---

    @Test
    fun ledgerValuesCarryAnExplicitSign() {
        assertEquals("+410", dacSignedKwh(410))
        assertEquals("−340", dacSignedKwh(-340))
        assertEquals("0", dacSignedKwh(0))
    }

    @Test
    fun negativeValuesUseARealMinusSignInsteadOfAHyphen() {
        assertEquals('−', dacSignedKwh(-70).first())
    }

    @Test
    fun thousandsAreGroupedInBothFormatters() {
        assertEquals("10,200", dacKwh(10_200))
        assertEquals("−10,200", dacSignedKwh(-10_200))
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

    // --- The tier bar only shows tiers the period actually reaches ---

    @Test
    fun tariffTiersBeyondTheProjectionCarryNoWeightAndAreNotDrawn() {
        // buildTariffTiers maps every block of the tariff, reached or not, so a
        // household inside the first block produces two tiers of exactly zero.
        // The bar used to coerce those to 0.001 and render two slivers with a
        // 4dp gap each, eating its right end; the legend listed them as
        // "Intermedio · 0 kWh". Both now filter on this being zero, so the
        // property has to hold.
        val blocks = listOf(
            TariffBlock(limitKwh = 150, rateMxnPerKwh = 1.0),
            TariffBlock(limitKwh = 280, rateMxnPerKwh = 2.0),
            TariffBlock(limitKwh = null, rateMxnPerKwh = 3.0),
        )

        val tiers = buildTariffTiersForTest(basisKwh = 100, blocks = blocks)

        assertEquals(3, tiers.size)
        assertEquals(100, tiers[0].usedKwh)
        assertEquals(0, tiers[1].usedKwh)
        assertEquals(0, tiers[2].usedKwh)
        assertEquals(0f, tiers[1].fraction, 0.0001f)
        // Exactly one tier survives the filter both the bar and the legend apply.
        assertEquals(1, tiers.count { it.usedKwh > 0 })
    }

    @Test
    fun aPeriodReachingEveryTierKeepsAllOfThem() {
        val blocks = listOf(
            TariffBlock(limitKwh = 150, rateMxnPerKwh = 1.0),
            TariffBlock(limitKwh = 280, rateMxnPerKwh = 2.0),
            TariffBlock(limitKwh = null, rateMxnPerKwh = 3.0),
        )

        val tiers = buildTariffTiersForTest(basisKwh = 400, blocks = blocks)

        assertEquals(listOf(150, 130, 120), tiers.map { it.usedKwh })
        assertEquals(3, tiers.count { it.usedKwh > 0 })
        // The fractions are the bar, so they have to add up to the whole bar.
        assertEquals(1.0f, tiers.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
    }
}
