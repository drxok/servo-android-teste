package br.xok.fps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/** Mostra a bolinha com FPS e estatísticas por cima dos jogos. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var view: TextView? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private val ui = Handler(Looper.getMainLooper())
    private var sampler: Sampler? = null
    private var running = false

    // estatísticas da sessão
    private var startMs = 0L
    private var fpsSum = 0L
    private var fpsCount = 0
    private var fpsMin = Int.MAX_VALUE
    private var fpsMax = 0
    private var sessionPkg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) start()
        return START_NOT_STICKY
    }

    private fun start() {
        running = true
        active = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("fps", "Contador de FPS", NotificationManager.IMPORTANCE_LOW))
        val stop =
            PendingIntent.getService(
                this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
        val open =
            PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n =
            Notification.Builder(this, "fps")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Xok FPS ligado")
                .setContentText("Toque para abrir. Use Parar para desligar.")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stop).build())
                .setOngoing(true)
                .build()
        startForeground(1, n)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.typeface = Typeface.MONOSPACE
        tv.setPadding(22, 12, 22, 12)
        tv.text = "Xok FPS\naguardando…"
        val bg = GradientDrawable()
        bg.setColor(Color.argb(170, 0, 0, 0))
        bg.cornerRadius = 28f
        tv.background = bg
        applySize(tv)
        lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Cfg.x(this)
        lp.y = Cfg.y(this)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(tv, lp)
                }
                MotionEvent.ACTION_UP -> Cfg.setPos(this, lp.x, lp.y)
            }
            true
        }
        try {
            wm.addView(tv, lp)
            view = tv
        } catch (e: Exception) {
            stopSelf()
            return
        }

        startMs = System.currentTimeMillis()
        sampler = Sampler(this)
        thread = HandlerThread("fps-sampler").also { it.start() }
        worker = Handler(thread!!.looper)
        worker!!.post(tick)
    }

    private fun applySize(tv: TextView) {
        tv.textSize = when (Cfg.size(this)) {
            0 -> 11f
            2 -> 16f
            else -> 13f
        }
    }

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                val started = System.currentTimeMillis()
                val s = sampler?.sample(Cfg.target(this@OverlayService).ifEmpty { null })
                if (s != null) {
                    if (s.pkg != null) sessionPkg = s.pkg
                    if (s.fps > 0) {
                        fpsSum += s.fps
                        fpsCount++
                        if (s.fps < fpsMin) fpsMin = s.fps
                        if (s.fps > fpsMax) fpsMax = s.fps
                    }
                    ui.post { show(s) }
                }
                val spent = System.currentTimeMillis() - started
                worker?.postDelayed(this, (1000 - spent).coerceAtLeast(200))
            }
        }

    private fun show(s: Sample) {
        val tv = view ?: return
        applySize(tv)
        val sb = StringBuilder()
        if (s.pkg == null) {
            sb.append("Abra um jogo…")
        } else {
            sb.append("${s.fps} FPS")
            if (s.low1 > 0) sb.append("  1%: ${s.low1}")
            val l2 = ArrayList<String>()
            if (Cfg.flag(this, "cpu")) l2.add("CPU ${s.cpu}%" + if (s.gameCpu > 0) " (jogo ${s.gameCpu}%)" else "")
            if (Cfg.flag(this, "gpu") && s.gpu > 0) l2.add("GPU ${s.gpu}%")
            if (l2.isNotEmpty()) sb.append("\n").append(l2.joinToString("  "))
            val l3 = ArrayList<String>()
            if (Cfg.flag(this, "ram")) {
                var r = "RAM ${"%.1f".format(s.ramUsedMb / 1024f)}/${"%.1f".format(s.ramTotalMb / 1024f)}G"
                if (s.gameRamMb > 0) r += " (jogo ${s.gameRamMb}M)"
                l3.add(r)
            }
            if (Cfg.flag(this, "temp")) l3.add("${"%.0f".format(s.tempC)}°C")
            if (l3.isNotEmpty()) sb.append("\n").append(l3.joinToString("  "))
        }
        tv.text = sb.toString()
    }

    override fun onDestroy() {
        running = false
        active = false
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        try {
            view?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        view = null
        val seconds = ((System.currentTimeMillis() - startMs) / 1000).toInt()
        val pkg = sessionPkg
        if (pkg != null && fpsCount >= 5) {
            val low1 =
                sampler?.all?.percentile(0.99)?.let { if (it > 0) Math.round(1e9 / it).toInt() else 0 } ?: 0
            val label =
                try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
            Sessions.add(
                this,
                Session(pkg, label, startMs, seconds, (fpsSum / fpsCount).toInt(), fpsMin, fpsMax, low1),
            )
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "br.xok.fps.STOP"
        @Volatile var active = false
    }
}
