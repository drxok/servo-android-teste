package br.xok.fps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Cfg {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun target(c: Context) = p(c).getString("target", "") ?: ""
    fun setTarget(c: Context, v: String) = p(c).edit().putString("target", v).apply()
    fun flag(c: Context, k: String, d: Boolean = true) = p(c).getBoolean(k, d)
    fun setFlag(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun size(c: Context) = p(c).getInt("size", 1)
    fun setSize(c: Context, v: Int) = p(c).edit().putInt("size", v).apply()
    fun x(c: Context) = p(c).getInt("x", 24)
    fun y(c: Context) = p(c).getInt("y", 120)
    fun setPos(c: Context, x: Int, y: Int) = p(c).edit().putInt("x", x).putInt("y", y).apply()
}

data class Session(
    val pkg: String,
    val label: String,
    val start: Long,
    val seconds: Int,
    val avg: Int,
    val min: Int,
    val max: Int,
    val low1: Int,
)

object Sessions {
    private fun file(c: Context) = File(c.filesDir, "sessions.json")

    fun load(c: Context): List<Session> =
        try {
            val arr = JSONArray(file(c).readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Session(
                    o.getString("pkg"), o.getString("label"), o.getLong("start"), o.getInt("seconds"),
                    o.getInt("avg"), o.getInt("min"), o.getInt("max"), o.getInt("low1"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

    fun add(c: Context, s: Session) {
        val list = (listOf(s) + load(c)).take(30)
        val arr = JSONArray()
        for (x in list) {
            arr.put(
                JSONObject()
                    .put("pkg", x.pkg).put("label", x.label).put("start", x.start).put("seconds", x.seconds)
                    .put("avg", x.avg).put("min", x.min).put("max", x.max).put("low1", x.low1)
            )
        }
        file(c).writeText(arr.toString())
    }

    fun clear(c: Context) {
        file(c).delete()
    }
}
