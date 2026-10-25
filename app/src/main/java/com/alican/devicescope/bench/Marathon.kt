package com.alican.devicescope.bench

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.alican.devicescope.AppInfo
import com.alican.devicescope.ConditionCollector
import com.alican.devicescope.ConditionsSnapshot
import com.alican.devicescope.Equilibrium
import com.alican.devicescope.ThermalCollector
import com.alican.devicescope.measureEquilibrium
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
 * multi core, memory, storage, Vulkan GPU, on-screen visual stage) and then keeps
 * the device under load for the rest of a fixed wall-clock budget so thermal
 * throttling and stability are measured instead of assumed.
 *
 * Guarantees
 *  - The configured duration is a *minimum*. The run never finishes early: the
 *    sustained loop keeps going until the wall clock reaches the budget.
 *  - The test refuses to start when the conditions make a comparison meaningless
 *    (charging, power save, low battery, charged thermal state, still drifting
 *    battery temperature) unless the caller explicitly forces it; a forced run
 *    records the reasons in the result.
 *  - The first [Config.warmupSeconds] seconds of the sustained loop are warm-up
 *    and excluded from every stability figure.
 *  - Retention is measured against the *best* sustained window, not the first
 *    third, so a slow start cannot flatter a throttling device. A raw ratio above
 *    100 % is clamped and flagged as "slow start".
 *  - CV is reported separately for single-core and multi-core rounds.
 *  - Contaminated rounds (screen off, or another process visibly busy according
 *    to /proc/stat) are marked and excluded.
 *  - Missing components and sensors stay missing; the weighted total
 *    renormalises and the JSON says why.
 */
class MarathonRunner(private val context: Context) {

    data class Config(
        val minutes: Int = 15,
        val scanSeconds: Int = 20,
        /** Battery temperature must be flat for this long before measuring. */
        val equilibrationSeconds: Int = 180,
        val maxEquilibriumSpanC: Float = 0.3f,
        /** Excluded from stability: the device is still settling. */
        val warmupSeconds: Int = 45,
        /** Post-load cooling curve length. */
        val cooldownSeconds: Int = 120,
        val roundSeconds: Double = 2.5,
        /** Start even though pre-flight conditions are blocking. Recorded. */
        val force: Boolean = false
    ) {
        /** Hard floor: a marathon shorter than 15 minutes is not a marathon. */
        val effectiveMinutes: Int get() = minutes.coerceAtLeast(15)
        val totalMs: Long get() = effectiveMinutes * 60_000L
        val warmupMs: Long get() = warmupSeconds * 1000L
    }

    /** Thrown before measuring when the pre-flight gate says "not comparable". */
    class ConditionsBlocked(
        val conditions: ConditionsSnapshot,
        val equilibrium: Equilibrium?,
        val reasons: List<String>
    ) : IllegalStateException(reasons.joinToString(" · "))

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
        val conditionsCollector = ConditionCollector(context)
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val appVersion = AppInfo.versionName(context)

        var screenChecks = 0
        var screenOff = 0

        // ------------------------------------------------------ pre-flight gate
        report("Koşullar", "Başlangıç koşulları okunuyor")
        var conditions = conditionsCollector.snapshot(forced = config.force)
        if (!conditions.canStart && !config.force) {
            throw ConditionsBlocked(
                conditions,
                null,
                conditions.blocking.map { "${it.label}: ${it.value}" }
            )
        }

        report("Isıl denge", "Batarya sıcaklığı ${config.equilibrationSeconds} sn izleniyor")
        val equilibrium = measureEquilibrium(
            collector = conditionsCollector,
            windowSeconds = config.equilibrationSeconds,
            maxSpanC = config.maxEquilibriumSpanC
        ) { _, fraction ->
            report(
                "Isıl denge",
                "Batarya sıcaklığı izleniyor · %${(fraction * 100).toInt()}"
            )
        }
        coroutineContext.ensureActive()

        // Re-read after the wait: charging state, thermal status and battery
        // level can change during three minutes.
        conditions = conditionsCollector.snapshot(forced = config.force)
        if (!conditions.canStart && !config.force) {
            throw ConditionsBlocked(
                conditions,
                equilibrium,
                conditions.blocking.map { "${it.label}: ${it.value}" }
            )
        }
        if (equilibrium.measurable && !equilibrium.passed && !config.force) {
            throw ConditionsBlocked(
                conditions,
                equilibrium,
                listOf(
                    String.format(
                        Locale.US,
                        "Isıl denge sağlanmadı (%.2f °C > %.1f °C)",
                        equilibrium.spanC ?: 0f,
                        config.maxEquilibriumSpanC
                    )
                )
            )
        }

