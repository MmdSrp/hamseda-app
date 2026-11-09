package ir.vibracenter.hamseda

import androidx.activity.ComponentActivity
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cafe Bazaar in-app billing (Poolakey). The site asks for a purchase with HamsedaApp.buy(...),
 * the Bazaar payment screen opens, and the result goes back to the site as a "hamseda-purchase" event.
 */
class BazaarBilling(
    private val activity: ComponentActivity,
    private val onResult: (JSONObject) -> Unit
) {
    private var payment: Payment? = null
    private var connection: Connection? = null
    private var busy = false

    private fun ensurePayment(): Payment {
        payment?.let { return it }
        val check = if (Config.BAZAAR_RSA_KEY.isBlank()) SecurityCheck.Disable
        else SecurityCheck.Enable(Config.BAZAAR_RSA_KEY)
        return Payment(activity, PaymentConfiguration(check)).also { payment = it }
    }

    /** Connects to the Bazaar app (once), then runs [block]. */
    private fun withConnection(fail: (String) -> Unit, block: (Payment) -> Unit) {
        val p = ensurePayment()
        if (connection != null) {
            block(p)
            return
        }
        connection = p.connect {
            connectionSucceed { block(p) }
            connectionFailed {
                connection = null
                fail("bazaar_unavailable")
            }
            disconnected { connection = null }
        }
    }

    private fun result(status: String, sku: String, type: String, extra: (JSONObject) -> Unit = {}) {
        val o = JSONObject()
            .put("ok", status == "success" || status == "restored" || status == "consumed")
            .put("status", status)
            .put("sku", sku)
            .put("type", type)
        extra(o)
        onResult(o)
    }

    fun buy(sku: String, type: String, payload: String, consume: Boolean) {
        if (busy) return
        busy = true
        val finish = { busy = false }
        withConnection(fail = { finish(); result("unavailable", sku, type) { it.put("error", "bazaar_unavailable") } }) { p ->
            val request = PurchaseRequest(productId = sku, payload = payload)
            val callback: ir.cafebazaar.poolakey.callback.PurchaseCallback.() -> Unit = {
                purchaseSucceed { purchase ->
                    finish()
                    val token = purchase.purchaseToken
                    result("success", sku, type) {
                        it.put("orderId", purchase.orderId)
                        it.put("purchaseToken", token)
                        it.put("payload", purchase.payload)
                        it.put("purchaseTime", purchase.purchaseTime)
                        it.put("signature", purchase.dataSignature)
                    }
                    if (consume && type != "subscription") consume(token)
                }
                purchaseCanceled { finish(); result("canceled", sku, type) }
                purchaseFailed { t -> finish(); result("failed", sku, type) { it.put("error", t.message ?: "") } }
                failedToBeginFlow { t -> finish(); result("failed", sku, type) { it.put("error", t.message ?: "") } }
            }
            if (type == "subscription") {
                p.subscribeProduct(activity.activityResultRegistry, request, callback)
            } else {
                p.purchaseProduct(activity.activityResultRegistry, request, callback)
            }
        }
    }

    /** Lists what the user already owns (use it after a reinstall, or to re-check a subscription). */
    fun restore() {
        withConnection(fail = { msg -> result("unavailable", "", "") { o -> o.put("error", msg) } }) { p ->
            val list = JSONArray()
            var pending = 2
            val done = {
                if (--pending == 0) result("restored", "", "") { it.put("purchases", list) }
            }
            p.getPurchasedProducts {
                querySucceed { items ->
                    items.forEach {
                        list.put(JSONObject().put("sku", it.productId).put("type", "inapp").put("purchaseToken", it.purchaseToken).put("orderId", it.orderId).put("purchaseTime", it.purchaseTime))
                    }
                    done()
                }
                queryFailed { done() }
            }
            p.getSubscribedProducts {
                querySucceed { items ->
                    items.forEach {
                        list.put(JSONObject().put("sku", it.productId).put("type", "subscription").put("purchaseToken", it.purchaseToken).put("orderId", it.orderId).put("purchaseTime", it.purchaseTime))
                    }
                    done()
                }
                queryFailed { done() }
            }
        }
    }

    /** One-time products must be consumed before they can be bought again. */
    fun consume(token: String) {
        withConnection(fail = { }) { p ->
            p.consumeProduct(token) {
                consumeSucceed { result("consumed", "", "inapp") { it.put("purchaseToken", token) } }
                consumeFailed { t -> result("failed", "", "inapp") { it.put("error", t.message ?: "") } }
            }
        }
    }

    fun release() {
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        connection = null
        payment = null
    }
}
