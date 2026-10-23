package ir.vibracenter.hamseda

object Config {
    const val HOST = "hamseda.vibracenter.ir"
    const val ORIGIN = "https://hamseda.vibracenter.ir"
    const val START_URL = "https://hamseda.vibracenter.ir/"
    private const val ROOT_DOMAIN = "vibracenter.ir"

    /** true = screenshots / screen recording of the app and the recents preview are blocked. */
    const val BLOCK_SCREENSHOTS = false

    /** true = site notifications are shown even while the app is open on screen. */
    const val NOTIFY_WHEN_FOREGROUND = true

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
}
