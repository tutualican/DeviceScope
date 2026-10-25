package com.alican.devicescope.bench

import android.content.Context
import android.os.Build
import com.alican.devicescope.ThermalCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Maraton: the long-form suite.
 *
 * It covers everything Mikyas and Kinetik cover (Geekbench-style single and
 * multi core, memory, storage, Vulkan GPU, on-screen visual stage, launch) and
 * then keeps the device under load for the rest of a fixed wall-clock budget so
 * thermal throttling and stability are measured instead of assumed.
 *
 * Guarantees
 *  - The configured duration is a *minimum*. The run never finishes early: the
 *    sustained loop keeps going until the wall clock reaches the budget.
 *  - Every score is derived from measured throughput against the same reference
 *    constants the short suites use, so numbers stay comparable.
 *  - Stability is measured from the sustained series, not from a single spot
 *    check: retention = median(last third) / median(first third), and the
 *    coefficient of variation of that series is reported next to it.
 *  - A missing component (for example a driver that rejects Vulkan and falls
 *    back to GLES) is reported, and the weighted total renormalises, so the
 *    absence is never silently scored as zero.
 */
class MarathonRunner(private val context: Context) {

    data class Config(
        val minutes: Int = 15,
        val scanSeconds: Int = 20
    ) {
        /** Hard floor: a marathon shorter than 15 minutes is not a marathon. */
        val effectiveMinutes: Int get() = minutes.coerceAtLeast(15)
        val totalMs: Long get() = effectiveMinutes * 60_000L
    }

    suspend fun run(
        config: Config = Config(),
        onProgress: (phase: String, detail: String, fraction: Float) -> Unit
    ): MarathonResult = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val totalMs = config.totalMs

        fun report(phase: String, detail: String) {
            val fraction = ((System.currentTimeMillis() - startedAt).toDouble() / totalMs)
                .coerceIn(0.0, 1.0).toFloat()
            onProgress(phase, detail, fraction)
        }

        val engine = Engine(context)
        val thermal = ThermalCollector(context)
        val monitor = SystemMonitor(context)
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        var screenChecks = 0
        var screenOff = 0
        val rounds = ArrayList<RoundSample>()
        val temps = ArrayList<Pair<Long, Float>>()
        val throughputSeries = ArrayList<Double>()

        var verified = true
        var failed = emptyList<Int>()

        report("Doğrulama", "Bilinen-cevap testleri")
        var verifyFailed = false
        failed = try {
            engine.verify()
        } catch (t: Throwable) {
            verifyFailed = true
            emptyList()
        }
        // A verification run that could not execute is not a pass.
        verified = failed.isEmpty() && !verifyFailed
        coroutineContext.ensureActive()

        monitor.start()

        // ---------------------------------------------------------- single core
        val single = ArrayList<Measurement>()
        val cores = Runtime.getRuntime().availableProcessors()
        val multiThreads = cores.coerceIn(2, 8)
        for ((i, w) in Workload.CPU.withIndex()) {
            report("Tek çekirdek", "${w.title} (${i + 1}/${Workload.CPU.size})")
            single.add(engine.measure(w, threads = 1, warmup = 1, repeats = 3))
        }
        val singleScore = Stats.geoMean(single.map { it.index })
        coroutineContext.ensureActive()

        // ----------------------------------------------------------- multi core
        val multi = ArrayList<Measurement>()
        for ((i, w) in Workload.CPU.withIndex()) {
            report("Çok çekirdek", "${w.title} · $multiThreads iş parçacığı (${i + 1}/${Workload.CPU.size})")
            multi.add(engine.measure(w, threads = multiThreads, warmup = 1, repeats = 3))
        }
        val multiScore = Stats.geoMean(multi.map { it.index })
        coroutineContext.ensureActive()

        // --------------------------------------------------------- memory/disk
        report("Bellek", "Bant genişliği ve sağlama toplamı")
        val memory = try {
            engine.measure(Workload.MEMORY, threads = 1, warmup = 1, repeats = 4, targetMs = 320)
        } catch (t: Throwable) {
            null
        }
        coroutineContext.ensureActive()

        report("Depolama", "128 MB yazma + fsync + okuma")
        val storage = try {
            engine.measureStorage(context, sizeMb = 128, repeats = 3)
        } catch (t: Throwable) {
            null
        }
        coroutineContext.ensureActive()

        // ------------------------------------------------------------------ GPU
        report("GPU", "Vulkan compute + 1024² çevrimdışı sahne")
        val gpu = try {
            GpuSuite.run()
        } catch (t: Throwable) {
            null
        }
        val gpuScore = gpu?.index ?: 0.0
        coroutineContext.ensureActive()

