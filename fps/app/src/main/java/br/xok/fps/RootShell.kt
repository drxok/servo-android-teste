package br.xok.fps

import java.io.BufferedReader
import java.io.BufferedWriter

/** Um shell de root que fica aberto, para rodar vários comandos sem pedir permissão de novo. */
object RootShell {
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Synchronized
    private fun ensure() {
        if (proc?.isAlive == true) return
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        proc = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()
    }

    @Synchronized
    fun run(cmd: String): String {
        return try {
            ensure()
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
            sb.toString()
        } catch (e: Exception) {
            close()
            ""
        }
    }

    @Synchronized
    fun available(): Boolean = run("id -u").trim() == "0"

    @Synchronized
    fun close() {
        try {
            proc?.destroy()
        } catch (_: Exception) {
        }
        proc = null
        writer = null
        reader = null
    }
}
