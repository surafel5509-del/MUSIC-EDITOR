package com.studioone.mobile.core.data.repo

import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.database.dao.ProjectDao
import com.studioone.mobile.core.domain.EntitlementRepository
import com.studioone.mobile.core.domain.LimitKind
import com.studioone.mobile.core.model.Entitlement
import com.studioone.mobile.core.model.Product
import com.studioone.mobile.core.model.ProductKind
import com.studioone.mobile.core.model.PurchaseState
import com.studioone.mobile.core.model.SubscriptionTier
import com.studioone.mobile.core.model.TierLimits
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.network.api.StudioOneApi
import com.studioone.mobile.core.network.api.VerifyPurchaseRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Play Billing + server-verified entitlements.
 *
 * Security model (docs/MONETIZATION.md): the client NEVER trusts itself for
 * entitlements. Every purchase token is verified server-side
 * (verify-purchase edge function calls the Play Developer API), and the
 * authoritative tier comes from get-entitlements. Local state is a cache for
 * instant UI + offline grace.
 */
@Singleton
class EntitlementRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: StudioOneApi,
    private val projectDao: ProjectDao,
    private val auth: CurrentUserProvider,
) : EntitlementRepository {

    private val _entitlement = MutableStateFlow(
        Entitlement(UserId("local"), SubscriptionTier.FREE, TierLimits.FREE),
    )
    override val entitlement: Flow<Entitlement> = _entitlement.asStateFlow()

    private var billingClient: BillingClient? = null
    private var productDetails = mapOf<String, ProductDetails>()

    override suspend fun refresh(): DataResult<Entitlement> {
        if (auth.currentUserId() == null) {
            val free = Entitlement(UserId("local-guest"), SubscriptionTier.FREE, TierLimits.FREE)
            _entitlement.value = free
            return DataResult.Success(free)
        }
        return try {
            val resp = api.getEntitlements()
            val body = resp.body() ?: return DataResult.Failure(DataError(DataError.Kind.NETWORK))
            val tier = runCatching { SubscriptionTier.valueOf(body.tier) }.getOrDefault(SubscriptionTier.FREE)
            val limits = when (tier) {
                SubscriptionTier.FREE -> TierLimits.FREE
                SubscriptionTier.PRO -> TierLimits.PRO
                SubscriptionTier.PRO_PLUS -> TierLimits.PRO_PLUS
            }
            val ent = Entitlement(
                userId = UserId(auth.currentUserId()!!),
                tier = tier,
                limits = limits,
                expiresAt = body.expiresAt?.let { kotlinx.datetime.Instant.parse(it) },
                ownedPackIds = body.ownedPacks,
                storageUsedBytes = body.storageUsedBytes,
            )
            _entitlement.value = ent
            DataResult.Success(ent)
        } catch (t: Throwable) {
            // Offline: keep serving the cached entitlement (grace period).
            DataResult.Success(_entitlement.value)
        }
    }

    override suspend fun products(): DataResult<List<Product>> {
        connectBilling()
        val client = billingClient ?: return DataResult.Failure(DataError(DataError.Kind.NETWORK, "Billing unavailable"))
        val ids = listOf(PRO_MONTHLY, PRO_ANNUAL, PACK_STARTER, PACK_PRO_DRUMS)
        return suspendCancellableCoroutine { cont ->
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    ids.map {
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(it)
                            .setProductType(
                                if (it.startsWith("sub_")) BillingClient.ProductType.SUBS
                                else BillingClient.ProductType.INAPP,
                            ).build()
                    },
                ).build()
            client.queryProductDetailsAsync(params) { result, list ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(DataResult.Failure(DataError(DataError.Kind.NETWORK, "Billing query failed")))
                    return@queryProductDetailsAsync
                }
                productDetails = list.associateBy { it.productId }
                cont.resume(
                    DataResult.Success(list.map {
                        val offer = it.oneTimePurchaseOfferDetails
                        val sub = it.subscriptionOfferDetails?.firstOrNull()
                        Product(
                            productId = it.productId,
                            kind = when {
                                it.productId == PRO_MONTHLY -> ProductKind.SUB_MONTHLY
                                it.productId == PRO_ANNUAL -> ProductKind.SUB_ANNUAL
                                else -> ProductKind.SOUND_PACK
                            },
                            title = it.title,
                            description = it.description,
                            priceMicros = (offer?.priceAmountMicros ?: 0),
                            currencyCode = offer?.priceCurrencyCode ?: sub?.pricingPhases?.pricingPhaseList?.firstOrNull()?.priceCurrencyCode ?: "USD",
                            formattedPrice = offer?.formattedPrice ?: sub?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice,
                        )
                    }),
                )
            }
        }
    }

    override suspend fun purchase(productId: String): DataResult<PurchaseState> {
        connectBilling()
        val client = billingClient ?: return DataResult.Failure(DataError(DataError.Kind.NETWORK))
        if (productDetails[productId] == null) products() // refresh catalog
        val details = productDetails[productId]
            ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND, "Unknown product"))

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .apply {
                            if (productId.startsWith("sub_")) {
                                details.subscriptionOfferDetails?.firstOrNull()
                                    ?.let { setOfferToken(it.offerToken) }
                            }
                        }
                        .build(),
                ),
            ).build()
        val activity = currentActivityProvider?.invoke()
            ?: return DataResult.Failure(DataError(DataError.Kind.UNKNOWN, "No activity for billing"))
        val result = client.launchBillingFlow(activity, flowParams)
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            DataResult.Success(PurchaseState.PENDING) // resolved by the purchases listener -> verify
        } else {
            DataResult.Failure(DataError(DataError.Kind.CANCELLED, "Billing flow failed"))
        }
    }

    /** Set by the app module (LifecycleActivity tracker) so billing can launch. */
    var currentActivityProvider: (() -> android.app.Activity?)? = null

    override suspend fun restorePurchases(): DataResult<Entitlement> = refresh()

    override suspend fun checkLimit(kind: LimitKind): DataResult<Unit> {
        val ent = _entitlement.value
        return when (kind) {
            LimitKind.PROJECT_COUNT -> {
                val count = projectDao.countForUser(auth.currentUserId())
                if (count >= ent.limits.maxProjects) denied(kind) else DataResult.Success(Unit)
            }
            else -> if (!ent.limits.premiumInstruments && kind == LimitKind.PREMIUM_INSTRUMENT) denied(kind)
            else DataResult.Success(Unit)
        }
    }

    private fun denied(kind: LimitKind) =
        DataResult.Failure(DataError(DataError.Kind.ENTITLEMENT, kind.message))

    /** Called by the billing listener after onPurchasesUpdated. */
    suspend fun verifyPurchase(purchase: Purchase): PurchaseState {
        return try {
            val resp = api.verifyPurchase(
                VerifyPurchaseRequest(
                    productId = purchase.products.firstOrNull() ?: "",
                    purchaseToken = purchase.purchaseToken,
                    packageName = context.packageName,
                ),
            )
            if (resp.body()?.granted == true) {
                acknowledge(purchase)
                refresh()
                PurchaseState.PURCHASED
            } else PurchaseState.FAILED
        } catch (t: Throwable) {
            Timber.e(t, "purchase verification failed")
            PurchaseState.FAILED
        }
    }

    private suspend fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        billingClient?.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        ) { }
    }

    private suspend fun connectBilling() {
        if (billingClient?.isReady == true) return
        val client = BillingClient.newBuilder(context)
            .enablePendingPurchases(
                com.android.billingclient.api.PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts().build(),
            )
            .setListener { _, purchases ->
                scope.launch {
                    purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                        .forEach { verifyPurchase(it) }
                }
            }
            .build()
        suspendCancellableCoroutine<Unit> { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
        billingClient = client
        // Query existing purchases to sync state after reinstall.
        runCatching {
            val subs = client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
            ).purchasesList
            val inapp = client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            ).purchasesList
            (subs + inapp).filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .forEach { verifyPurchase(it) }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    companion object {
        const val PRO_MONTHLY = "sub_pro_monthly"
        const val PRO_ANNUAL = "sub_pro_annual"
        const val PACK_STARTER = "pack_starter_loops"
        const val PACK_PRO_DRUMS = "pack_pro_drums"
    }
}
