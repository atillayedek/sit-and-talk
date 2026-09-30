package com.sitandtalk.core.data

import android.app.Activity
import android.content.Context
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
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.Entitlements
import com.sitandtalk.core.model.GiftItem
import com.sitandtalk.core.model.GiftResult
import com.sitandtalk.core.model.WalletTx
import com.sitandtalk.core.network.Api
import com.sitandtalk.core.network.SafeLog
import com.sitandtalk.core.network.apiCall
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A Play product with the price text Google Play returned — never a hard-coded price. */
data class StoreProduct(
    val productId: String,
    val kind: String,
    val title: String,
    val price: String,
    val coins: Int?,
    internal val details: ProductDetails,
    internal val offerToken: String?,
)

sealed interface PurchaseEvent {
    data object Pending : PurchaseEvent
    data object Cancelled : PurchaseEvent
    data class Verified(val entitlements: Entitlements) : PurchaseEvent
    data class Failed(val error: AppException) : PurchaseEvent
}

@Singleton
class WalletRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: Api,
    private val auth: AuthRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _entitlements = MutableStateFlow<Entitlements?>(null)
    val entitlements: StateFlow<Entitlements?> = _entitlements.asStateFlow()

    private val _events = MutableStateFlow<PurchaseEvent?>(null)
    val events: StateFlow<PurchaseEvent?> = _events.asStateFlow()

    private val listener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { handlePurchase(it) }
            BillingClient.BillingResponseCode.USER_CANCELED -> _events.value = PurchaseEvent.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restorePurchases()
            else -> _events.value = PurchaseEvent.Failed(AppException("billing_unavailable"))
        }
    }

    private val billing: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    }

    suspend fun refresh(): Entitlements = api.rpc<Entitlements>("my_entitlements").also { _entitlements.value = it }

    suspend fun history(): List<WalletTx> = api.rpc("wallet_history") { put("p_limit", 100) }

    suspend fun gifts(): List<GiftItem> = apiCall {
        api.client.from("gift_catalog").select(Columns.list("id", "code", "name_tr", "name_en", "icon_key", "price_coins", "sort")) {
            order("sort", Order.ASCENDING)
        }.decodeList<GiftItem>()
    }

    /** Server-side atomic, idempotent debit. [contextId] identifies the call/room/profile/post. */
    suspend fun sendGift(code: String, contextType: String, contextId: String, idempotencyKey: String = UUID.randomUUID().toString().replace("-", "")): GiftResult {
        val result = api.rpc<GiftResult>("send_gift") {
            put("p_gift_code", code)
            put("p_context_type", contextType)
            put("p_context_id", contextId)
            put("p_idempotency_key", idempotencyKey)
        }
        _entitlements.value = _entitlements.value?.copy(balance = result.balance)
        return result
    }

    private suspend fun connect(): Boolean {
        if (billing.isReady) return true
        return suspendCancellableCoroutine { cont ->
            billing.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    /** Products configured on the server that Google Play actually knows; empty when Play is not set up. */
    suspend fun storeProducts(): List<StoreProduct> {
        val ent = _entitlements.value ?: refresh()
        val coinIds = ent.coinProducts.keys.toList()
        val subIds = ent.premiumProducts
        if (coinIds.isEmpty() && subIds.isEmpty()) return emptyList()
        if (!connect()) throw AppException("billing_unavailable")
        val result = mutableListOf<StoreProduct>()
        if (coinIds.isNotEmpty()) result += query(coinIds, BillingClient.ProductType.INAPP).map { d ->
            val offer = d.oneTimePurchaseOfferDetails
            StoreProduct(d.productId, "consumable", d.name, offer?.formattedPrice.orEmpty(), ent.coinProducts[d.productId], d, null)
        }
        if (subIds.isNotEmpty() && ent.premiumEnabled) result += query(subIds, BillingClient.ProductType.SUBS).mapNotNull { d ->
            val offer = d.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
            val phase = offer.pricingPhases.pricingPhaseList.lastOrNull()
            StoreProduct(d.productId, "subscription", d.name, phase?.formattedPrice.orEmpty(), null, d, offer.offerToken)
        }
        return result.filter { it.price.isNotBlank() }
    }

    private suspend fun query(ids: List<String>, type: String): List<ProductDetails> = suspendCancellableCoroutine { cont ->
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() })
            .build()
        billing.queryProductDetailsAsync(params) { billingResult, details ->
            if (cont.isActive) {
                cont.resume(if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) details.productDetailsList else emptyList())
            }
        }
    }

    fun launchPurchase(activity: Activity, product: StoreProduct) {
        val uid = auth.currentUserId() ?: return
        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product.details)
        product.offerToken?.let { paramsBuilder.setOfferToken(it) }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            // Binds the purchase to this account; verified again on the server.
            .setObfuscatedAccountId(sha256(uid))
            .build()
        val result = billing.launchBillingFlow(activity, flow)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _events.value = PurchaseEvent.Failed(AppException("billing_unavailable"))
        }
    }

    /** Re-submits purchases Google Play still reports (restore / recover after a crash). */
    fun restorePurchases() {
        scope.launch {
            if (!connect()) {
                _events.value = PurchaseEvent.Failed(AppException("billing_unavailable"))
                return@launch
            }
            for (type in listOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS)) {
                val purchases = suspendCancellableCoroutine<List<Purchase>> { cont ->
                    billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { r, list ->
                        if (cont.isActive) cont.resume(if (r.responseCode == BillingClient.BillingResponseCode.OK) list else emptyList())
                    }
                }
                purchases.forEach { handlePurchase(it) }
            }
            runCatching { refresh() }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        scope.launch {
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                // Nothing is granted until Google reports the payment as completed.
                _events.value = PurchaseEvent.Pending
                return@launch
            }
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return@launch
            val ent = _entitlements.value ?: runCatching { refresh() }.getOrNull()
            purchase.products.forEach { productId ->
                val kind = if (ent?.premiumProducts?.contains(productId) == true) "subscription" else "consumable"
                try {
                    api.function<JsonObject>("verify-purchase") {
                        put("kind", kind)
                        put("product_id", productId)
                        put("purchase_token", purchase.purchaseToken)
                    }
                    _events.value = PurchaseEvent.Verified(refresh())
                } catch (e: Exception) {
                    SafeLog.error("billing", "verify_failed", e)
                    _events.value = PurchaseEvent.Failed(e.toAppException())
                }
            }
        }
    }

    fun consumeEvent() {
        _events.value = null
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
