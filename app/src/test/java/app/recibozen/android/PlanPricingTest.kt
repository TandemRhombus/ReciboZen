package app.recibozen.android

import app.recibozen.android.feature.premium.BasePlanPrice
import app.recibozen.android.feature.premium.OfferCandidate
import app.recibozen.android.feature.premium.PlanPricing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the paywall offers and what it claims an annual plan saves.
 *
 * A price claim on a paywall is a promise about money. These tests exist so that
 * the only saving the app can state is one the prices Play returned support.
 */
class PlanPricingTest {

    private fun plan(product: String, basePlan: String, period: String, pesos: Double, currency: String = "MXN") =
        BasePlanPrice(product, basePlan, period, (pesos * 1_000_000).toLong(), currency)

    private val plusMonthly = plan("recibozen_plus_monthly", "mensual", "P1M", 99.0)
    private val plusAnnual = plan("recibozen_plus_monthly", "anual", "P1Y", 990.0)

    @Test
    fun periodsAreReadAsWholeMonths() {
        assertEquals(1, PlanPricing.months("P1M"))
        assertEquals(12, PlanPricing.months("P1Y"))
        assertEquals(3, PlanPricing.months("P3M"))
        assertEquals(18, PlanPricing.months("P1Y6M"))
        assertNull(PlanPricing.months("P1W"))
        assertNull(PlanPricing.months("P"))
        assertNull(PlanPricing.months("mensual"))
    }

    @Test
    fun everyBasePlanOfAProductIsOfferedNotJustTheFirst() {
        val offers = PlanPricing.basePlanOffers(
            listOf(
                OfferCandidate(plusMonthly, offerId = null, offerToken = "t-mensual"),
                OfferCandidate(plusAnnual, offerId = null, offerToken = "t-anual"),
            ),
        )

        assertEquals(listOf("mensual", "anual"), offers.map { it.price.basePlanId })
    }

    @Test
    fun aPromotionIsNotShownAsIfItWereTheBasePrice() {
        val offers = PlanPricing.basePlanOffers(
            listOf(
                OfferCandidate(plusMonthly, offerId = "primer-mes-gratis", offerToken = "t-promo"),
                OfferCandidate(plusMonthly, offerId = null, offerToken = "t-base"),
            ),
        )

        assertEquals(listOf("t-base"), offers.map { it.offerToken })
    }

    @Test
    fun theAnnualSavingIsComputedFromTheRealPrices() {
        val comparison = PlanPricing.annualComparison(plusAnnual, listOf(plusMonthly, plusAnnual))!!

        // 12 × 99 − 990 = 198, and 990 / 12 = 82.50.
        assertEquals(198_000_000L, comparison.savingMicros)
        assertEquals(82_500_000L, comparison.monthlyEquivalentMicros)
    }

    @Test
    fun whenThePriceChangesInPlayConsoleTheClaimFollowsIt() {
        val annualAt1090 = plan("recibozen_plus_monthly", "anual", "P1Y", 1090.0)

        val comparison = PlanPricing.annualComparison(annualAt1090, listOf(plusMonthly, annualAt1090))!!

        assertEquals(98_000_000L, comparison.savingMicros)
    }

    @Test
    fun anotherProductsMonthlyPriceIsNeverTheYardstick() {
        // Only Familiar has a monthly plan. Comparing Plus annual with it would
        // state a saving that has nothing to do with Plus.
        val familyMonthly = plan("recibozen_family_monthly", "mensual", "P1M", 199.0)

        assertNull(PlanPricing.annualComparison(plusAnnual, listOf(familyMonthly, plusAnnual)))
    }

    @Test
    fun pricesInDifferentCurrenciesAreNotCompared() {
        // 99 and not a realistic 6: at 6 USD, 12 × 6 − 990 is negative and the
        // no-saving guard refused it, so this test passed with the currency check
        // deleted. At 99 the numbers alone would claim a 198 saving across
        // currencies, and only the currency check stops it.
        val monthlyInUsd = plan("recibozen_plus_monthly", "mensual", "P1M", 99.0, currency = "USD")

        assertNull(PlanPricing.annualComparison(plusAnnual, listOf(monthlyInUsd, plusAnnual)))
    }

    @Test
    fun anAnnualPlanThatDoesNotCostLessClaimsNothing() {
        val annualAt1200 = plan("recibozen_plus_monthly", "anual", "P1Y", 1200.0)
        val annualAt1188 = plan("recibozen_plus_monthly", "anual", "P1Y", 1188.0)

        assertNull(PlanPricing.annualComparison(annualAt1200, listOf(plusMonthly, annualAt1200)))
        assertNull(PlanPricing.annualComparison(annualAt1188, listOf(plusMonthly, annualAt1188)))
    }

    @Test
    fun aMonthlyPlanClaimsNoSavingEvenAgainstADearerMonthlyPlan() {
        // The plan itself is deliberately NOT among the siblings. With it there,
        // the cheapest monthly is always the plan itself, the saving is zero, and
        // the no-saving guard answers before the months guard is ever reached --
        // an earlier version of this test passed with the months guard deleted.
        val legacyMonthly = plan("recibozen_plus_monthly", "mensual-2025", "P1M", 129.0)

        assertNull(PlanPricing.annualComparison(plusMonthly, listOf(legacyMonthly)))
    }

    @Test
    fun theSavingIsMeasuredAgainstTheCheapestMonthlyPlanNotTheFirstListed() {
        val legacyMonthly = plan("recibozen_plus_monthly", "mensual-2025", "P1M", 129.0)

        val comparison = PlanPricing.annualComparison(plusAnnual, listOf(legacyMonthly, plusMonthly, plusAnnual))!!

        // Against $129 this would claim 12 × 129 − 990 = $558.
        assertEquals(198_000_000L, comparison.savingMicros)
    }
}