        // ------------------------------------------------------- visual stage
        val sceneMs = (config.scanSeconds * 1000L).coerceIn(15_000L, 60_000L)
        report("Görsel sahne", "Ekranda gerçek kare ölçümü")
        val scene = try {
            withTimeout(sceneMs + 8_000L) {
                VisualStage.runScene(
                    title = "Görsel test",
                    detail = "Piksel ve kare hattı — AnTuTu tarzı sahne",
                    durationMs = sceneMs,
                    variant = 1
                )
            }
        } catch (t: Throwable) {
            null
        }
        coroutineContext.ensureActive()

        report("Arayüz", "Kart listesi kare ölçümü")
        val uxNumeric = try {
            withTimeout(20_000L) { UxBench().run(4500) }
        } catch (t: Throwable) {
            null
        }

        val uxFps = scene?.avgFps ?: uxNumeric?.avgFps ?: 0.0
        val uxJank = scene?.jankPercent ?: uxNumeric?.jankPercent ?: 0.0
        val uxScore = if (uxFps <= 0.0) 0.0 else Stats.geoMean(
            listOf(
                (uxFps / 60.0).coerceAtMost(2.0) * 1000.0,
                (100.0 - uxJank).coerceAtLeast(1.0) / 100.0 * 1000.0
            )
        )

        // ------------------------------------------------------- sustained loop
        // Everything above is preparation; from here the device is held under
        // load until the wall-clock budget is exhausted.
        var tick = 0
        var lastTempRead = 0L
        while (true) {
            coroutineContext.ensureActive()
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= totalMs) break

            tick++
            val singleRound = tick % 2 == 1
            val workload = if (singleRound) Workload.INT else Workload.MATRIX
            val threads = if (singleRound) 1 else multiThreads

            // A round is a fixed wall-clock batch (~2.5 s), not a single call:
            // that keeps the round count bounded for 15–45 minute runs instead
            // of producing hundreds of thousands of tiny samples.
            val throughput = try {
                runTimed(workload, threads, 2.5)
            } catch (t: Throwable) {
                null
            }
            val index = if (throughput != null && throughput > 0) {
                throughput / workload.refUnitsPerSec * 1000.0
            } else 0.0
            if (index > 0) throughputSeries.add(index)

            val now = System.currentTimeMillis()
            var cpuC: Float? = null
            var battC: Float? = null
            var freq = 0L
            if (now - lastTempRead >= 900L) {
                lastTempRead = now
                try {
                    val snap = thermal.read()
                    cpuC = snap.cpuC
                    battC = snap.batteryC
                    freq = snap.coreMhz.maxOrNull() ?: 0L
                    cpuC?.let { temps.add(now to it) }
                } catch (_: Throwable) {
                }
                if (power != null) {
                    screenChecks++
                    if (!power.isInteractive) screenOff++
                }
            }

            rounds.add(
                RoundSample(
                    timestamp = now,
                    singleCore = singleRound,
                    index = index,
                    cpuTempC = cpuC,
                    batteryTempC = battC,
                    freqMhz = freq
                )
            )

