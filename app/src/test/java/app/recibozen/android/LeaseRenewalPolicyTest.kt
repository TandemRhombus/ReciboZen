package app.recibozen.android

import app.recibozen.android.feature.premium.LeaseRenewalPolicy
import app.recibozen.android.feature.premium.LeaseRenewalPolicy.MIN_RETRY_SECONDS
import app.recibozen.android.feature.premium.LeaseRenewalPolicy.RENEW_AHEAD_SECONDS
import app.recibozen.android.feature.premium.OwnedSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaseRenewalPolicyTest {

    private val now = 1_800_000_000L

    @Test
    fun noValidLeaseMeansAskPlay() {
        // A new phone, cleared data or a lapsed lease all look like this, and
        // all of them are a household that may well be paying.
        assertTrue(LeaseRenewalPolicy.shouldAttempt(now, leaseExpiresAtEpochSeconds = null, lastAttemptEpochSeconds = null))
    }

    @Test
    fun aLeaseCloseToExpiryIsRenewedBeforeItLapses() {
        assertTrue(LeaseRenewalPolicy.shouldAttempt(now, now + RENEW_AHEAD_SECONDS, null))
        assertTrue(LeaseRenewalPolicy.shouldAttempt(now, now + 60, null))
    }

    @Test
    fun aFreshLeaseIsLeftAlone() {
        assertFalse(LeaseRenewalPolicy.shouldAttempt(now, now + RENEW_AHEAD_SECONDS + 1, null))
        assertFalse(LeaseRenewalPolicy.shouldAttempt(now, now + 20 * 60 * 60, null))
    }

    @Test
    fun switchingAppsDoesNotSpendAnIntegrityTokenEveryTime() {
        assertFalse(LeaseRenewalPolicy.shouldAttempt(now, null, lastAttemptEpochSeconds = now - MIN_RETRY_SECONDS + 1))
        assertTrue(LeaseRenewalPolicy.shouldAttempt(now, null, lastAttemptEpochSeconds = now - MIN_RETRY_SECONDS))
    }

    @Test
    fun onlyAPaidSubscriptionOfOursIsExchanged() {
        val pending = OwnedSubscription("recibozen_plus_monthly", "pending-token", purchased = false)
        val foreign = OwnedSubscription("some_other_product", "foreign-token", purchased = true)
        val family = OwnedSubscription("recibozen_family_monthly", "family-token", purchased = true)

        assertNull(LeaseRenewalPolicy.purchaseToExchange(emptyList()))
        assertNull(LeaseRenewalPolicy.purchaseToExchange(listOf(pending, foreign)))
        assertEquals(family, LeaseRenewalPolicy.purchaseToExchange(listOf(pending, foreign, family)))
    }
}
