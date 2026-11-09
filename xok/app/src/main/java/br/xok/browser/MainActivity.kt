/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package br.xok.browser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.consumeWindowInsets
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.LocalDensity

class MainActivity : ComponentActivity() {
    private val urlTextFieldState = TextFieldState()
    private lateinit var historyManager: HistoryManager
    private lateinit var controller: BrowserController
    private var showTabs by mutableStateOf(false)
    private var manualFullscreen by mutableStateOf(false)
    private var tabActions: TabActions? = null
    private var homeUrl = "https://duckduckgo.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Tabs.load(this)
        if (Intent.ACTION_VIEW == intent.action && intent.data != null) {
            Tabs.list.add(Tab(Tabs.newId(), intent.data.toString(), ""))
            Tabs.active = Tabs.list.size - 1
        }
        historyManager = HistoryManager(this)
        controller = BrowserController(this)

        val chooserLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                controller.onFileChooserResult(result.resultCode, result.data)
            }
        controller.launchChooser = { chooserLauncher.launch(it) }

        val historyLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val url = result.data?.getStringExtra("url")
                if (result.resultCode == RESULT_OK && !url.isNullOrEmpty()) controller.navigate(url)
            }

        controller.onUrl = { url ->
            val shown = if (url.startsWith("data:") || url == "about:blank") "" else url
            urlTextFieldState.edit { replace(0, length, shown) }
            if (url != "about:blank") {
                Tabs.update(Tabs.active) { it.copy(url = shown) }
            }
        }
        controller.onTitle = { title ->
            Tabs.update(Tabs.active) { it.copy(title = title) }
            Tabs.save(this)
        }
        controller.onPageDone = { url, title ->
            historyManager.addEntry(url, title)
            Tabs.save(this)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        controller.customView != null -> controller.hideCustomView()
                        showTabs -> showTabs = false
                        manualFullscreen -> manualFullscreen = false
                        controller.canGoBack -> controller.back()
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )

        setContent {
            BrowserTheme {
                val engine by rememberStringPref(Prefs.ENGINE, "ddg")
                val httpsOnly by rememberBoolPref(Prefs.HTTPS_ONLY, true)
                val blockTrackers by rememberBoolPref(Prefs.BLOCK_TRACKERS, true)
                val pageDark by rememberStringPref(Prefs.PAGE_DARK, "off")
                val fullscreen = controller.pageFullscreen || manualFullscreen
                val cs = MaterialTheme.colorScheme
                val startPage = remember {
                    startPageUrl(
                        engine,
                        cs.background.toArgb(),
                        cs.onBackground.toArgb(),
                        cs.primary.toArgb(),
                        cs.secondaryContainer.toArgb(),
                        cs.onSurfaceVariant.toArgb(),
                    )
                }
                val homeMode by rememberStringPref(Prefs.HOME_MODE, "ddg")
                val homeCustom by rememberStringPref(Prefs.HOME_CUSTOM, "")
                val home =
                    when (homeMode) {
                        "start" -> startPage
                        "custom" -> resolveInput(homeCustom, engine, httpsOnly).ifEmpty { "https://duckduckgo.com/" }
                        else -> "https://duckduckgo.com/"
                    }
                homeUrl = home
                val activity = this@MainActivity
                val actions = remember { TabActions(activity, controller, home).also { tabActions = it } }
                actions.home = home
                controller.blockTrackers = blockTrackers
                controller.httpsOnly = httpsOnly
                val appDark = cs.background.luminance() < 0.5f

                LaunchedEffect(fullscreen) {
                    val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (fullscreen) c.hide(WindowInsetsCompat.Type.systemBars())
                    else c.show(WindowInsetsCompat.Type.systemBars())
                }
                LaunchedEffect(pageDark, appDark) {
                    controller.applyDark(pageDark == "force" || (pageDark == "auto" && appDark))
                }
                LaunchedEffect(Unit) {
                    controller.show(Tabs.list[Tabs.active], home)
                }

                Box(Modifier.fillMaxSize().background(cs.background)) {
                    Column(Modifier.fillMaxSize().imePadding()) {
                        if (!fullscreen) {
                            Spacer(
                                Modifier.fillMaxWidth()
                                    .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                                    .background(cs.surface)
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(
                                factory = { controller.createWebView() },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (controller.loading && controller.progress < 100) {
                                LinearProgressIndicator(
                                    progress = { controller.progress / 100f },
                                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                                )
                            }
                            if (manualFullscreen && !controller.pageFullscreen) {
                                FilledTonalIconButton(
                                    onClick = { manualFullscreen = false },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(48.dp).alpha(0.85f),
                                ) {
                                    Icon(painterResource(R.drawable.cancel), "Sair da tela cheia")
                                }
                            }
                        }
                        if (!fullscreen) {
                            Dock(
                                blockedCount = Stats.blocked,
                                onFullscreen = { manualFullscreen = true },
                                onHome = { controller.navigate(home) },
                                tabCount = Tabs.list.size,
                                onTabs = {
                                    showTabs = true
                                },
                                urlState = urlTextFieldState,
                                navigator = controller,
                                isRefreshing = controller.loading,
                                onSubmit = { text ->
                                    val target = resolveInput(text, engine, httpsOnly)
                                    if (target.isNotEmpty()) controller.navigate(target)
                                },
                                onHistory = {
                                    historyLauncher.launch(Intent(activity, HistoryActivity::class.java))
                                },
                                onDownloads = { startActivity(Intent(activity, DownloadsActivity::class.java)) },
                                onSettings = { startActivity(Intent(activity, SettingsActivity::class.java)) },
                            )
                        }
                    }

                    controller.customView?.let { custom ->
                        AndroidView(
                            factory = {
                                FrameLayout(it).apply {
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    (custom.parent as? ViewGroup)?.removeView(custom)
                                    addView(
                                        custom,
                                        FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (showTabs) {
                        TabsScreen(
                            tabs = Tabs.list,
                            active = Tabs.active,
                            onSelect = { i ->
                                actions.switchTo(i)
                                showTabs = false
                            },
                            onClose = { i -> actions.close(i) },
                            onNew = {
                                actions.newTab()
                                showTabs = false
                            },
                            onDone = { showTabs = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data
        if (Intent.ACTION_VIEW == intent.action && data != null) {
            tabActions?.newTab(data.toString())
        }
    }

    override fun onPause() {
        super.onPause()
        Tabs.save(this)
    }
}

/** Shrinks a little while pressed and springs back (Material 3 Expressive motion). */
@Composable
private fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) 0.88f else 1f,
            animationSpec = Motion.fastSpatial(),
            label = "press",
        )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
private fun Dock(
    blockedCount: Int,
    onFullscreen: () -> Unit,
    onHome: () -> Unit,
    tabCount: Int,
    onTabs: () -> Unit,
    urlState: TextFieldState,
    navigator: BrowserController,
    isRefreshing: Boolean,
    onSubmit: (String) -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var dockHeight by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val enter = fadeIn(Motion.effects()) + expandHorizontally(Motion.spatial<IntSize>())
    val exit = fadeOut(Motion.fastEffects()) + shrinkHorizontally(Motion.fastSpatial<IntSize>())

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.ui.graphics.RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Box(Modifier.onSizeChanged { dockHeight = it.height }) {
        Row(
            modifier =
                Modifier.navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(visible = !focused, enter = enter, exit = exit) {
                val src = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = { navigator.back() },
                    enabled = navigator.canGoBack,
                    interactionSource = src,
                    modifier = Modifier.size(40.dp).pressScale(src),
                ) {
                    Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.history_back), modifier = Modifier.size(20.dp))
                }
            }

            Surface(
                modifier = Modifier.weight(1f).height(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val secure = urlState.text.startsWith("https://")
                    if (secure) {
                        Icon(
                            painterResource(R.drawable.lock),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (urlState.text.isEmpty()) {
                            Text(
                                stringResource(R.string.url_or_search),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            state = urlState,
                            modifier =
                                Modifier.fillMaxWidth().onFocusChanged { s ->
                                    focused = s.isFocused
                                    if (s.isFocused) {
                                        scope.launch { urlState.edit { selectAll() } }
                                    }
                                },
                            textStyle =
                                MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go,
                                ),
                            onKeyboardAction = {
                                focusManager.clearFocus()
                                onSubmit(urlState.text.toString())
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(visible = !focused, enter = enter, exit = exit) {
                val src = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = { if (isRefreshing) navigator.stop() else navigator.reload() },
                    interactionSource = src,
                    modifier = Modifier.size(40.dp).pressScale(src),
                ) {
                    if (isRefreshing) {
                        Icon(painterResource(R.drawable.cancel), stringResource(R.string.cancel), modifier = Modifier.size(20.dp))
                    } else {
                        Icon(painterResource(R.drawable.refresh), stringResource(R.string.refresh), modifier = Modifier.size(20.dp))
                    }
                }
            }

            AnimatedVisibility(visible = !focused, enter = enter, exit = exit) {
                val src = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = onTabs,
                    interactionSource = src,
                    modifier = Modifier.size(40.dp).pressScale(src),
                ) {
                    Box(
                        Modifier.size(20.dp)
                            .border(1.6.dp, MaterialTheme.colorScheme.onSecondaryContainer, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (tabCount > 99) ":)" else tabCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = !focused, enter = enter, exit = exit) {
                val src = remember { MutableInteractionSource() }
                Box {
                    FilledIconButton(
                        onClick = { menuOpen = true },
                        interactionSource = src,
                        modifier = Modifier.size(40.dp).pressScale(src),
                    ) {
                        Icon(painterResource(R.drawable.more), stringResource(R.string.options), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (menuOpen) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomEnd,
                offset = androidx.compose.ui.unit.IntOffset(0, -dockHeight),
                onDismissRequest = { menuOpen = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.width(260.dp),
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        DropdownMenuItem(
                            text = { Text("$blockedCount rastreadores bloqueados") },
                            leadingIcon = { Icon(painterResource(R.drawable.lock), null, modifier = Modifier.size(20.dp)) },
                            enabled = false,
                            onClick = {},
                        )
                        DropdownMenuItem(
                            text = { Text("Página inicial") },
                            leadingIcon = { Icon(painterResource(R.drawable.home), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onHome()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Tela cheia") },
                            leadingIcon = { Icon(painterResource(R.drawable.refresh), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onFullscreen()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_forward)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.arrow_forward), null, modifier = Modifier.size(20.dp))
                            },
                            enabled = navigator.canGoForward,
                            onClick = {
                                menuOpen = false
                                navigator.forward()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Downloads") },
                            leadingIcon = { Icon(painterResource(R.drawable.download), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onDownloads()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_title)) },
                            leadingIcon = { Icon(painterResource(R.drawable.history), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onHistory()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.options)) },
                            leadingIcon = { Icon(painterResource(R.drawable.settings), null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onSettings()
                            },
                        )
                    }
                }
            }
        }
      }
    }
}


/** Ações das abas: mantém a lista do app e o WebView em sincronia. */
class TabActions(
    private val context: android.content.Context,
    private val controller: BrowserController,
    var home: String,
) {
    fun switchTo(index: Int) {
        if (index !in Tabs.list.indices || index == Tabs.active) return
        controller.saveState(Tabs.list[Tabs.active].id)
        Tabs.active = index
        controller.show(Tabs.list[index], home)
        Tabs.save(context)
    }

    fun newTab(url: String = "") {
        controller.saveState(Tabs.list[Tabs.active].id)
        Tabs.list.add(Tab(Tabs.newId(), url, if (url.isEmpty()) "Nova aba" else ""))
        Tabs.active = Tabs.list.size - 1
        controller.showFresh(url.ifEmpty { home })
        Tabs.save(context)
    }

    fun close(index: Int) {
        if (index !in Tabs.list.indices) return
        if (Tabs.list.size == 1) {
            controller.states.remove(Tabs.list[0].id)
            Tabs.list[0] = Tab(Tabs.list[0].id, "", "Nova aba")
            controller.showFresh(home)
            Tabs.save(context)
            return
        }
        val wasActive = index == Tabs.active
        val newActive =
            when {
                wasActive -> index.coerceAtMost(Tabs.list.size - 2)
                index < Tabs.active -> Tabs.active - 1
                else -> Tabs.active
            }
        controller.states.remove(Tabs.list[index].id)
        Tabs.list.removeAt(index)
        Tabs.active = newActive
        if (wasActive) controller.show(Tabs.list[newActive], home)
        Tabs.save(context)
    }
}
