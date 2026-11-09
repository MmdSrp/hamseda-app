package ir.vibracenter.hamseda

import android.net.Uri

object Config {
    const val HOST = "hamseda.vibracenter.ir"
    const val ORIGIN = "https://hamseda.vibracenter.ir"
    const val START_URL = "https://hamseda.vibracenter.ir/"
    private const val ROOT_DOMAIN = "vibracenter.ir"

    /** true = screenshots / screen recording of the app and the recents preview are blocked. */
    const val BLOCK_SCREENSHOTS = false

    /** true = site notifications are shown even while the app is open on screen. */
    const val NOTIFY_WHEN_FOREGROUND = true

    /**
     * true = while the app is open (even in the background) a small ongoing notification keeps it alive,
     * so room invitations and site notifications still arrive. Swiping the app away or tapping
     * "خروج" in that notification turns it off.
     */
    const val KEEP_ALIVE = true

    /** How long an invitation keeps ringing (ms). */
    const val INVITE_RING_MS = 30_000L

    /** Connection quality check: a reply slower than this (ms) counts as "very weak". */
    const val WEAK_LATENCY_MS = 3500L
    const val PROBE_TIMEOUT_MS = 7000
    const val PROBE_INTERVAL_MS = 6000L

    /** How often the app checks whether the user is inside a room (ms). */
    const val ROOM_POLL_MS = 2500L

    /**
     * Cafe Bazaar in-app billing. Paste the RSA public key from the Bazaar developer panel
     * (Billing section) between the quotes. Empty = the key is not checked inside the app
     * (then verify every purchase on your server with Bazaar's API).
     */
    const val BAZAAR_RSA_KEY =
        "MIHNMA0GCSqGSIb3DQEBAQUAA4G7ADCBtwKBrwCvmPJYoZH1aJ6/3iU6rUxwTKri2akjTAZPexQ+RLvTU6eej3+fEYdQemNXc1bRu26uJbNSP+yM2Pu6jSzwHlpFzC/6jgy6cIFVRMvIUYVFH5SP3JDiWYIBgLWC8W3n7wqImc9fG7BytnC2wj1UnocPDkBhBXPFXeVymU1gsOm2suE/N3yjwypvPXkEjcNfzCSt0R2vrEg4RVYbPuhIIU3NGd6zmqaeCMThqru9AyMCAwEAAQ=="

    /**
     * Products defined in the Bazaar panel: id -> "subscription" or "inapp".
     * Only these ids can be bought from the app; the site just passes the id.
     */
    val BAZAAR_PRODUCTS = mapOf(
        "35324" to "subscription",   // VIP سه ماهه (60 روزه)
        "3534345" to "subscription", // VIP ماهانه (30 روزه)
        "245220" to "subscription"   // VIP شش ماهه (180 روزه)
    )

    /** Payment gateways never open from inside the app: all payments go through Bazaar. */
    private val PAYMENT_HOSTS = listOf(
        "zibal.ir", "zarinpal.com", "idpay.ir", "nextpay.org", "pay.ir", "payping.ir", "vandar.io",
        "shaparak.ir", "behpardakht.com", "sep.ir", "parsian-bank.ir", "sadadpay.net", "sizpay.ir",
        "paystar.ir", "payment.ir", "irankish.com", "bpm.shaparak.ir", "saman.com"
    )

    fun isPaymentHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return PAYMENT_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Pages that may stay inside the app. Everything else opens in the phone's browser. */
    fun isTrustedHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return h == ROOT_DOMAIN || h.endsWith(".$ROOT_DOMAIN")
    }

    /**
     * A link coming from a notification. Only pages of the Hamseda site are accepted
     * ("/room/12" style paths are resolved against the site).
     */
    fun resolveAppUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 2048) return null
        val u = raw.trim()
        val full = if (u.startsWith("/") && !u.startsWith("//")) ORIGIN + u else u
        val uri = try {
            Uri.parse(full)
        } catch (e: Exception) {
            return null
        }
        return if (uri.scheme == "https" && uri.host == HOST) full else null
    }

    /** Notification pictures may only come from the site's own domain. */
    fun resolveIconUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 2048) return null
        val u = raw.trim()
        val full = if (u.startsWith("/") && !u.startsWith("//")) ORIGIN + u else u
        val uri = try {
            Uri.parse(full)
        } catch (e: Exception) {
            return null
        }
        return if (uri.scheme == "https" && isTrustedHost(uri.host)) full else null
    }
}
