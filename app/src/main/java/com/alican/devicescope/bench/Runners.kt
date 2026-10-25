package com.alican.devicescope.bench

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.alican.devicescope.LaunchMetrics
import com.alican.devicescope.ThermalCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --------------------------------------------------------------------- results

/** Temperature / frequency / battery trace captured while a suite runs. */
data class MonitorSamples(
    val cpuTemps: List<Float> = emptyList(),
    val batteryTemps: List<Float> = emptyList(),
    val freqMhz: List<Long> = emptyList(),
    val batteryPercent: List<Int> = emptyList(),
    /** Wall clock of each CPU-temperature sample; aligned with [cpuTemps]. */
    val timestamps: List<Long> = emptyList(),
    /** Arithmetic mean of the CPU zones, next to the hottest-zone series. */
    val cpuAvgTemps: List<Float> = emptyList(),
    /** PowerManager thermal status at each CPU-temperature sample. */
    val thermalStatuses: List<Int> = emptyList(),
    /** PowerManager thermal headroom at each CPU-temperature sample. */
    val headrooms: List<Float> = emptyList()
) {
    val peakCpu: Float? get() = cpuTemps.maxOrNull()
    val minCpu: Float? get() = cpuTemps.minOrNull()
    val peakBattery: Float? get() = batteryTemps.maxOrNull()
    val maxFreq: Long? get() = freqMhz.maxOrNull()
    val minFreq: Long? get() = freqMhz.minOrNull()
    val batteryStart: Int? get() = batteryPercent.firstOrNull()?.takeIf { it in 0..100 }
    val batteryEnd: Int? get() = batteryPercent.lastOrNull()?.takeIf { it in 0..100 }

    val empty: Boolean
        get() = cpuTemps.isEmpty() && freqMhz.isEmpty() && batteryPercent.isEmpty()
}

data class MikyasResult(
    val single: List<Measurement>,
    val multi: List<Measurement>,
    val memory: Measurement?,
    val storage: Engine.StorageResult?,
    val singleScore: Double,
    val multiScore: Double,
    val memoryScore: Double,
    val storageScore: Double,
    val total: Double,
    val failedWorkloads: List<Int>,
    val monitor: MonitorSamples,
    val device: String,
    val cores: Int,
    val timestamp: Long,
    val durationSeconds: Double
) {
    val verified: Boolean get() = failedWorkloads.isEmpty()
    val peakTempC: Float? get() = monitor.peakCpu
    val startTempC: Float? get() = monitor.cpuTemps.firstOrNull()
    val maxCv: Double
        get() = (single + multi).maxOfOrNull { it.cvPercent } ?: 0.0
    val allStable: Boolean
        get() = (single + multi).all { it.checksumStable && it.cvPercent <= 3.0 }
    val repeats: Int get() = single.firstOrNull()?.repeats ?: 0
}

data class KinetikResult(
    val gpu: GpuBench.GpuResult,
    val ux: UxBench.UxResult?,
    val sustainedRetention: Double?,
    val sustainedSamples: List<Pair<Long, Double>>,
    val tempSamples: List<Pair<Long, Float>>,
    val monitor: MonitorSamples,
    val launchMs: Long?,
    val gpuScore: Double,
    val uxScore: Double,
    val sustainedScore: Double,
    val total: Double,
    val device: String,
    val timestamp: Long,
    val durationSeconds: Double
) {
    val gpuApi: String get() = gpu.api
    val gpuNote: String? get() = gpu.fallbackNote ?: gpu.error
}

// ------------------------------------------------------------- system monitor

/**
 * Samples CPU temperature, CPU frequency and battery level while a suite runs.
 * Values come from the same collectors the live screen uses; nothing is invented
 * and a missing sensor simply yields no sample.
 */
class SystemMonitor(private val context: Context) {

