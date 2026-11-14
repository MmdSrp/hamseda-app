package ir.vibracenter.hamseda

import android.Manifest
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.webkit.JavascriptInterface
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
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

    companion object {
        const val EXTRA_URL = "hamseda_url"
        const val EXTRA_QUIT = "hamseda_quit"
        const val EXTRA_STOP_RING = "hamseda_stop_ring"

        private val SKU_RE = Regex("^[A-Za-z0-9_.\\-]{1,64}\$")
        private val HEX_REGEX = Regex("^#[0-9a-fA-F]{6}\$")
        private val INVITE_REGEX = Regex("invite|invitation|\\bcall\\b|\\bring\\b|دعوت|زنگ", RegexOption.IGNORE_CASE)
    }

    private enum class ErrorKind { OFFLINE, SERVER, SSL }

    private var initialized = false

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var statusBg: View
    private lateinit var navBg: View
    private lateinit var webView: WebView

    private lateinit var loadingView: View
    private lateinit var loadingLogo: View
    private lateinit var loadingRing1: View
    private lateinit var loadingRing2: View
    private lateinit var loadingName: TextView
    private lateinit var loadingTitle: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingHint: TextView

    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView

    private lateinit var weakView: View
    private lateinit var weakCard: View
    private lateinit var weakTitle: TextView
    private lateinit var weakText: TextView

    private lateinit var insetsController: WindowInsetsControllerCompat
    private lateinit var currentTheme: ThemeStore.Theme
    private var themeAnimator: ValueAnimator? = null
    private var sahel: Typeface? = null

    private lateinit var connectivityManager: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val probeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val loadingAnimators = mutableListOf<Animator>()

    private var pageLoaded = false
    private var mainFrameError = false
    private var inForeground = false

    private val billing by lazy { BazaarBilling(this) { deliverBilling(it) } }
    private var roomActive = false
    private var serviceRunning = false
    private var consecutiveBad = 0
    private var probeInFlight = false
    private var networkCallbackRegistered = false
    private var settingsDialogShown = false
    private var pendingUrl: String? = null

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
        loadingView.animate().alpha(0f).setDuration(280).withEndAction {
            loadingView.visibility = View.GONE
            loadingView.alpha = 1f
            stopLoadingAnimations()
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

        // "خروج" button of the ongoing notification, tapped while the screen was already closed
        if (intent?.getBooleanExtra(EXTRA_QUIT, false) == true) {
            stopRoomService()
            finishAndRemoveTask()
            return
        }

        if (Config.BLOCK_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Edge-to-edge: our own colour strips are drawn behind the (transparent) system bars,
        // and the site itself sits below the status bar / cutout and above the navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false)
            window.setStatusBarContrastEnforced(false)
        }
        insetsController = WindowInsetsControllerCompat(window, window.decorView)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        statusBg = findViewById(R.id.statusBg)
        navBg = findViewById(R.id.navBg)
        webView = findViewById(R.id.webView)
        loadingView = findViewById(R.id.loadingView)
        loadingLogo = findViewById(R.id.loadingLogo)
        loadingRing1 = findViewById(R.id.loadingRing1)
        loadingRing2 = findViewById(R.id.loadingRing2)
        loadingName = findViewById(R.id.loadingName)
        loadingTitle = findViewById(R.id.loadingTitle)
        loadingProgress = findViewById(R.id.loadingProgress)
        loadingHint = findViewById(R.id.loadingHint)
        errorView = findViewById(R.id.errorView)
        errorTitle = findViewById(R.id.errorTitle)
        errorText = findViewById(R.id.errorText)
        weakView = findViewById(R.id.weakView)
        weakCard = findViewById(R.id.weakCard)
        weakTitle = findViewById(R.id.weakTitle)
        weakText = findViewById(R.id.weakText)

        sahel = ResourcesCompat.getFont(this, R.font.sahel_bold)
        applyFont(root)

        currentTheme = ThemeStore.load(this)
        renderTheme(currentTheme)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            statusBg.layoutParams.height = bars.top
            navBg.layoutParams.height = bars.bottom
            statusBg.requestLayout()
            navBg.requestLayout()
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
                    // Keeping the app alive in the background (to receive invitations).
                    serviceRunning -> moveTaskToBack(true)
                    else -> finish()
                }
            }
        })

        setupWebView()
        handleIntent(intent)
        loadSite()
        root.post { requestStartupPermissions() }
        initialized = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!initialized) return
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_QUIT, false)) {
            quitApp()
            return
        }
        if (intent.getBooleanExtra(EXTRA_STOP_RING, false)) {
            Ringer.stop()
            NotificationHelper.cancelInvite(this)
        }
        val url = Config.resolveAppUrl(intent.getStringExtra(EXTRA_URL))
        if (url != null) {
            if (pageLoaded && !mainFrameError) webView.loadUrl(url) else pendingUrl = url
        }
    }

    private fun quitApp() {
        Ringer.stop()
        roomActive = false
        serviceRunning = false
        stopRoomService()
        finishAndRemoveTask()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // touching the screen = you saw the invitation
        if (Ringer.stop()) NotificationHelper.cancelInvite(this)
    }

    override fun onStart() {
        super.onStart()
        if (!initialized) return
        inForeground = true
        if (Ringer.stop()) NotificationHelper.cancelInvite(this)
        registerNetworkCallback()
        webView.onResume()
        webView.resumeTimers()
        handler.removeCallbacks(roomPoll)
        handler.post(roomPoll)
        handler.removeCallbacks(probeLoop)
        handler.postDelayed(probeLoop, 4000)
        if (pageLoaded) syncService()
    }

    override fun onStop() {
        if (!initialized) {
            super.onStop()
            return
        }
        inForeground = false
        handler.removeCallbacks(probeLoop)
        unregisterNetworkCallback()
        if (!serviceRunning) {
            // Nothing keeps the app alive: save battery. Otherwise the page keeps running
            // (room audio, invitations, notifications).
            webView.onPause()
            handler.removeCallbacks(roomPoll)
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        themeAnimator?.cancel()
        Ringer.stop()
        if (initialized) {
            billing.release()
            stopRoomService()
            roomActive = false
            serviceRunning = false
            stopLoadingAnimations()
            probeExecutor.shutdownNow()
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            try {
                content.removeView(webView)
                webView.destroy()
            } catch (e: Exception) {
                // ignore
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- fonts / theme

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun applyFont(v: View?) {
        val tf = sahel ?: return
        when (v) {
            is TextView -> v.typeface = tf
            is ViewGroup -> for (i in 0 until v.childCount) applyFont(v.getChildAt(i))
            else -> Unit
        }
    }

    private fun parseHex(s: String?): Int? {
        if (s == null || !HEX_REGEX.matches(s)) return null
        return Color.parseColor(s)
    }

    private fun applyTheme(target: ThemeStore.Theme, animate: Boolean) {
        val from = currentTheme
        currentTheme = target
        themeAnimator?.cancel()
        if (!animate || from == target) {
            renderTheme(target)
            return
        }
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 250
        anim.addUpdateListener { a ->
            val p = a.animatedFraction
            renderTheme(
                ThemeStore.Theme(
                    ColorUtils.blendARGB(from.bg, target.bg, p),
                    ColorUtils.blendARGB(from.top, target.top, p),
                    ColorUtils.blendARGB(from.bottom, target.bottom, p),
                    target.accent
                )
            )
        }
        themeAnimator = anim
        anim.start()
    }

    private fun renderTheme(t: ThemeStore.Theme) {
        root.setBackgroundColor(t.bg)
        statusBg.setBackgroundColor(t.top)
        navBg.setBackgroundColor(t.bottom)
        webView.setBackgroundColor(t.bg)
        insetsController.isAppearanceLightStatusBars = ColorUtils.calculateLuminance(t.top) > 0.5
        insetsController.isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(t.bottom) > 0.5
        applyContentColors(t.bg)
    }

    /** Loading / error / weak-connection screens follow the site's light or dark theme. */
    private fun applyContentColors(bg: Int) {
        val dark = ColorUtils.calculateLuminance(bg) < 0.5
        val primary = if (dark) Color.WHITE else 0xFF0F172A.toInt()
        val secondary = if (dark) 0xFF9AA6C0.toInt() else 0xFF5B6478.toInt()

        loadingView.setBackgroundColor(bg)
        errorView.setBackgroundColor(bg)
        weakView.setBackgroundColor(ColorUtils.setAlphaComponent(bg, 0xB3))

        loadingName.setTextColor(primary)
        loadingTitle.setTextColor(secondary)
        errorTitle.setTextColor(primary)
        errorText.setTextColor(secondary)
        weakTitle.setTextColor(primary)
        weakText.setTextColor(secondary)

        val card = GradientDrawable()
        card.cornerRadius = dp(26f)
        card.setColor(if (dark) ColorUtils.blendARGB(bg, Color.WHITE, 0.07f) else Color.WHITE)
        card.setStroke(dp(1f).toInt(), if (dark) 0x26FFFFFF else 0x1A000000)
        weakCard.background = card
    }

    private fun handleTheme(o: JSONObject) {
        val bg = parseHex(o.optString("bg")) ?: return
        val top = parseHex(o.optString("top")) ?: bg
        val bottom = parseHex(o.optString("bottom")) ?: bg
        val accent = ThemeStore.safeAccent(
            this, parseHex(o.optString("accent")) ?: ContextCompat.getColor(this, R.color.brand)
        )
        val t = ThemeStore.Theme(bg, top, bottom, accent)
        val accentChanged = t.accent != currentTheme.accent
        ThemeStore.save(this, t)
        applyTheme(t, animate = pageLoaded)
        if (accentChanged && serviceRunning) NotificationHelper.refreshRoomNotification(this, roomActive)
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

        s.userAgentString = s.userAgentString + " HamsedaApp/2.0"

        webView.overScrollMode = View.OVER_SCROLL_NEVER
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
        // Second, independent way for the site to reach Bazaar billing: works on every WebView version.
        webView.addJavascriptInterface(PayJs(), "HamsedaPay")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "about" || scheme == "blob") return false
                if (Config.isPaymentHost(uri.host)) {
                    showPaymentBlocked()
                    return true
                }
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
                syncService()
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
                loadingProgress.setProgress(newProgress, true)
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
        val target = pendingUrl ?: Config.START_URL
        pendingUrl = null
        webView.visibility = View.VISIBLE
        webView.loadUrl(target)
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
        startLoadingAnimations()
    }

    private fun hideLoading() {
        handler.removeCallbacks(slowLoadRunnable)
        handler.removeCallbacks(giveUpRunnable)
        if (loadingView.visibility != View.VISIBLE) return
        handler.removeCallbacks(hideLoadingRunnable)
        handler.postDelayed(hideLoadingRunnable, 300) // let the page paint first, so it appears clean
    }

    private fun startLoadingAnimations() {
        stopLoadingAnimations()

        fun ring(v: View, delay: Long) {
            val a = ObjectAnimator.ofPropertyValuesHolder(
                v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.75f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.75f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f)
            )
            a.duration = 2000
            a.startDelay = delay
            a.repeatCount = ValueAnimator.INFINITE
            a.interpolator = DecelerateInterpolator()
            a.start()
            loadingAnimators.add(a)
        }
        ring(loadingRing1, 0)
        ring(loadingRing2, 1000)

        val breathe = ObjectAnimator.ofPropertyValuesHolder(
            loadingLogo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f)
        )
        breathe.duration = 1100
        breathe.repeatCount = ValueAnimator.INFINITE
        breathe.repeatMode = ValueAnimator.REVERSE
        breathe.interpolator = AccelerateDecelerateInterpolator()
        breathe.start()
        loadingAnimators.add(breathe)

        // "در حال بارگذاری" with three dots that appear one by one (the text width never changes)
        val base = getString(R.string.loading_base)
        val full = "$base..."
        val dots = ValueAnimator.ofInt(0, 4)
        dots.duration = 1600
        dots.repeatCount = ValueAnimator.INFINITE
        dots.interpolator = LinearInterpolator()
        dots.addUpdateListener { anim ->
            val n = (anim.animatedValue as Int).coerceIn(0, 3)
            val sp = SpannableString(full)
            if (n < 3) {
                sp.setSpan(
                    ForegroundColorSpan(Color.TRANSPARENT), base.length + n, full.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            loadingTitle.text = sp
        }
        dots.start()
        loadingAnimators.add(dots)
    }

    private fun stopLoadingAnimations() {
        for (a in loadingAnimators) a.cancel()
        loadingAnimators.clear()
        if (!initialized && !::loadingLogo.isInitialized) return
        loadingLogo.scaleX = 1f
        loadingLogo.scaleY = 1f
        loadingRing1.alpha = 0f
        loadingRing2.alpha = 0f
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
        stopLoadingAnimations()
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

    // ---------------------------------------------------------------- room / background service

    private fun updateRoomState(inRoom: Boolean) {
        if (inRoom == roomActive) return
        roomActive = inRoom
        syncService()
    }

    /**
     * Starts / updates the ongoing "Hamseda is active" notification service.
     * It runs while the app is open (even in the background) so invitations keep arriving.
     */
    private fun syncService() {
        if (isFinishing || isDestroyed) return
        val wanted = roomActive || (Config.KEEP_ALIVE && pageLoaded)
        if (!wanted) {
            if (serviceRunning) {
                stopRoomService()
                serviceRunning = false
                if (!inForeground) {
                    webView.onPause()
                    handler.removeCallbacks(roomPoll)
                }
            }
            return
        }
        if (serviceRunning && !inForeground) {
            // Android does not allow (re)starting it from the background: just update the text.
            NotificationHelper.refreshRoomNotification(this, roomActive)
            return
        }
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, RoomService::class.java).putExtra(RoomService.EXTRA_IN_ROOM, roomActive)
            )
            serviceRunning = true
        } catch (e: Exception) {
            // the system refused right now - it will be retried on the next change
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
            "notify" -> handleNotify(o)
            "close" -> NotificationHelper.cancelSite(this, o.optString("tag").take(64))
            "theme" -> handleTheme(o)
            "buy" -> {
                val sku = o.optString("sku")
                val type = Config.BAZAAR_PRODUCTS[sku]
                deliverBilling(JSONObject().put("ok", true).put("status", "ack").put("sku", sku))
                if (type != null && SKU_RE.matches(sku)) {
                    billing.buy(sku, type, o.optString("payload").take(200), o.optBoolean("consume"))
                } else {
                    // never leave the site waiting: this product id is not allowed / unknown
                    deliverBilling(JSONObject().put("ok", false).put("status", "failed").put("sku", sku).put("error", "unknown_sku"))
                }
            }
            "restore" -> billing.restore()
            "consume" -> o.optString("token").takeIf { it.length in 1..512 }?.let { billing.consume(it) }
        }
    }

    /** Sends a purchase result back to the site (only while the Hamseda page is open). */
    private fun deliverBilling(result: JSONObject) {
        if (isDestroyed || !::webView.isInitialized) return
        if (Uri.parse(webView.url ?: "").host != Config.HOST) return
        webView.evaluateJavascript(
            "(function(d){try{window.dispatchEvent(new CustomEvent('hamseda-purchase',{detail:d}));" +
                "if(typeof window.onHamsedaPurchase==='function'){window.onHamsedaPurchase(d);}}catch(e){}})(" +
                result.toString() + ")", null
        )
        if (result.optString("status") == "unavailable") {
            val installed = result.optJSONObject("bazaar")?.optBoolean("installed", true) ?: true
            if (!installed) {
                showDialog(
                    AlertDialog.Builder(this)
                        .setTitle(R.string.bazaar_missing_title)
                        .setMessage(R.string.bazaar_missing_text)
                        .setPositiveButton(R.string.bazaar_install) { _, _ ->
                            openExternal(Uri.parse("https://cafebazaar.ir/app/com.farsitel.bazaar"))
                        }
                        .setNegativeButton(R.string.close, null)
                )
            } else {
                // Bazaar is installed but its billing service did not answer: do NOT say "install Bazaar".
                showDialog(
                    AlertDialog.Builder(this)
                        .setTitle(R.string.bazaar_connect_title)
                        .setMessage(R.string.bazaar_connect_text)
                        .setPositiveButton(R.string.bazaar_open) { _, _ ->
                            try {
                                packageManager.getLaunchIntentForPackage(BazaarBilling.BAZAAR_PKG)?.let { startActivity(it) }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                        .setNegativeButton(R.string.close, null)
                )
            }
        }
    }

    /** window.HamsedaPay: only the Hamseda page itself (main frame, our host) may use it. */
    private inner class PayJs {
        @JavascriptInterface
        fun status(): String = try {
            billing.status().toString()
        } catch (e: Exception) {
            "{}"
        }

        @JavascriptInterface
        fun buy(json: String?) {
            if (json == null || json.length > 4096) return
            runOnUiThread {
                if (isDestroyed || Uri.parse(webView.url ?: "").host != Config.HOST) return@runOnUiThread
                try {
                    handleBridgeMessage(JSONObject(json).put("t", "buy").toString())
                } catch (e: Exception) {
                    // ignore malformed request
                }
            }
        }

        @JavascriptInterface
        fun restore() {
            runOnUiThread {
                if (isDestroyed || Uri.parse(webView.url ?: "").host != Config.HOST) return@runOnUiThread
                billing.restore()
            }
        }
    }

    private fun showPaymentBlocked() {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.pay_blocked_title)
                .setMessage(R.string.pay_blocked_text)
                .setPositiveButton(R.string.close, null)
        )
    }

    private fun handleNotify(o: JSONObject) {
        if (!Config.NOTIFY_WHEN_FOREGROUND && inForeground) return
        val now = SystemClock.elapsedRealtime()
        while (notifyTimes.isNotEmpty() && now - notifyTimes.first() > 60_000) notifyTimes.removeFirst()
        if (notifyTimes.size >= 20) return // simple flood protection
        notifyTimes.addLast(now)

        val title = o.optString("title").take(100)
        val body = o.optString("body").take(300)
        val tag = o.optString("tag").take(64)
        val type = o.optString("type").take(20).lowercase()
        val n = NotificationHelper.SiteNotification(
            title, body, tag,
            Config.resolveAppUrl(o.optString("url")),
            Config.resolveIconUrl(o.optString("icon"))
        )
        val isInvite = type == "invite" || type == "call" || type == "ring" ||
            INVITE_REGEX.containsMatchIn("$tag $title $body")
        if (isInvite) {
            // inside a room: only a short sound, so the conversation is not interrupted by a long ring
            Ringer.start(applicationContext, !roomActive)
            NotificationHelper.showInvite(this, n)
        } else {
            NotificationHelper.showSite(this, n)
        }
    }

    // ---------------------------------------------------------------- permissions

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showDialog(builder: AlertDialog.Builder) {
        val d = builder.create()
        d.show()
        applyFont(d.window?.decorView)
    }

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
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle(R.string.perm_intro_title)
                    .setMessage(R.string.perm_intro_text)
                    .setCancelable(false)
                    .setPositiveButton(R.string.perm_continue) { _, _ ->
                        prefs.edit().putBoolean("perm_intro_shown", true).apply()
                        launch()
                    }
            )
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
        showDialog(
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
        )
    }
}
