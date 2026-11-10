package br.xok.fps

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var history: TextView
    private var rootOk = false
    private lateinit var shizukuBtn: Button
    private val apps = ArrayList<Pair<String, String>>() // rótulo, pacote

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f, color: Int = Color.WHITE): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.setPadding(0, dp(6), 0, dp(6))
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(36), dp(18), dp(24))
        root.setBackgroundColor(Color.rgb(24, 20, 36))

        root.addView(text("Xok FPS", 26f))
        status = text("Verificando root…", 14f, Color.rgb(190, 180, 220))
        root.addView(status)

        toggle = Button(this)
        toggle.setOnClickListener { onToggle() }
        root.addView(toggle)

        val perm = Button(this)
        perm.text = "Permitir sobreposição (automático)"
        perm.setOnClickListener { grantOverlay() }
        root.addView(perm)

        shizukuBtn = Button(this)
        shizukuBtn.text = "Conceder acesso ao Shizuku"
        shizukuBtn.setOnClickListener {
            if (!RootShell.shizukuRunning()) {
                Toast.makeText(this, "Abra o app Shizuku e inicie o serviço primeiro", Toast.LENGTH_LONG).show()
            } else {
                RootShell.requestShizuku()
            }
        }
        root.addView(shizukuBtn)

        val diag = Button(this)
        diag.text = "Diagnóstico (se o FPS não aparecer)"
        diag.setOnClickListener { runDiagnosis() }
        root.addView(diag)

        root.addView(text("Jogo medido", 16f))
        val spinner = Spinner(this)
        root.addView(spinner)
        loadApps(spinner)

        root.addView(text("Mostrar na bolinha", 16f))
        for ((k, label) in listOf("cpu" to "CPU", "gpu" to "GPU", "ram" to "RAM", "temp" to "Temperatura")) {
            val cb = CheckBox(this)
            cb.text = label
            cb.setTextColor(Color.WHITE)
            cb.isChecked = Cfg.flag(this, k)
            cb.setOnCheckedChangeListener { _, v -> Cfg.setFlag(this, k, v) }
            root.addView(cb)
        }

        root.addView(text("Tamanho do texto", 16f))
        val group = RadioGroup(this)
        group.orientation = RadioGroup.HORIZONTAL
        for ((i, label) in listOf("Pequeno", "Médio", "Grande").withIndex()) {
            val rb = RadioButton(this)
            rb.id = 100 + i
            rb.text = label
            rb.setTextColor(Color.WHITE)
            group.addView(rb)
        }
        group.check(100 + Cfg.size(this))
        group.setOnCheckedChangeListener { _, id -> Cfg.setSize(this, id - 100) }
        root.addView(group)

        root.addView(text("Últimas sessões", 16f))
        history = text("", 13f, Color.rgb(210, 205, 230))
        root.addView(history)
        val clear = Button(this)
        clear.text = "Apagar histórico"
        clear.setOnClickListener {
            Sessions.clear(this)
            refresh()
        }
        root.addView(clear)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(24, 20, 36))
        scroll.addView(root)
        setContentView(scroll)

        Thread {
            rootOk = RootShell.available()
            runOnUiThread { refresh() }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Thread {
            val ok = RootShell.available()
            runOnUiThread {
                rootOk = ok
                refresh()
            }
        }.start()
    }

    private fun loadApps(spinner: Spinner) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list =
            pm.queryIntentActivities(intent, 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .filter { it.second != packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        apps.clear()
        apps.add("Automático (app em primeiro plano)" to "")
        apps.addAll(list)
        spinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps.map { it.first })
        val current = Cfg.target(this)
        spinner.setSelection(apps.indexOfFirst { it.second == current }.coerceAtLeast(0))
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    Cfg.setTarget(this@MainActivity, apps[pos].second)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        status.text =
            "Acesso: " + (if (rootOk) "ok (${RootShell.label})" else "nenhum — permita o root no Magisk ou conceda o Shizuku") +
                "\nSobreposição: " + (if (overlay) "permitida" else "falta permitir")
        shizukuBtn.visibility = if (rootOk) View.GONE else View.VISIBLE
        toggle.text = if (OverlayService.active) "Parar contador" else "Iniciar contador"
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val list = Sessions.load(this)
        history.text =
            if (list.isEmpty()) "Nenhuma sessão ainda."
            else
                list.joinToString("\n\n") {
                    "${fmt.format(Date(it.start))}  ${it.label}\n" +
                        "média ${it.avg} · mín ${it.min} · máx ${it.max} · 1% ${it.low1} · ${it.seconds / 60} min ${it.seconds % 60}s"
                }
    }

    private fun onToggle() {
        if (OverlayService.active) {
            stopService(Intent(this, OverlayService::class.java))
            toggle.postDelayed({ refresh() }, 400)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            grantOverlay()
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        toggle.postDelayed({ refresh() }, 400)
    }

    private fun runDiagnosis() {
        Toast.makeText(this, "Coletando… abra o jogo em outra tela e volte", Toast.LENGTH_SHORT).show()
        Thread {
            val out =
                if (!rootOk) "Sem acesso (root ou Shizuku)."
                else {
                    try {
                        Sampler(this).diagnose(Cfg.target(this).ifEmpty { null })
                    } catch (e: Exception) {
                        "Erro: $e"
                    }
                }
            runOnUiThread {
                val tv = TextView(this)
                tv.text = out
                tv.textSize = 11f
                tv.setPadding(dp(16), dp(12), dp(16), dp(12))
                tv.setTextIsSelectable(true)
                val sv = ScrollView(this)
                sv.addView(tv)
                AlertDialog.Builder(this)
                    .setTitle("Diagnóstico")
                    .setView(sv)
                    .setPositiveButton("Copiar") { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("diagnóstico", out))
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
            }
        }.start()
    }

    private fun grantOverlay() {
        Thread {
            if (rootOk) RootShell.run("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            runOnUiThread {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                refresh()
            }
        }.start()
    }
}