    private val thermal = ThermalCollector(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val temps = java.util.Collections.synchronizedList(ArrayList<Float>())
    private val batteryTemps = java.util.Collections.synchronizedList(ArrayList<Float>())
    private val freqs = java.util.Collections.synchronizedList(ArrayList<Long>())
    private val battery = java.util.Collections.synchronizedList(ArrayList<Int>())
    private val times = java.util.Collections.synchronizedList(ArrayList<Long>())
    private val cpuAvg = java.util.Collections.synchronizedList(ArrayList<Float>())
    private val statuses = java.util.Collections.synchronizedList(ArrayList<Int>())
    private val headrooms = java.util.Collections.synchronizedList(ArrayList<Float>())

    fun start() {
        if (job != null) return
        job = scope.launch {
            // Long enough for a 45 minute marathon plus preparation; the app is
            // a foreground service during that time, so the loop is not killed
            // by Doze. Samples cap at ~70 minutes of 0.5 s readings.
            val deadline = System.currentTimeMillis() + 70 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                try {
                    val snap = thermal.read()
                    if (snap.cpuC != null && temps.size < 9000) {
                        val now = snap.timestamp
                        temps.add(snap.cpuC)
                        times.add(now)
                        snap.cpuAvgC?.let { cpuAvg.add(it) }
                        snap.thermalStatus?.let { statuses.add(it) }
                        snap.thermalHeadroom?.let { headrooms.add(it) }
                    }
                    snap.batteryC?.let { if (batteryTemps.size < 9000) batteryTemps.add(it) }
                    snap.coreMhz.maxOrNull()?.let { if (freqs.size < 9000) freqs.add(it) }
                } catch (_: Throwable) {
                }
                batteryPercent()?.let { if (battery.size < 9000) battery.add(it) }
                delay(500)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun batteryPercent(): Int? = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    } catch (t: Throwable) {
        null
    }

    fun snapshot(): MonitorSamples = MonitorSamples(
        cpuTemps = synchronized(temps) { ArrayList(temps) },
        batteryTemps = synchronized(batteryTemps) { ArrayList(batteryTemps) },
        freqMhz = synchronized(freqs) { ArrayList(freqs) },
        batteryPercent = synchronized(battery) { ArrayList(battery) },
        timestamps = synchronized(times) { ArrayList(times) },
        cpuAvgTemps = synchronized(cpuAvg) { ArrayList(cpuAvg) },
        thermalStatuses = synchronized(statuses) { ArrayList(statuses) },
        headrooms = synchronized(headrooms) { ArrayList(headrooms) }
    )
}

// ---------------------------------------------------------------------- Mikyas

class MikyasRunner(private val context: Context) {

    private val engine = Engine(context)

    suspend fun run(onProgress: (String, Float) -> Unit): MikyasResult = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()

        onProgress("Bilinen-cevaplı doğrulama testleri…", 0.01f)
        val failed = engine.verify()

        val monitor = SystemMonitor(context)
        monitor.start()

        val single = ArrayList<Measurement>()
        val multi = ArrayList<Measurement>()
        val cores = Runtime.getRuntime().availableProcessors()
        val multiThreads = cores.coerceIn(2, 8)
        val steps = Workload.CPU.size * 2

        var done = 0
        for (w in Workload.CPU) {
            onProgress("Tek çekirdek · ${w.title}", 0.03f + 0.47f * done / steps)
            single.add(engine.measure(w, threads = 1, warmup = 1, repeats = 3))
            done++
        }

        for (w in Workload.CPU) {
            onProgress(
                "Çok çekirdek · ${w.title} ($multiThreads iş parçacığı)",
                0.50f + 0.30f * (done - Workload.CPU.size) / steps
            )
            multi.add(engine.measure(w, threads = multiThreads, warmup = 1, repeats = 3))
            done++
        }

        onProgress("Bellek bant genişliği", 0.84f)
        val memory = try {
            engine.measure(Workload.MEMORY, threads = 1, warmup = 1, repeats = 3, targetMs = 300)
        } catch (t: Throwable) {
            null
        }

        onProgress("Depolama · 128 MB yazma + fsync + okuma", 0.92f)
        val storage = try {
            engine.measureStorage(context, sizeMb = 128, repeats = 3)
        } catch (t: Throwable) {
            null
        }

        monitor.stop()
        val samples = monitor.snapshot()

        val singleScore = Stats.geoMean(single.map { it.index })
        val multiScore = Stats.geoMean(multi.map { it.index })
        val memoryScore = memory?.index ?: 0.0
        val storageScore = storage?.index ?: 0.0

        val parts = ArrayList<Double>()
        parts.add(singleScore)
        parts.add(multiScore)
        if (memoryScore > 0) parts.add(memoryScore)
        if (storageScore > 0) parts.add(storageScore)

        onProgress("Tamamlandı", 1f)

        MikyasResult(
            single = single,
            multi = multi,
            memory = memory,
            storage = storage,
            singleScore = singleScore,
            multiScore = multiScore,
            memoryScore = memoryScore,
            storageScore = storageScore,
            total = Stats.geoMean(parts),
            failedWorkloads = failed,
            monitor = samples,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            cores = cores,
            timestamp = System.currentTimeMillis(),
            durationSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
        )
    }
}

// --------------------------------------------------------------------- Kinetik

class KinetikRunner(private val context: Context) {

