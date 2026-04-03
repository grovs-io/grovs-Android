package io.grovs.model.events

import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.grovs.model.EventType
import io.grovs.utils.InstantCompat
import kotlinx.parcelize.Parcelize

enum class PaymentEventType {
    @SerializedName("buy")
    BUY,
    @SerializedName("cancel")
    CANCEL,
    @SerializedName("refund")
    REFUND,
}

@Parcelize
class PaymentEvent(
    @SerializedName("event_type")
    val eventType: PaymentEventType? = null,
    @SerializedName("identifier")
    val appId: String? = null,
    @SerializedName("price_cents")
    val priceCents: Long? = null,
    val currency: String? = null,
    val date: InstantCompat? = null,
    @SerializedName("transaction_id")
    val transactionToken: String? = null,
    @SerializedName("original_transaction_id")
    val originalTransactionId: Int? = null,
    @SerializedName("product_id")
    val productId: String? = null,
    val store: Boolean = false,

    var link: String? = null,
) : Parcelable {

    companion object {
        // Internal data class to parse the purchase JSON
        private data class PurchaseJson(
            val orderId: String? = null,
            val packageName: String? = null,
            val productId: String? = null,
            val productIds: List<String>? = null,
            val purchaseTime: Long = 0L,
            val purchaseState: Int = 0,
            val purchaseToken: String? = null,
            val quantity: Int = 1,
            val autoRenewing: Boolean? = null,
            val acknowledged: Boolean = false,
            val obfuscatedAccountId: String? = null,
            val obfuscatedProfileId: String? = null,
            val developerPayload: String? = null
        )

        /**
         * Creates PaymentEvent(s) from Android Billing Purchase's originalJson
         * Returns a list to handle both single product and multi-product purchases gracefully
         *
         * @param originalJson The JSON string from purchase.originalJson
         * @param gson Gson instance for parsing
         * @param eventType The type of payment event
         * @param priceCents Optional price in cents for single product (not included in originalJson)
         * @param currency Optional currency code for single product (not included in originalJson)
         * @param productPrices Optional map of productId to (priceCents, currency) for multi-product
         */
        fun fromOriginalJson(
            originalJson: String,
            gson: Gson = Gson(),
            eventType: PaymentEventType = PaymentEventType.BUY,
            priceCents: Long? = null,
            currency: String? = null,
            productPrices: Map<String, Pair<Long?, String?>>? = null
        ): List<PaymentEvent> {
            return try {
                val purchaseJson = gson.fromJson(originalJson, PurchaseJson::class.java)

                // Get all product IDs (handles both single and multiple products)
                val productIds = when {
                    !purchaseJson.productIds.isNullOrEmpty() -> purchaseJson.productIds
                    !purchaseJson.productId.isNullOrEmpty() -> listOf(purchaseJson.productId)
                    else -> emptyList()
                }

                // If no products found, return empty list
                if (productIds.isEmpty()) {
                    return emptyList()
                }

                // Convert purchase time to InstantCompat
                val date = if (purchaseJson.purchaseTime > 0) {
                    InstantCompat.ofEpochMilli(purchaseJson.purchaseTime)
                } else null

                // Convert order ID to transaction ID
                val transactionId = purchaseJson.orderId?.hashCode()

                // Create a PaymentEvent for each product
                productIds.map { productId ->
                    // Try to get price from productPrices map, otherwise use single price params
                    val (productPrice, productCurrency) = productPrices?.get(productId)
                        ?: (priceCents to currency)

                    PaymentEvent(
                        eventType = eventType,
                        appId = purchaseJson.packageName,
                        priceCents = productPrice,
                        currency = productCurrency,
                        date = date,
                        transactionToken = purchaseJson.purchaseToken,
                        productId = productId,
                        store = true,
                        link = null
                    )
                }
            } catch (e: Exception) {
                // Return empty list on error
                emptyList()
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentEvent) return false

        return eventType == other.eventType && transactionToken == other.transactionToken && date == other.date
    }

    override fun toString(): String {
        return "PaymentEvent(event=$eventType, appId=$appId, priceCents=$priceCents, " +
                "currency=$currency, date=$date, transactionId=$transactionToken, " +
                "originalTransactionId=$transactionToken, productId=$productId, store=$store)"
    }

}