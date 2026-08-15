package com.recibozen.app

import com.recibozen.app.core.model.EntitlementTier
import com.recibozen.app.core.notifications.PremiumFeaturePolicy
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
