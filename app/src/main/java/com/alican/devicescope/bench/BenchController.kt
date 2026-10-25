package com.alican.devicescope.bench

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alican.devicescope.ConditionsSnapshot
import com.alican.devicescope.Equilibrium
import com.alican.devicescope.MainActivity
import com.alican.devicescope.ThermalCollector
import com.alican.devicescope.TestMode
import com.alican.devicescope.TestModeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Drives every suite and exposes the state to Compose.
 *
 * One instance exists per process ([shared]); the activity, the test-mode
 * foreground service and the notification all talk to the same object.
 *
 * The marathon is gated: [runMarathon] first measures the pre-flight conditions
 * and the thermal equilibrium, and refuses to start unless the caller forces it.
 * A forced start is recorded in the result file, never hidden.
 */
class BenchController private constructor(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var marathonJob: Job? = null

    var mikyasRunning by mutableStateOf(false)
        private set
    var mikyasProgress by mutableStateOf(0f)
        private set
    var mikyasLabel by mutableStateOf("")
        private set
    var mikyas by mutableStateOf<MikyasResult?>(null)
        private set
    var mikyasSavedFile by mutableStateOf<File?>(null)
        private set

    var kinetikRunning by mutableStateOf(false)
        private set
    var kinetikProgress by mutableStateOf(0f)
        private set
    var kinetikLabel by mutableStateOf("")
        private set
    var kinetik by mutableStateOf<KinetikResult?>(null)
        private set
    var kinetikSavedFile by mutableStateOf<File?>(null)
        private set

    var marathonRunning by mutableStateOf(false)
        private set
    var marathonProgress by mutableStateOf(0f)
        private set
    var marathonPhase by mutableStateOf("")
        private set
    var marathonDetail by mutableStateOf("")
        private set
    var marathonMinutes by mutableStateOf(15)
        private set
    var marathonStartedAt by mutableStateOf(0L)
        private set
    var marathon by mutableStateOf<MarathonResult?>(null)
        private set
    var marathonSavedFile by mutableStateOf<File?>(null)
        private set

    /** Series support (3 runs with cooling pauses). */
    var seriesRuns by mutableStateOf(1)
        private set
    var seriesIndex by mutableStateOf(0)
        private set
    var series by mutableStateOf<RepeatStats?>(null)
        private set

    /** Pre-flight gate */
    var conditions by mutableStateOf<ConditionsSnapshot?>(null)
        private set
    var equilibrium by mutableStateOf<Equilibrium?>(null)
        private set
    var blockedReasons by mutableStateOf<List<String>?>(null)
        private set
    var preflightBusy by mutableStateOf(false)
        private set

    /** Difference between this run and the previous comparable run, if any. */
    var repeatWarning by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var nativeAvailable by mutableStateOf(Native.available())
        private set
    var nativeError by mutableStateOf(Native.error())
        private set

    private val thermal = ThermalCollector(context)
    private var liveAt = 0L
    private var liveCpu: Float? = null
    private var liveBattery: Float? = null
    private var liveFreq = 0L

    val anyRunning: Boolean get() = marathonRunning || mikyasRunning || kinetikRunning

    // ------------------------------------------------------------- short suites

    fun runMikyas() {
        if (anyRunning) return
        mikyasRunning = true
        mikyasProgress = 0f
        mikyasLabel = "Hazırlanıyor…"
        error = null
        scope.launch {
            try {
                val result = MikyasRunner(context).run { label, progress ->
                    main.post {
                        mikyasLabel = label
                        mikyasProgress = progress.coerceIn(0f, 1f)
                    }
                }
                val file = withContext(Dispatchers.IO) {
                    try {
                        ResultStore.saveMikyas(context, result)
                    } catch (t: Throwable) {
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    mikyas = result
                    mikyasSavedFile = file
                    mikyasProgress = 1f
                    mikyasLabel = "Tamamlandı"
                    mikyasRunning = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = "${t.javaClass.simpleName}: ${t.message}"
                    mikyasRunning = false
                }
            }
        }
    }

    fun runKinetik() {
        if (anyRunning) return
        kinetikRunning = true
        kinetikProgress = 0f
        kinetikLabel = "Hazırlanıyor…"
        error = null
        scope.launch {
            try {
                val result = KinetikRunner(context).run { label, progress ->
                    main.post {
                        kinetikLabel = label
                        kinetikProgress = progress.coerceIn(0f, 1f)
                    }
                }
                val file = withContext(Dispatchers.IO) {
                    try {
                        ResultStore.saveKinetik(context, result)
                    } catch (t: Throwable) {
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    kinetik = result
                    kinetikSavedFile = file
                    kinetikProgress = 1f
                    kinetikLabel = "Tamamlandı"
                    kinetikRunning = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = "${t.javaClass.simpleName}: ${t.message}"
                    kinetikRunning = false
                }
            }
        }
    }

    // --------------------------------------------------------------- preflight

    /**
     * Reads the current conditions and records them. The UI calls this when the
     * Maraton card is opened and before starting; it never blocks on the
     * three-minute equilibrium window (that happens inside the run).
     */
    fun refreshConditions() {
        if (preflightBusy || marathonRunning) return
        preflightBusy = true
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                try {
                    com.alican.devicescope.ConditionCollector(context).snapshot()
                } catch (t: Throwable) {
                    null
                }
            }
            withContext(Dispatchers.Main) {
                if (snap != null) conditions = snap
                preflightBusy = false
            }
        }
    }

    // ---------------------------------------------------------------- marathon

    /**
     * Starts the long-form suite.
     *
     * [minutes] is clamped to at least 15. [runs] (1..3) repeats the whole
     * marathon with [cooldownMinutes] pauses, reporting median and range.
     * [force] starts despite blocking conditions; the reasons are stored.
     */
    fun runMarathon(
        minutes: Int = 15,
        force: Boolean = false,
        runs: Int = 1,
        cooldownMinutes: Int = 5,
        bringToFront: Boolean = true
    ) {
        if (anyRunning) return
        if (!nativeAvailable) {
            error = "Ölçüm çekirdeği yüklenemedi: ${nativeError ?: "bilinmeyen hata"}"
            return
        }

        val total = runs.coerceIn(1, 3)
        val config = MarathonRunner.Config(
            minutes = minutes.coerceAtLeast(15),
            force = force
        )
        // Test mode shows a countdown for the whole session including the 3 min
        // equilibration, the 2 min cooldown and the pauses between runs.
        val sessionMinutes = config.effectiveMinutes * total +
            total * ((config.equilibrationSeconds + config.cooldownSeconds) / 60) +
            (total - 1) * cooldownMinutes.coerceIn(1, 30)
        marathonRunning = true
        marathonProgress = 0f
        marathonPhase = "Hazırlanıyor"
        marathonDetail = ""
        marathonMinutes = config.effectiveMinutes
        marathonStartedAt = System.currentTimeMillis()
        marathon = null
        marathonSavedFile = null
        series = null
        seriesRuns = total
        seriesIndex = 0
        blockedReasons = null
        repeatWarning = null
        error = null

        TestMode.begin(sessionMinutes)
        try {
            TestModeService.start(context, sessionMinutes)
        } catch (_: Throwable) {
        }
        if (bringToFront) bringAppToFront()

        marathonJob = scope.launch {
            val collected = ArrayList<MarathonResult>()
            try {
                for (run in 0 until total) {
                    seriesIndex = run + 1
                    if (run > 0) {
                        marathonPhase = "Seriler arası soğuma"
                        val coolSeconds = cooldownMinutes.coerceIn(1, 30) * 60
                        for (left in coolSeconds downTo 1) {
                            marathonDetail = "Sonraki koşuya $left sn · cihaz soğuyor"
                            TestMode.phase = "Soğuma arası"
                            TestMode.detail = "Sonraki koşuya $left sn"
                            TestMode.progress = run.toFloat() / total
                            delay(1000L)
                        }
                    }

                    val base = run.toDouble() / total
                    val span = 1.0 / total
                    val result = MarathonRunner(context).run(config) { phase, detail, fraction ->
                        main.post {
                            val overall = (base + fraction * span).coerceIn(0.0, 1.0).toFloat()
                            marathonPhase = if (total > 1) "$phase · koşu ${run + 1}/$total" else phase
                            marathonDetail = detail
                            marathonProgress = overall
                            TestMode.phase = phase
                            TestMode.detail = detail
                            TestMode.progress = overall
                        }
                    }
                    val file = withContext(Dispatchers.IO) {
                        try {
                            ResultStore.saveMarathon(context, result)
                        } catch (t: Throwable) {
                            null
                        }
                    }
                    conditions = result.conditions
                    equilibrium = result.equilibrium
                    collected.add(result)

                    withContext(Dispatchers.Main) {
                        marathon = result
                        marathonSavedFile = file
                        conditions = result.conditions
                        equilibrium = result.equilibrium
                    }

                    if (total == 1) {
                        repeatWarning = withContext(Dispatchers.IO) {
                            Repeatability.compareWithPrevious(context, result)
                        }
                    }
                }

                if (collected.size > 1) {
                    series = Repeatability.summarise(collected.map { it.total })
                    val s = series
                    if (s != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                ResultStore.saveSeries(context, s, collected)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    if (s != null && s.unstable) {
                        repeatWarning = String.format(
                            Locale.US,
                            "Koşular arası fark %.1f%% (>%%5) — sonuç güvenilmez, koşulları kontrol edin",
                            s.spreadPercent
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    marathonProgress = 1f
                    marathonPhase = "Tamamlandı"
                    marathonDetail = if (collected.size > 1) "${collected.size} koşu tamamlandı" else "Rapor kaydedildi"
                    marathonRunning = false
                }
            } catch (c: CancellationException) {
                withContext(Dispatchers.Main) {
                    marathonPhase = "İptal edildi"
                    marathonRunning = false
                }
            } catch (b: MarathonRunner.ConditionsBlocked) {
                withContext(Dispatchers.Main) {
                    conditions = b.conditions
                    equilibrium = b.equilibrium
                    blockedReasons = b.reasons
                    error = "Koşullar uygun değil: " + b.reasons.joinToString(" · ")
                    marathonPhase = "Koşullar uygun değil"
                    marathonDetail = b.reasons.joinToString(" · ")
                    marathonRunning = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = "${t.javaClass.simpleName}: ${t.message}"
                    marathonPhase = "Durduruldu"
                    marathonRunning = false
                }
            } finally {
                VisualStage.reset()
                TestMode.end()
                seriesIndex = 0
                try {
                    TestModeService.stop(context)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun cancelMarathon() {
        marathonJob?.cancel()
        marathonJob = null
        VisualStage.reset()
        TestMode.end()
        try {
            TestModeService.stop(context)
        } catch (_: Throwable) {
        }
        if (marathonRunning) {
            marathonPhase = "İptal edildi"
            marathonRunning = false
        }
    }

    fun clearBlocked() {
        blockedReasons = null
        error = null
    }

    fun bringAppToFront() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------- live values

    data class Live(
        val cpuTemp: Float?,
        val cpuAvgTemp: Float?,
        val batteryTemp: Float?,
        val freqMhz: Long,
        val fps: Double,
        val thermalStatus: String,
        val thermalHeadroom: Float?
    )

    /** Cached for one second: sysfs reads are not free, the UI ticks every second. */
    fun live(): Live {
        val now = System.currentTimeMillis()
        if (now - liveAt > 900L) {
            liveAt = now
            try {
                val snap = thermal.read()
                liveCpu = snap.cpuC
                liveBattery = snap.batteryC
                liveFreq = snap.coreMhz.maxOrNull() ?: 0L
                liveAvg = snap.cpuAvgC
                liveStatus = snap.thermalStatusLabel
                liveHeadroom = snap.thermalHeadroom
            } catch (_: Throwable) {
            }
        }
        val fps = if (VisualStage.active) VisualStage.fps else 0.0
        return Live(liveCpu, liveAvg, liveBattery, liveFreq, fps, liveStatus ?: "—", liveHeadroom)
    }

    private var liveAvg: Float? = null
    private var liveStatus: String? = null
    private var liveHeadroom: Float? = null

    // ------------------------------------------------------------------- share

    fun shareText(suite: String): String? {
        return when (suite) {
            "Mikyas" -> mikyas?.let { r ->
                buildString {
                    appendLine("Mikyas · ${r.device}")
                    appendLine("Toplam: ${fmt1(r.total)}")
                    appendLine("Tek çekirdek: ${fmt1(r.singleScore)}")
                    appendLine("Çok çekirdek: ${fmt1(r.multiScore)}")
                    appendLine("Bellek: ${fmt1(r.memoryScore)}")
                    appendLine("Depolama: ${fmt1(r.storageScore)}")
                    appendLine("Isıl tepe: ${r.peakTempC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"}")
                    appendLine("Doğrulama: ${if (r.verified) "geçti" else "başarısız ${r.failedWorkloads}"}")
                    append("Maks. sapma (CV): ${String.format(Locale.US, "%.2f%%", r.maxCv)}")
                }
            }
            "Maraton" -> marathon?.let { r ->
                buildString {
                    appendLine("Maraton · ${r.device} · v${r.appVersion} (iş yükü v${r.workloadVersion})")
                    appendLine("Toplam: ${fmt1(r.total)}")
                    appendLine("Tek çekirdek: ${fmt1(r.singleScore)}")
                    appendLine("Çok çekirdek: ${fmt1(r.multiScore)}")
                    appendLine("Bellek: ${fmt1(r.memoryScore)}")
                    appendLine("Depolama: ${fmt1(r.storageScore)}")
                    appendLine("GPU: ${fmt1(r.gpuScore)} (${r.gpuApi})")
                    appendLine("Arayüz: ${fmt1(r.uxScore)}")
                    appendLine("Kararlılık: ${fmt1(r.stabilityScore)} (hız ${fmt1(r.stability.retentionScore)} · sapma ${fmt1(r.stability.consistencyScore)})")
                    appendLine("Hız koruma: ${r.retentionPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"}")
                    appendLine("CV tek/çok: ${String.format(Locale.US, "%.2f%% / %.2f%%", r.stability.singleCv, r.stability.multiCv)}")
                    appendLine("Süre: ${r.durationSeconds.toInt()} sn")
                    appendLine("Isıl tepe: ${r.peakTempC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"}")
                    r.energy?.avgWatts?.let { appendLine("Ortalama güç: ${String.format(Locale.US, "%.2f W", it)}") }
                    series?.let { s ->
                        appendLine("Koşular: ${s.totals.joinToString(" / ") { fmt1(it) }}")
                        appendLine("Medyan: ${fmt1(s.median)} ± ${fmt1(s.halfRange)}")
                    }
                    append("Koşul: ${r.conditions.batteryPercent?.let { "%$it" } ?: "—"} · " +
                        "şarj ${if (r.conditions.charging == true) "takılı" else "değil"} · " +
                        "güç tasarrufu ${if (r.conditions.powerSave == true) "açık" else "kapalı"}")
                }
            }
            else -> kinetik?.let { r ->
                buildString {
                    appendLine("Kinetik · ${r.device}")
                    appendLine("Toplam: ${fmt1(r.total)}")
                    appendLine("GPU: ${fmt1(r.gpuScore)}")
                    appendLine("Arayüz: ${fmt1(r.uxScore)}")
                    appendLine("Isıl dayanım: ${fmt1(r.sustainedScore)}")
                    append("GPU: ${r.gpu.renderer}")
                }
            }
        }
    }

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    companion object {
        @Volatile
        private var instance: BenchController? = null

        fun shared(context: Context): BenchController =
            instance ?: synchronized(this) {
                instance ?: BenchController(context.applicationContext).also { instance = it }
            }
    }
}
