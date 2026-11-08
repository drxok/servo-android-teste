/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell

import android.content.Intent
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.component1
import androidx.activity.result.component2
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
import org.servo.servoview.Servo
import org.servo.servoview.ServoNavigator

class MainActivity : ComponentActivity(), Servo.Client {
    private val urlTextFieldState = TextFieldState()
    private var isRefreshing by mutableStateOf(false)
    private lateinit var historyManager: HistoryManager
    private var currentUrl = ""
    private var currentTitle = ""
    private var alertMessage by mutableStateOf<String?>(null)
    private var pageFullscreen by mutableStateOf(false)
    private var showTabs by mutableStateOf(false)
    private var tabActions: TabActions? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val navigator = ServoNavigator()
        Tabs.load(this)
        if (Intent.ACTION_VIEW == intent.action && intent.data != null) {
            Tabs.list.add(Tab(Tabs.newId(), intent.data.toString(), ""))
            Tabs.active = Tabs.list.size - 1
            Tabs.markPending()
        }
        historyManager = HistoryManager(this)

        val historyActivityResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                (resultCode, data) ->
                if (resultCode == RESULT_OK && data != null) {
                    val url = data.getStringExtra("url")
                    if (!url.isNullOrEmpty()) {
                        urlTextFieldState.edit { replace(0, length, url) }
                        navigator.navigate(url)
                    }
                }
            }

        setContent {
            BrowserTheme {
                val engine by rememberStringPref(Prefs.ENGINE, "ddg")
                val httpsOnly by rememberBoolPref(Prefs.HTTPS_ONLY, true)
                val experimentalModeEnabled by rememberBoolPref("experimental", false)
                val blockTrackers by rememberBoolPref(Prefs.BLOCK_TRACKERS, true)
                val pageDark by rememberStringPref(Prefs.PAGE_DARK, "off")
                var manualFullscreen by remember { mutableStateOf(false) }
                val fullscreen = pageFullscreen || manualFullscreen
                val cs = MaterialTheme.colorScheme
                val servoFocusRequester = remember { FocusRequester() }
                val home = remember {
                    startPageUrl(
                        engine,
                        cs.background.toArgb(),
                        cs.onBackground.toArgb(),
                        cs.primary.toArgb(),
                        cs.secondaryContainer.toArgb(),
                        cs.onSurfaceVariant.toArgb(),
                    )
                }
                val activity = this@MainActivity
                val restoreUrls = remember { Tabs.list.map { if (it.url.isEmpty()) home else it.url } }
                val restoreActive = remember { Tabs.active }
                val actions = remember { TabActions(activity, navigator, home).also { tabActions = it } }
                BackHandler(enabled = showTabs) { showTabs = false }
                LaunchedEffect(fullscreen) {
                    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
                    else controller.show(WindowInsetsCompat.Type.systemBars())
                }
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Servo(
                        experimentalModeEnabled = experimentalModeEnabled,
                        blockTrackers = blockTrackers,
                        pageDarkMode =
                            when (pageDark) {
                                "auto" -> 1
                                "force" -> 2
                                else -> 0
                            },
                        args = intent.getStringExtra("servoargs"),
                        initialUrl = if (restoreActive == 0) restoreUrls[0] else "about:blank",
                        restoreUrls = restoreUrls,
                        restoreActive = restoreActive,
                        logStr = intent.getStringExtra("servolog"),
                        client = this@MainActivity,
                        navigator = navigator,
                        modifier =
                            (if (fullscreen) Modifier.fillMaxSize()
                                else
                                    Modifier.fillMaxSize()
                                        .windowInsetsPadding(WindowInsets.statusBars)
                                        .padding(bottom = 52.dp + navBottom))
                                .focusRequester(servoFocusRequester),
                    )
                    LaunchedEffect(servoFocusRequester) { servoFocusRequester.requestFocus() }

                    if (!fullscreen) {
                        Spacer(
                            Modifier.fillMaxWidth()
                                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                                .background(cs.surface)
                                .align(Alignment.TopCenter)
                        )
                    }
                    if (isRefreshing) {
                        LinearProgressIndicator(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .align(Alignment.TopCenter)
                        )
                    }

                    if (manualFullscreen && !pageFullscreen) {
                        FilledTonalIconButton(
                            onClick = { manualFullscreen = false },
                            modifier =
                                Modifier.align(Alignment.BottomEnd).padding(20.dp).size(48.dp).alpha(0.85f),
                        ) {
                            Icon(painterResource(R.drawable.cancel), "Sair da tela cheia")
                        }
                    }
                    if (!fullscreen) Dock(
                        blockedCount = Stats.blocked,
                        onFullscreen = { manualFullscreen = true },
                        tabCount = Tabs.list.size,
                        onTabs = { showTabs = true },
                        urlState = urlTextFieldState,
                        navigator = navigator,
                        isRefreshing = isRefreshing,
                        onSubmit = { text ->
                            val target = resolveInput(text, engine, httpsOnly)
                            if (target.isNotEmpty()) navigator.navigate(target)
                            servoFocusRequester.requestFocus()
                        },
                        onHistory = { historyActivityResultLauncher.launch(
                            Intent(this@MainActivity, HistoryActivity::class.java)
                        ) },
                        onSettings = {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        },
                        modifier =
                            Modifier.align(Alignment.BottomCenter)
                                .imePadding(),
                    )

                    if (showTabs) {
                        TabsScreen(
                            tabs = Tabs.list,
                            active = Tabs.active,
                            onSelect = { i ->
                                actions.switchTo(i)
                                showTabs = false
                                servoFocusRequester.requestFocus()
                            },
                            onClose = { i -> actions.close(i) },
                            onNew = {
                                actions.newTab()
                                showTabs = false
                                servoFocusRequester.requestFocus()
                            },
                            onDone = { showTabs = false },
                        )
                    }

                    alertMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { alertMessage = null },
                            confirmButton = {
                                TextButton(onClick = { alertMessage = null }) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                            text = { Text(message) },
                        )
                    }
                }
            }
        }

        val sdcard = getExternalFilesDir("")
        val host = sdcard!!.toPath().resolve("android_hosts").toString()
        try {
            Os.setenv("HOST_FILE", host, false)
        } catch (e: ErrnoException) {
            e.printStackTrace()
        }
    }

    override fun onAlert(message: String) {
        alertMessage = message
    }

    override fun onLoadStarted() {
        Log.i(TAG, "onLoadStarted: ")
        isRefreshing = true
    }

    override fun onLoadEnded() {
        Log.i(TAG, "onLoadEnded: ")
        if (currentUrl.isNotEmpty()) {
            historyManager.addEntry(currentUrl, currentTitle)
        }
        isRefreshing = false
    }

    override fun onTitleChanged(title: String) {
        currentTitle = title
        Tabs.update(Tabs.active) { if (it.pending) it else it.copy(title = title) }
        Tabs.save(this)
    }

    override fun onUrlChanged(url: String) {
        val shown = if (url.startsWith("data:")) "" else url
        urlTextFieldState.edit { replace(0, length, shown) }
        currentUrl = shown
        if (url != "about:blank") {
            Tabs.update(Tabs.active) { if (it.pending) it else it.copy(url = shown) }
            Tabs.save(this)
        }
    }

    override fun onTabsChanged(count: Int, active: Int) {
        // Abas criadas ou fechadas pela própria página (ex.: links que abrem em nova aba).
        if (count > Tabs.list.size) {
            while (Tabs.list.size < count) Tabs.list.add(Tab(Tabs.newId(), "", ""))
            Tabs.active = active.coerceIn(0, Tabs.list.size - 1)
        }
        Tabs.save(this)
    }

    override fun onPause() {
        super.onPause()
        Tabs.save(this)
    }

    override fun onFullscreenChanged(enabled: Boolean) {
        pageFullscreen = enabled
    }

    override fun onBlockedCountChanged(count: Int) {
        Stats.blocked = count
    }

    companion object {
        private const val TAG = "MainActivity"
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
    tabCount: Int,
    onTabs: () -> Unit,
    urlState: TextFieldState,
    navigator: ServoNavigator,
    isRefreshing: Boolean,
    onSubmit: (String) -> Unit,
    onHistory: () -> Unit,
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


/** Ações das abas: mantém a lista do app e o motor em sincronia. */
class TabActions(
    private val context: android.content.Context,
    private val navigator: ServoNavigator,
    private val home: String,
) {
    fun switchTo(index: Int) {
        if (index !in Tabs.list.indices || index == Tabs.active) return
        Tabs.active = index
        navigator.switchTab(index)
        loadIfPending(index)
        Tabs.save(context)
    }

    fun newTab() {
        Tabs.list.add(Tab(Tabs.newId(), "", "Nova aba"))
        Tabs.active = Tabs.list.size - 1
        navigator.newTab(home)
        Tabs.save(context)
    }

    fun close(index: Int) {
        if (index !in Tabs.list.indices) return
        if (Tabs.list.size == 1) {
            Tabs.list[0] = Tab(Tabs.list[0].id, "", "Nova aba")
            navigator.navigate(home)
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
        Tabs.list.removeAt(index)
        Tabs.active = newActive
        navigator.closeTab(index)
        navigator.switchTab(newActive)
        loadIfPending(newActive)
        Tabs.save(context)
    }

    private fun loadIfPending(index: Int) {
        val tab = Tabs.list[index]
        if (tab.pending) {
            navigator.navigate(if (tab.url.isEmpty()) home else tab.url)
            Tabs.list[index] = tab.copy(pending = false)
        }
    }
}
