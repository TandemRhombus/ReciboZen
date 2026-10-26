package app.recibozen.android.feature.premium

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * One base plan's recurring price, as Google Play reports it.
 *
 * Kept free of Billing Library types on purpose: ProductDetails cannot be
 * constructed outside Play, so anything that reads it directly cannot be tested.
 */
data class BasePlanPrice(
    val productId: String,
    val basePlanId: String,
    /** ISO 8601, exactly as Play sends it: "P1M", "P1Y". */
    val billingPeriod: String,
    val priceMicros: Long,
    val currencyCode: String,
)

/** A subscription offer as returned by Play, reduced to what selection needs. */
data class OfferCandidate(
    val price: BasePlanPrice,
    /** Null for a base plan's own offer; set for a developer-defined promotion. */
    val offerId: String?,
    val offerToken: String,
)

data class AnnualComparison(
    val monthlyEquivalentMicros: Long,
    /** What paying the monthly plan for the same span would cost on top. Always positive. */
    val savingMicros: Long,
)

object PlanPricing {

    /** Whole months in an ISO 8601 period, or null for weeks, days or anything unparseable. */
    fun months(billingPeriod: String): Int? {
        val match = Regex("^P(?:(\\d+)Y)?(?:(\\d+)M)?$").matchEntire(billingPeriod) ?: return null
        val years = match.groupValues[1].toIntOrNull() ?: 0
        val months = match.groupValues[2].toIntOrNull() ?: 0
        return (years * 12 + months).takeIf { it > 0 }
    }

    /**
     * The offers the paywall shows: one per base plan.
     *
     * This replaces taking the first offer of each product, which was correct
     * only while every product had a single base plan. Add an annual base plan to
     * Plus and that version showed either the monthly or the annual one --
     * whichever Play happened to list first -- and never both.
     *
     * Promotional offers are left out. Their first pricing phase can be a trial
     * or an introductory price, and showing only the recurring phase, as the
     * paywall does, would hide it. They get a design of their own if one is ever
     * configured.
     */
    fun basePlanOffers(candidates: List<OfferCandidate>): List<OfferCandidate> =
        candidates
            .filter { it.offerId == null }
            .distinctBy { it.price.productId to it.price.basePlanId }

    /**
     * What a multi-month plan works out to per month and saves against paying
     * monthly, computed from the prices Play actually returned.
     *
     * Never written as a fixed "2 meses gratis": that is true only for one pair of
     * prices, and prices are edited in Play Console, not in this code. Null
     * whenever the claim cannot be made honestly -- no monthly plan of the SAME
     * product to compare with (Familiar monthly says nothing about Plus annual),
     * a different currency, or a long plan that does not actually cost less.
     */
    fun annualComparison(plan: BasePlanPrice, siblings: List<BasePlanPrice>): AnnualComparison? {
        val months = months(plan.billingPeriod) ?: return null
        if (months <= 1) return null
        // The cheapest monthly plan, not the first one listed. A product can carry
        // two -- a legacy price kept for existing subscribers next to the current
        // one -- and measuring against the dearer one inflates the saving by
        // exactly the difference, times twelve.
        val monthly = siblings
            .filter {
                it.productId == plan.productId &&
                    it.currencyCode == plan.currencyCode &&
                    months(it.billingPeriod) == 1
            }
            .minByOrNull { it.priceMicros }
            ?: return null
        val saving = monthly.priceMicros * months - plan.priceMicros
        if (saving <= 0) return null
        return AnnualComparison(
            monthlyEquivalentMicros = plan.priceMicros / months,
            savingMicros = saving,
        )
    }

    fun formatMicros(micros: Long, currencyCode: String): String {
        val format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"))
        runCatching { format.currency = Currency.getInstance(currencyCode) }
        return format.format(micros / 1_000_000.0)
    }
}
