"""Aplica as nossas mudanças ao código do Servo (versão fixa). Falha alto se algo não casar."""
import sys

root = sys.argv[1]


def edit(path, pairs):
    p = f"{root}/{path}"
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        assert s.count(old) == 1, f"não achei exatamente 1x em {path}: {old[:70]!r} (achei {s.count(old)})"
        s = s.replace(old, new)
    open(p, "w", encoding="utf-8").write(s)


# --- Rust: módulo novo ---
edit("ports/servoshell/lib.rs", [("mod parser;\n", "mod adblock;\nmod parser;\n")])

# --- Rust: HostTrait ---
edit(
    "ports/servoshell/egl/host_trait.rs",
    [
        (
            "    /// Called when we get a panic message from constellation\n",
            "    /// A página entrou/saiu da tela cheia.\n"
            "    fn on_fullscreen_changed(&self, _is_fullscreen: bool) {}\n"
            "    /// A contagem de rastreadores bloqueados mudou.\n"
            "    fn on_blocked_count_changed(&self, _count: u32) {}\n"
            "    /// Called when we get a panic message from constellation\n",
        )
    ],
)

# --- Rust: janela EGL (fullscreen + modo escuro + contador) ---
edit(
    "ports/servoshell/egl/app.rs",
    [
        (
            "    fn get_fullscreen(&self) -> bool {\n        false\n    }\n",
            "    fn get_fullscreen(&self) -> bool {\n        false\n    }\n\n"
            "    fn set_fullscreen(&self, state: bool) {\n        self.host.on_fullscreen_changed(state);\n    }\n",
        ),
        (
            "        let new_title = active_webview.page_title();\n",
            "        crate::adblock::apply_dark_mode(&active_webview, &state.user_content_manager);\n"
            "        if let Some(count) = crate::adblock::take_blocked_change() {\n"
            "            self.host.on_blocked_count_changed(count);\n        }\n\n"
            "        let new_title = active_webview.page_title();\n",
        ),
    ],
)

# --- Rust: bloqueio de recursos ---
edit(
    "ports/servoshell/running_app_state.rs",
    [
        (
            "    fn notify_fullscreen_state_changed(&self, webview: WebView, fullscreen_state: bool) {\n",
            "    fn load_web_resource(&self, _webview: WebView, load: servo::WebResourceLoad) {\n"
            "        if crate::adblock::should_block(load.request()) {\n"
            "            let url = load.request().url.clone().into_url();\n"
            "            load.intercept(servo::WebResourceResponse::new(url)).cancel();\n        }\n    }\n\n"
            "    fn notify_fullscreen_state_changed(&self, webview: WebView, fullscreen_state: bool) {\n",
        )
    ],
)

# --- Rust: JNI (Android) ---
edit(
    "ports/servoshell/egl/android/mod.rs",
    [
        (
            "impl HostTrait for HostCallbacks {\n",
            "#[unsafe(no_mangle)]\n"
            "pub extern \"C\" fn Java_org_servo_servoview_JNIServo_setBlockTrackers<'local>(\n"
            "    mut env: EnvUnowned<'local>,\n    _: JClass<'local>,\n    enable: jboolean,\n) {\n"
            "    env.with_env(|_env| -> jni::errors::Result<_> {\n"
            "        crate::adblock::set_enabled(enable);\n        Ok(())\n    })\n"
            "    .resolve::<ThrowRuntimeExAndDefault>()\n}\n\n"
            "#[unsafe(no_mangle)]\n"
            "pub extern \"C\" fn Java_org_servo_servoview_JNIServo_setPageDarkMode<'local>(\n"
            "    mut env: EnvUnowned<'local>,\n    _: JClass<'local>,\n    mode: jint,\n) {\n"
            "    env.with_env(|_env| -> jni::errors::Result<_> {\n"
            "        crate::adblock::set_dark_mode(mode);\n        Ok(())\n    })\n"
            "    .resolve::<ThrowRuntimeExAndDefault>()\n}\n\n"
            "impl HostTrait for HostCallbacks {\n"
            "    fn on_fullscreen_changed(&self, is_fullscreen: bool) {\n"
            "        self.jvm\n            .attach_current_thread(|env| -> Result<(), Error> {\n"
            "                env.call_method(\n                    callback_ref(),\n"
            "                    jni_str!(\"onFullscreenChanged\"),\n"
            "                    jni_sig!(\"(Z)V\"),\n"
            "                    &[JValue::Bool(is_fullscreen as jboolean)],\n                )?;\n"
            "                Ok(())\n            })\n            .unwrap();\n    }\n\n"
            "    fn on_blocked_count_changed(&self, count: u32) {\n"
            "        self.jvm\n            .attach_current_thread(|env| -> Result<(), Error> {\n"
            "                env.call_method(\n                    callback_ref(),\n"
            "                    jni_str!(\"onBlockedCountChanged\"),\n"
            "                    jni_sig!(\"(I)V\"),\n"
            "                    &[JValue::Int(count as jint)],\n                )?;\n"
            "                Ok(())\n            })\n            .unwrap();\n    }\n\n",
        )
    ],
)