        val batteryStartPercent = conditions.batteryPercent
        val capacityMah = conditions.capacityMah

        val rounds = ArrayList<RoundSample>()
        val temps = ArrayList<Pair<Long, Float>>()
        val cooldown = ArrayList<CooldownSample>()

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
            val now0 = System.currentTimeMillis()
            val elapsed = now0 - startedAt
            if (elapsed >= totalMs) break

            tick++
            val singleRound = tick % 2 == 1
            val workload = if (singleRound) Workload.INT else Workload.MATRIX
            val threads = if (singleRound) 1 else multiThreads

            val ownBefore = ProcStat.read()
            val wall0 = System.nanoTime()

            // A round is a fixed wall-clock batch (~2.5 s), not a single call:
            // that keeps the round count bounded for 15–45 minute runs instead
            // of producing hundreds of thousands of tiny samples.
            val throughput = try {
                runTimed(workload, threads, config.roundSeconds)
            } catch (t: Throwable) {
                null
            }
            val seconds = (System.nanoTime() - wall0) / 1e9
            val ownAfter = ProcStat.read()

            val index = if (throughput != null && throughput > 0) {
                throughput / workload.refUnitsPerSec * 1000.0
            } else 0.0

            val now = System.currentTimeMillis()
            var cpuC: Float? = null
            var cpuAvgC: Float? = null
            var battC: Float? = null
            var freq = 0L
            var coreFreq: List<Long> = emptyList()
            var thermalStatus: Int? = null
            var headroom: Float? = null
            var screenOn = true
            if (power != null) {
                screenOn = power.isInteractive
                screenChecks++
                if (!screenOn) screenOff++
            }
            if (now - lastTempRead >= 900L) {
                lastTempRead = now
                try {
                    val snap = thermal.read()
                    cpuC = snap.cpuC
                    cpuAvgC = snap.cpuAvgC
                    battC = snap.batteryC
                    freq = snap.coreMhz.maxOrNull() ?: 0L
                    coreFreq = snap.coreMhz
                    thermalStatus = snap.thermalStatus
                    headroom = snap.thermalHeadroom
                    cpuC?.let { temps.add(now to it) }
                } catch (_: Throwable) {
                }
            }

            val otherBusyCores = if (ownBefore != null && ownAfter != null) {
                val total = (ownAfter.totalJiffies - ownBefore.totalJiffies).coerceAtLeast(1L)
                val idle = (ownAfter.idleJiffies - ownBefore.idleJiffies).coerceAtLeast(0L)
                val own = (ownAfter.ownJiffies - ownBefore.ownJiffies).coerceAtLeast(0L)
                val busyCores = (total - idle).toDouble() / total * cores
                val ownCores = own.toDouble() / total * cores
                (busyCores - ownCores).coerceAtLeast(0.0)
            } else {
                null
            }

            val energy = conditionsCollector.energy()
            val contamination = when {
                !screenOn -> "ekran kapandı"
                otherBusyCores != null && otherBusyCores >= 0.75 ->
                    String.format(Locale.US, "başka süreç ~%.2f çekirdek meşgul", otherBusyCores)
                else -> null
            }

            rounds.add(
                RoundSample(
                    timestamp = now,
                    elapsedMs = now - startedAt,
                    singleCore = singleRound,
                    index = index,
                    cpuTempC = cpuC,
                    cpuAvgC = cpuAvgC,
                    batteryTempC = battC,
                    freqMhz = freq,
                    coreFreqMhz = coreFreq,
                    thermalStatus = thermalStatus,
                    headroom = headroom,
                    otherBusyCores = otherBusyCores,
                    contaminated = contamination != null,
                    contamination = contamination,
                    watts = energy.watts,
                    currentMa = energy.currentMa,
                    roundSeconds = seconds
                )
            )

