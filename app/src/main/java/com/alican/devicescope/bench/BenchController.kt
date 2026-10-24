package com.alican.devicescope.bench

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Drives both suites and exposes their state to Compose. */
class BenchController(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Owned by the controller, not by the composable: leaving the Test tab must
     * not cancel a running measurement (which would also leave the running flag
     * stuck and lose 60+ seconds of work).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    var error by mutableStateOf<String?>(null)
        private set

    var nativeAvailable by mutableStateOf(Native.available())
        private set
    var nativeError by mutableStateOf(Native.error())
        private set

    fun runMikyas() {
        if (mikyasRunning) return
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
        if (kinetikRunning) return
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

    fun shareText(suite: String): String? {
        return when (suite) {
            "Mikyas" -> mikyas?.let { r ->
                buildString {
                    appendLine("Mikyas · ${r.device}")
                    appendLine("Toplam: ${fmt(r.total)}")
                    appendLine("Tek çekirdek: ${fmt(r.singleScore)}")
                    appendLine("Çok çekirdek: ${fmt(r.multiScore)}")
                    appendLine("Bellek: ${fmt(r.memoryScore)}")
                    appendLine("Depolama: ${fmt(r.storageScore)}")
                    appendLine("Isıl tepe: ${r.peakTempC?.let { String.format(java.util.Locale.US, "%.1f °C", it) } ?: "—"}")
                    appendLine("Doğrulama: ${if (r.verified) "geçti" else "başarısız ${r.failedWorkloads}"}")
                    append("Maks. sapma (CV): ${String.format(java.util.Locale.US, "%.2f%%", r.maxCv)}")
                }
            }
            else -> kinetik?.let { r ->
                buildString {
                    appendLine("Kinetik · ${r.device}")
                    appendLine("Toplam: ${fmt(r.total)}")
                    appendLine("GPU: ${fmt(r.gpuScore)}")
                    appendLine("Arayüz: ${fmt(r.uxScore)}")
                    appendLine("Isıl dayanım: ${fmt(r.sustainedScore)}")
                    append("GPU: ${r.gpu.renderer}")
                }
            }
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
