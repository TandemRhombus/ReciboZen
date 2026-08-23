package app.recibozen.android

import app.recibozen.android.core.model.EntitlementPolicy
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.model.Paywall
import app.recibozen.android.core.notifications.PremiumFeaturePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Plus paywall is currently off on purpose ([Paywall.ENFORCED]) because the
 * subscription cannot yet be purchased, so gating features would withhold them
 * from everyone while collecting from nobody.
 *
 * These tests cover both states rather than whatever the flag happens to say
 * today. Turning the paywall back on is a one-line change, and it has to be one
 * that ships already proven -- not one whose behaviour is first exercised in
 * production.
 */
class PremiumFeaturePolicyTest {

    // --- What ships today: the paywall is off ---

    @Test
    fun whileThePaywallIsOffNothingIsWithheldFromAnyone() {
        assertFalse("the flag is expected to be off in this build", Paywall.ENFORCED)

        assertTrue(PremiumFeaturePolicy.notificationsAllowed(EntitlementTier.FREE))
        assertEquals(5, EntitlementPolicy.activeServiceLimit(EntitlementTier.FREE))
    }

    // --- What happens the day it is switched on ---

    @Test
    fun enforcingThePaywallLocksNotificationsForTheFreeTierAgain() {
        assertFalse(PremiumFeaturePolicy.notificationsAllowed(EntitlementTier.FREE, enforced = true))
        assertTrue(PremiumFeaturePolicy.notificationsAllowed(EntitlementTier.PLUS, enforced = true))
        assertTrue(PremiumFeaturePolicy.notificationsAllowed(EntitlementTier.FAMILY, enforced = true))
    }

    @Test
    fun enforcingThePaywallRestoresTheServiceLimitsEachTierPaysFor() {
        assertEquals(1, EntitlementPolicy.activeServiceLimit(EntitlementTier.FREE, enforced = true))
        assertEquals(2, EntitlementPolicy.activeServiceLimit(EntitlementTier.PLUS, enforced = true))
        assertEquals(5, EntitlementPolicy.activeServiceLimit(EntitlementTier.FAMILY, enforced = true))
    }

    // --- The switch itself ---

    @Test
    fun theSwitchLiftsEveryGateAtOnceInsteadOfOnePerFeature() {
        // Every tier resolves to the top one while off, so a gate added later
        // cannot be forgotten and left closed.
        EntitlementTier.entries.forEach { tier ->
            assertEquals(EntitlementTier.FAMILY, Paywall.effective(tier, enforced = false))
            assertEquals(tier, Paywall.effective(tier, enforced = true))
        }
    }
}
