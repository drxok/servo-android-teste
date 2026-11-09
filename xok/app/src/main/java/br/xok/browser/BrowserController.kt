package br.xok.browser

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.view.View
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File

/** Controla o GeckoView (motor do Firefox) e guarda o estado que a interface mostra. */
class BrowserController(private val activity: ComponentActivity) {
    private class TabInfo {
        var url = ""
        var title = ""
        var canBack = false
        var canForward = false
        var progress = 0
        var loading = false
    }

    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var customView by mutableStateOf<View?>(null)
    var pageFullscreen by mutableStateOf(false)

    var blockTrackers = true
        set(value) {
            if (field != value) {
                field = value
                applyBlocking()
            }
        }
    var newTabMode = true
    var onOpenNewTab: (String) -> Unit = {}
    var httpsOnly = true
        set(value) {
            if (field != value) {
                field = value
                runtime.settings.setAllowInsecureConnections(
                    if (value) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL
                )
            }
        }
    var onUrl: (String) -> Unit = {}
    var onTitle: (String) -> Unit = {}
    var onPageDone: (String, String?) -> Unit = { _, _ -> }
    var launchChooser: (Intent) -> Unit = {}

    private val runtime: GeckoRuntime = obtainRuntime(activity)
    private lateinit var geckoView: GeckoView

    /** Cada aba tem a sua própria sessão (histórico e rolagem separados). */
    private val sessions = LinkedHashMap<Long, GeckoSession>()
    private val infos = HashMap<Long, TabInfo>()
    private val saved = HashMap<Long, GeckoSession.SessionState>()
    private val latestState = HashMap<Long, GeckoSession.SessionState>()
    private var activeId = -1L

    private var filePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var fileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    fun createContainer(): GeckoView {
        geckoView = GeckoView(activity)
        return geckoView
    }

    private fun current(): GeckoSession? = sessions[activeId]

    fun navigate(url: String) {
        current()?.loadUri(url)
    }

    fun back() {
        current()?.goBack()
    }

    fun forward() {
        current()?.goForward()
    }

    fun reload() {
        current()?.reload()
    }

    fun stop() {
        current()?.stop()
    }

    private fun publish(id: Long) {
        val info = infos[id] ?: return
        canGoBack = info.canBack
        canGoForward = info.canForward
        progress = info.progress
        loading = info.loading
        if (info.url.isNotEmpty()) onUrl(info.url)
        if (info.title.isNotEmpty()) onTitle(info.title)
    }

    private fun attach(id: Long, session: GeckoSession) {
        val previous = current()
        if (previous != null && previous !== session) {
            previous.setActive(false)
            previous.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
        }
        activeId = id
        if (!session.isOpen) session.open(runtime)
        geckoView.setSession(session)
        session.setActive(true)
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        // mantém no máximo 3 sessões vivas; as mais antigas guardam o estado e são fechadas
        sessions.remove(id)
        sessions[id] = session
        while (sessions.size > 3) {
            val oldest = sessions.entries.first()
            if (oldest.key == id) break
            latestState[oldest.key]?.let { saved[oldest.key] = it }
            closeSession(oldest.key)
        }
        publish(id)
    }

    private fun closeSession(id: Long) {
        val s = sessions.remove(id) ?: return
        if (::geckoView.isInitialized && geckoView.session === s) geckoView.releaseSession()
        s.close()
    }

    /** Mostra uma aba: usa a sessão dela, ou recria (restaurando o estado ou carregando o endereço). */
    fun show(tab: Tab, home: String) {
        val existing = sessions[tab.id]
        if (existing != null) {
            attach(tab.id, existing)
            return
        }
        val s = newSession(tab.id)
        attach(tab.id, s)
        val state = saved.remove(tab.id)
        if (state != null) s.restoreState(state) else s.loadUri(tab.url.ifEmpty { home })
    }

    /** Aba nova (ou reiniciada): sessão limpa, sem histórico. */
    fun open(tabId: Long, url: String) {
        closeSession(tabId)
        saved.remove(tabId)
        latestState.remove(tabId)
        infos.remove(tabId)
        val s = newSession(tabId)
        attach(tabId, s)
        s.loadUri(url)
    }

    fun discard(tabId: Long) {
        saved.remove(tabId)
        latestState.remove(tabId)
        infos.remove(tabId)
        if (tabId != activeId) closeSession(tabId)
        else {
            sessions.remove(tabId)?.let {
                geckoView.releaseSession()
                it.close()
            }
        }
    }

    fun hideCustomView() {
        current()?.exitFullScreen()
        pageFullscreen = false
    }

