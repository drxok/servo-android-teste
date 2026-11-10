package br.xok.fps

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * Um shell que fica aberto para rodar vários comandos: usa root (su) se existir
 * e, se não, o Shizuku (shell do ADB).
 */
object RootShell {
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** "root", "Shizuku" ou "nenhum". */
    @Volatile var label = "nenhum"
        private set

    fun shizukuRunning(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }

    fun shizukuGranted(): Boolean =
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }

    fun requestShizuku() {
        try {
            Shizuku.requestPermission(1001)
        } catch (e: Throwable) {
        }
    }

    private fun newShizukuProcess(): Process {
        val m =
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            )
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh"), null, null) as Process
    }

    /** Abre o processo e confere quem somos (uid 0 = root, 2000 = shell do Shizuku). */
    private fun open(shizuku: Boolean): Boolean {
        return try {
            val p = if (shizuku) newShizukuProcess() else ProcessBuilder("su").redirectErrorStream(true).start()
            proc = p
            writer = p.outputStream.bufferedWriter()
            reader = p.inputStream.bufferedReader()
            val out = raw("exec 2>&1\nid -u")
            val uid = out.trim()
            val ok = if (shizuku) uid == "0" || uid == "2000" else uid == "0"
            if (ok) {
                label = if (shizuku) "Shizuku" else "root"
            } else {
                close()
            }
            ok
        } catch (e: Throwable) {
            close()
            false
        }
    }

    private fun raw(cmd: String): String {
        val w = writer!!
        val r = reader!!
        w.write(cmd + "\necho __END__\n")
        w.flush()
        val sb = StringBuilder()
        while (true) {
            val line = r.readLine() ?: break
            if (line == "__END__") break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    @Synchronized
    private fun connect(): Boolean {
        if (proc?.isAlive == true) return true
        if (open(false)) return true
        if (shizukuRunning() && shizukuGranted() && open(true)) return true
        label = "nenhum"
        return false
    }

    @Synchronized
    fun run(cmd: String): String {
        return try {
            if (!connect()) return ""
            raw(cmd)
        } catch (e: Exception) {
            close()
            ""
        }
    }

    @Synchronized
    fun available(): Boolean {
        val uid = run("id -u").trim()
        return uid == "0" || uid == "2000"
    }

    @Synchronized
    fun close() {
        try {
            proc?.destroy()
        } catch (_: Throwable) {
        }
        proc = null
        writer = null
        reader = null
    }
}
