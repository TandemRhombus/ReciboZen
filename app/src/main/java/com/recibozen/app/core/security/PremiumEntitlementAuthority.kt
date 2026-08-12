package com.recibozen.app.core.security

import android.content.Context
import com.recibozen.app.BuildConfig
import com.recibozen.app.core.model.EntitlementTier

/**
 * Single trust boundary for future Billing integration. The backend response is
 * accepted only when both the install environment and its signed lease verify.
 */
class PremiumEntitlementAuthority(context: Context) {
    private val appContext = context.applicationContext
    private val installTrustChecker = AppInstallTrustChecker(appContext)
    private val leaseVerifier = EntitlementLeaseVerifier(
        publicKeyBase64 = BuildConfig.ENTITLEMENT_PUBLIC_KEY_B64,
        expectedAudience = BuildConfig.APPLICATION_ID,
        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
    )

    fun verify(token: String?, expectedSubject: String): EntitlementLeaseVerification {
        if (!installTrustChecker.evaluate().premiumEnvironmentAccepted) {
            return EntitlementLeaseVerification.Rejected(EntitlementLeaseFailure.UNTRUSTED_INSTALL)
        }
        return leaseVerifier.verify(token, expectedSubject)
    }

    fun effectiveTier(token: String?, expectedSubject: String?): EntitlementTier =
        when (val result = verify(token, expectedSubject.orEmpty())) {
            is EntitlementLeaseVerification.Accepted -> result.lease.tier
            is EntitlementLeaseVerification.Rejected -> EntitlementTier.FREE
        }
}