    fun onFileChooserResult(resultCode: Int, data: Intent?) {
        val prompt = filePrompt
        val result = fileResult
        filePrompt = null
        fileResult = null
        if (prompt == null || result == null) return
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            result.complete(prompt.dismiss())
            return
        }
        val clip = data.clipData
        val uris = ArrayList<Uri>()
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) {
            result.complete(prompt.dismiss())
        } else if (uris.size == 1) {
            result.complete(prompt.confirm(activity, uris[0]))
        } else {
            result.complete(prompt.confirm(activity, uris.toTypedArray()))
        }
    }

    /** Escuro nas páginas: o Firefox só entende "prefere tema escuro" (sem escurecer à força). */
    fun applyDark(allow: Boolean) {
        runtime.settings.setPreferredColorScheme(
            if (allow) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        )
    }

    private fun applyBlocking() {
        runtime.settings.contentBlocking.setEnhancedTrackingProtectionLevel(
            if (blockTrackers) ContentBlocking.EtpLevel.DEFAULT else ContentBlocking.EtpLevel.NONE
        )
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

    private fun userAgent(): String =
        "Mozilla/5.0 (Android 14; Mobile; rv:${GECKO_RV}) Gecko/${GECKO_RV} Firefox/${GECKO_RV}"

    private fun newSession(id: Long): GeckoSession {
        val settings = GeckoSessionSettings.Builder().usePrivateMode(false).build()
        val s = GeckoSession(settings)
        val info = infos.getOrPut(id) { TabInfo() }
        fun active() = id == activeId

        s.navigationDelegate =
            object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                    hasUserGesture: Boolean,
                ) {
                    info.url = url ?: ""
                    if (active() && url != null) onUrl(url)
                }

                override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                    info.canBack = value
                    if (active()) canGoBack = value
                }

                override fun onCanGoForward(session: GeckoSession, value: Boolean) {
                    info.canForward = value
                    if (active()) canGoForward = value
                }

                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<AllowOrDeny>? {
                    val uri = Uri.parse(request.uri)
                    val scheme = uri.scheme?.lowercase() ?: return null
                    if (scheme in INTERNAL_SCHEMES) return null
                    try {
                        val intent =
                            if (scheme == "intent") Intent.parseUri(request.uri, Intent.URI_INTENT_SCHEME)
                            else Intent(Intent.ACTION_VIEW, uri)
                        intent.addCategory(Intent.CATEGORY_BROWSABLE)
                        intent.component = null
                        intent.selector = null
                        activity.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        // nenhum app para abrir: ignora
                    } catch (e: Exception) {
                        // endereço inválido: ignora
                    }
                    return GeckoResult.deny()
                }

                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                    activity.runOnUiThread { openLink(uri, newTabMode) }
                    return GeckoResult.fromValue(null)
                }

                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: WebRequestError,
                ): GeckoResult<String>? {
                    val failing = uri ?: ""
                    val safe = failing.replace("&", "&amp;").replace("<", "&lt;").replace("'", "&#39;")
                    val html =
                        "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                            "<style>body{font-family:sans-serif;padding:28px;color:#444}h2{margin-bottom:6px}" +
                            "code{word-break:break-all;color:#777}a{display:inline-block;margin-top:18px;padding:10px 18px;" +
                            "border-radius:20px;background:#5B3FE0;color:#fff;text-decoration:none}</style></head><body>" +
                            "<h2>Não foi possível abrir a página</h2><p>Erro ${error.code}</p><code>$safe</code><br>" +
                            "<a href='$safe'>Tentar de novo</a></body></html>"
                    return GeckoResult.fromValue("data:text/html;charset=utf-8," + Uri.encode(html))
                }
            }

        s.progressDelegate =
            object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    info.loading = true
                    info.url = url
                    if (active()) {
                        loading = true
                        onUrl(url)
                    }
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    info.loading = false
                    info.progress = 100
                    if (active()) {
                        loading = false
                        progress = 100
                        onPageDone(info.url, info.title)
                    }
                }

                override fun onProgressChange(session: GeckoSession, value: Int) {
                    info.progress = value
                    if (active()) progress = value
                }

                override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                    latestState[id] = sessionState
                }
            }

        s.contentDelegate =
            object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    info.title = title ?: ""
                    if (active() && title != null) onTitle(title)
                }

                override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                    if (active()) pageFullscreen = fullScreen
                }

                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    val disposition = response.headers["Content-Disposition"] ?: response.headers["content-disposition"]
                    val mime = response.headers["Content-Type"] ?: response.headers["content-type"]
                    DownloadsStore.enqueue(activity, response.uri, userAgent(), disposition, mime)
                }

                override fun onContextMenu(
                    session: GeckoSession,
                    screenX: Int,
                    screenY: Int,
                    element: GeckoSession.ContentDelegate.ContextElement,
                ) {
                    val link = element.linkUri
                    val src = element.srcUri
                    if (!link.isNullOrEmpty()) {
                        showMenu(
                            listOf(
                                "Abrir em nova aba" to { openLink(link, true) },
                                "Abrir nesta aba" to { openLink(link, false) },
                                "Copiar endereço" to { copyText(link) },
                                "Baixar link" to { DownloadsStore.enqueue(activity, link, userAgent()) },
                            )
                        )
                    } else if (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && !src.isNullOrEmpty()) {
                        showMenu(
                            listOf(
                                "Abrir imagem em nova aba" to { openLink(src, true) },
                                "Baixar imagem" to { DownloadsStore.enqueue(activity, src, userAgent()) },
                                "Copiar endereço da imagem" to { copyText(src) },
                            )
                        )
                    }
                }

                override fun onCrash(session: GeckoSession) {
                    recover(id, session)
                }

                override fun onKill(session: GeckoSession) {
                    recover(id, session)
                }
            }

        s.contentBlockingDelegate =
            object : ContentBlocking.Delegate {
                override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                    activity.runOnUiThread { Stats.blocked += 1 }
                }
            }

        s.promptDelegate =
            object : GeckoSession.PromptDelegate {
                override fun onAlertPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.AlertPrompt,
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    AlertDialog.Builder(activity)
                        .setMessage(prompt.message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
                        .setOnCancelListener { result.complete(prompt.dismiss()) }
                        .show()
                    return result
                }

                override fun onButtonPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.ButtonPrompt,
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    AlertDialog.Builder(activity)
                        .setMessage(prompt.message)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                        }
                        .setOnCancelListener { result.complete(prompt.dismiss()) }
                        .show()
                    return result
                }

                override fun onTextPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.TextPrompt,
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    val input = EditText(activity)
                    input.setText(prompt.defaultValue ?: "")
                    AlertDialog.Builder(activity)
                        .setMessage(prompt.message)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
                        .setOnCancelListener { result.complete(prompt.dismiss()) }
                        .show()
                    return result
                }

                override fun onFilePrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.FilePrompt,
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    val intent = Intent(Intent.ACTION_GET_CONTENT)
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    val types = prompt.mimeTypes
                    if (types != null && types.size == 1 && types[0].isNotEmpty()) {
                        intent.type = types[0]
                    } else {
                        intent.type = "*/*"
                        if (types != null && types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types)
                    }
                    intent.putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                    )
                    filePrompt = prompt
                    fileResult = result
                    try {
                        launchChooser(intent)
                    } catch (e: Exception) {
                        filePrompt = null
                        fileResult = null
                        return GeckoResult.fromValue(prompt.dismiss())
                    }
                    return result
                }
            }
        return s
    }

    /** O processo da página morreu: recria a sessão e recarrega o endereço. */
    private fun recover(id: Long, dead: GeckoSession) {
        activity.runOnUiThread {
            val url = infos[id]?.url ?: ""
            val wasActive = id == activeId
            if (sessions[id] !== dead) return@runOnUiThread
            closeSession(id)
            if (wasActive) {
                val s = newSession(id)
                attach(id, s)
                if (url.isNotEmpty()) s.loadUri(url)
            }
        }
    }

    companion object {
        private const val GECKO_RV = "159.0"
        private val INTERNAL_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file", "resource", "moz-extension")
        private var shared: GeckoRuntime? = null

        @Synchronized
        private fun obtainRuntime(context: Context): GeckoRuntime {
            shared?.let { return it }
            val app = context.applicationContext
            // Ajustes de memória do motor: sem Fission, um só processo de conteúdo, caches pequenos.
            val config = File(app.filesDir, "geckoview-config.yaml")
            config.writeText(
                """
                prefs:
                  fission.autostart: false
                  dom.ipc.processCount: 1
                  dom.ipc.processPrelaunch.enabled: false
                  browser.sessionhistory.max_total_viewers: 0
                  browser.cache.memory.capacity: 16384
                  image.cache.size: 2097152
                  toolkit.telemetry.enabled: false
                  datareporting.healthreport.uploadEnabled: false
                  browser.safebrowsing.malware.enabled: false
                  browser.safebrowsing.phishing.enabled: false
                  media.peerconnection.enabled: false
                  dom.webnotifications.enabled: false
                  javascript.options.mem.gc_incremental_slice_ms: 20
                """.trimIndent() + "\n"
            )
            val blocking =
                ContentBlocking.Settings.Builder()
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    .build()
            val settings =
                GeckoRuntimeSettings.Builder()
                    .configFilePath(config.absolutePath)
                    .fissionEnabled(false)
                    .javaScriptEnabled(true)
                    .consoleOutput(false)
                    .remoteDebuggingEnabled(false)
                    .aboutConfigEnabled(false)
                    .contentBlocking(blocking)
                    .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
                    .build()
            return GeckoRuntime.create(app, settings).also { shared = it }
        }
    }
}