# --- Kotlin: servoview ---
base = "support/android/apk/servoview/src/main/java/org/servo/servoview"
edit(
    f"{base}/JNIServo.kt",
    [
        ("    external fun setExperimentalMode(enable: Boolean)\n",
         "    external fun setExperimentalMode(enable: Boolean)\n\n"
         "    external fun setBlockTrackers(enable: Boolean)\n\n"
         "    external fun setPageDarkMode(mode: Int)\n"),
        ("        fun onImeHide()\n    }\n}",
         "        fun onImeHide()\n\n        fun onFullscreenChanged(enabled: Boolean)\n\n"
         "        fun onBlockedCountChanged(count: Int)\n    }\n}"),
    ],
)
edit(
    f"{base}/Servo.kt",
    [
        ("    experimentalModeEnabled: Boolean,\n    args: String?,",
         "    experimentalModeEnabled: Boolean,\n    blockTrackers: Boolean = true,\n    pageDarkMode: Int = 0,\n    args: String?,"),
        ("    LaunchedEffect(experimentalModeEnabled) { servo.setExperimentalMode(experimentalModeEnabled) }\n",
         "    LaunchedEffect(experimentalModeEnabled) { servo.setExperimentalMode(experimentalModeEnabled) }\n"
         "    LaunchedEffect(blockTrackers) { servo.setBlockTrackers(blockTrackers) }\n"
         "    LaunchedEffect(pageDarkMode) { servo.setPageDarkMode(pageDarkMode) }\n"),
        ("    fun onDoFrame() {\n",
         "    fun setBlockTrackers(enable: Boolean) {\n        scope.launch(glDispatcher) { jni.setBlockTrackers(enable) }\n    }\n\n"
         "    fun setPageDarkMode(mode: Int) {\n        scope.launch(glDispatcher) { jni.setPageDarkMode(mode) }\n    }\n\n"
         "    fun onDoFrame() {\n"),
        ("        fun onUrlChanged(url: String)\n    }\n\n    private class Callbacks(",
         "        fun onUrlChanged(url: String)\n\n        fun onFullscreenChanged(enabled: Boolean) {}\n\n"
         "        fun onBlockedCountChanged(count: Int) {}\n    }\n\n    private class Callbacks("),
        ("        override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {\n",
         "        override fun onFullscreenChanged(enabled: Boolean) {\n            scope.launch { client.onFullscreenChanged(enabled) }\n        }\n\n"
         "        override fun onBlockedCountChanged(count: Int) {\n            scope.launch { client.onBlockedCountChanged(count) }\n        }\n\n"
         "        override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {\n"),
    ],
)
edit(
    f"{base}/Servo.kt",
    [
        (
            "startInputMethod { _ -> BaseInputConnection(view, true) }",
            "startInputMethod { outAttrs ->\n"
            "                        outAttrs.inputType =\n"
            "                            android.text.InputType.TYPE_CLASS_TEXT or\n"
            "                                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or\n"
            "                                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS\n"
            "                        outAttrs.imeOptions =\n"
            "                            android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or\n"
            "                                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI\n"
            "                        object : BaseInputConnection(view, false) {\n"
            "                            private fun press(code: Int, times: Int) {\n"
            "                                repeat(times) {\n"
            "                                    sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))\n"
            "                                    sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))\n"
            "                                }\n"
            "                            }\n\n"
            "                            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {\n"
            "                                if (text != null && (text.contains('\\n') || text.contains('\\r'))) {\n"
            "                                    press(android.view.KeyEvent.KEYCODE_ENTER, 1)\n"
            "                                    return true\n"
            "                                }\n"
            "                                return super.commitText(text, newCursorPosition)\n"
            "                            }\n\n"
            "                            override fun performEditorAction(actionCode: Int): Boolean {\n"
            "                                press(android.view.KeyEvent.KEYCODE_ENTER, 1)\n"
            "                                return true\n"
            "                            }\n\n"
            "                            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {\n"
            "                                press(android.view.KeyEvent.KEYCODE_DEL, beforeLength)\n"
            "                                press(android.view.KeyEvent.KEYCODE_FORWARD_DEL, afterLength)\n"
            "                                return true\n"
            "                            }\n\n"
            "                            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {\n"
            "                                return deleteSurroundingText(beforeLength, afterLength)\n"
            "                            }\n"
            "                        }\n"
            "                    }",
        )
    ],
)
edit(
    "ports/servoshell/egl/android/mod.rs",
    [
        (
            "    char::from_u32(unicode)\n        .filter(|c| *c != '\\0')\n        .map(|c| Key::Character(String::from(c)))\n        .or_else(|| KeyCode::try_from(keycode).ok().map(Key::from))\n",
            "    if let Ok(named) = KeyCode::try_from(keycode) {\n        return Some(Key::from(named));\n    }\n"
            "    char::from_u32(unicode)\n        .filter(|c| *c != '\\0')\n        .map(|c| Key::Character(String::from(c)))\n",
        )
    ],
)

