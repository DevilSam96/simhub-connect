package com.local.simhubconnect

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.local.simhubconnect.databinding.ActivityDashboardBinding
import com.local.simhubconnect.net.ServerStore

/**
 * Native, fullscreen shell for SimHub's web dashboards.
 *
 * SimHub renders Dash Studio web dashboards as HTML/JS served by its own web
 * server, so the app hosts them in a hardware accelerated WebView while the
 * native layer handles kiosk mode, the menu and automatic reconnection.
 */
class DashboardActivity : ThemedActivity() {

    /** Kiosk shell: stays black in both light and dark themes. */
    override val themedStyle: Int
        get() = R.style.Theme_SimHubConnect_Fullscreen

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var store: ServerStore
    private lateinit var host: String
    private var port: Int = ServerStore.DEFAULT_PORT
    private lateinit var baseUrl: String

    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryAttempt = 0
    private var connectionFailed = false
    private var mainFrameFailed = false
    private val retryRunnable = Runnable {
        if (!connectionFailed) return@Runnable
        retryAttempt++
        binding.errorPanel.isVisible = false
        binding.webView.reload()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ServerStore(this)

        host = intent.getStringExtra(EXTRA_HOST) ?: run {
            finish()
            return
        }
        port = intent.getIntExtra(EXTRA_PORT, store.lastPort)
        baseUrl = "http://$host:$port/"
        val startUrl = store.lastDashboardUrl ?: baseUrl

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        setupWebView()
        setupMenu()

        binding.retryButton.setOnClickListener { reloadNow() }
        binding.changePcButton.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        binding.webView.loadUrl(startUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
        }
        binding.webView.setBackgroundColor(Color.BLACK)
        binding.webView.isVerticalScrollBarEnabled = false
        binding.webView.isHorizontalScrollBarEnabled = false
        binding.webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.isVisible = true
                mainFrameFailed = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // A failed load still reports onPageFinished with an error page:
                // keep the native error panel and the retry loop alive in that case.
                if (mainFrameFailed) return
                binding.progress.isVisible = false
                connectionFailed = false
                retryAttempt = 0
                retryHandler.removeCallbacks(retryRunnable)
                binding.errorPanel.isVisible = false
                if (url != null && url != baseUrl && !url.startsWith("$baseUrl?")) {
                    store.saveDashboardUrl(url)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                mainFrameFailed = true
                val description = error?.description?.toString()
                    ?: getString(R.string.dashboard_error_generic)
                showConnectionError(description)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                response: WebResourceResponse?
            ) {
                if (request?.isForMainFrame != true) return
                val code = response?.statusCode ?: return
                if (code >= 500) {
                    mainFrameFailed = true
                    showConnectionError("HTTP $code")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val isWeb = uri.scheme == "http" || uri.scheme == "https"
                if (isWeb && uri.host == host) return false
                // External links open in the browser; anything without a handler
                // is consumed so it cannot turn into a fake connection error.
                return openExternally(uri) || !isWeb
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) binding.progress.isVisible = false
            }
        }
    }

    private fun setupMenu() {
        binding.menuButton.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, MENU_RELOAD, 0, R.string.dashboard_reload)
            popup.menu.add(0, MENU_LIST, 1, R.string.dashboard_list)
            popup.menu.add(0, MENU_BROWSER, 2, R.string.dashboard_browser)
            popup.menu.add(0, MENU_CHANGE_PC, 3, R.string.dashboard_change_pc)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RELOAD -> reloadNow()
                    MENU_LIST -> binding.webView.loadUrl(baseUrl)
                    MENU_BROWSER -> openExternally(Uri.parse(binding.webView.url ?: baseUrl))
                    MENU_CHANGE_PC -> finish()
                }
                true
            }
            popup.show()
        }
    }

    private fun showConnectionError(description: String) {
        connectionFailed = true
        binding.progress.isVisible = false
        binding.errorDetail.text = description
        binding.errorPanel.isVisible = true
        retryHandler.removeCallbacks(retryRunnable)
        val delay = when {
            retryAttempt < 4 -> 3_000L
            retryAttempt < 12 -> 6_000L
            else -> 12_000L
        }
        retryHandler.postDelayed(retryRunnable, delay)
    }

    private fun reloadNow() {
        retryHandler.removeCallbacks(retryRunnable)
        connectionFailed = false
        mainFrameFailed = false
        binding.errorPanel.isVisible = false
        binding.progress.isVisible = true
        binding.webView.reload()
    }

    private fun openExternally(uri: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: Exception) {
        false
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        retryHandler.removeCallbacksAndMessages(null)
        with(binding.webView) {
            stopLoading()
            loadUrl("about:blank")
            webViewClient = WebViewClient()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        private const val MENU_RELOAD = 1
        private const val MENU_LIST = 2
        private const val MENU_BROWSER = 3
        private const val MENU_CHANGE_PC = 4

        fun createIntent(context: Context, host: String, port: Int): Intent =
            Intent(context, DashboardActivity::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
    }
}
