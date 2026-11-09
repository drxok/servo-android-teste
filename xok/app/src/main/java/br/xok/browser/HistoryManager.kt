package br.xok.browser

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class HistoryEntry(val timestamp: Long, val url: String, val title: String?)

sealed interface HistoryItem

class HistoryHeaderItem(val headerText: String) : HistoryItem

class HistoryEntryItem(val entry: HistoryEntry) : HistoryItem

/** Histórico de páginas visitadas, guardado num arquivo JSON do app. */
class HistoryManager(context: Context) {
    private val file = File(context.filesDir, "history.json")

    val history: List<HistoryEntry>
        get() = load()

    fun addEntry(url: String, title: String?) {
        if (url.startsWith("about:") || url.startsWith("data:")) return
        val list = load().toMutableList()
        if (list.isNotEmpty() && list.first().url == url) return
        list.add(0, HistoryEntry(System.currentTimeMillis(), url, title))
        save(list.take(1000))
    }

    fun clearHistory() {
        if (file.exists()) file.delete()
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryEntry(o.getLong("t"), o.getString("u"), o.optString("n"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<HistoryEntry>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("t", it.timestamp).put("u", it.url).put("n", it.title ?: "")) }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