    private val engine = Engine(context)
    private val uxBench = UxBench()

    suspend fun run(onProgress: (String, Float) -> Unit): KinetikResult = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()

        onProgress("GPU · Vulkan compute + sahne…", 0.02f)
        val gpu = GpuSuite.run()

        onProgress("Arayüz akıcılığı · 4,5 sn gerçek kare ölçümü", 0.30f)
        val ux = try {
            // A backgrounded app gets no Choreographer callbacks; without a
            // timeout this coroutine would never resume and the suite would hang.
            kotlinx.coroutines.withTimeout(20_000L) { uxBench.run(4500) }
        } catch (t: Throwable) {
            null
        }

        val monitor = SystemMonitor(context)
        monitor.start()

        val sustained = measureSustained { p ->
            onProgress(
                "Isıl dayanım · 60 sn tam yük… %${(p * 100).toInt()}",
                0.42f + p * 0.55f
            )
        }

        monitor.stop()
        val samples = monitor.snapshot()

        val gpuScore = gpu.index ?: 0.0
        val uxScore = if (ux == null) 0.0 else Stats.geoMean(
            listOf(
                (ux.avgFps / 60.0).coerceAtMost(2.0) * 1000.0,
                ((100.0 - ux.jankPercent).coerceAtLeast(1.0)) / 100.0 * 1000.0
            )
        )
        val sustainedScore = (sustained?.first ?: 0.0) * 10.0

        val parts = ArrayList<Double>()
        if (gpuScore > 0) parts.add(gpuScore)
        if (uxScore > 0) parts.add(uxScore)
        if (sustainedScore > 0) parts.add(sustainedScore)

        onProgress("Tamamlandı", 1f)

        KinetikResult(
            gpu = gpu,
            ux = ux,
            sustainedRetention = sustained?.first,
            sustainedSamples = sustained?.second ?: emptyList(),
            tempSamples = sustained?.third ?: emptyList(),
            monitor = samples,
            launchMs = LaunchMetrics.coldStartMs,
            gpuScore = gpuScore,
            uxScore = uxScore,
            sustainedScore = sustainedScore,
            total = Stats.geoMean(parts),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            timestamp = System.currentTimeMillis(),
            durationSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
        )
    }

