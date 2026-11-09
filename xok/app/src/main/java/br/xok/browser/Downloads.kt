package br.xok.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class DownloadItem(
    val id: Long,
    val name: String,
    val status: Int,
    val total: Long,
    val done: Long,
    val mime: String,
)

object DownloadsStore {
    private const val PREFS = "downloads"
    private const val KEY = "ids"

    private fun ids(context: Context): List<Long> =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: "")
            .split(',')
            .mapNotNull { it.toLongOrNull() }

    private fun saveIds(context: Context, ids: List<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, ids.joinToString(",")).apply()
    }

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mime: String? = null,
    ) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val request =
                DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    .setAllowedOverMetered(true)
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            android.webkit.CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (!mime.isNullOrEmpty()) request.setMimeType(mime)
            val id = dm.enqueue(request)
            saveIds(context, listOf(id) + ids(context))
            Toast.makeText(context, "Baixando $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Não consegui baixar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun query(context: Context): List<DownloadItem> {
        val list = ids(context)
        if (list.isEmpty()) return emptyList()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val found = HashMap<Long, DownloadItem>()
        dm.query(DownloadManager.Query().setFilterById(*list.toLongArray()))?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                found[id] =
                    DownloadItem(
                        id = id,
                        name = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "arquivo",
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)) ?: "*/*",
                    )
            }
        }
        return list.mapNotNull { found[it] }
    }

    fun remove(context: Context, id: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(id)
        saveIds(context, ids(context).filter { it != id })
    }

    fun open(context: Context, item: DownloadItem) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(item.id) ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, item.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Nenhum app para abrir este arquivo", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun sizeText(bytes: Long): String =
    when {
        bytes <= 0 -> "?"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(context: Context, onBack: () -> Unit) {
    var items by remember { mutableStateOf(DownloadsStore.query(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            items = DownloadsStore.query(context)
            delay(1000)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back), "Voltar", modifier = Modifier.size(22.dp))
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.download), null, modifier = Modifier.size(40.dp))
                Text(
                    "Nenhum download ainda.\nAo abrir um link de arquivo (APK, ZIP, PDF, vídeo…), ele aparece aqui.",
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(items, key = { it.id }) { item ->
                    val running = item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING || item.status == DownloadManager.STATUS_PAUSED
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = item.status == DownloadManager.STATUS_SUCCESSFUL) { DownloadsStore.open(context, item) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            val info =
                                when (item.status) {
                                    DownloadManager.STATUS_SUCCESSFUL -> "Concluído · ${sizeText(item.total)}"
                                    DownloadManager.STATUS_FAILED -> "Falhou"
                                    DownloadManager.STATUS_PAUSED -> "Pausado · ${sizeText(item.done)} de ${sizeText(item.total)}"
                                    DownloadManager.STATUS_PENDING -> "Na fila"
                                    else -> "${sizeText(item.done)} de ${sizeText(item.total)}"
                                }
                            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (running) {
                                if (item.total > 0) {
                                    LinearProgressIndicator(
                                        progress = { (item.done.toFloat() / item.total).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    )
                                } else {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                            }
                        }
                        IconButton(onClick = {
                            DownloadsStore.remove(context, item.id)
                            items = DownloadsStore.query(context)
                        }) {
                            Icon(painterResource(R.drawable.cancel), if (running) "Cancelar" else "Remover", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
