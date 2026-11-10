package br.xok.fps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class Sample(
    val pkg: String?,
    val fps: Int,
    val low1: Int,
    val cpu: Int,
    val gameCpu: Int,
    val gpu: Int,
    val ramUsedMb: Int,
    val ramTotalMb: Int,
    val gameRamMb: Int,
    val tempC: Float,
)

/** Lê FPS (via SurfaceFlinger), CPU, GPU, RAM e temperatura usando root. */
class Sampler(private val context: Context) {
    private var layer: String? = null
    private var layerPkg: String? = null
    private var prevNewest = 0L
    private val intervals = ArrayDeque<Long>()
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var lastProcTicks = 0L
    private var lastPid = ""
    private var lastForegroundAt = 0L
    private var foreground: String? = null

    /** Todos os intervalos entre quadros da sessão, para o 1% low geral. */
    val all = LongList()

    private fun foregroundPackage(fixed: String?): String? {
        if (!fixed.isNullOrEmpty()) return fixed
        val now = System.currentTimeMillis()
        if (now - lastForegroundAt < 1500 && foreground != null) return foreground
        lastForegroundAt = now
        val out = RootShell.run("dumpsys window | grep mCurrentFocus")
        val m = Regex("u\\d+ ([A-Za-z0-9_.]+)/").find(out)
        var pkg = m?.groupValues?.get(1)
        if (pkg == null) {
            val out2 = RootShell.run("dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity' | head -1")
            pkg = Regex("u\\d+ ([A-Za-z0-9_.]+)/").find(out2)?.groupValues?.get(1)
        }
        if (pkg != null && pkg != context.packageName && pkg != "com.android.systemui" && !pkg.contains("launcher")) {
            foreground = pkg
        }
        return foreground
    }

    private fun findLayer(pkg: String): String? {
        val list = RootShell.run("dumpsys SurfaceFlinger --list").lines().map { it.trim() }
        val cands =
            list.filter {
                it.contains(pkg) && !it.startsWith("Background for") && !it.startsWith("Bounds for") && !it.startsWith("Dim")
            }
        return cands.firstOrNull { it.startsWith("SurfaceView") } ?: cands.firstOrNull()
    }

    fun sample(fixedPkg: String?): Sample {
        val pkg = foregroundPackage(fixedPkg)
        if (pkg != layerPkg) {
            layerPkg = pkg
            layer = null
            prevNewest = 0L
            intervals.clear()
        }
        if (pkg != null && layer == null) layer = findLayer(pkg)

        val sf =
            if (layer != null) "dumpsys SurfaceFlinger --latency '${layer!!.replace("'", "")}'" else "echo"
        val pidCmd = if (pkg != null) "pid=\$(pidof $pkg | cut -d' ' -f1)" else "pid="
        val cmd =
            "$sf; echo @@; head -1 /proc/stat; echo @@; " +
                "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null; echo @@; " +
                "grep -E 'MemTotal|MemAvailable' /proc/meminfo; echo @@; " +
                "$pidCmd; echo \$pid; cat /proc/\$pid/stat 2>/dev/null; echo @@; " +
                "grep VmRSS /proc/\$pid/status 2>/dev/null"
        val parts = RootShell.run(cmd).split("@@\n")
        fun part(i: Int) = parts.getOrNull(i) ?: ""

        // FPS
        var fps = 0
        val stamps = ArrayList<Long>()
        for (line in part(0).lines().drop(1)) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 3) continue
            val t = cols[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue
            stamps.add(t)
        }
        if (stamps.isNotEmpty()) {
            val newest = stamps.last()
            val fresh = stamps.filter { it > prevNewest }
            if (prevNewest != 0L && newest > prevNewest) {
                var prev = prevNewest
                for (t in fresh) {
                    val d = t - prev
                    if (d in 1..500_000_000L) {
                        intervals.addLast(d)
                        all.add(d)
                    }
                    prev = t
                }
                while (intervals.size > 900) intervals.removeFirst()
                fps = Math.round(fresh.size * 1e9 / (newest - prevNewest)).toInt()
            } else if (prevNewest == 0L) {
                fps = stamps.count { it > newest - 1_000_000_000L }
            }
            prevNewest = maxOf(prevNewest, newest)
        }
        var low1 = 0
        if (intervals.size >= 20) {
            val sorted = intervals.sorted()
            val idx = (sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)
            low1 = Math.round(1e9 / sorted[idx]).toInt()
        }

        // CPU total
        var cpu = 0
        var dTotal = 0L
        val cpuNums = part(1).trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (cpuNums.size >= 5) {
            val total = cpuNums.take(8).sum()
            val idle = cpuNums[3] + cpuNums[4]
            dTotal = total - lastCpuTotal
            val dIdle = idle - lastCpuIdle
            if (lastCpuTotal != 0L && dTotal > 0) cpu = (100 * (dTotal - dIdle) / dTotal).toInt()
            lastCpuTotal = total
            lastCpuIdle = idle
        }

        // GPU
        val gpu = Regex("\\d+").find(part(2))?.value?.toIntOrNull() ?: 0

        // RAM
        var totalKb = 0L
        var availKb = 0L
        for (line in part(3).lines()) {
            val n = Regex("\\d+").find(line)?.value?.toLongOrNull() ?: continue
            if (line.startsWith("MemTotal")) totalKb = n else if (line.startsWith("MemAvailable")) availKb = n
        }

        // processo do jogo
        val procLines = part(4).lines()
        val pid = procLines.firstOrNull()?.trim() ?: ""
        var gameCpu = 0
        val statLine = procLines.getOrNull(1) ?: ""
        val close = statLine.lastIndexOf(')')
        if (pid.isNotEmpty() && close > 0) {
            val f = statLine.substring(close + 2).split(" ")
            val ticks = (f.getOrNull(11)?.toLongOrNull() ?: 0L) + (f.getOrNull(12)?.toLongOrNull() ?: 0L)
            if (pid == lastPid && lastProcTicks != 0L && dTotal > 0) {
                gameCpu = (100 * (ticks - lastProcTicks) / dTotal).toInt().coerceIn(0, 100)
            }
            lastProcTicks = ticks
            lastPid = pid
        } else {
            lastPid = ""
            lastProcTicks = 0L
        }
        val gameRam = (Regex("\\d+").find(part(5))?.value?.toLongOrNull() ?: 0L) / 1024

        // temperatura (bateria)
        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        return Sample(
            pkg = pkg,
            fps = fps,
            low1 = low1,
            cpu = cpu.coerceIn(0, 100),
            gameCpu = gameCpu,
            gpu = gpu.coerceIn(0, 100),
            ramUsedMb = ((totalKb - availKb) / 1024).toInt(),
            ramTotalMb = (totalKb / 1024).toInt(),
            gameRamMb = gameRam.toInt(),
            tempC = temp,
        )
    }
}

/** Lista simples de Long (evita guardar milhares de objetos). */
class LongList {
    private var data = LongArray(4096)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun percentile(p: Double): Long {
        if (size == 0) return 0L
        val copy = data.copyOf(size)
        copy.sort()
        return copy[(size * p).toInt().coerceAtMost(size - 1)]
    }
}
