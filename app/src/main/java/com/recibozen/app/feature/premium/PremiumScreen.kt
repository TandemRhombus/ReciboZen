package com.recibozen.app.feature.premium

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.recibozen.app.BuildConfig
import com.recibozen.app.R
import com.recibozen.app.core.components.RzBannerTone
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzInlineBanner
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.model.EntitlementTier
import com.recibozen.app.demo.repository.ReciboZenRepository
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val PLUS_PRODUCT_ID = "recibozen_plus_monthly"
private const val FAMILY_PRODUCT_ID = "recibozen_family_monthly"

data class PremiumOffer(
    val productId: String,
    val title: String,
    val price: String,
    val productDetails: ProductDetails,
    val offerToken: String,
)

enum class PremiumPurchaseStatus { LOADING, READY, PROCESSING, PENDING, ACTIVE, UNAVAILABLE, ERROR }

data class PremiumPurchaseUiState(
    val status: PremiumPurchaseStatus = PremiumPurchaseStatus.LOADING,
    val offers: List<PremiumOffer> = emptyList(),
    val message: String? = null,
)

@Composable
fun PremiumRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val context = LocalContext.current
    val activity = context as? Activity
    val session by repository.session.collectAsStateWithLifecycle()
    val coordinator = remember(context, repository) {
        PremiumPurchaseCoordinator(
            context = context.applicationContext,
            subject = repository.premiumSubject(),
            onSignedLease = repository::acceptPremiumLease,
        )
    }
    val purchaseState by coordinator.state.collectAsStateWithLifecycle()
    DisposableEffect(coordinator) {
        coordinator.start()
        onDispose(coordinator::close)
    }
    PremiumScreen(
        tier = session.entitlementTier,
        purchaseState = purchaseState,
        onBack = navController::popBackStack,
        onBuy = { offer -> activity?.let { coordinator.launchPurchase(it, offer) } },
        onRestore = coordinator::restorePurchases,
    )
}

@Composable
fun PremiumScreen(
    tier: EntitlementTier,
    purchaseState: PremiumPurchaseUiState,
    onBack: () -> Unit,
    onBuy: (PremiumOffer) -> Unit,
    onRestore: () -> Unit,
) {
    Scaffold(topBar = { RzFlowTopBar(stringResource(R.string.premium_title), onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(stringResource(R.string.premium_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tier != EntitlementTier.FREE) {
                RzInlineBanner(
                    title = stringResource(R.string.premium_active_title),
                    text = stringResource(R.string.premium_active_body, tier.name),
                    tone = RzBannerTone.SUCCESS,
                    icon = RzIcons.CheckCircle,
                )
            }
            RzCard(modifier = Modifier.fillMaxWidth()) {
                PremiumBenefit(stringResource(R.string.premium_benefit_notifications))
                PremiumBenefit(stringResource(R.string.premium_benefit_services))
                PremiumBenefit(stringResource(R.string.premium_benefit_future))
            }
            purchaseState.message?.let { message ->
                RzInlineBanner(
                    text = message,
                    tone = if (purchaseState.status == PremiumPurchaseStatus.ERROR) RzBannerTone.WARNING else RzBannerTone.INFO,
                    icon = if (purchaseState.status == PremiumPurchaseStatus.ERROR) RzIcons.ErrorInfo else RzIcons.Info,
                )
            }
            purchaseState.offers.forEach { offer ->
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(offer.title, style = MaterialTheme.typography.titleLarge)
                    Text(offer.price, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = RzSpacing.xs))
                    RzPrimaryButton(
                        text = stringResource(R.string.premium_buy),
                        onClick = { onBuy(offer) },
                        enabled = purchaseState.status == PremiumPurchaseStatus.READY && tier == EntitlementTier.FREE,
                        modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                    )
                }
            }
            if (purchaseState.offers.isEmpty() && purchaseState.status != PremiumPurchaseStatus.LOADING) {
                RzInlineBanner(stringResource(R.string.premium_play_required), RzBannerTone.NEUTRAL, icon = RzIcons.Info)
            }
            RzPrimaryButton(
                text = stringResource(
                    if (purchaseState.status == PremiumPurchaseStatus.UNAVAILABLE) R.string.premium_retry
                    else R.string.premium_restore,
                ),
                onClick = onRestore,
                enabled = purchaseState.status != PremiumPurchaseStatus.PROCESSING,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.premium_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun PremiumBenefit(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm), modifier = Modifier.padding(vertical = RzSpacing.xs)) {
        Icon(RzIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

class PremiumPurchaseCoordinator(
    context: Context,
    private val subject: String,
    private val onSignedLease: (String) -> EntitlementTier?,
) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val integrityClient = PremiumIntegrityClient(appContext)
    private val exchangeClient = PremiumExchangeClient()
    private val mutableState = MutableStateFlow(PremiumPurchaseUiState())
    val state: StateFlow<PremiumPurchaseUiState> = mutableState.asStateFlow()
    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    fun start() {
        if (billingClient.isReady) {
            loadProducts()
            return
        }
        mutableState.value = PremiumPurchaseUiState(status = PremiumPurchaseStatus.LOADING)
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    loadProducts()
                    restorePurchases()
                } else {
                    unavailable()
                }
            }

            override fun onBillingServiceDisconnected() {
                mutableState.value = mutableState.value.copy(
                    status = PremiumPurchaseStatus.UNAVAILABLE,
                    message = appContext.getString(R.string.premium_play_disconnected),
                )
            }
        })
    }

    private fun loadProducts() {
        val products = listOf(PLUS_PRODUCT_ID, FAMILY_PRODUCT_ID).map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
        ) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                unavailable()
                return@queryProductDetailsAsync
            }
            val offers = queryResult.productDetailsList.mapNotNull { details ->
                val subscriptionOffer = details.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
                val phase = subscriptionOffer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                PremiumOffer(
                    productId = details.productId,
                    title = details.name.ifBlank { details.title },
                    price = phase.formattedPrice,
                    productDetails = details,
                    offerToken = subscriptionOffer.offerToken,
                )
            }
            mutableState.value = PremiumPurchaseUiState(
                status = if (offers.isEmpty()) PremiumPurchaseStatus.UNAVAILABLE else PremiumPurchaseStatus.READY,
                offers = offers,
                message = if (offers.isEmpty()) appContext.getString(R.string.premium_products_unavailable) else null,
            )
        }
    }

    fun launchPurchase(activity: Activity, offer: PremiumOffer) {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(offer.productDetails)
            .setOfferToken(offer.offerToken)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(subjectHash(subject))
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) unavailable()
    }

    fun restorePurchases() {
        if (!billingClient.isReady) {
            start()
            return
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                unavailable()
            } else if (purchases.isEmpty()) {
                mutableState.value = mutableState.value.copy(
                    status = if (mutableState.value.offers.isEmpty()) PremiumPurchaseStatus.UNAVAILABLE else PremiumPurchaseStatus.READY,
                    message = appContext.getString(R.string.premium_nothing_to_restore),
                )
            } else {
                processPurchases(purchases)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> processPurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> mutableState.value = mutableState.value.copy(
                status = PremiumPurchaseStatus.READY,
                message = appContext.getString(R.string.premium_purchase_cancelled),
            )
            else -> unavailable()
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val purchase = purchases.firstOrNull { candidate ->
            candidate.products.any { it == PLUS_PRODUCT_ID || it == FAMILY_PRODUCT_ID }
        } ?: return
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            mutableState.value = mutableState.value.copy(
                status = PremiumPurchaseStatus.PENDING,
                message = appContext.getString(R.string.premium_purchase_pending),
            )
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val productId = purchase.products.first { it == PLUS_PRODUCT_ID || it == FAMILY_PRODUCT_ID }
        mutableState.value = mutableState.value.copy(
            status = PremiumPurchaseStatus.PROCESSING,
            message = appContext.getString(R.string.premium_verifying),
        )
        scope.launch {
            runCatching {
                val requestHash = requestHash(subject, productId, purchase.purchaseToken)
                val integrityToken = integrityClient.token(requestHash)
                exchangeClient.exchange(subject, productId, purchase.purchaseToken, integrityToken)
            }.onSuccess { signedLease ->
                val tier = onSignedLease(signedLease)
                mutableState.value = if (tier != null) {
                    mutableState.value.copy(
                        status = PremiumPurchaseStatus.ACTIVE,
                        message = appContext.getString(R.string.premium_verified),
                    )
                } else {
                    mutableState.value.copy(
                        status = PremiumPurchaseStatus.ERROR,
                        message = appContext.getString(R.string.premium_invalid_lease),
                    )
                }
            }.onFailure {
                mutableState.value = mutableState.value.copy(
                    status = PremiumPurchaseStatus.ERROR,
                    message = appContext.getString(R.string.premium_verification_failed),
                )
            }
        }
    }

    private fun unavailable() {
        mutableState.value = mutableState.value.copy(
            status = PremiumPurchaseStatus.UNAVAILABLE,
            message = appContext.getString(R.string.premium_play_unavailable),
        )
    }

    fun close() {
        billingClient.endConnection()
        scope.cancel()
    }
}

