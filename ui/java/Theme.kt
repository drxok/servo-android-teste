package org.servo.servoshell

import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager

object Prefs {
    const val THEME = "theme_mode" // system | light | dark
    const val ACCENT = "accent" // lilac | blue | green | pink | orange
    const val ENGINE = "engine" // ddg | google | bing
    const val HTTPS_ONLY = "https_only"
    const val BLOCK_TRACKERS = "block_trackers"
    const val PAGE_DARK = "page_dark" // off | auto | force
    const val HOME_MODE = "home_mode" // ddg | start | custom
    const val HOME_CUSTOM = "home_custom"
}

class Accent(
    val id: String,
    val label: String,
    val primary: Long,
    val container: Long,
    val onContainer: Long,
    val lightBg: Long,
    val darkPrimary: Long,
    val darkContainer: Long,
    val darkBg: Long,
)

val ACCENTS =
    listOf(
        Accent("lilac", "Lilás", 0xFF5B3FE0, 0xFFE6E0FF, 0xFF35209E, 0xFFFBFAFF, 0xFFA592FF, 0xFF3A2F8F, 0xFF0E0B2C),
        Accent("blue", "Azul", 0xFF1F6FEB, 0xFFDCE8FF, 0xFF0B3A8F, 0xFFF9FBFF, 0xFF8AB4FF, 0xFF1F3A73, 0xFF0A1226),
        Accent("green", "Verde", 0xFF1E8E5A, 0xFFD5F3E3, 0xFF0B4D2E, 0xFFF7FCF9, 0xFF6FD6A0, 0xFF1B4A33, 0xFF08140E),
        Accent("pink", "Rosa", 0xFFD6336C, 0xFFFFE0EB, 0xFF8A1340, 0xFFFFF9FB, 0xFFFF8FB5, 0xFF5C1F3A, 0xFF160A10),
        Accent("orange", "Laranja", 0xFFE8590C, 0xFFFFE5D4, 0xFF8A3305, 0xFFFFFAF6, 0xFFFFA066, 0xFF5C2D12, 0xFF150D07),
    )

fun accentById(id: String): Accent = ACCENTS.firstOrNull { it.id == id } ?: ACCENTS[0]

