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
print("patch ok")