# ======================= ABAS =======================
edit(
    "ports/servoshell/egl/host_trait.rs",
    [("    fn on_blocked_count_changed(&self, _count: u32) {}\n",
      "    fn on_blocked_count_changed(&self, _count: u32) {}\n"
      "    /// A quantidade de abas ou a aba ativa mudou.\n"
      "    fn on_tabs_changed(&self, _count: u32, _active: u32) {}\n")],
)
edit(
    "ports/servoshell/window.rs",
    [
        ("    #[cfg_attr(any(target_os = \"android\", target_env = \"ohos\"), expect(dead_code))]\n    pub(crate) fn activate_webview_by_index",
         "    pub(crate) fn activate_webview_by_index"),
        ("    #[cfg_attr(any(target_os = \"android\", target_env = \"ohos\"), expect(dead_code))]\n    pub(crate) fn get_active_webview_index",
         "    pub(crate) fn get_active_webview_index"),
    ],
)
edit(
    "ports/servoshell/egl/app.rs",
    [
        ("    current_load_status: Cell<Option<LoadStatus>>,\n",
         "    current_load_status: Cell<Option<LoadStatus>>,\n    current_tabs: Cell<(u32, u32)>,\n"),
        ("            current_load_status: Default::default(),\n",
         "            current_load_status: Default::default(),\n            current_tabs: Default::default(),\n"),
        ("        let new_title = active_webview.page_title();\n",
         "        let tabs_now = (\n            window.webview_ids().len() as u32,\n            window.get_active_webview_index().unwrap_or(0) as u32,\n        );\n"
         "        if tabs_now != self.current_tabs.get() {\n            self.current_tabs.set(tabs_now);\n            self.host.on_tabs_changed(tabs_now.0, tabs_now.1);\n        }\n\n"
         "        let new_title = active_webview.page_title();\n"),
        ("    /// The active webview will be immediately valid via `active_or_newest_webview()`\n",
         "    /// Abre uma aba nova (e a ativa).\n"
         "    pub fn new_tab(&self, location: &str) {\n"
         "        let url = Url::parse(location).unwrap_or_else(|_| Url::parse(\"about:blank\").unwrap());\n"
         "        self.window().create_and_activate_toplevel_webview(\n"
         "            self.state.clone(),\n            TopLevelWebViewCreationRequest::WithUrl(url),\n        );\n"
         "        self.spin_event_loop();\n    }\n\n"
         "    /// Troca para a aba de índice `index`.\n"
         "    pub fn switch_tab(&self, index: usize) {\n"
         "        self.window().activate_webview_by_index(index);\n        self.spin_event_loop();\n    }\n\n"
         "    /// Fecha a aba de índice `index` (nunca fecha a última).\n"
         "    pub fn close_tab(&self, index: usize) {\n"
         "        let window = self.window();\n        let ids = window.webview_ids();\n"
         "        if ids.len() > 1 {\n            if let Some(id) = ids.get(index) {\n                window.close_webview(*id);\n            }\n        }\n"
         "        self.spin_event_loop();\n    }\n\n"
         "    /// The active webview will be immediately valid via `active_or_newest_webview()`\n"),
    ],
)
edit(
    "ports/servoshell/egl/android/mod.rs",
    [
        ("#[unsafe(no_mangle)]\npub extern \"C\" fn Java_org_servo_servoview_JNIServo_setBlockTrackers<'local>(\n",
         "#[unsafe(no_mangle)]\n"
         "pub extern \"C\" fn Java_org_servo_servoview_JNIServo_newTab<'local>(\n"
         "    mut env: EnvUnowned<'local>,\n    _class: JClass<'local>,\n    url: JString<'local>,\n) {\n"
         "    env.with_env(|env| -> jni::errors::Result<_> {\n"
         "        call(env, |s| s.new_tab(&url.to_string()));\n        Ok(())\n    })\n"
         "    .resolve::<ThrowRuntimeExAndDefault>()\n}\n\n"
         "#[unsafe(no_mangle)]\n"
         "pub extern \"C\" fn Java_org_servo_servoview_JNIServo_switchTab<'local>(\n"
         "    mut env: EnvUnowned<'local>,\n    _class: JClass<'local>,\n    index: jint,\n) {\n"
         "    env.with_env(|env| -> jni::errors::Result<_> {\n"
         "        call(env, |s| s.switch_tab(index.max(0) as usize));\n        Ok(())\n    })\n"
         "    .resolve::<ThrowRuntimeExAndDefault>()\n}\n\n"
         "#[unsafe(no_mangle)]\n"
         "pub extern \"C\" fn Java_org_servo_servoview_JNIServo_closeTab<'local>(\n"
         "    mut env: EnvUnowned<'local>,\n    _class: JClass<'local>,\n    index: jint,\n) {\n"
         "    env.with_env(|env| -> jni::errors::Result<_> {\n"
         "        call(env, |s| s.close_tab(index.max(0) as usize));\n        Ok(())\n    })\n"
         "    .resolve::<ThrowRuntimeExAndDefault>()\n}\n\n"
         "#[unsafe(no_mangle)]\npub extern \"C\" fn Java_org_servo_servoview_JNIServo_setBlockTrackers<'local>(\n"),
        ("    fn on_blocked_count_changed(&self, count: u32) {\n",
         "    fn on_tabs_changed(&self, count: u32, active: u32) {\n"
         "        self.jvm\n            .attach_current_thread(|env| -> Result<(), Error> {\n"
         "                env.call_method(\n                    callback_ref(),\n"
         "                    jni_str!(\"onTabsChanged\"),\n"
         "                    jni_sig!(\"(II)V\"),\n"
         "                    &[JValue::Int(count as jint), JValue::Int(active as jint)],\n                )?;\n"
         "                Ok(())\n            })\n            .unwrap();\n    }\n\n"
         "    fn on_blocked_count_changed(&self, count: u32) {\n"),
    ],
)
base2 = "support/android/apk/servoview/src/main/java/org/servo/servoview"
edit(
    f"{base2}/JNIServo.kt",
    [
        ("    external fun setPageDarkMode(mode: Int)\n",
         "    external fun setPageDarkMode(mode: Int)\n\n    external fun newTab(url: String)\n\n"
         "    external fun switchTab(index: Int)\n\n    external fun closeTab(index: Int)\n"),
        ("        fun onBlockedCountChanged(count: Int)\n    }\n}",
         "        fun onBlockedCountChanged(count: Int)\n\n        fun onTabsChanged(count: Int, active: Int)\n    }\n}"),
    ],
)
edit(
    f"{base2}/Servo.kt",
    [
        ("    pageDarkMode: Int = 0,\n    args: String?,",
         "    pageDarkMode: Int = 0,\n    restoreUrls: List<String>? = null,\n    restoreActive: Int = 0,\n    args: String?,"),
        ("        var paused = false\n",
         "        var paused = false\n        var restoreDone = false\n"),
        ("                    resources.displayMetrics.density,\n                    surface,\n                )\n            } else {",
         "                    resources.displayMetrics.density,\n                    surface,\n                )\n"
         "                if (!restoreDone) {\n                    restoreDone = true\n"
         "                    if (restoreUrls != null) servo.restoreTabs(restoreUrls, restoreActive)\n                }\n            } else {"),
        ("        data object Stop : NavigationEvent\n    }",
         "        data object Stop : NavigationEvent\n\n        data class NewTab(val uri: String) : NavigationEvent\n\n"
         "        data class SwitchTab(val index: Int) : NavigationEvent\n\n        data class CloseTab(val index: Int) : NavigationEvent\n    }"),
        ("                NavigationEvent.Stop -> servo.stop()\n",
         "                NavigationEvent.Stop -> servo.stop()\n"
         "                is NavigationEvent.NewTab -> servo.newTab(navigationEvent.uri)\n"
         "                is NavigationEvent.SwitchTab -> servo.switchTab(navigationEvent.index)\n"
         "                is NavigationEvent.CloseTab -> servo.closeTab(navigationEvent.index)\n"),
        ("    fun stop() {\n        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Stop) }\n    }\n",
         "    fun stop() {\n        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Stop) }\n    }\n\n"
         "    fun newTab(uri: String) {\n        coroutineScope.launch { navigationEvents.emit(NavigationEvent.NewTab(uri)) }\n    }\n\n"
         "    fun switchTab(index: Int) {\n        coroutineScope.launch { navigationEvents.emit(NavigationEvent.SwitchTab(index)) }\n    }\n\n"
         "    fun closeTab(index: Int) {\n        coroutineScope.launch { navigationEvents.emit(NavigationEvent.CloseTab(index)) }\n    }\n"),
        ("    fun loadUri(uri: String) {\n        scope.launch(glDispatcher) { jni.loadUri(uri) }\n    }\n",
         "    fun loadUri(uri: String) {\n        scope.launch(glDispatcher) { jni.loadUri(uri) }\n    }\n\n"
         "    fun newTab(uri: String) {\n        scope.launch(glDispatcher) { jni.newTab(uri) }\n    }\n\n"
         "    fun switchTab(index: Int) {\n        scope.launch(glDispatcher) { jni.switchTab(index) }\n    }\n\n"
         "    fun closeTab(index: Int) {\n        scope.launch(glDispatcher) { jni.closeTab(index) }\n    }\n\n"
         "    internal fun restoreTabs(urls: List<String>, active: Int) {\n"
         "        scope.launch(glDispatcher) {\n"
         "            for (i in 1 until urls.size) jni.newTab(if (i == active) urls[i] else \"about:blank\")\n"
         "            jni.switchTab(active)\n        }\n    }\n"),
        ("        fun onBlockedCountChanged(count: Int) {}\n    }\n\n    private class Callbacks(",
         "        fun onBlockedCountChanged(count: Int) {}\n\n        fun onTabsChanged(count: Int, active: Int) {}\n    }\n\n    private class Callbacks("),
        ("        override fun onBlockedCountChanged(count: Int) {\n            scope.launch { client.onBlockedCountChanged(count) }\n        }\n\n",
         "        override fun onBlockedCountChanged(count: Int) {\n            scope.launch { client.onBlockedCountChanged(count) }\n        }\n\n"
         "        override fun onTabsChanged(count: Int, active: Int) {\n            scope.launch { client.onTabsChanged(count, active) }\n        }\n\n"),
    ],
)
edit(
    "support/android/apk/servoapp/src/main/AndroidManifest.xml",
    [('<activity android:name=".MainActivity"',
      '<activity android:name=".MainActivity"\n            android:windowSoftInputMode="adjustNothing"')],
)