@Composable
fun rememberStringPref(key: String, default: String): State<String> {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val state = remember { mutableStateOf(prefs.getString(key, default) ?: default) }
    DisposableEffect(prefs, key) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
                if (k == key) state.value = p.getString(key, default) ?: default
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
fun rememberBoolPref(key: String, default: Boolean): State<Boolean> {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val state = remember { mutableStateOf(prefs.getBoolean(key, default)) }
    DisposableEffect(prefs, key) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
                if (k == key) state.value = p.getBoolean(key, default)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
fun BrowserTheme(content: @Composable () -> Unit) {
    val mode by rememberStringPref(Prefs.THEME, "system")
    val accentId by rememberStringPref(Prefs.ACCENT, "lilac")
    val dark =
        when (mode) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val a = accentById(accentId)
    val scheme =
        if (dark) {
            val bg = Color(a.darkBg)
            val container = Color(a.darkContainer)
            val surface = lerp(bg, container, 0.45f)
            darkColorScheme(
                primary = Color(a.darkPrimary),
                onPrimary = bg,
                primaryContainer = container,
                onPrimaryContainer = Color(0xFFEDE8FF),
                secondaryContainer = container,
                onSecondaryContainer = Color(0xFFEDE8FF),
                background = bg,
                onBackground = Color(0xFFF4F1FF),
                surface = surface,
                onSurface = Color(0xFFF4F1FF),
                surfaceVariant = container,
                onSurfaceVariant = Color(0xFFB9B3DC),
                surfaceContainer = surface,
                surfaceContainerHigh = lerp(surface, container, 0.5f),
                outlineVariant = Color(0x24FFFFFF),
            )
        } else {
            lightColorScheme(
                primary = Color(a.primary),
                onPrimary = Color.White,
                primaryContainer = Color(a.container),
                onPrimaryContainer = Color(a.onContainer),
                secondaryContainer = Color(a.container),
                onSecondaryContainer = Color(a.onContainer),
                background = Color(a.lightBg),
                onBackground = Color(0xFF17142E),
                surface = Color.White,
                onSurface = Color(0xFF17142E),
                surfaceVariant = Color(a.container),
                onSurfaceVariant = Color(0xFF5C5778),
                surfaceContainer = Color.White,
                surfaceContainerHigh = Color(a.container),
                outlineVariant = Color(a.primary).copy(alpha = 0.14f),
            )
        }

    val context = LocalContext.current
    LaunchedEffect(dark) {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) {
                    dark
                },
            navigationBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) {
                    dark
                },
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

/** Turns what the user typed into an address (or a search) and applies HTTPS-only. */
fun resolveInput(raw: String, engine: String, httpsOnly: Boolean): String {
    val text = raw.trim()
    if (text.isEmpty()) return text
    val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)
    val looksLikeHost = !text.contains(' ') && (text.contains('.') || text.startsWith("localhost"))
    if (hasScheme || text.startsWith("data:") || text.startsWith("about:")) {
        return if (httpsOnly && text.startsWith("http://")) "https://" + text.removePrefix("http://")
        else text
    }
    if (looksLikeHost) return "https://$text"
    val q = java.net.URLEncoder.encode(text, "UTF-8")
    return when (engine) {
        "google" -> "https://www.google.com/search?q=$q"
        "bing" -> "https://www.bing.com/search?q=$q"
        else -> "https://duckduckgo.com/?q=$q"
    }
}

/** Molas no estilo Material 3 Expressive (valores do esquema "expressive"). */
object Motion {
    fun <T> spatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> fastSpatial() = spring<T>(dampingRatio = 0.6f, stiffness = 800f)

    fun <T> effects() = spring<T>(dampingRatio = 1f, stiffness = 1600f)

    fun <T> fastEffects() = spring<T>(dampingRatio = 1f, stiffness = 3800f)
}

object Stats {
    var blocked by mutableIntStateOf(0)
}

private fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

/** Tela inicial (data: URL) com as cores do tema atual. */
fun startPageUrl(
    engine: String,
    background: Int,
    onBackground: Int,
    primary: Int,
    container: Int,
    muted: Int,
): String {
    val (action, field) =
        when (engine) {
            "google" -> "https://www.google.com/search" to "q"
            "bing" -> "https://www.bing.com/search" to "q"
            else -> "https://duckduckgo.com/" to "q"
        }
    val tiles =
        listOf(
            "Notícias" to "https://g1.globo.com/",
            "Vídeos" to "https://www.youtube.com/",
            "Mapas" to "https://www.openstreetmap.org/",
            "Clima" to "https://www.climatempo.com.br/",
            "Wikipédia" to "https://pt.wikipedia.org/",
            "E-mail" to "https://mail.proton.me/",
            "GitHub" to "https://github.com/",
            "Tradutor" to "https://translate.google.com/",
        )
    val tileHtml =
        tiles.joinToString("") { (name, url) ->
            "<a class=t href=\"$url\"><b>${name.first()}</b><span>$name</span></a>"
        }
    val html =
        """<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Início</title>
<style>
body{margin:0;padding:28px 20px 40px;background:${hex(background)};color:${hex(onBackground)};font-family:sans-serif}
h1{font-size:30px;margin:8px 0 20px}
form{margin:0 0 24px}
input{width:100%;box-sizing:border-box;height:52px;border:0;border-radius:26px;padding:0 20px;font-size:17px;background:${hex(container)};color:${hex(onBackground)}}
.g{display:flex;flex-wrap:wrap}
.t{width:25%;box-sizing:border-box;padding:0 4px 18px;text-align:center;text-decoration:none;color:${hex(onBackground)};font-size:12px}
.t b{display:block;width:56px;height:56px;line-height:56px;margin:0 auto 8px;border-radius:20px;background:${hex(container)};color:${hex(primary)};font-size:22px}
.s{margin-top:8px;padding:16px;border-radius:24px;background:${hex(container)};font-size:14px;color:${hex(muted)}}
.s strong{color:${hex(primary)};display:block;font-size:16px;margin-bottom:4px}
</style></head><body>
<h1>Início</h1>
<form action="$action" method="get"><input name="$field" placeholder="Pesquisar na web" autocomplete="off"></form>
<div class="g">$tileHtml</div>
<div class="s"><strong>Proteção ativa</strong>Somente HTTPS e bloqueio de rastreadores conhecidos.</div>
</body></html>"""
    return "data:text/html;charset=utf-8," + android.net.Uri.encode(html)
}
