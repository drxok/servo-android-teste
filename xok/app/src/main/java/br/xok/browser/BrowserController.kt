package br.xok.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/** Controla o WebView (o motor) e guarda o estado que a interface mostra. */
class BrowserController(private val activity: ComponentActivity) {
    lateinit var webView: WebView
        private set

    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var customView by mutableStateOf<View?>(null)
    var pageFullscreen by mutableStateOf(false)

    var blockTrackers = true
    var httpsOnly = true
    var onUrl: (String) -> Unit = {}
    var onTitle: (String) -> Unit = {}
    var onPageDone: (String, String?) -> Unit = { _, _ -> }
    var launchChooser: (Intent) -> Unit = {}

    /** Estados (histórico e rolagem) das abas que não estão na tela. */
    val states = HashMap<Long, Bundle>()

    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var clearHistoryPending = false

    fun navigate(url: String) = webView.loadUrl(url)

    fun back() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun forward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reload() = webView.reload()

    fun stop() = webView.stopLoading()

    fun saveState(tabId: Long) {
        val bundle = Bundle()
        webView.saveState(bundle)
        states[tabId] = bundle
    }

    /** Mostra uma aba: restaura o que estava nela ou carrega o endereço. */
    fun show(tab: Tab, home: String) {
        val saved = states[tab.id]
        if (saved != null) {
            webView.restoreState(saved)
        } else {
            showFresh(tab.url.ifEmpty { home })
        }
    }

    fun showFresh(url: String) {
        clearHistoryPending = true
        webView.loadUrl(url)
    }

    fun hideCustomView() {
        customCallback?.onCustomViewHidden()
        customCallback = null
        customView = null
        pageFullscreen = false
    }

    fun onFileChooserResult(resultCode: Int, data: Intent?) {
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        fileCallback = null
    }

    @Suppress("DEPRECATION")
    fun applyDark(allow: Boolean) {
        val settings = webView.settings
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, allow)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                settings,
                if (allow) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF,
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        val view = WebView(activity)
        webView = view
        val s = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Tira "wv" e "Version/4.0" do identificador: sites tratam como Chrome normal.
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        CookieManager.getInstance().setAcceptCookie(true)

        view.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
            DownloadsStore.enqueue(activity, url, userAgent, contentDisposition, mime)
        }
        view.webViewClient = client()
        view.webChromeClient = chromeClient()
        return view
    }

    private fun refreshNav(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    private fun client() =
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return false
                return when (scheme) {
                    "http" -> {
                        if (httpsOnly) {
                            view.loadUrl(url.toString().replaceFirst("http://", "https://"))
                            true
                        } else {
                            false
                        }
                    }
                    "https", "about", "data", "blob", "javascript", "file" -> false
                    else -> {
                        try {
                            val intent =
                                if (scheme == "intent") Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                                else Intent(Intent.ACTION_VIEW, url)
                            intent.addCategory(Intent.CATEGORY_BROWSABLE)
                            intent.component = null
                            intent.selector = null
                            activity.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            // nenhum app para abrir: ignora
                        } catch (e: Exception) {
                            // endereço inválido: ignora
                        }
                        true
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (blockTrackers && !request.isForMainFrame && Trackers.isTracker(request.url.host)) {
                    activity.runOnUiThread { Stats.blocked += 1 }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loading = true
                onUrl(url)
                refreshNav(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading = false
                if (clearHistoryPending) {
                    clearHistoryPending = false
                    view.clearHistory()
                }
                refreshNav(view)
                onPageDone(url, view.title)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onUrl(url)
                refreshNav(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val failing = request.url.toString()
                val safe = failing.replace("&", "&amp;").replace("<", "&lt;")
                val message = error.description.toString().replace("<", "&lt;")
                val html =
                    "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "<style>body{font-family:sans-serif;padding:28px;color:#444}h2{margin-bottom:6px}" +
                        "code{word-break:break-all;color:#777}a{display:inline-block;margin-top:18px;padding:10px 18px;" +
                        "border-radius:20px;background:#5B3FE0;color:#fff;text-decoration:none}</style></head><body>" +
                        "<h2>Não foi possível abrir a página</h2><p>$message</p><code>$safe</code><br>" +
                        "<a href='$safe'>Tentar de novo</a></body></html>"
                view.loadDataWithBaseURL(failing, html, "text/html", "utf-8", failing)
            }
        }

    private fun chromeClient() =
        object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                onTitle(title)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customCallback?.onCustomViewHidden()
                customCallback = callback
                customView = view
                pageFullscreen = true
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    launchChooser(fileChooserParams.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(activity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(activity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String?,
                result: JsPromptResult,
            ): Boolean {
                val input = EditText(activity)
                input.setText(defaultValue ?: "")
                AlertDialog.Builder(activity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
        }
}
