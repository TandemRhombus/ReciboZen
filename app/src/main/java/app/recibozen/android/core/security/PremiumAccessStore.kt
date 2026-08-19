package app.recibozen.android.core.security

import android.content.Context
import androidx.core.content.edit
import app.recibozen.android.core.model.EntitlementTier
import java.util.UUID

/**
 * Durable home for the anonymous install subject and the backend-signed lease.
 * Neither value grants access by itself: every read verifies the lease signature,
 * audience, subject and expiration through [PremiumEntitlementAuthority].
 */
class PremiumAccessStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val authority = PremiumEntitlementAuthority(appContext)

    val subject: String
        get() = preferences.getString(KEY_SUBJECT, null)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { generated ->
                preferences.edit { putString(KEY_SUBJECT, generated) }
            }

    fun signedLease(): String? = preferences.getString(KEY_SIGNED_LEASE, null)

    fun effectiveTier(): EntitlementTier = authority.effectiveTier(signedLease(), subject)

    /** Persists only a lease that is valid for this exact install. */
    fun acceptSignedLease(token: String): EntitlementTier? = when (val result = authority.verify(token, subject)) {
        is EntitlementLeaseVerification.Accepted -> {
            preferences.edit { putString(KEY_SIGNED_LEASE, token) }
            result.lease.tier
        }
        is EntitlementLeaseVerification.Rejected -> null
    }

    fun clearLease() {
        preferences.edit { remove(KEY_SIGNED_LEASE) }
    }

    private companion object {
        const val PREFERENCES_NAME = "recibozen_premium_access"
        const val KEY_SUBJECT = "install_subject_v1"
        const val KEY_SIGNED_LEASE = "signed_entitlement_lease_v1"
    }
}