    /**
     * Sustained load: keeps the CPU busy for 60 s and records throughput per
     * second. Retention = median(last third) / median(first third).
     */
    private suspend fun measureSustained(
        onProgress: (Float) -> Unit
    ): Triple<Double, List<Pair<Long, Double>>, List<Pair<Long, Float>>> =
        withContext(Dispatchers.Default) {
            val workload = Workload.MATRIX
            val throughputs = ArrayList<Pair<Long, Double>>()
            val temps = ArrayList<Pair<Long, Float>>()
            val start = System.currentTimeMillis()
            val durationMs = 60_000L
            var lastReport = 0L
            var lastTemp = 0L

            while (System.currentTimeMillis() - start < durationMs) {
                val wall0 = System.nanoTime()
                val r = Native.run(workload.id, 2)
                val seconds = (System.nanoTime() - wall0) / 1e9
                if (seconds > 0) {
                    throughputs.add(System.currentTimeMillis() to (r[0] / seconds))
                }
                val now = System.currentTimeMillis()
                if (now - lastTemp >= 1000L) {
                    lastTemp = now
                    try {
                        engine.thermalCollector().read().cpuC?.let { temps.add(now to it) }
                    } catch (_: Throwable) {
                    }
                }
                val elapsed = now - start
                if (now - lastReport >= 250L) {
                    lastReport = now
                    onProgress((elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat())
                }
            }

            val values = throughputs.map { it.second }
            val third = values.size / 3
            val retention = if (third >= 2) {
                val firstThird = Stats.median(values.take(third))
                val lastThird = Stats.median(values.takeLast(third))
                if (firstThird > 0) (lastThird / firstThird * 100.0).coerceIn(0.0, 150.0) else 0.0
            } else {
                0.0
            }
            Triple(retention, throughputs, temps)
        }
}

// ----------------------------------------------------------------------- store

object ResultStore {

    private const val DIR = "results"

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun stamp(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ts))

    fun saveMikyas(context: Context, result: MikyasResult): File {
        val file = File(dir(context), "mikyas_${stamp(result.timestamp)}.json")
        file.writeText(Json.mikyas(result))
        return file
    }

    fun saveKinetik(context: Context, result: KinetikResult): File {
        val file = File(dir(context), "kinetik_${stamp(result.timestamp)}.json")
        file.writeText(Json.kinetik(result))
        return file
    }

    /** Maraton: full series, so the result screen can chart it after a restart. */
    fun saveMarathon(context: Context, result: MarathonResult): File {
        val file = File(dir(context), "maraton_${stamp(result.timestamp)}.json")
        file.writeText(MarathonJson.encode(result))
        return file
    }

