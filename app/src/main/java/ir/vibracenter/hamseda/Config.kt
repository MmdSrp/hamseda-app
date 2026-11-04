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
