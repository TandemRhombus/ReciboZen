package app.recibozen.android.core.security

import android.content.Context
import androidx.core.content.edit
import app.recibozen.android.core.model.EntitlementTier
import java.util.UUID

/**
 * Durable home for the install identity and the backend-signed lease. Neither
 * value grants access by itself: every read verifies the lease signature,
 * audience, subject and expiration through [PremiumEntitlementAuthority].
 *
 * Two identities live here and they are not interchangeable:
 *
 * [subject] is generated locally and binds a purchase to this installation while
 * it is being verified. It survives nothing — clearing app data replaces it.
 *
 * [quotaSubject] is derived by the backend from the purchase itself and arrives
 * inside the signed lease. It is what the AI quota is counted against, and it is
 * the reason clearing app data no longer mints a fresh monthly allowance: the
 * new install asks with a new [subject], and gets back a lease carrying the same
 * [quotaSubject] as before, because the subscription did not change.
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

    /** The signed quota identity, once a lease has taught it to this install. */
    val quotaSubject: String?
        get() = preferences.getString(KEY_QUOTA_SUBJECT, null)?.takeIf { it.isNotBlank() }

    fun signedLease(): String? = preferences.getString(KEY_SIGNED_LEASE, null)

    fun effectiveTier(): EntitlementTier {
        val lease = signedLease() ?: return EntitlementTier.FREE
        val pinned = quotaSubject ?: return EntitlementTier.FREE
        return authority.effectiveTier(lease, pinned)
    }

    /**
     * Persists a lease and, the first time, the quota subject it carries.
     *
     * After that first lease the subject is pinned: a later one has to match.
     * That is what keeps a lease from being useful on someone else's phone now
     * that the subject is no longer chosen locally — the signature proves the
     * subject is genuine, and the pin proves it is ours.
     */
    fun acceptSignedLease(token: String): EntitlementTier? {
        val pinned = quotaSubject
        val result = if (pinned == null) {
            authority.verifyLearningSubject(token)
        } else {
            authority.verify(token, pinned)
        }
        return when (result) {
            is EntitlementLeaseVerification.Accepted -> {
                preferences.edit {
                    putString(KEY_SIGNED_LEASE, token)
                    putString(KEY_QUOTA_SUBJECT, result.lease.subject)
                }
                result.lease.tier
            }
            is EntitlementLeaseVerification.Rejected -> null
        }
    }

    fun clearLease() {
        // The pinned subject stays. Dropping it here would hand back the very
        // reset this design exists to prevent, and it is not a secret: it is
        // derived from a purchase the household made.
        preferences.edit { remove(KEY_SIGNED_LEASE) }
    }

    private companion object {
        const val PREFERENCES_NAME = "recibozen_premium_access"
        const val KEY_SUBJECT = "install_subject_v1"
        const val KEY_SIGNED_LEASE = "signed_entitlement_lease_v1"
        const val KEY_QUOTA_SUBJECT = "quota_subject_v1"
    }
}