    /**
     * Summary of a 3-run series: median, half-range and the spread. Written as a
     * separate file so each individual run stays intact while the repeatability
     * evidence is still on disk.
     */
    fun saveSeries(context: Context, stats: RepeatStats, results: List<MarathonResult>): File {
        val file = File(dir(context), "seri_maraton_${stamp()}.json")
        val json = org.json.JSONObject().apply {
            put("suite", "MaratonSeri")
            put("schemaVersion", MarathonJson.SCHEMA_VERSION)
            put("workloadVersion", results.firstOrNull()?.workloadVersion ?: WORKLOAD_VERSION)
            put("appVersion", results.firstOrNull()?.appVersion ?: "?")
            put("device", results.firstOrNull()?.device ?: "?")
            put("runs", stats.totals.size)
            put("totals", org.json.JSONArray(stats.totals))
            put("median", stats.median)
            put("halfRange", stats.halfRange)
            put("spreadPercent", stats.spreadPercent)
            put("unreliable", stats.unstable)
            put("thresholdPercent", Repeatability.UNSTABLE_SPREAD_PERCENT)
            put("note", "Aynı cihaz, aralarda soğuma. %5 üzeri yayılım güvenilmez sayılır.")
            put("runFiles", org.json.JSONArray(results.map { "maraton_${stamp(it.timestamp)}.json" }))
        }
        file.writeText(json.toString(2))
        return file
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun read(file: File): String? = try {
        file.readText()
    } catch (t: Throwable) {
        null
    }

    /** Parsed one-line summary of a saved run, used by the comparison screen. */
    data class RunSummary(
        val file: File,
        val suite: String,
        val timestamp: Long,
        val device: String,
        val total: Double,
        val single: Double?,
        val multi: Double?,
        val memory: Double?,
        val storage: Double?,
        val gpu: Double?,
        val ux: Double?,
        val sustained: Double?,
        val stability: Double?,
        val api: String?,
        val appVersion: String?,
        val workloadVersion: Int?
    )

    /**
     * Reads every saved result. Uses the platform JSON parser on files this app
     * wrote itself; a corrupt file is skipped instead of crashing the screen.
     */
    fun summaries(context: Context): List<RunSummary> = list(context).mapNotNull { file ->
        try {
            val root = JSONObject(file.readText())
            val suite = root.optString("suite", "?")
            val scores = root.optJSONObject("scores")
            RunSummary(
                file = file,
                suite = suite,
                timestamp = root.optLong("timestamp", file.lastModified()),
                device = root.optString("device", "—"),
                total = scores?.optDouble("total", 0.0) ?: 0.0,
                single = scores?.optDoubleOrNull("single"),
                multi = scores?.optDoubleOrNull("multi"),
                memory = scores?.optDoubleOrNull("memory"),
                storage = scores?.optDoubleOrNull("storage"),
                gpu = scores?.optDoubleOrNull("gpu"),
                ux = scores?.optDoubleOrNull("ux"),
                sustained = scores?.optDoubleOrNull("sustained"),
                stability = scores?.optDoubleOrNull("stability"),
                api = root.optJSONObject("gpu")?.optString("api")?.takeIf { it.isNotBlank() },
                appVersion = root.optString("appVersion").takeIf { it.isNotBlank() },
                workloadVersion = root.optInt("workloadVersion", -1).takeIf { it > 0 }
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun previousMikyas(context: Context, current: MikyasResult): RunSummary? =
        summaries(context)
            .filter { it.suite == "Mikyas" && it.device == current.device && it.timestamp < current.timestamp }
            .maxByOrNull { it.timestamp }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }
}

// ------------------------------------------------------------------------ json

object Json {

    private fun esc(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun n(v: Double?, decimals: Int = 3): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "null"
        return String.format(Locale.US, "%.${decimals}f", v)
    }

    private fun monitor(m: MonitorSamples): String = buildString {
        append('{')
        append("\"cpuPeakC\":").append(n(m.peakCpu?.toDouble(), 1)).append(',')
        append("\"cpuMinC\":").append(n(m.minCpu?.toDouble(), 1)).append(',')
        append("\"batteryPeakC\":").append(n(m.peakBattery?.toDouble(), 1)).append(',')
        append("\"freqMaxMhz\":").append(m.maxFreq?.toString() ?: "null").append(',')
        append("\"freqMinMhz\":").append(m.minFreq?.toString() ?: "null").append(',')
        append("\"batteryStart\":").append(m.batteryStart?.toString() ?: "null").append(',')
        append("\"batteryEnd\":").append(m.batteryEnd?.toString() ?: "null").append(',')
        append("\"sampleCount\":").append(m.cpuTemps.size)
        append('}')
    }

    private fun measurement(m: Measurement): String = buildString {
        append('{')
        append("\"workload\":").append(esc(m.workload.title)).append(',')
        append("\"category\":").append(esc(m.workload.cat.label)).append(',')
        append("\"threads\":").append(m.threads).append(',')
        append("\"iterations\":").append(m.iterations).append(',')
        append("\"medianSeconds\":").append(n(m.medianSeconds, 6)).append(',')
        append("\"throughput\":").append(n(m.throughput, 2)).append(',')
        append("\"unit\":").append(esc(m.unit)).append(',')
        append("\"index\":").append(n(m.index, 2)).append(',')
        append("\"cvPercent\":").append(n(m.cvPercent, 3)).append(',')
        append("\"checksumStable\":").append(m.checksumStable).append(',')
        append("\"repeats\":").append(m.repeats)
        append('}')
    }

    fun mikyas(r: MikyasResult): String = buildString {
        append('{')
        append("\"suite\":\"Mikyas\",")
        append("\"version\":2,")
        append("\"timestamp\":").append(r.timestamp).append(',')
        append("\"durationSeconds\":").append(n(r.durationSeconds, 1)).append(',')
        append("\"device\":").append(esc(r.device)).append(',')
        append("\"cores\":").append(r.cores).append(',')
        append("\"scores\":{")
        append("\"single\":").append(n(r.singleScore, 1)).append(',')
        append("\"multi\":").append(n(r.multiScore, 1)).append(',')
        append("\"memory\":").append(n(r.memoryScore, 1)).append(',')
        append("\"storage\":").append(n(r.storageScore, 1)).append(',')
        append("\"total\":").append(n(r.total, 1))
        append("},")
        append("\"monitor\":").append(monitor(r.monitor)).append(',')
        append("\"verification\":{")
        append("\"passed\":").append(r.verified).append(',')
        append("\"failedWorkloads\":[").append(r.failedWorkloads.joinToString(",")).append("],")
        append("\"maxCvPercent\":").append(n(r.maxCv, 2)).append(',')
        append("\"checksumsStable\":").append(r.allStable)
        append("},")
        append("\"single\":[").append(r.single.joinToString(",") { measurement(it) }).append("],")
        append("\"multi\":[").append(r.multi.joinToString(",") { measurement(it) }).append("],")
        append("\"memory\":").append(r.memory?.let { measurement(it) } ?: "null").append(',')
        append("\"storage\":")
        val storage = r.storage
        if (storage == null) {
            append("null")
        } else {
            append('{')
            append("\"writeMbPerSec\":").append(n(storage.writeMbPerSec, 1)).append(',')
            append("\"readMbPerSec\":").append(n(storage.readMbPerSec, 1)).append(',')
            append("\"cvWrite\":").append(n(storage.cvWrite, 2)).append(',')
            append("\"cvRead\":").append(n(storage.cvRead, 2)).append(',')
            append("\"sizeMb\":").append(storage.sizeMb)
            append('}')
        }
        append('}')
    }

    fun kinetik(r: KinetikResult): String = buildString {
        append('{')
        append("\"suite\":\"Kinetik\",")
        append("\"version\":2,")
        append("\"timestamp\":").append(r.timestamp).append(',')
        append("\"durationSeconds\":").append(n(r.durationSeconds, 1)).append(',')
        append("\"device\":").append(esc(r.device)).append(',')
        append("\"scores\":{")
        append("\"gpu\":").append(n(r.gpuScore, 1)).append(',')
        append("\"ux\":").append(n(r.uxScore, 1)).append(',')
        append("\"sustained\":").append(n(r.sustainedScore, 1)).append(',')
        append("\"total\":").append(n(r.total, 1))
        append("},")
        append("\"gpu\":{")
        append("\"api\":").append(esc(r.gpu.api)).append(',')
        append("\"renderer\":").append(esc(r.gpu.renderer)).append(',')
        append("\"driverVersion\":").append(esc(r.gpu.glVersion)).append(',')
        append("\"computeGops\":").append(n(r.gpu.computeGops, 2)).append(',')
        append("\"sceneFps\":").append(n(r.gpu.sceneFps, 1)).append(',')
        append("\"note\":").append(esc(r.gpu.error))
        append("},")
        append("\"ux\":")
        val ux = r.ux
        if (ux == null) {
            append("null")
        } else {
            append('{')
            append("\"avgFps\":").append(n(ux.avgFps, 1)).append(',')
            append("\"medianFrameMs\":").append(n(ux.medianFrameMs, 2)).append(',')
            append("\"p95FrameMs\":").append(n(ux.p95FrameMs, 2)).append(',')
            append("\"jankPercent\":").append(n(ux.jankPercent, 2)).append(',')
            append("\"frames\":").append(ux.frames)
            append('}')
        }
        append(',')
        append("\"launchMs\":").append(r.launchMs?.toString() ?: "null").append(',')
        append("\"sustained\":{")
        append("\"retentionPercent\":").append(n(r.sustainedRetention, 1))
        append("},")
        append("\"monitor\":").append(monitor(r.monitor))
        append('}')
    }
}
