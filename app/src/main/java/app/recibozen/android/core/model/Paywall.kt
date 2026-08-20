package app.recibozen.android.core.model

/**
 * Whether the Plus paywall is enforced.
 *
 * It is off, deliberately, and this is not a permanent decision. Until a
 * subscription can actually be bought, the paywall gates two features for every
 * user while collecting nothing from anyone: no base plan is active, so
 * `queryProductDetailsAsync` returns no offer and the Premium screen reports the
 * subscription as unavailable. Charging nobody and blocking everybody is the
 * worst of both.
 *
 * It also made an honest answer impossible on Play's App access declaration.
 * That form requires attesting that the details supplied grant full access to
 * every feature "including premium or paid content". With the gate on and no way
 * to hand a reviewer a purchase, that attestation would be false, and a false
 * declaration there is grounds for removal from Google Play.
 *
 * **To turn the paywall back on, set [ENFORCED] to true.** Do it in the same
 * release that makes the subscription purchasable -- active base plans, a build
 * carrying the Play App Signing certificates, and a backend answering
 * `playBillingConfigured: true` -- so the gate and the ability to pay for what
 * is behind it ship together.
 */
object Paywall {

    /** True once Plus can be purchased and verified end to end. */
    const val ENFORCED = false

    /**
     * The tier to enforce against. While the paywall is off, every install is
     * treated as the top tier, so nothing is withheld from anyone -- rather than
     * special-casing each gate and risking one being missed.
     */
    fun effective(tier: EntitlementTier, enforced: Boolean = ENFORCED): EntitlementTier =
        if (enforced) tier else EntitlementTier.FAMILY
}
