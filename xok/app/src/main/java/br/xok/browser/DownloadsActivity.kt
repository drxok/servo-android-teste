package br.xok.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class DownloadsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BrowserTheme { DownloadsScreen(this@DownloadsActivity) { finish() } } }
    }
}
