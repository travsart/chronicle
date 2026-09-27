package local.oss.chronicle.application

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
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
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.oss.chronicle.data.local.PrefsRepo
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton wrapper around the Google Play Billing library. Handles the initialization of
 * Billing, restores previous purchases, and exposes a method to launch the billing flow.
 *
 * TODO: use a more sophisticated method to prevent cheats
 */
@Singleton
class ChronicleBillingManager
    @Inject
    constructor(
        applicationContext: Context,
        private val prefsRepo: PrefsRepo,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val purchasesUpdatedListener =
            PurchasesUpdatedListener { billingResult, purchases ->
                when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        purchases.orEmpty()
                            .filter { it.products.contains(PREMIUM_IAP_SKU) }
                            .forEach(::handlePremiumPurchase)
                    }
                    BillingClient.BillingResponseCode.USER_CANCELED -> {
                        Timber.d("Premium purchase cancelled by user")
                    }
                    else -> {
                        Timber.w("Purchase update failed: %s", billingResult.debugMessage)
                    }
                }
            }

        private val billingClient =
            BillingClient
                .newBuilder(applicationContext)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams
                        .newBuilder()
                        .enableOneTimeProducts()
                        .build(),
                ).build()

        init {
            startConnection()
        }

        fun launchBillingFlow(activity: Activity) {
            if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTED) {
                showPremiumPurchaseFlow(activity)
            } else {
                startConnection { showPremiumPurchaseFlow(activity) }
            }
        }

        private fun startConnection(onConnected: () -> Unit = {}) {
            if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTED) {
                onConnected()
                return
            }
            billingClient.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            restorePurchases()
                            onConnected()
                        } else {
                            Timber.w("Billing setup failed: %s", billingResult.debugMessage)
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        // Simple reconnect happens on next use of the billing client.
                        Timber.d("Billing service disconnected")
                    }
                },
            )
        }

        private fun restorePurchases() {
            scope.launch {
                try {
                    val purchasesResult =
                        billingClient.queryPurchasesAsync(
                            QueryPurchasesParams
                                .newBuilder()
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build(),
                        )
                    purchasesResult.purchasesList
                        .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                        .filter { it.products.contains(PREMIUM_IAP_SKU) }
                        .forEach { prefsRepo.premiumPurchaseToken = it.purchaseToken }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to restore purchases")
                }
            }
        }

        private fun showPremiumPurchaseFlow(activity: Activity) {
            scope.launch {
                val productDetails = queryPremiumProductDetails() ?: return@launch
                val billingFlowParams =
                    BillingFlowParams
                        .newBuilder()
                        .setProductDetailsParamsList(
                            listOf(
                                BillingFlowParams.ProductDetailsParams
                                    .newBuilder()
                                    .setProductDetails(productDetails)
                                    .build(),
                            ),
                        ).build()
                withContext(Dispatchers.Main) {
                    val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        Timber.w("Failed to launch billing flow: %s", billingResult.debugMessage)
                    }
                }
            }
        }

        private suspend fun queryPremiumProductDetails(): ProductDetails? =
            try {
                val productDetailsResult =
                    billingClient.queryProductDetails(
                        QueryProductDetailsParams
                            .newBuilder()
                            .setProductList(
                                listOf(
                                    QueryProductDetailsParams.Product
                                        .newBuilder()
                                        .setProductId(PREMIUM_IAP_SKU)
                                        .setProductType(BillingClient.ProductType.INAPP)
                                        .build(),
                                ),
                            ).build(),
                    )
                if (productDetailsResult.billingResult.responseCode ==
                    BillingClient.BillingResponseCode.OK
                ) {
                    productDetailsResult.productDetailsList?.firstOrNull()
                } else {
                    Timber.w(
                        "Failed to query product details: %s",
                        productDetailsResult.billingResult.debugMessage,
                    )
                    null
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to query product details")
                null
            }

        private fun handlePremiumPurchase(purchase: Purchase) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    prefsRepo.premiumPurchaseToken = purchase.purchaseToken
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }
                }
                Purchase.PurchaseState.PENDING -> {
                    Timber.d("Premium purchase is pending")
                }
                else -> {
                    Timber.d("Unexpected premium purchase state: %d", purchase.purchaseState)
                }
            }
        }

        private fun acknowledgePurchase(purchase: Purchase) {
            scope.launch {
                try {
                    val billingResult =
                        billingClient.acknowledgePurchase(
                            AcknowledgePurchaseParams
                                .newBuilder()
                                .setPurchaseToken(purchase.purchaseToken)
                                .build(),
                        )
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        Timber.w(
                            "Failed to acknowledge purchase: %s",
                            billingResult.debugMessage,
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to acknowledge purchase")
                }
            }
        }
    }