            val minute = ((now - startedAt) / 60_000L) + 1
            report(
                "Isıl dayanım",
                "Tur $tick · dakika $minute/${config.effectiveMinutes}" +
                    (if (singleRound) " · tek çekirdek" else " · çok çekirdek") +
                    (if (contamination != null) " · kirli tur" else "")
            )
        }

        // ------------------------------------------------------------ cooldown
        report("Soğuma", "Yük kaldırıldı; soğuma eğrisi kaydediliyor")
        val coolEnd = System.currentTimeMillis() + config.cooldownSeconds * 1000L
        while (System.currentTimeMillis() < coolEnd) {
            coroutineContext.ensureActive()
            try {
                val snap = thermal.read()
                cooldown.add(
                    CooldownSample(
                        timestamp = System.currentTimeMillis(),
                        cpuC = snap.cpuC,
                        batteryC = snap.batteryC,
                        freqMhz = snap.coreMhz.maxOrNull() ?: 0L
                    )
                )
            } catch (_: Throwable) {
            }
            val left = (coolEnd - System.currentTimeMillis()).coerceAtLeast(0L)
            report("Soğuma", "Soğuma eğrisi · kalan ${left / 1000} sn")
            kotlinx.coroutines.delay(5_000L)
        }

        monitor.stop()
        val samples = monitor.snapshot()

        // ---------------------------------------------------------- stability
        val stability = analyseStability(rounds, config.warmupMs)

        val memoryScore = memory?.index ?: 0.0
        val storageScore = storage?.index ?: 0.0
        val breakdown = linkedMapOf(
            "single" to singleScore,
            "multi" to multiScore,
            "memory" to memoryScore,
            "storage" to storageScore,
            "gpu" to gpuScore,
            "ux" to uxScore,
            "stability" to stability.score
        )
        val total = weightedTotal(breakdown)

        // ------------------------------------------------------------- energy
        val validWatts = rounds
            .filter { !it.contaminated && it.index > 0 && it.elapsedMs >= config.warmupMs }
            .mapNotNull { it.watts }
        val avgWatts = if (validWatts.isNotEmpty()) validWatts.average() else null
        val peakWatts = validWatts.maxOrNull()
        val firstElapsed = rounds.firstOrNull()?.elapsedMs ?: 0L
        val lastElapsed = rounds.lastOrNull()?.elapsedMs ?: totalMs
        val sustainedSeconds = ((lastElapsed - firstElapsed).coerceAtLeast(1L)) / 1000.0
        val currentNow = rounds
            .filter { !it.contaminated && it.index > 0 && it.elapsedMs >= config.warmupMs }
            .mapNotNull { it.currentMa }
            .takeIf { it.isNotEmpty() }
            ?.average()
        val voltageNow = conditionsCollector.voltageV()
        val energySummary = EnergySummary(
            avgWatts = avgWatts,
            peakWatts = peakWatts,
            avgCurrentMa = currentNow,
            voltageV = voltageNow,
            capacityMah = capacityMah,
            energyWh = avgWatts?.let { it * sustainedSeconds / 3600.0 },
            wattsPer1000Points = if (avgWatts != null && total > 0) avgWatts / (total / 1000.0) else null,
            samples = validWatts.size
        )

        val endTemp = temps.lastOrNull()?.second
        val batteryEndPercent = samples.batteryEnd
        val lowBatteryWarning =
            (batteryEndPercent != null && batteryEndPercent < 20) ||
                samples.batteryPercent.any { it < 20 }
        val coolingRate = if (cooldown.size >= 2) {
            val first = cooldown.first()
            val last = cooldown.last()
            val minutes = ((last.timestamp - first.timestamp) / 60_000.0).coerceAtLeast(0.001)
            val a = first.cpuC
            val b = last.cpuC
            if (a != null && b != null) (a - b) / minutes else null
        } else {
            null
        }

        report("Tamamlandı", "Rapor yazılıyor")

        MarathonResult(
            config = config,
            conditions = conditions,
            equilibrium = equilibrium,
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
            stability = stability,
            stabilityScore = stability.score,
            total = total,
            breakdown = breakdown,
            rounds = rounds,
            tempSamples = temps,
            cooldown = cooldown,
            coolingRatePerMin = coolingRate,
            monitor = samples,
            energy = energySummary,
            peakTempC = samples.peakCpu ?: temps.maxOfOrNull { it.second },
            startTempC = temps.firstOrNull()?.second,
            endTempC = endTemp,
            lowBatteryWarning = lowBatteryWarning,
            batteryStartPercent = batteryStartPercent,
            batteryEndPercent = batteryEndPercent,
            verified = verified,
            failedWorkloads = failed,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            cores = cores,
            screenChecks = screenChecks,
            screenOffChecks = screenOff,
            appVersion = appVersion,
            workloadVersion = WORKLOAD_VERSION,
            timestamp = System.currentTimeMillis(),
            durationSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
        )
    }

    /**
     * Splits the sustained series by workload type, drops warm-up and
     * contaminated rounds, and computes:
     *
     *  retention = median(last window) / median(best window), per type, averaged
     *  consistency = 1 - CV of the valid rounds, per type, averaged
     *
     * Using the best window instead of the first third means a device that was
     * slow to reach its steady state is not rewarded for it; a ratio above 100 %
     * is clamped to 100 % and flagged as a slow start.
     */
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

        /** Stability sub-weights: speed retained vs. fluctuation. */
        const val RETENTION_WEIGHT = 0.6
        const val CONSISTENCY_WEIGHT = 0.4

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
    val elapsedMs: Long,
    val singleCore: Boolean,
    val index: Double,
    val cpuTempC: Float?,
    val cpuAvgC: Float?,
    val batteryTempC: Float?,
    val freqMhz: Long,
    val coreFreqMhz: List<Long>,
    val thermalStatus: Int?,
    val headroom: Float?,
    val otherBusyCores: Double?,
    val contaminated: Boolean,
    val contamination: String?,
    val watts: Double?,
    val currentMa: Double?,
    val roundSeconds: Double
)

