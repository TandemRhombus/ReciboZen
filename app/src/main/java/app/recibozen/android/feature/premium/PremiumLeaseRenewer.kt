package app.recibozen.android.feature.premium

import android.content.Context
import app.recibozen.android.core.model.EntitlementTier
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When to go and ask Play for a fresh lease without the household doing anything.
 *
 * A lease lives at most 24 hours. Before this existed the only thing that
 * renewed it was opening the Premium screen, so a paying household lost what it
 * paid for the day after buying it, and a new phone stayed free until someone
 * thought to press "restore". The renewal now runs on every app start, and this
 * decides whether a given start needs it.
 */
object LeaseRenewalPolicy {

    /**
     * Renew this far ahead of expiry. A household that opens the app once a day
     * mid-afternoon would otherwise find the lease lapsing in the evening, in the
     * middle of whatever it was doing.
     */
    const val RENEW_AHEAD_SECONDS = 6L * 60 * 60

    /**
     * Never ask more often than this. Each renewal that finds a purchase costs a
     * Play Integrity token, which has a daily allowance per app, and switching
     * apps fires a start every time.
     */
    const val MIN_RETRY_SECONDS = 15L * 60

    /**
     * @param leaseExpiresAtEpochSeconds expiry of the lease that verifies now, or
     *   null when there is none -- which is exactly when a new phone, cleared data
     *   or a lapsed lease needs Play to be asked.
     */
    fun shouldAttempt(nowEpochSeconds: Long, leaseExpiresAtEpochSeconds: Long?, lastAttemptEpochSeconds: Long?): Boolean {
        if (lastAttemptEpochSeconds != null && nowEpochSeconds - lastAttemptEpochSeconds < MIN_RETRY_SECONDS) return false
        return leaseExpiresAtEpochSeconds == null || leaseExpiresAtEpochSeconds - nowEpochSeconds <= RENEW_AHEAD_SECONDS
    }

    /** The subscription to exchange, or null when Play reports none that is ours and paid. */
    fun purchaseToExchange(purchases: List<OwnedSubscription>): OwnedSubscription? =
        purchases.firstOrNull { it.purchased && it.productId in PRODUCT_IDS }

    val PRODUCT_IDS = setOf(PLUS_PRODUCT_ID, FAMILY_PRODUCT_ID)
}

/** What the renewal needs from a Play purchase, without Play's types. */
data class OwnedSubscription(val productId: String, val purchaseToken: String, val purchased: Boolean)

/**
 * Renews the premium lease in the background from whatever Play already knows.
 *
 * It asks Play for the subscriptions owned by the Google account on this phone,
 * which is what makes a subscription follow its owner: a new phone, a reinstall
 * or cleared data all come back to the same purchase without any sign-in of our
 * own. Free households cost one local Play query per start and nothing else;
 * the backend and Play Integrity are only reached when there is a purchase.
 *
 * Every failure is silent. The lease already stored stays valid until it
 * expires, and the Premium screen still shows the errors when asked directly.
 */
class PremiumLeaseRenewer(
    context: Context,
    private val subject: () -> String,
    private val leaseExpiresAtEpochSeconds: () -> Long?,
    private val onSignedLease: (String) -> EntitlementTier?,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val appContext = context.applicationContext
    private val integrityClient = PremiumIntegrityClient(appContext)
    private val exchangeClient = PremiumExchangeClient()
    private val mutex = Mutex()
    private var lastAttemptEpochSeconds: Long? = null

    suspend fun renewIfNeeded() {
        // A second start while one renewal is in flight waits for it and then
        // finds the retry window closed, instead of exchanging the same token twice.
        mutex.withLock {
            val now = nowEpochSeconds()
            if (!LeaseRenewalPolicy.shouldAttempt(now, leaseExpiresAtEpochSeconds(), lastAttemptEpochSeconds)) return
            lastAttemptEpochSeconds = now
            try {
                renew()
            } catch (cancelled: CancellationException) {
                // Leaving the app mid-renewal is not an attempt; the next start
                // should try again rather than wait out the retry window.
                lastAttemptEpochSeconds = null
                throw cancelled
            } catch (_: Exception) {
                // Silent by design: the stored lease stays valid until it expires.
            }
        }
    }

    private suspend fun renew() {
        val owned = ownedSubscriptions() ?: return
        val purchase = LeaseRenewalPolicy.purchaseToExchange(owned) ?: return
        val installSubject = subject()
        val integrityToken = integrityClient.token(requestHash(installSubject, purchase.productId, purchase.purchaseToken))
        val lease = exchangeClient.exchange(installSubject, purchase.productId, purchase.purchaseToken, integrityToken)
        onSignedLease(lease)
    }

    /** Null when Play could not be reached; an empty list when it answered with nothing. */
    private suspend fun ownedSubscriptions(): List<OwnedSubscription>? {
        val client = BillingClient.newBuilder(appContext)
            // Purchases are never started from here, so updates have nowhere to go.
            .setListener { _, _ -> }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        try {
            if (!client.connect()) return null
            return suspendCancellableCoroutine { continuation ->
                client.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                ) { result, purchases ->
                    val owned = if (result.responseCode != BillingClient.BillingResponseCode.OK) null else {
                        purchases.flatMap { purchase ->
                            purchase.products.map { productId ->
                                OwnedSubscription(
                                    productId = productId,
                                    purchaseToken = purchase.purchaseToken,
                                    purchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
                                )
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(owned)
                }
            }
        } finally {
            client.endConnection()
        }
    }

    private suspend fun BillingClient.connect(): Boolean = suspendCancellableCoroutine { continuation ->
        startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                if (continuation.isActive) continuation.resume(false)
            }
        })
    }
}
