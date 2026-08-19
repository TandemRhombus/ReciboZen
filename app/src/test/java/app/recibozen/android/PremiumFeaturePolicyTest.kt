package app.recibozen.android

import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.notifications.PremiumFeaturePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumFeaturePolicyTest {
    @Test fun notificationsRemainLockedForFreeTier() {
        assertFalse(PremiumFeaturePolicy.notificationsAllowed(EntitlementTier.FREE))
    }

    @Test fun trustedPaidTiersCanScheduleNotifications() {
        assertTrue(PremiumFeaturePolicy.notificationsAllowed(EntitlementTier.PLUS))
        assertTrue(PremiumFeaturePolicy.notificationsAllowed(EntitlementTier.FAMILY))
    }
}