private class PremiumIntegrityClient(context: Context) {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    suspend fun token(requestHash: String): String {
        val cloudProjectNumber = BuildConfig.PLAY_CLOUD_PROJECT_NUMBER
        require(cloudProjectNumber > 0L) { "Play Integrity todavía no está configurado para esta compilación." }
        val tokenProvider = provider ?: prepare(cloudProjectNumber).also { provider = it }
        val request = StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
            .setRequestHash(requestHash)
            .build()
        return suspendCancellableCoroutine { continuation ->
            tokenProvider.request(request)
                .addOnSuccessListener { result -> continuation.resume(result.token()) }
                .addOnFailureListener(continuation::resumeWithException)
        }
    }

    private suspend fun prepare(cloudProjectNumber: Long): StandardIntegrityManager.StandardIntegrityTokenProvider =
        suspendCancellableCoroutine { continuation ->
            val request = StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build()
            manager.prepareIntegrityToken(request)
                .addOnSuccessListener(continuation::resume)
                .addOnFailureListener(continuation::resumeWithException)
        }
}

private class PremiumExchangeClient {
    suspend fun exchange(subject: String, productId: String, purchaseToken: String, integrityToken: String): String =
        withContext(Dispatchers.IO) {
            val connection = (URL("${BuildConfig.API_BASE_URL.trimEnd('/')}/v1/entitlements/exchange").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val request = JSONObject()
                    .put("subject", subject)
                    .put("productId", productId)
                    .put("purchaseToken", purchaseToken)
                    .put("integrityToken", integrityToken)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(request.size)
                connection.outputStream.use { it.write(request) }
                val status = connection.responseCode
                val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val response = runCatching { JSONObject(responseText) }.getOrNull()
                if (status !in 200..299) {
                    throw IllegalStateException(response?.optString("message")?.takeIf(String::isNotBlank) ?: "No fue posible verificar la compra ($status).")
                }
                response?.optString("entitlementLease")?.takeIf(String::isNotBlank)
                    ?: throw IllegalStateException("El backend no devolvió un permiso Premium válido.")
            } finally {
                connection.disconnect()
            }
        }
}

internal fun requestHash(subject: String, productId: String, purchaseToken: String): String {
    val canonical = "1|$subject|$productId|$purchaseToken"
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)),
    )
}

internal fun subjectHash(subject: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8)),
)
