package ir.vibracenter.hamseda

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cafe Bazaar in-app billing (Poolakey). The site asks for a purchase (HamsedaPay.buy / HamsedaApp.buy),
 * the Bazaar payment screen opens, and every answer goes back to the site as a "hamseda-purchase" event:
 *   status = ack | success | canceled | failed | unavailable | restored | consumed
 * "unavailable" carries a "bazaar" object (installed / version / bindable) so the site can say exactly why.
 */
class BazaarBilling(
    private val activity: ComponentActivity,
    private val onResult: (JSONObject) -> Unit
) {
    companion object {
        const val BAZAAR_PKG = "com.farsitel.bazaar"
        private const val BIND_ACTION = "ir.cafebazaar.pardakht.InAppBillingService.BIND"
        private const val BUSY_MS = 120_000L
    }

    private var payment: Payment? = null
    private var connection: Connection? = null
    private var connected = false
    private var connecting = false
    private val waiting = ArrayList<Pair<(String) -> Unit, (Payment) -> Unit>>()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var busySince = 0L

    // ------------------------------------------------------------ is Bazaar there?

    /** Never throws. installed = Bazaar app exists; bindable = its billing service is visible (informational). */
    @Suppress("DEPRECATION")
    fun status(): JSONObject {
        val pm = activity.packageManager
        var installed = false
        var version = 0L
        try {
            val pi = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(BAZAAR_PKG, PackageManager.PackageInfoFlags.of(0))
            else pm.getPackageInfo(BAZAAR_PKG, 0)
            installed = true
            version = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        } catch (e: Exception) {
            // not installed (or not visible)
        }
        var bindable = false
        try {
            val i = Intent(BIND_ACTION).setPackage(BAZAAR_PKG)
            bindable = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentServices(i, PackageManager.ResolveInfoFlags.of(0)).isNotEmpty()
            else pm.queryIntentServices(i, 0).isNotEmpty()
        } catch (e: Exception) {
            // ignore
        }
        return JSONObject().put("installed", installed).put("version", version).put("bindable", bindable)
    }

    // ------------------------------------------------------------ connection (with one silent retry)

    private fun ensurePayment(): Payment {
        payment?.let { return it }
        val check = if (Config.BAZAAR_RSA_KEY.isBlank()) SecurityCheck.Disable
        else SecurityCheck.Enable(Config.BAZAAR_RSA_KEY)
        return Payment(activity, PaymentConfiguration(check)).also { payment = it }
    }

    /** Connects to the Bazaar app (once), then runs [block]. Several callers may wait for the same connection. */
    private fun withConnection(fail: (String) -> Unit, block: (Payment) -> Unit) {
        if (connected) {
            block(ensurePayment())
            return
        }
        waiting.add(fail to block)
        if (connecting) return
        connecting = true
        attempt(0)
    }

    private fun attempt(n: Int) {
        val p = ensurePayment()
        connection = p.connect {
            connectionSucceed {
                connecting = false
                connected = true
                val list = ArrayList(waiting)
                waiting.clear()
                list.forEach { it.second(p) }
            }
            connectionFailed {
                connection = null
                payment = null
                if (n < 2) {
                    // The first bind sometimes fails on MIUI / slow phones: try again with a fresh object.
                    main.postDelayed({ attempt(n + 1) }, 700L)
                } else {
                    connecting = false
                    connected = false
                    val list = ArrayList(waiting)
                    waiting.clear()
                    val why = if (status().optBoolean("installed")) "bazaar_connect_failed" else "bazaar_not_installed"
                    list.forEach { it.first(why) }
                }
            }
            disconnected {
                connected = false
                connection = null
            }
        }
    }

    private fun unavailable(sku: String, type: String, why: String) {
        result("unavailable", sku, type) {
            it.put("error", why)
            it.put("bazaar", status())
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

    private fun describe(t: Throwable): String = (t.javaClass.simpleName + ": " + (t.message ?: "")).take(200)

    // ------------------------------------------------------------ buy / restore / consume

    fun buy(sku: String, type: String, payload: String, consume: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // A purchase that never answered must not block the button forever: after 2 minutes a new try is allowed.
        if (busy && now - busySince < BUSY_MS) return
        busy = true
        busySince = now
        val finish = { busy = false }
        withConnection(fail = { why -> finish(); unavailable(sku, type, why) }) { p ->
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
                        it.put("originalJson", purchase.originalJson)
                    }
                    if (consume && type != "subscription") consume(token)
                }
                purchaseCanceled { finish(); result("canceled", sku, type) }
                purchaseFailed { t -> finish(); result("failed", sku, type) { it.put("error", describe(t)) } }
                failedToBeginFlow { t -> finish(); result("failed", sku, type) { it.put("error", describe(t)) } }
            }
            try {
                if (type == "subscription") {
                    p.subscribeProduct(activity.activityResultRegistry, request, callback)
                } else {
                    p.purchaseProduct(activity.activityResultRegistry, request, callback)
                }
            } catch (e: Exception) {
                finish()
                result("failed", sku, type) { it.put("error", describe(e)) }
            }
        }
    }

    /** Lists what the user already owns (use it after a reinstall, or to re-check a subscription). */
    fun restore() {
        withConnection(fail = { why -> unavailable("", "", why) }) { p ->
            val list = JSONArray()
            var pending = 2
            val done = {
                if (--pending == 0) result("restored", "", "") { it.put("purchases", list) }
            }
            p.getPurchasedProducts {
                querySucceed { items ->
                    items.forEach {
                        list.put(JSONObject().put("sku", it.productId).put("type", "inapp").put("purchaseToken", it.purchaseToken).put("orderId", it.orderId).put("purchaseTime", it.purchaseTime).put("payload", it.payload).put("signature", it.dataSignature).put("originalJson", it.originalJson))
                    }
                    done()
                }
                queryFailed { done() }
            }
            p.getSubscribedProducts {
                querySucceed { items ->
                    items.forEach {
                        list.put(JSONObject().put("sku", it.productId).put("type", "subscription").put("purchaseToken", it.purchaseToken).put("orderId", it.orderId).put("purchaseTime", it.purchaseTime).put("payload", it.payload).put("signature", it.dataSignature).put("originalJson", it.originalJson))
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
                consumeFailed { t -> result("failed", "", "inapp") { it.put("error", describe(t)) } }
            }
        }
    }

    fun release() {
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        main.removeCallbacksAndMessages(null)
        connection = null
        payment = null
        connected = false
        connecting = false
        waiting.clear()
    }
}