# --- Diagnóstico de rede: mostra a causa real do erro "SendRequest" ---
import os, re
_done = False
for _rel in ["components/shared/net/lib.rs", "components/net_traits/lib.rs"]:
    _p = f"{root}/{_rel}"
    if not os.path.exists(_p):
        continue
    _s = open(_p, encoding="utf-8").read()
    _old = "let error_string = error.to_string();"
    if _s.count(_old) == 1:
        _new = (
            "let mut error_string = error.to_string();\n"
            "        {\n            let mut source = std::error::Error::source(error);\n"
            "            while let Some(cause) = source {\n"
            "                error_string.push_str(\" <- \");\n"
            "                error_string.push_str(&cause.to_string());\n"
            "                source = cause.source();\n            }\n        }"
        )
        open(_p, "w", encoding="utf-8").write(_s.replace(_old, _new))
        print("diagnóstico de rede aplicado em", _rel)
        _done = True
        break
if not _done:
    print("AVISO: não achei from_hyper_error; diagnóstico de rede não aplicado")

# --- Rede: só HTTP/1.1 (o HTTP/2 do Servo dá "stream error" no YouTube) ---
edit(
    "components/net/connector.rs",
    [("        .enable_http1()\n        .enable_http2()\n        .wrap_connector(ProxyConnector::new());",
      "        .enable_http1()\n        .wrap_connector(ProxyConnector::new());")],
)

