package br.xok.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Guarda o erro que fechou o app e o mostra na próxima abertura, para dar para copiar e enviar. */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "crash.txt")

    private fun header(): String =
        "Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\n\n"

    fun save(context: Context, error: Throwable) {
        try {
            file(context).writeText(header() + android.util.Log.getStackTraceString(error))
        } catch (_: Exception) {
        }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            save(app, error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        try {
            file(context).takeIf { it.exists() }?.readText()
        } catch (_: Exception) {
            null
        }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
    }

    fun show(activity: Activity, text: String) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)
        val title = TextView(activity)
        title.text = "O Xok encontrou um erro"
        title.textSize = 20f
        root.addView(title)
        val body = TextView(activity)
        body.text = text
        body.textSize = 11f
        body.setTextIsSelectable(true)
        val scroll = ScrollView(activity)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val copy = Button(activity)
        copy.text = "Copiar erro"
        copy.setOnClickListener {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("erro", text))
        }
        root.addView(copy)
        val retry = Button(activity)
        retry.text = "Limpar e tentar de novo"
        retry.setOnClickListener {
            clear(activity)
            activity.recreate()
        }
        root.addView(retry)
        activity.setContentView(root)
    }
}
