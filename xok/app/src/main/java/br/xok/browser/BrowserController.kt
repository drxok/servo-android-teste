package br.xok.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.widget.Toast
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
    /** O WebView da aba que está na tela. */
    lateinit var webView: WebView
        private set

    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var customView by mutableStateOf<View?>(null)
    var pageFullscreen by mutableStateOf(false)

    var blockTrackers = true
    var newTabMode = true
    var onOpenNewTab: (String) -> Unit = {}
    var httpsOnly = true
    var onUrl: (String) -> Unit = {}
    var onTitle: (String) -> Unit = {}
    var onPageDone: (String, String?) -> Unit = { _, _ -> }
    var launchChooser: (Intent) -> Unit = {}

    private lateinit var container: FrameLayout
    private var darkAllowed = false

    /** Cada aba tem o seu próprio WebView (histórico e rolagem separados). */
    private val views = LinkedHashMap<Long, WebView>()
    private val tabOf = HashMap<WebView, Long>()

    /** Estado das abas cujo WebView foi descartado para economizar memória. */
    private val states = HashMap<Long, Bundle>()

    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    fun createContainer(): FrameLayout {
        container = FrameLayout(activity)
        return container
    }

    fun navigate(url: String) = webView.loadUrl(url)

    fun back() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun forward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reload() = webView.reload()

    fun stop() = webView.stopLoading()

    private fun attach(tabId: Long, wv: WebView) {
        val old = if (::webView.isInitialized) webView else null
        if (old != null && old !== wv) old.onPause()
        container.removeAllViews()
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        wv.onResume()
        // mantém no máximo 3 WebViews vivos; os mais antigos guardam o estado e são descartados
        views.remove(tabId)
        views[tabId] = wv
        while (views.size > 3) {
            val oldest = views.entries.first()
            if (oldest.value === wv) break
            val bundle = Bundle()
            oldest.value.saveState(bundle)
            states[oldest.key] = bundle
            dispose(oldest.key)
        }
        loading = wv.progress in 1..99
        progress = wv.progress
        refreshNav(wv)
        wv.url?.let { onUrl(it) }
        wv.title?.let { onTitle(it) }
    }

    private fun dispose(tabId: Long) {
        val wv = views.remove(tabId) ?: return
        tabOf.remove(wv)
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    /** Mostra uma aba: usa o WebView dela, ou recria (restaurando o estado ou carregando o endereço). */
    fun show(tab: Tab, home: String) {
        val existing = views[tab.id]
        if (existing != null) {
            attach(tab.id, existing)
            return
        }
        val wv = newWebView(tab.id)
        val saved = states.remove(tab.id)
        attach(tab.id, wv)
        if (saved != null) wv.restoreState(saved) else wv.loadUrl(tab.url.ifEmpty { home })
    }

    /** Aba nova (ou reiniciada): WebView limpo, sem histórico. */
    fun open(tabId: Long, url: String) {
        dispose(tabId)
        states.remove(tabId)
        val wv = newWebView(tabId)
        attach(tabId, wv)
        wv.loadUrl(url)
    }

    fun discard(tabId: Long) {
        states.remove(tabId)
        if (::webView.isInitialized && views[tabId] === webView) {
            // o WebView que está na tela só sai depois que outra aba assumir
            container.removeAllViews()
        }
        dispose(tabId)
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
    private fun applyDarkTo(wv: WebView, allow: Boolean) {
        val settings = wv.settings
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, allow)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                settings,
                if (allow) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF,
            )
        }
    }

    fun applyDark(allow: Boolean) {
        darkAllowed = allow
        views.values.forEach { applyDarkTo(it, allow) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(tabId: Long): WebView {
        val view = WebView(activity)
        tabOf[view] = tabId
        val s = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(true)
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
        applyDarkTo(view, darkAllowed)

        view.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
            DownloadsStore.enqueue(activity, url, userAgent, contentDisposition, mime)
        }
        view.setOnLongClickListener { v -> onLongPress(v as WebView) }
        view.webViewClient = client()
        view.webChromeClient = chromeClient()
        return view
    }

    private fun openLink(url: String, newTab: Boolean) {
        if (newTab) onOpenNewTab(url) else navigate(url)
    }

    private fun copyText(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("endereço", text))
        Toast.makeText(activity, "Endereço copiado", Toast.LENGTH_SHORT).show()
    }

    private fun showMenu(items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(activity)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Segurar o dedo num link ou imagem abre um menu. */
    private fun onLongPress(view: WebView): Boolean {
        val hit = view.hitTestResult
        val extra = hit.extra
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val handler =
                    Handler(Looper.getMainLooper()) { msg: Message ->
                        val url = msg.data.getString("url") ?: extra
                        if (!url.isNullOrEmpty()) {
                            showMenu(
                                listOf(
                                    "Abrir em nova aba" to { openLink(url, true) },
                                    "Abrir nesta aba" to { openLink(url, false) },
                                    "Copiar endereço" to { copyText(url) },
                                    "Baixar link" to { DownloadsStore.enqueue(activity, url, view.settings.userAgentString) },
                                )
                            )
                        }
                        true
                    }
                view.requestFocusNodeHref(handler.obtainMessage())
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (extra.isNullOrEmpty()) return false
                showMenu(
                    listOf(
                        "Abrir imagem em nova aba" to { openLink(extra, true) },
                        "Baixar imagem" to { DownloadsStore.enqueue(activity, extra, view.settings.userAgentString) },
                        "Copiar endereço da imagem" to { copyText(extra) },
                    )
                )
                true
            }
            else -> false
        }
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
                if (view !== webView) return
                loading = true
                onUrl(url)
                refreshNav(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view !== webView) return
                loading = false
                refreshNav(view)
                onPageDone(url, view.title)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (view !== webView) return
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
                if (view === webView) progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                if (view === webView) onTitle(title)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val temp = WebView(activity)
                temp.settings.javaScriptEnabled = true
                temp.webViewClient =
                    object : WebViewClient() {
                        private var handled = false

                        private fun open(url: String) {
                            if (handled || url == "about:blank") return
                            handled = true
                            openLink(url, newTabMode)
                            temp.post {
                                temp.stopLoading()
                                temp.destroy()
                            }
                        }

                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            open(request.url.toString())
                            return true
                        }

                        override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                            open(url)
                        }
                    }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
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
