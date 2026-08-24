package app.recibozen.android.core.security

import android.content.Context
import app.recibozen.android.BuildConfig
import app.recibozen.android.core.model.EntitlementTier

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

    /**
     * Verifies a lease and takes the subject from inside it. The install trust
     * check still applies: a lease is only as good as the installation holding
     * it, whichever subject it names.
     */
    fun verifyLearningSubject(token: String?): EntitlementLeaseVerification {
        if (!installTrustChecker.evaluate().premiumEnvironmentAccepted) {
            return EntitlementLeaseVerification.Rejected(EntitlementLeaseFailure.UNTRUSTED_INSTALL)
        }
        return leaseVerifier.verifyLearningSubject(token)
    }

    fun effectiveTier(token: String?, expectedSubject: String?): EntitlementTier =
        when (val result = verify(token, expectedSubject.orEmpty())) {
            is EntitlementLeaseVerification.Accepted -> result.lease.tier
            is EntitlementLeaseVerification.Rejected -> EntitlementTier.FREE
        }
}