# ======================= DOWNLOADS =======================
edit(
    "ports/servoshell/running_app_state.rs",
    [("    fn load_web_resource(&self, _webview: WebView, load: servo::WebResourceLoad) {\n",
      "    fn request_navigation(&self, _webview: WebView, request: servo::NavigationRequest) {\n"
      "        if crate::adblock::take_if_download(&request.url) {\n            request.deny();\n        } else {\n            request.allow();\n        }\n    }\n\n"
      "    fn load_web_resource(&self, _webview: WebView, load: servo::WebResourceLoad) {\n")],
)
edit(
    "ports/servoshell/egl/host_trait.rs",
    [("    fn on_tabs_changed(&self, _count: u32, _active: u32) {}\n",
      "    fn on_tabs_changed(&self, _count: u32, _active: u32) {}\n"
      "    /// Um link de arquivo foi aberto: o app deve baixá-lo.\n"
      "    fn on_download_requested(&self, _url: String) {}\n")],
)
edit(
    "ports/servoshell/egl/app.rs",
    [("            self.host.on_shutdown_complete()\n        }\n    }\n\n    /// Load an URL.",
      "            self.host.on_shutdown_complete()\n        }\n"
      "        for url in crate::adblock::drain_downloads() {\n            self.host.on_download_requested(url);\n        }\n    }\n\n    /// Load an URL.")],
)
edit(
    "ports/servoshell/egl/android/mod.rs",
    [("    fn on_tabs_changed(&self, count: u32, active: u32) {\n",
      "    fn on_download_requested(&self, url: String) {\n"
      "        self.jvm\n            .attach_current_thread(|env| -> Result<(), Error> {\n"
      "                let Ok(url_string) = new_string_as_jvalue(env, &url) else {\n                    return Ok(());\n                };\n"
      "                env.call_method(\n                    callback_ref(),\n"
      "                    jni_str!(\"onDownloadRequested\"),\n"
      "                    jni_sig!(\"(Ljava/lang/String;)V\"),\n"
      "                    &[(&url_string).into()],\n                )?;\n"
      "                Ok(())\n            })\n            .unwrap();\n    }\n\n"
      "    fn on_tabs_changed(&self, count: u32, active: u32) {\n")],
)
edit(
    f"{base2}/JNIServo.kt",
    [("        fun onTabsChanged(count: Int, active: Int)\n    }\n}",
      "        fun onTabsChanged(count: Int, active: Int)\n\n        fun onDownloadRequested(url: String)\n    }\n}")],
)
edit(
    f"{base2}/Servo.kt",
    [
        ("        fun onTabsChanged(count: Int, active: Int) {}\n    }\n\n    private class Callbacks(",
         "        fun onTabsChanged(count: Int, active: Int) {}\n\n        fun onDownloadRequested(url: String) {}\n    }\n\n    private class Callbacks("),
        ("        override fun onTabsChanged(count: Int, active: Int) {\n            scope.launch { client.onTabsChanged(count, active) }\n        }\n\n",
         "        override fun onTabsChanged(count: Int, active: Int) {\n            scope.launch { client.onTabsChanged(count, active) }\n        }\n\n"
         "        override fun onDownloadRequested(url: String) {\n            scope.launch { client.onDownloadRequested(url) }\n        }\n\n"),
    ],
)
edit(
    "support/android/apk/servoapp/src/main/AndroidManifest.xml",
    [('        <activity android:name=".HistoryActivity"',
      '        <activity android:name=".DownloadsActivity"\n            android:configChanges="density|keyboardHidden|navigation|orientation|screenSize|uiMode"\n            android:exported="true"\n            android:theme="@style/AppTheme">\n        </activity>\n        <activity android:name=".HistoryActivity"')],
)
# --- Preferências (só o que não pesa): editores de texto e histórico menor ---
edit("ports/servoshell/egl/android/mod.rs", [(
    '        preferences.set_value("viewport_meta_enabled", servo::PrefValue::Bool(true));\n',
    '        preferences.set_value("viewport_meta_enabled", servo::PrefValue::Bool(true));\n'
    '        preferences.set_value("dom_exec_command_enabled", servo::PrefValue::Bool(true));\n'
    '        preferences.set_value("session_history_max_length", servo::PrefValue::Int(10));\n',
)])
print("preferências aplicadas")

print("patch ok")