data class CooldownSample(
    val timestamp: Long,
    val cpuC: Float?,
    val batteryC: Float?,
    val freqMhz: Long
)

data class StabilityStats(
    val retentionPercent: Double?,
    val retentionRawPercent: Double?,
    val bestWindowIndex: Double?,
    val lastWindowIndex: Double?,
    val retentionScore: Double,
    val consistencyScore: Double,
    val score: Double,
    val singleCv: Double,
    val multiCv: Double,
    val combinedCv: Double,
    val slowStart: Boolean,
    val warmupExcludedRounds: Int,
    val contaminatedRounds: Int,
    val validSingleRounds: Int,
    val validMultiRounds: Int,
    val bestWindowRetentionPercent: Double?
)

data class EnergySummary(
    val avgWatts: Double?,
    val peakWatts: Double?,
    val avgCurrentMa: Double?,
    val voltageV: Double?,
    val capacityMah: Double?,
    val energyWh: Double?,
    val wattsPer1000Points: Double?,
    val samples: Int
)

data class MarathonResult(
    val config: MarathonRunner.Config,
    val conditions: ConditionsSnapshot,
    val equilibrium: Equilibrium,
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
    val stability: StabilityStats,
    val stabilityScore: Double,
    val total: Double,
    val breakdown: Map<String, Double>,
    val rounds: List<RoundSample>,
    val tempSamples: List<Pair<Long, Float>>,
    val cooldown: List<CooldownSample>,
    val coolingRatePerMin: Double?,
    val monitor: MonitorSamples,
    val energy: EnergySummary?,
    val peakTempC: Float?,
    val startTempC: Float?,
    val endTempC: Float?,
    val lowBatteryWarning: Boolean,
    val batteryStartPercent: Int?,
    val batteryEndPercent: Int?,
    val verified: Boolean,
    val failedWorkloads: List<Int>,
    val device: String,
    val abi: String,
    val cores: Int,
    val screenChecks: Int,
    val screenOffChecks: Int,
    val appVersion: String,
    val workloadVersion: Int,
    val timestamp: Long,
    val durationSeconds: Double
) {
    val gpuApi: String get() = gpu?.api ?: "—"
    val gpuNote: String? get() = gpu?.fallbackNote ?: gpu?.error
    val retentionPercent: Double? get() = stability.retentionPercent
    val stabilityCv: Double get() = stability.combinedCv
    val throttled: Boolean get() = (stability.retentionPercent ?: 100.0) < 85.0
    val screenKeptOn: Boolean get() = screenChecks > 0 && screenOffChecks == 0
    val maxCv: Double get() = (single + multi).maxOfOrNull { it.cvPercent } ?: 0.0
    val thermalThrottleObserved: Boolean
        get() = rounds.any { (it.thermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE }
    val minHeadroom: Float? get() = rounds.mapNotNull { it.headroom }.minOrNull()
}
