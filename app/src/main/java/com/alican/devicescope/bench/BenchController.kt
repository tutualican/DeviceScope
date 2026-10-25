package com.alican.devicescope.bench

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alican.devicescope.MainActivity
import com.alican.devicescope.ThermalCollector
import com.alican.devicescope.TestMode
import com.alican.devicescope.TestModeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Drives every suite and exposes the state to Compose *and* to the LAN panel.
 *
 * One instance exists per process ([shared]); the activity, the foreground
 * service and the HTTP server all talk to the same object, so a test started
 * from the browser updates the phone screen and vice versa.
 */
class BenchController private constructor(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Owned by the controller, not by the composable: leaving the Test tab must
     * not cancel a running measurement (which would also leave the running flag
     * stuck and lose 15+ minutes of work).
     */
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
        if (mikyasRunning || marathonRunning) return
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
        if (kinetikRunning || marathonRunning) return
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

    // ---------------------------------------------------------------- marathon

    /**
     * Starts the long-form suite. [minutes] is clamped to at least 15 by the
     * runner; passing a smaller value cannot shorten it.
     */
    fun runMarathon(minutes: Int = 15, bringToFront: Boolean = true) {
        if (anyRunning) return
        if (!nativeAvailable) {
            error = "Ölçüm çekirdeği yüklenemedi: ${nativeError ?: "bilinmeyen hata"}"
            return
        }

        val config = MarathonRunner.Config(minutes = minutes.coerceAtLeast(15))
        marathonRunning = true
        marathonProgress = 0f
        marathonPhase = "Hazırlanıyor"
        marathonDetail = ""
        marathonMinutes = config.effectiveMinutes
        marathonStartedAt = System.currentTimeMillis()
        marathon = null
        error = null

        TestMode.begin(config.effectiveMinutes)
        try {
            TestModeService.start(context, config.effectiveMinutes)
        } catch (_: Throwable) {
        }
        if (bringToFront) bringAppToFront()

        marathonJob = scope.launch {
            try {
                val result = MarathonRunner(context).run(config) { phase, detail, fraction ->
                    main.post {
                        marathonPhase = phase
                        marathonDetail = detail
                        marathonProgress = fraction.coerceIn(0f, 1f)
                        TestMode.phase = phase
                        TestMode.progress = fraction.coerceIn(0f, 1f)
                        TestMode.detail = detail
                    }
                }
                val file = withContext(Dispatchers.IO) {
                    try {
                        ResultStore.saveMarathon(context, result)
                    } catch (t: Throwable) {
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    marathon = result
                    marathonSavedFile = file
                    marathonProgress = 1f
                    marathonPhase = "Tamamlandı"
                    marathonDetail = "Rapor kaydedildi"
                    marathonRunning = false
                }
            } catch (c: CancellationException) {
                withContext(Dispatchers.Main) {
                    marathonPhase = "İptal edildi"
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
        val batteryTemp: Float?,
        val freqMhz: Long,
        val fps: Double
    )

    /** Cached for one second: the panel polls once a second, sysfs reads are not free. */
    fun live(): Live {
        val now = System.currentTimeMillis()
        if (now - liveAt > 900L) {
            liveAt = now
            try {
                val snap = thermal.read()
                liveCpu = snap.cpuC
                liveBattery = snap.batteryC
                liveFreq = snap.coreMhz.maxOrNull() ?: 0L
            } catch (_: Throwable) {
            }
        }
        val fps = if (VisualStage.active) VisualStage.fps else 0.0
        return Live(liveCpu, liveBattery, liveFreq, fps)
    }

    // -------------------------------------------------------------- web status

    /** JSON consumed by the browser panel roughly once a second. */
    fun statusJson(): String {
        val live = live()
        val suite = when {
            marathonRunning -> "Maraton"
            mikyasRunning -> "Mikyas"
            kinetikRunning -> "Kinetik"
            else -> ""
        }
        val progress = when {
            marathonRunning -> marathonProgress
            mikyasRunning -> mikyasProgress
            kinetikRunning -> kinetikProgress
            else -> 0f
        }
        val phase = when {
            marathonRunning -> marathonPhase
            mikyasRunning -> mikyasLabel
            kinetikRunning -> kinetikLabel
            else -> ""
        }
        val detail = when {
            marathonRunning -> marathonDetail
            else -> ""
        }
        val elapsed = if (marathonRunning) {
            ((System.currentTimeMillis() - marathonStartedAt) / 1000L).coerceAtLeast(0)
        } else 0L
        val remaining = if (marathonRunning) {
            (marathonMinutes * 60L - elapsed).coerceAtLeast(0)
        } else 0L

        return buildString {
            append('{')
            append("\"nativeAvailable\":").append(nativeAvailable).append(',')
            append("\"running\":").append(anyRunning).append(',')
            append("\"suite\":").append(q(suite)).append(',')
            append("\"phase\":").append(q(phase)).append(',')
            append("\"detail\":").append(q(detail)).append(',')
            append("\"progress\":").append(fmt(progress.toDouble(), 4)).append(',')
            append("\"scene\":").append(VisualStage.active).append(',')
            append("\"minutes\":").append(if (marathonRunning) marathonMinutes else 0).append(',')
            append("\"elapsedSeconds\":").append(elapsed).append(',')
            append("\"remainingSeconds\":").append(remaining).append(',')
            append("\"live\":{")
            append("\"cpu\":").append(num(live.cpuTemp?.toDouble())).append(',')
            append("\"battery\":").append(num(live.batteryTemp?.toDouble())).append(',')
            append("\"freqMhz\":").append(live.freqMhz).append(',')
            append("\"fps\":").append(fmt(live.fps, 1))
            append("},")
            append("\"error\":").append(q(error))
            append('}')
        }
    }

    /** Full detail of the last marathon: series for charts, not just the summary. */
    fun detailJson(): String {
        marathon?.let { return MarathonJson.encode(it) }
        val newest = ResultStore.list(context)
            .firstOrNull { it.name.startsWith("maraton_") }
        if (newest != null) {
            try {
                return newest.readText()
            } catch (_: Throwable) {
            }
        }
        return "{\"available\":false}"
    }

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
                    appendLine("Maraton · ${r.device}")
                    appendLine("Toplam: ${fmt1(r.total)}")
                    appendLine("Tek çekirdek: ${fmt1(r.singleScore)}")
                    appendLine("Çok çekirdek: ${fmt1(r.multiScore)}")
                    appendLine("Bellek: ${fmt1(r.memoryScore)}")
                    appendLine("Depolama: ${fmt1(r.storageScore)}")
                    appendLine("GPU: ${fmt1(r.gpuScore)} (${r.gpuApi})")
                    appendLine("Arayüz: ${fmt1(r.uxScore)}")
                    appendLine("Kararlılık: ${fmt1(r.stabilityScore)}")
                    appendLine("Süre: ${r.durationSeconds.toInt()} sn")
                    appendLine("Isıl tepe: ${r.peakTempC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"}")
                    appendLine("Hız koruma: ${r.retentionPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"}")
                    append("Sürdürülen CV: ${String.format(Locale.US, "%.2f%%", r.stabilityCv)}")
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

    private fun fmt(v: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", v)

    private fun num(v: Double?): String =
        if (v == null || v.isNaN() || v.isInfinite()) "null" else fmt(v, 1)

    private fun q(s: String?): String {
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

    companion object {
        @Volatile
        private var instance: BenchController? = null

        fun shared(context: Context): BenchController =
            instance ?: synchronized(this) {
                instance ?: BenchController(context.applicationContext).also { instance = it }
            }
    }
}