            val minute = ((now - startedAt) / 60_000L) + 1
            report(
                "Isıl dayanım",
                "Tur $tick · dakika $minute/${config.effectiveMinutes}" +
                    (if (singleRound) " · tek çekirdek" else " · çok çekirdek")
            )
        }

        monitor.stop()
        val samples = monitor.snapshot()

        // -------------------------------------------------------- stability
        val series = throughputSeries.filter { it > 0 }
        val stabilityCv = Stats.cvPercent(series)
        val third = (series.size / 3).coerceAtLeast(1)
        val retention = if (series.size >= 6) {
            val first = Stats.median(series.take(third))
            val last = Stats.median(series.takeLast(third))
            if (first > 0) (last / first * 100.0) else 100.0
        } else {
            null
        }
        val retentionFactor = (retention ?: 100.0) / 100.0
        val stabilityScore = (1000.0 * retentionFactor.coerceIn(0.0, 1.10) *
            (1.0 - (stabilityCv / 100.0).coerceIn(0.0, 0.35)))
            .coerceIn(0.0, 1100.0)
            .takeIf { series.size >= 6 } ?: 0.0

        val memoryScore = memory?.index ?: 0.0
        val storageScore = storage?.index ?: 0.0
        val breakdown = linkedMapOf(
            "single" to singleScore,
            "multi" to multiScore,
            "memory" to memoryScore,
            "storage" to storageScore,
            "gpu" to gpuScore,
            "ux" to uxScore,
            "stability" to stabilityScore
        )
        val total = weightedTotal(breakdown)

        val endTemp = temps.lastOrNull()?.second
        report("Tamamlandı", "Rapor yazılıyor")

        MarathonResult(
            config = config,
            single = single,
            multi = multi,
            memory = memory,
            storage = storage,
            gpu = gpu,
            scene = scene,
            uxNumeric = uxNumeric,
            singleScore = singleScore,
            multiScore = multiScore,
            memoryScore = memoryScore,
            storageScore = storageScore,
            gpuScore = gpuScore,
            uxScore = uxScore,
            stabilityScore = stabilityScore,
            stabilityCv = stabilityCv,
            retentionPercent = retention,
            total = total,
            breakdown = breakdown,
            rounds = rounds,
            tempSamples = temps,
            monitor = samples,
            peakTempC = samples.peakCpu ?: temps.maxOfOrNull { it.second },
            startTempC = temps.firstOrNull()?.second,
            endTempC = endTemp,
            verified = verified,
            failedWorkloads = failed,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            cores = cores,
            screenChecks = screenChecks,
            screenOffChecks = screenOff,
            timestamp = System.currentTimeMillis(),
            durationSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
        )
    }

    /**
     * Runs the native kernel for a fixed wall-clock window and returns the
     * measured units per second. [threads] worker threads run until the shared
     * deadline, so a round is always the same length regardless of how fast the
     * device is — that is what makes round-over-round retention comparable.
     */
    private fun runTimed(w: Workload, threads: Int, seconds: Double): Double? {
        val budgetNs = (seconds * 1e9).toLong()
        val deadline = System.nanoTime() + budgetNs
        val units = java.util.concurrent.atomic.AtomicLong(0)
        val start = System.nanoTime()
        val workers = threads.coerceIn(1, 64)
        if (workers == 1) {
            while (System.nanoTime() < deadline) {
                units.addAndGet(Native.run(w.id, 2)[0])
            }
        } else {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
            try {
                val futures = (0 until workers).map {
                    pool.submit {
                        while (System.nanoTime() < deadline) {
                            units.addAndGet(Native.run(w.id, 2)[0])
                        }
                    }
                }
                futures.forEach { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        if (elapsed <= 0.0 || units.get() <= 0L) return null
        return units.get() / elapsed
    }

    companion object {
        /**
         * Weights for the marathon total. Values mirror assets/scoring.json so the
         * JSON file and the app cannot drift silently; [ScoreConfig] is still the
         * authority for the short-suite score.
         */
        val WEIGHTS: Map<String, Double> = linkedMapOf(
            "single" to 0.18,
            "multi" to 0.22,
            "memory" to 0.08,
            "storage" to 0.08,
            "gpu" to 0.20,
            "ux" to 0.12,
            "stability" to 0.12
        )

        fun weightedTotal(parts: Map<String, Double>): Double {
            var sum = 0.0
            var weight = 0.0
            for ((key, w) in WEIGHTS) {
                val v = parts[key] ?: continue
                if (v <= 0.0 || v.isNaN()) continue
                sum += w * v
                weight += w
            }
            return if (weight > 0) sum / weight else 0.0
        }

        fun fmt(v: Double, digits: Int = 1): String =
            String.format(Locale.US, "%.${digits}f", v)
    }
}

data class RoundSample(
    val timestamp: Long,
    val singleCore: Boolean,
    val index: Double,
    val cpuTempC: Float?,
    val batteryTempC: Float?,
    val freqMhz: Long
)

data class MarathonResult(
    val config: MarathonRunner.Config,
    val single: List<Measurement>,
    val multi: List<Measurement>,
    val memory: Measurement?,
    val storage: Engine.StorageResult?,
    val gpu: GpuBench.GpuResult?,
    val scene: VisualStage.SceneSample?,
    val uxNumeric: UxBench.UxResult?,
    val singleScore: Double,
    val multiScore: Double,
    val memoryScore: Double,
    val storageScore: Double,
    val gpuScore: Double,
    val uxScore: Double,
    val stabilityScore: Double,
    val stabilityCv: Double,
    val retentionPercent: Double?,
    val total: Double,
    val breakdown: Map<String, Double>,
    val rounds: List<RoundSample>,
    val tempSamples: List<Pair<Long, Float>>,
    val monitor: MonitorSamples,
    val peakTempC: Float?,
    val startTempC: Float?,
    val endTempC: Float?,
    val verified: Boolean,
    val failedWorkloads: List<Int>,
    val device: String,
    val abi: String,
    val cores: Int,
    val screenChecks: Int,
    val screenOffChecks: Int,
    val timestamp: Long,
    val durationSeconds: Double
) {
    val gpuApi: String get() = gpu?.api ?: "—"
    val gpuNote: String? get() = gpu?.fallbackNote ?: gpu?.error
    val throttled: Boolean get() = (retentionPercent ?: 100.0) < 85.0
    val screenKeptOn: Boolean get() = screenChecks > 0 && screenOffChecks == 0
    val maxCv: Double get() = (single + multi).maxOfOrNull { it.cvPercent } ?: 0.0
}
