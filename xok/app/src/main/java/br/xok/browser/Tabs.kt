package br.xok.browser

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Uma aba. `url` vazio = página inicial. `pending` = ainda não carregada (economiza memória). */
data class Tab(val id: Long, val url: String, val title: String, val pending: Boolean = false)

/** Lista de abas e aba ativa, guardadas no aparelho para continuar de onde parou. */
object Tabs {
    val list = mutableStateListOf<Tab>()
    var active by mutableIntStateOf(0)
    private var nextId = 1L

    fun newId() = nextId++

    fun load(context: Context) {
        list.clear()
        try {
            val prefs = context.getSharedPreferences("tabs", Context.MODE_PRIVATE)
            val json = JSONObject(prefs.getString("state", null) ?: "{}")
            val arr = json.optJSONArray("tabs") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Tab(newId(), o.optString("u"), o.optString("t")))
            }
            active = json.optInt("active", 0)
        } catch (e: Exception) {
            list.clear()
        }
        if (list.isEmpty()) list.add(Tab(newId(), "", "Nova aba"))
        active = active.coerceIn(0, list.size - 1)
        markPending()
    }

    fun markPending() {
        for (i in list.indices) list[i] = list[i].copy(pending = i != active)
    }

    fun save(context: Context) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
            val json = JSONObject().put("active", active).put("tabs", arr)
            context.getSharedPreferences("tabs", Context.MODE_PRIVATE).edit().putString("state", json.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun update(index: Int, change: (Tab) -> Tab) {
        if (index in list.indices) list[index] = change(list[index])
    }
}

private fun hostOf(url: String): String {
    if (url.isEmpty()) return "Página inicial"
    return try {
        android.net.Uri.parse(url).host?.removePrefix("www.") ?: url
    } catch (e: Exception) {
        url
    }
}

@Composable
fun TabsScreen(
    tabs: List<Tab>,
    active: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } },
        color = cs.background,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (tabs.size == 1) "1 aba" else "${tabs.size} abas",
                    style = MaterialTheme.typography.headlineSmall,
                    color = cs.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDone) { Text("Concluído") }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(tabs, key = { _, t -> t.id }) { index, tab ->
                    val isActive = index == active
                    Surface(
                        modifier = Modifier.fillMaxWidth().animateItem().clickable { onSelect(index) },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isActive) cs.secondaryContainer else cs.surfaceVariant,
                        border = if (isActive) BorderStroke(1.5.dp, cs.primary) else BorderStroke(0.5.dp, cs.outlineVariant),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(24.dp).background(cs.primary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        hostOf(tab.url).take(1).uppercase(),
                                        color = cs.onPrimary,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Text(
                                    if (tab.title.isEmpty()) hostOf(tab.url) else tab.title,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.onSurface,
                                )
                                IconButton(onClick = { onClose(index) }, modifier = Modifier.size(28.dp)) {
                                    Icon(painterResource(R.drawable.cancel), "Fechar aba", modifier = Modifier.size(16.dp))
                                }
                            }
                            Box(
                                Modifier.fillMaxWidth().padding(top = 10.dp).height(96.dp)
                                    .background(cs.background.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    hostOf(tab.url),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                FilledTonalButton(onClick = onNew, modifier = Modifier.height(48.dp)) {
                    Icon(painterResource(R.drawable.add), null, modifier = Modifier.size(20.dp))
                    Text("  Nova aba")
                }
            }
        }
    }
}
