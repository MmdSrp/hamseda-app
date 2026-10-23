package ir.vibracenter.hamseda

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private enum class ErrorKind { OFFLINE, SERVER, SSL }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingHint: TextView
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var weakView: View
    private lateinit var weakTitle: TextView
    private lateinit var weakText: TextView

    private lateinit var connectivityManager: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val probeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var pageLoaded = false
    private var mainFrameError = false
    private var inForeground = false
    private var roomActive = false
    private var consecutiveBad = 0
    private var probeInFlight = false
    private var networkCallbackRegistered = false
    private var settingsDialogShown = false

    // permissions
    private var startupPermInFlight = false
    private var webPermInFlight = false
    private val pendingWebRequests = mutableListOf<PermissionRequest>()

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val notifyTimes = ArrayDeque<Long>()

    private val startupPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startupPermInFlight = false
            resolvePendingWebRequests()
        }

    private val webPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            webPermInFlight = false
            resolvePendingWebRequests()
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    // ---------------------------------------------------------------- runnables

    private val hideLoadingRunnable = Runnable {
        loadingView.animate().alpha(0f).setDuration(250).withEndAction {
            loadingView.visibility = View.GONE
            loadingView.alpha = 1f
        }.start()
    }

    private val slowLoadRunnable = Runnable {
        if (loadingView.visibility == View.VISIBLE) loadingHint.visibility = View.VISIBLE
    }

    private val giveUpRunnable = Runnable {
        if (!pageLoaded && loadingView.visibility == View.VISIBLE) showError(ErrorKind.OFFLINE)
    }

    private val probeLoop = object : Runnable {
        override fun run() {
            if (!inForeground) return
            if (pageLoaded && !mainFrameError) runProbe()
            handler.postDelayed(this, Config.PROBE_INTERVAL_MS)
        }
    }

    private val roomPoll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            webView.evaluateJavascript(Scripts.IN_ROOM_CHECK) { value ->
                if (!isFinishing && !isDestroyed) updateRoomState(value == "true")
            }
            handler.postDelayed(this, Config.ROOM_POLL_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { onNetworkBack() }
        }

        override fun onLost(network: Network) {
            handler.postDelayed({ onNetworkLost() }, 1500)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Config.BLOCK_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Edge-to-edge, but the site is pushed below the status bar / cutout and above the nav bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val bg = ContextCompat.getColor(this, R.color.bg)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webView)
        loadingView = findViewById(R.id.loadingView)
        loadingProgress = findViewById(R.id.loadingProgress)
        loadingHint = findViewById(R.id.loadingHint)
        errorView = findViewById(R.id.errorView)
        errorTitle = findViewById(R.id.errorTitle)
        errorText = findViewById(R.id.errorText)
        weakView = findViewById(R.id.weakView)
        weakTitle = findViewById(R.id.weakTitle)
        weakText = findViewById(R.id.weakText)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        NotificationHelper.ensureChannels(this)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { onRefreshClicked() }
        findViewById<Button>(R.id.weakRetryButton).setOnClickListener {
            consecutiveBad = 0
            runProbe()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    errorView.visibility == View.VISIBLE -> finish()
                    // Inside a room: Back only minimises, it never kicks you out of the room.
                    roomActive -> moveTaskToBack(true)
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        setupWebView()
        loadSite()
        root.post { requestStartupPermissions() }
    }

    override fun onStart() {
        super.onStart()
        inForeground = true
        registerNetworkCallback()
        webView.onResume()
        webView.resumeTimers()
        handler.removeCallbacks(roomPoll)
        handler.post(roomPoll)
        handler.removeCallbacks(probeLoop)
        handler.postDelayed(probeLoop, 4000)
    }

    override fun onStop() {
        inForeground = false
        handler.removeCallbacks(probeLoop)
        unregisterNetworkCallback()
        if (!roomActive) {
            // Not in a room: save battery. In a room we keep everything running (no pause).
            webView.onPause()
            handler.removeCallbacks(roomPoll)
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRoomService()
        roomActive = false
        probeExecutor.shutdownNow()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        try {
            root.removeView(webView)
            webView.destroy()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(false)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false // voice chat audio must start without a tap
        s.textZoom = 100                           // phone font size must not break the site layout
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false

        // security
        s.allowFileAccess = false
        @Suppress("DEPRECATION")
        run {
            s.setAllowFileAccessFromFileURLs(false)
            s.setAllowUniversalAccessFromFileURLs(false)
            s.setSavePassword(false)
            s.setSaveFormData(false)
        }
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setGeolocationEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true)

        s.userAgentString = s.userAgentString + " HamsedaApp/1.0"

        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Keep the web page's process important even when the screen is left (room stays alive).
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        // Bridge: only the Hamseda origin (main frame) may talk to the app.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView, "HamsedaNative", setOf(Config.ORIGIN)
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                val data = message.data
                if (isMainFrame && sourceOrigin.host == Config.HOST && data != null) {
                    handleBridgeMessage(data)
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, Scripts.BRIDGE, setOf(Config.ORIGIN))
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "about" || scheme == "blob") return false
                if (scheme == "https" && Config.isTrustedHost(uri.host)) return false
                if (scheme == "https" || scheme == "mailto" || scheme == "tel") {
                    openExternal(uri)
                }
                // http://, intent:, file:, javascript: and anything else is blocked
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    // very old WebView: inject late, only for the trusted host
                    if (Uri.parse(url ?: "").host == Config.HOST) {
                        view.evaluateJavascript(Scripts.BRIDGE, null)
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (mainFrameError) return
                pageLoaded = true
                CookieManager.getInstance().flush()
                webView.visibility = View.VISIBLE
                hideLoading()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if (error.description?.toString()?.contains("ERR_ABORTED") == true) return
                showError(
                    if (error.errorCode == ERROR_FAILED_SSL_HANDSHAKE) ErrorKind.SSL else ErrorKind.OFFLINE
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    showError(ErrorKind.SERVER)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Never continue on certificate problems.
                handler.cancel()
                if (Uri.parse(error.url ?: "").host == Config.HOST) showError(ErrorKind.SSL)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The web page process died: restart cleanly instead of crashing the app.
                this@MainActivity.handler.post { recreate() }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingProgress.progress = newProgress
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                handleWebPermission(request)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                pendingWebRequests.remove(request)
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    true
                }
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            val uri = Uri.parse(url)
            if (uri.scheme == "https") openExternal(uri)
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            // nothing can open it
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- loading / error / weak connection

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadSite() {
        mainFrameError = false
        pageLoaded = false
        showLoading()
        if (!isOnline()) {
            showError(ErrorKind.OFFLINE)
            return
        }
        webView.visibility = View.VISIBLE
        webView.loadUrl(Config.START_URL)
        handler.postDelayed(slowLoadRunnable, 12_000)
        handler.postDelayed(giveUpRunnable, 60_000)
    }

    private fun showLoading() {
        handler.removeCallbacks(hideLoadingRunnable)
        loadingView.animate().cancel()
        loadingView.alpha = 1f
        loadingView.visibility = View.VISIBLE
        loadingHint.visibility = View.GONE
        loadingProgress.progress = 0
        errorView.visibility = View.GONE
    }

    private fun hideLoading() {
        handler.removeCallbacks(slowLoadRunnable)
        handler.removeCallbacks(giveUpRunnable)
        if (loadingView.visibility != View.VISIBLE) return
        handler.removeCallbacks(hideLoadingRunnable)
        handler.postDelayed(hideLoadingRunnable, 300) // let the page paint first, so it appears clean
    }

    private fun showError(kind: ErrorKind) {
        mainFrameError = true
        handler.removeCallbacks(slowLoadRunnable)
        handler.removeCallbacks(giveUpRunnable)
        handler.removeCallbacks(hideLoadingRunnable)
        hideWeak()
        // Hide the browser's own error page completely - only our styled screen is shown.
        webView.visibility = View.INVISIBLE
        loadingView.animate().cancel()
        loadingView.visibility = View.GONE
        loadingView.alpha = 1f
        when (kind) {
            ErrorKind.OFFLINE -> {
                errorTitle.setText(R.string.error_offline_title)
                errorText.setText(R.string.error_offline_text)
            }
            ErrorKind.SERVER -> {
                errorTitle.setText(R.string.error_server_title)
                errorText.setText(R.string.error_server_text)
            }
            ErrorKind.SSL -> {
                errorTitle.setText(R.string.error_ssl_title)
                errorText.setText(R.string.error_ssl_text)
            }
        }
        errorView.visibility = View.VISIBLE
    }

    private fun onRefreshClicked() {
        if (!isOnline()) {
            Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
            return
        }
        loadSite()
    }

    private fun showWeak(offline: Boolean) {
        if (errorView.visibility == View.VISIBLE || loadingView.visibility == View.VISIBLE) return
        if (offline) {
            weakTitle.setText(R.string.weak_offline_title)
            weakText.setText(R.string.weak_offline_text)
        } else {
            weakTitle.setText(R.string.weak_title)
            weakText.setText(R.string.weak_text)
        }
        if (weakView.visibility != View.VISIBLE) {
            weakView.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                webView.setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP))
            }
        }
    }

    private fun hideWeak() {
        if (weakView.visibility == View.VISIBLE) {
            weakView.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) webView.setRenderEffect(null)
        }
    }

    // connection quality ------------------------------------------------------

    private fun runProbe() {
        if (probeInFlight || isFinishing || isDestroyed) return
        probeInFlight = true
        try {
            probeExecutor.execute {
                val ms = probe()
                handler.post {
                    probeInFlight = false
                    onProbeResult(ms)
                }
            }
        } catch (e: Exception) {
            probeInFlight = false
        }
    }

    /** Returns the reply time in ms, or -1 when the server could not be reached. */
    private fun probe(): Long {
        val start = SystemClock.elapsedRealtime()
        var c: HttpsURLConnection? = null
        return try {
            c = URL(Config.START_URL).openConnection() as HttpsURLConnection
            c.requestMethod = "HEAD"
            c.connectTimeout = Config.PROBE_TIMEOUT_MS
            c.readTimeout = Config.PROBE_TIMEOUT_MS
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code <= 0) -1L else SystemClock.elapsedRealtime() - start
        } catch (e: Exception) {
            -1L
        } finally {
            try {
                c?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun onProbeResult(ms: Long) {
        if (isFinishing || isDestroyed || !pageLoaded || mainFrameError) return
        val bad = ms < 0 || ms > Config.WEAK_LATENCY_MS
        if (bad) {
            consecutiveBad++
            if (consecutiveBad >= 2) {
                showWeak(offline = ms < 0 || !isOnline())
            } else {
                handler.postDelayed({ runProbe() }, 1500) // quick second look before blaming the network
            }
        } else {
            consecutiveBad = 0
            hideWeak()
        }
    }

    private fun onNetworkBack() {
        if (isFinishing || isDestroyed) return
        if (errorView.visibility == View.VISIBLE) {
            loadSite()
        } else if (weakView.visibility == View.VISIBLE) {
            consecutiveBad = 0
            runProbe()
        }
    }

    private fun onNetworkLost() {
        if (isFinishing || isDestroyed || !pageLoaded || mainFrameError) return
        if (!isOnline()) {
            consecutiveBad = 2
            showWeak(offline = true)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // ignore
        }
        networkCallbackRegistered = false
    }

    // ---------------------------------------------------------------- room (background)

    private fun updateRoomState(inRoom: Boolean) {
        if (inRoom == roomActive) return
        roomActive = inRoom
        if (inRoom) {
            startRoomService()
        } else {
            stopRoomService()
            if (!inForeground) {
                webView.onPause()
                handler.removeCallbacks(roomPoll)
            }
        }
    }

    private fun startRoomService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, RoomService::class.java))
        } catch (e: Exception) {
            // the system refused to start it right now - the app will try again on the next check
            roomActive = false
        }
    }

    private fun stopRoomService() {
        try {
            stopService(Intent(this, RoomService::class.java))
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- messages from the site

    private fun handleBridgeMessage(raw: String) {
        if (raw.length > 4096) return
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        when (o.optString("t")) {
            "notify" -> {
                if (!Config.NOTIFY_WHEN_FOREGROUND && inForeground) return
                val now = SystemClock.elapsedRealtime()
                while (notifyTimes.isNotEmpty() && now - notifyTimes.first() > 60_000) notifyTimes.removeFirst()
                if (notifyTimes.size >= 20) return // simple flood protection
                notifyTimes.addLast(now)
                NotificationHelper.showSite(
                    this,
                    o.optString("title").take(100),
                    o.optString("body").take(300),
                    o.optString("tag").take(64)
                )
            }
            "close" -> NotificationHelper.cancelSite(this, o.optString("tag").take(64))
        }
    }

    // ---------------------------------------------------------------- permissions

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestStartupPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = wanted.filter { !has(it) }
        if (missing.isEmpty()) return

        val prefs = getSharedPreferences("hamseda", MODE_PRIVATE)
        val launch = {
            startupPermInFlight = true
            startupPermLauncher.launch(missing.toTypedArray())
        }
        if (!prefs.getBoolean("perm_intro_shown", false)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_intro_title)
                .setMessage(R.string.perm_intro_text)
                .setCancelable(false)
                .setPositiveButton(R.string.perm_continue) { _, _ ->
                    prefs.edit().putBoolean("perm_intro_shown", true).apply()
                    launch()
                }
                .show()
        } else {
            launch()
        }
    }

    private fun handleWebPermission(request: PermissionRequest) {
        val origin = request.origin
        if (origin.scheme != "https" || origin.host != Config.HOST) {
            request.deny()
            return
        }
        val needed = mutableSetOf<String>()
        for (res in request.resources) {
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> needed.add(Manifest.permission.RECORD_AUDIO)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> needed.add(Manifest.permission.CAMERA)
            }
        }
        val missing = needed.filter { !has(it) }
        if (missing.isEmpty()) {
            grantWebRequest(request)
            return
        }
        val firstInQueue = pendingWebRequests.isEmpty() && !startupPermInFlight && !webPermInFlight
        pendingWebRequests.add(request)
        if (firstInQueue) {
            webPermInFlight = true
            webPermLauncher.launch(missing.toTypedArray())
        }
    }

    private fun resolvePendingWebRequests() {
        val list = pendingWebRequests.toList()
        pendingWebRequests.clear()
        list.forEach { grantWebRequest(it) }
    }

    private fun grantWebRequest(request: PermissionRequest) {
        val granted = mutableListOf<String>()
        var micDenied = false
        for (res in request.resources) {
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    if (has(Manifest.permission.RECORD_AUDIO)) granted.add(res) else micDenied = true
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    if (has(Manifest.permission.CAMERA)) granted.add(res)
            }
        }
        try {
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        } catch (e: Exception) {
            // request was already cancelled by the page
        }
        if (micDenied &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        ) {
            offerOpenSettings()
        }
    }

    private fun offerOpenSettings() {
        if (settingsDialogShown || isFinishing || isDestroyed) return
        settingsDialogShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_denied_title)
            .setMessage(R.string.perm_denied_text)
            .setPositiveButton(R.string.perm_open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton(R.string.perm_later, null)
            .setOnDismissListener { settingsDialogShown = false }
            .show()
    }
}
