package com.alican.devicescope.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.alican.devicescope.bench.CheckRow
import com.alican.devicescope.bench.CrossDevice
import com.alican.devicescope.bench.SeriesStat
import com.alican.devicescope.bench.ValidationReport
import com.alican.devicescope.bench.ValidationRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * State holder for the validation battery. It owns its coroutine scope so the
 * run keeps going when the user switches tabs, and cancels it when the screen is
 * disposed (which stops every later measurement instead of leaking a thread).
 */
class ValidationController(private val context: Context) {

    var running by mutableStateOf(false)
        private set
    var label by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var report by mutableStateOf<ValidationReport?>(null)
        private set
    var files by mutableStateOf<List<File>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start() {
        if (running) return
        running = true
        error = null
        files = emptyList()
        report = null
        label = "Hazırlanıyor"
        progress = 0f

        job = scope.launch {
            try {
                val runner = ValidationRunner(context)
                val result = runner.run { step, fraction ->
                    label = step
                    progress = fraction
                }
                report = result
                label = "Rapor yazılıyor"
                files = runner.writeReports(result)
                label = "Tamamlandı"
                progress = 1f
            } catch (t: Throwable) {
                error = "${t.javaClass.simpleName}: ${t.message ?: "bilinmeyen hata"}"
                label = "Durduruldu"
            } finally {
                running = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        running = false
        label = "İptal edildi"
    }

    fun dispose() {
        job?.cancel()
        job = null
    }
}

@Composable
fun ValidationView() {
    val p = LocalSoft.current
    val context = LocalContext.current
    val controller = remember { ValidationController(context) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    ScreenScroll {
        SoftCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            SoftText("Doğrulama", size = 22, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            SoftText(
                "Ölçümün kendisini sınayan paket: 30 tekrar, bilinen cevaplar, derleyici hilesi, " +
                    "ölçekleme, bellek ve depolama hiyerarşisi, GPU kare dağılımı, puanlama aritmetiği.",
                size = 13,
                color = p.text2
            )
            Spacer(Modifier.height(16.dp))
            if (controller.running) {
                SoftText(controller.label, size = 13, color = p.text2, maxLines = 2)
                Spacer(Modifier.height(10.dp))
                SoftBar(progress = controller.progress, color = p.accent, height = 9.dp)
                Spacer(Modifier.height(8.dp))
                SoftText(
                    "${(controller.progress * 100).toInt()}% · ekranı kapatmayın, cihazı serin tutun",
                    size = 12, color = p.text3
                )
                Spacer(Modifier.height(12.dp))
                SoftButton("İptal", onClick = { controller.cancel() }, primary = false, danger = true)
            } else {
                SoftButton(
                    if (controller.report == null) "Doğrulamayı çalıştır  ·  ≈4–6 dk" else "Yeniden çalıştır",
                    onClick = { controller.start() },
                    modifier = Modifier.fillMaxWidth()
                )
                errorText(controller.error)
            }
        }

        val report = controller.report
        if (report != null) {
            Spacer(Modifier.height(12.dp))
            SummaryCard(report)

            val failed = report.failed
            if (failed.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SoftDisclosure(
                    title = "Başarısız kontroller",
                    subtitle = "Ölçüm kendi kuralını sağlamadı; sonuç yayınlanmamalı",
                    trailing = "${failed.size}",
                    defaultOpen = true
                ) {
                    failed.forEachIndexed { index, c ->
                        if (index > 0) SoftDivider()
                        SoftText("${c.group} · ${c.label}", size = 13, weight = FontWeight.SemiBold, color = p.crit)
                        Spacer(Modifier.height(3.dp))
                        SoftText("ölçülen: ${c.measured} · beklenen: ${c.expected}", size = 12, color = p.text2)
                        if (c.note.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            SoftText(c.note, size = 12, color = p.text3)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "İstatistik",
                subtitle = "n · ortanca · CV · %95 güven aralığı · aykırı değer",
                trailing = "${report.stats.size} seri"
            ) {
                StatsBody(report.stats)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Kontroller",
                subtitle = "Tüm doğrulama satırları, grup grup",
                trailing = "${report.checks.size}"
            ) {
                CheckGroupsBody(report)
            }

            if (report.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SoftDisclosure(
                    title = "Notlar",
                    subtitle = "Paketin kendi gözlemleri",
                    trailing = "${report.notes.size}"
                ) {
                    report.notes.forEach {
                        SoftText("• $it", size = 12, color = p.text2)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Raporları paylaş",
                subtitle = "Markdown özet + ham CSV + istatistik + puanlama girdileri",
                trailing = "${controller.files.size}"
            ) {
                ShareFilesBody(controller.files)
            }
        }

        Spacer(Modifier.height(10.dp))
        CrossDeviceCard()

        Spacer(Modifier.height(10.dp))
        SoftDisclosure(
            title = "Bu cihazda doğrulanamayanlar",
            subtitle = "Uydurulmuş sayı yerine açıkça yazılır",
            defaultOpen = false
        ) {
            listOf(
                "20 °C / 30 °C sabit ortam sıcaklığı — uygulama ortamı kontrol edemez; başlangıç sıcaklığı kaydedilir.",
                "0/5/15 arka plan uygulaması — Android başka süreçleri saydırmaz.",
                "Yeniden paketleme testi — cihaz kendi APK'sını yeniden imzalayamaz; paket + imza özeti kaydedilir.",
                "Geekbench/AnTuTu Spearman korelasyonu — aynı cihazların iki puanı gerekir; n=1 ile tanımsız.",
                "15 dakikalık ısıl dayanıklılık koşusu — ayrı uzun test, bu paketin dışında."
            ).forEach {
                SoftText("• $it", size = 12, color = p.text2)
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun errorText(message: String?) {
    if (message == null) return
    Spacer(Modifier.height(10.dp))
    SoftText(message, size = 12, color = LocalSoft.current.crit, mono = true)
}

@Composable
private fun SummaryCard(report: ValidationReport) {
    val p = LocalSoft.current
    val passed = report.checks.count { it.pass == true }
    val failed = report.checks.count { it.pass == false }
    val info = report.checks.count { it.pass == null }

    SoftCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SoftText(report.device, size = 20, weight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                SoftText(
                    "${report.abi} · SDK ${report.sdk} · ${"%.1f".format(Locale.US, report.durationSeconds)} sn",
                    size = 12, color = p.text3
                )
            }
            SoftText(
                SimpleDateFormat("HH:mm", Locale.US).format(Date(report.timestamp)),
                size = 12, color = p.text3, mono = true
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            SoftText("$passed", size = 40, weight = FontWeight.Bold, color = p.accent)
            SoftText(
                " / ${report.checks.size} kontrol geçti",
                size = 13,
                color = p.text3,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        SoftBar(
            progress = if (report.checks.isEmpty()) 0f else passed.toFloat() / report.checks.size,
            color = if (failed == 0) p.accent else p.crit,
            height = 9.dp
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SoftChip("$failed başarısız", if (failed == 0) p.accent else p.crit)
            SoftChip("$info bilgi", p.text2)
            if (failed == 0) SoftChip("Kurallar sağlandı", p.accent)
        }
        Spacer(Modifier.height(10.dp))
        SoftText(
            if (failed == 0)
                "Bu, cihazın hızlı olduğu anlamına gelmez; ölçümün kendi doğruluk kurallarını sağladığı anlamına gelir."
            else
                "En az bir kural sağlanmadı. Ayrıntı aşağıdaki listede; sonuç yayınlanmamalı.",
            size = 12,
            color = p.text3
        )
    }
}

@Composable
private fun StatsBody(stats: List<SeriesStat>) {
    val p = LocalSoft.current
    if (stats.isEmpty()) {
        SoftText("İstatistik üretilemedi.", size = 12, color = p.text2)
    }
    stats.forEachIndexed { index, s ->
        if (index > 0) SoftDivider()
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SoftText(s.label, size = 13, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                SoftText(
                    "CV " + String.format(Locale.US, "%.1f%%", s.cvPercent),
                    size = 12,
                    color = if (s.cvPercent < 5.0) p.accent else p.crit,
                    mono = true
                )
            }
            Spacer(Modifier.height(3.dp))
            SoftText(
                "n=${s.n} · ort " + String.format(Locale.US, "%.2f", s.median) +
                    " · " + String.format(Locale.US, "%.2f", s.ciLow) + "–" +
                    String.format(Locale.US, "%.2f", s.ciHigh) +
                    " · aykırı ${s.outliers} ${s.unit}",
                size = 11, color = p.text3, mono = true
            )
        }
    }
}

@Composable
private fun CheckGroupsBody(report: ValidationReport) {
    val p = LocalSoft.current
    val groups = report.checks.map { it.group }.distinct()
    groups.forEachIndexed { index, group ->
        if (index > 0) SoftDivider()
        val rows = report.checks.filter { it.group == group }
        val failed = rows.count { it.pass == false }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftText(group, size = 13, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SoftText(
                if (failed == 0) "${rows.size}/${rows.size}" else "$failed hata",
                size = 12,
                color = if (failed == 0) p.accent else p.crit,
                mono = true
            )
        }
        Spacer(Modifier.height(4.dp))
        rows.forEach { c -> CheckLine(c) }
    }
}

@Composable
private fun CheckLine(c: CheckRow) {
    val p = LocalSoft.current
    val color = when (c.pass) {
        true -> p.accent
        false -> p.crit
        null -> p.text2
    }
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SoftText(c.label, size = 12, color = p.text, maxLines = 2, modifier = Modifier.weight(1f))
            SoftText(
                when (c.pass) {
                    true -> "geçti"
                    false -> "kaldı"
                    null -> "bilgi"
                },
                size = 11, color = color, weight = FontWeight.SemiBold
            )
        }
        SoftText(c.measured, size = 11, color = color, mono = true)
        if (c.note.isNotBlank()) {
            SoftText(c.note, size = 11, color = p.text3, maxLines = 3)
        }
    }
}

@Composable
private fun ShareFilesBody(files: List<File>) {
    val p = LocalSoft.current
    val context = LocalContext.current
    if (files.isEmpty()) {
        SoftText("Rapor dosyası yazılmadı.", size = 12, color = p.text3)
        return
    }
    files.forEachIndexed { index, file ->
        if (index > 0) SoftDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { share(context, file) }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoftText(file.name, size = 12, maxLines = 1, modifier = Modifier.weight(1f))
            SoftText("paylaş", size = 12, color = p.accent, weight = FontWeight.SemiBold)
        }
    }
}

private fun share(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension) {
                "csv" -> "text/csv"
                else -> "text/markdown"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (t: Throwable) {
        // nothing to do: the file stays in cache/shared and can be pulled via adb
    }
}

@Composable
private fun CrossDeviceCard() {
    val p = LocalSoft.current
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<CrossDevice.PairResult>>(emptyList()) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (text.isNullOrBlank()) {
                error = "Dosya boş okundu."
                return@rememberLauncherForActivityResult
            }
            results = CrossDevice.correlate(CrossDevice.parse(text))
            fileName = uri.lastPathSegment?.substringAfterLast('/')
            error = null
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    SoftDisclosure(
        title = "Cihazlar arası tutarlılık",
        subtitle = "Geekbench/AnTuTu CSV'si ile Spearman sıralama korelasyonu",
        trailing = if (results.isEmpty()) null else "${results.size}"
    ) {
        SoftText(
            "Aynı satırda hem referans hem DeviceScope puanı olmalı (ör. `geekbench_single` ve " +
                "`mikyas_single`). Hedef: toplam ρ > 0,9 · kategoriler > 0,8.",
            size = 12, color = p.text2
        )
        Spacer(Modifier.height(10.dp))
        SoftButton(
            "CSV seç",
            onClick = {
                picker.launch(arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel", "*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
            primary = false
        )
        if (fileName != null) {
            Spacer(Modifier.height(8.dp))
            SoftText(fileName!!, size = 11, color = p.text3, mono = true, maxLines = 1)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            SoftText(error!!, size = 12, color = p.crit, mono = true)
        }

        if (results.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            results.forEachIndexed { index, r ->
                if (index > 0) SoftDivider()
                val color = when (r.pass) {
                    true -> p.accent
                    false -> p.crit
                    null -> p.text2
                }
                SoftText(
                    "${r.tag}: " + (r.spearman?.let { String.format(Locale.US, "ρ = %.3f", it) } ?: "hesaplanamadı") +
                        " (n=${r.n}, hedef ≥ ${String.format(Locale.US, "%.1f", r.target)})",
                    size = 13, weight = FontWeight.SemiBold, color = color
                )
                SoftText("${r.referenceColumn} ↔ ${r.oursColumn}", size = 11, color = p.text3, mono = true)
                SoftText(r.note, size = 11, color = p.text3)
                if (r.deviations.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SoftText("En çok sapan cihazlar:", size = 11, color = p.text2)
                    r.deviations.forEach { d ->
                        SoftText(
                            "• ${d.device} — sıra ${d.referenceRank.toInt()}→${d.oursRank.toInt()} " +
                                "(Δ${String.format(Locale.US, "%+.0f", d.rankDelta)}) · " +
                                "ref ${String.format(Locale.US, "%.0f", d.reference)}, " +
                                "bizim ${String.format(Locale.US, "%.0f", d.ours)}",
                            size = 11, color = p.text3
                        )
                    }
                }
            }
        }
    }
}
