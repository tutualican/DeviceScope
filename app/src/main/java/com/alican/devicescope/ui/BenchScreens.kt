package com.alican.devicescope.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alican.devicescope.bench.BenchController
import com.alican.devicescope.bench.KinetikResult
import com.alican.devicescope.bench.Measurement
import com.alican.devicescope.bench.MikyasResult
import com.alican.devicescope.bench.MonitorSamples
import com.alican.devicescope.bench.ResultStore
import com.alican.devicescope.bench.ShareCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun n1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun pct(v: Double): String = String.format(Locale.US, "%+.1f%%", v)
private fun temp(v: Float?): String =
    v?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"

private fun duration(seconds: Double): String {
    val s = seconds.toInt()
    return if (s >= 60) "${s / 60} dk ${s % 60} sn" else "$s sn"
}

@Composable
private fun cvColor(cv: Double): Color {
    val p = LocalSoft.current
    return when {
        cv <= 2.0 -> p.accent
        cv <= 5.0 -> p.warm
        else -> p.crit
    }
}

@Composable
fun TestScreen(state: AppState, bench: BenchController) {
    val p = LocalSoft.current
    var sub by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            SoftTitle("Test", "Ölçüm, kıyas ve kendi kendini doğrulama")
            Spacer(Modifier.height(10.dp))
            SoftSegmented(
                items = listOf("Maraton", "Mikyas", "Kinetik", "Kıyas", "Doğrulama"),
                selected = sub,
                onSelect = { sub = it }
            )
            Spacer(Modifier.height(4.dp))
        }

        if (sub < 3 && !bench.nativeAvailable) {
            Box(Modifier.padding(18.dp)) {
                SoftCard(Modifier.fillMaxWidth()) {
                    SoftText("Ölçüm çekirdeği yüklenemedi", size = 15, weight = FontWeight.SemiBold, color = p.crit)
                    Spacer(Modifier.height(6.dp))
                    SoftText(bench.nativeError ?: "Bilinmeyen hata", size = 12, color = p.text2, mono = true)
                    Spacer(Modifier.height(8.dp))
                    SoftText(
                        "Bu yapı ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")} için derlendi. " +
                            "Cihazın ABI'si desteklenmiyorsa test çalışmaz.",
                        size = 12,
                        color = p.text3
                    )
                }
            }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (sub) {
                0 -> MaratonView(bench)
                1 -> MikyasView(bench)
                2 -> KinetikView(bench)
                3 -> CompareView(bench)
                else -> ValidationView()
            }
        }
    }
}

// --------------------------------------------------------------------- Maraton

@Composable
private fun MaratonView(bench: BenchController) {
    val p = LocalSoft.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableStateOf(15) }

    ScreenScroll {
        SoftCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            SoftText("Maraton", size = 22, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            SoftText(
                "Tek çekirdek, çok çekirdek, bellek, depolama, GPU, ekran sahnesi ve ısıl kararlılık " +
                    "tek koşuda. Süre bir alt sınırdır: 15 dakikadan önce bitmez.",
                size = 13,
                color = p.text2
            )
            Spacer(Modifier.height(14.dp))
            SoftSegmented(
                items = listOf("15 dk", "30 dk", "45 dk"),
                selected = when (minutes) {
                    30 -> 1
                    45 -> 2
                    else -> 0
                },
                onSelect = { minutes = listOf(15, 30, 45)[it] }
            )
            Spacer(Modifier.height(14.dp))

            if (bench.marathonRunning) {
                ProgressCard(
                    "${bench.marathonPhase} · ${bench.marathonDetail}",
                    bench.marathonProgress
                )
                Spacer(Modifier.height(10.dp))
                SoftButton(
                    "Testi durdur",
                    onClick = { bench.cancelMarathon() },
                    modifier = Modifier.fillMaxWidth(),
                    danger = true
                )
            } else {
                SoftButton(
                    (if (bench.marathon == null) "Maratonu başlat  ·  $minutes dk"
                    else "Yeniden ölç  ·  $minutes dk"),
                    onClick = { bench.runMarathon(minutes) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))
            SoftText(
                "Başlarken telefon test moduna girer: tam ekran görsel sahne, ekran açık, parlaklık %100 " +
                    "ve ön plan servisi. Rootsuz bir uygulama diğer uygulamaları askıya alamaz; bu yüzden " +
                    "test sırasında ekranın kapanmadığı ayrıca kontrol edilir ve raporda yazar.",
                size = 11,
                color = p.text3
            )
        }

        bench.error?.let {
            Spacer(Modifier.height(10.dp))
            EmptyNote("Hata: $it")
        }

        val r = bench.marathon
        if (r != null) {
            Spacer(Modifier.height(12.dp))
            SoftHeroScore(
                label = "MARATON PUANI",
                score = r.total,
                subtitle = "${r.device} · ${duration(r.durationSeconds)}\n" +
                    "${r.config.effectiveMinutes} dk hedef · ${r.rounds.size} sürdürülen tur",
                fraction = (r.total / 1600.0).toFloat(),
                badge = when {
                    !r.verified -> "Doğrulama hatası"
                    r.throttled -> "Isıl kısıtlama saptandı"
                    else -> "Kararlı koşu"
                },
                badgeColor = when {
                    !r.verified -> p.crit
                    r.throttled -> p.warm
                    else -> p.accent
                }
            )

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Bileşen puanları",
                subtitle = "Ağırlıklı ortalama; eksik bileşen varsa kalanlar normalize edilir",
                defaultOpen = true
            ) {
                ScoreRow("Tek çekirdek · %18", r.singleScore, p.accent)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Çok çekirdek · %22", r.multiScore, p.cool)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Bellek · %8", r.memoryScore, p.warm)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Depolama · %8", r.storageScore, p.hot)
                Spacer(Modifier.height(12.dp))
                ScoreRow("GPU · %20", r.gpuScore, p.cool)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Görsel/arayüz · %12", r.uxScore, p.accent)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Kararlılık · %12", r.stabilityScore, p.warm)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Isıl kararlılık",
                subtitle = "Sürdürülen yük boyunca hız koruma ve sapma",
                trailing = r.retentionPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
            ) {
                StabilityBody(r)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Tek çekirdek ölçümleri",
                subtitle = "Geekbench tarzı: 7 iş yükü, tek iş parçacığı",
                trailing = "${r.single.size}"
            ) {
                MeasurementTable(r.single)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Çok çekirdek ölçümleri",
                subtitle = "${r.cores} çekirdek · ${r.multi.firstOrNull()?.threads ?: 0} iş parçacığı",
                trailing = "${r.multi.size}"
            ) {
                MeasurementTable(r.multi)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "GPU ve görsel sahne",
                subtitle = "Vulkan compute + ekranda gerçek kare ölçümü",
                trailing = r.gpuApi
            ) {
                SoftKeyValue("API", r.gpuApi, if (r.gpuApi == "Vulkan") p.accent else p.warm)
                SoftKeyValue("Aygıt", r.gpu?.renderer ?: "—", p.text2)
                SoftKeyValue(
                    "Compute",
                    r.gpu?.computeGops?.let { String.format(Locale.US, "%.1f Gop/s", it) } ?: "ölçülemedi",
                    if (r.gpu?.computeGops != null) p.accent else p.warm
                )
                SoftKeyValue(
                    "Çevrimdışı sahne",
                    r.gpu?.sceneFps?.let { String.format(Locale.US, "%.0f fps (1024²)", it) } ?: "ölçülemedi",
                    if (r.gpu?.sceneFps != null) p.cool else p.warm
                )
                val scene = r.scene
                if (scene != null) {
                    SoftDivider()
                    SoftKeyValue("Ekran sahnesi", String.format(Locale.US, "%.1f fps", scene.avgFps), p.accent)
                    SoftKeyValue("Ortanca kare", String.format(Locale.US, "%.2f ms", scene.medianFrameMs), p.text2)
                    SoftKeyValue("95. yüzdelik", String.format(Locale.US, "%.2f ms", scene.p95FrameMs), p.text2)
                    SoftKeyValue("Takılma", String.format(Locale.US, "%.1f%%", scene.jankPercent), cvColor(scene.jankPercent))
                    SoftKeyValue("Ölçülen kare", "${scene.frames} / ${scene.durationMs / 1000} sn", p.text3)
                } else {
                    Spacer(Modifier.height(6.dp))
                    SoftText(
                        "Ekran sahnesi ölçülemedi (uygulama ön planda değildi veya sahne iptal edildi).",
                        size = 12,
                        color = p.warm
                    )
                }
                r.gpuNote?.let {
                    Spacer(Modifier.height(6.dp))
                    SoftText(it, size = 12, color = p.warm)
                }
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Sistem izleme",
                subtitle = "0,5 sn'de bir sıcaklık, frekans ve batarya",
                trailing = "${r.monitor.cpuTemps.size} örnek"
            ) {
                MonitorBody(r.monitor)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Bu puan nasıl doğrulandı?",
                subtitle = "Bilinen-cevap, sağlama, uzun süreli tur ve ekran kontrolü",
                trailing = if (r.verified) "geçti" else "hata"
            ) {
                SoftKeyValue(
                    "Bilinen-cevap testleri",
                    if (r.verified) "tümü geçti" else "başarısız: ${r.failedWorkloads}",
                    if (r.verified) p.accent else p.crit
                )
                SoftKeyValue("Sağlama toplamı", if ((r.single + r.multi).all { it.checksumStable }) "eşleşti" else "EŞLEŞMEDİ",
                    if ((r.single + r.multi).all { it.checksumStable }) p.accent else p.crit)
                SoftKeyValue(
                    "Ekran açık kaldı",
                    if (r.screenChecks == 0) "kontrol edilemedi"
                    else if (r.screenKeptOn) "evet (${r.screenChecks} kontrol)"
                    else "HAYIR (${r.screenOffChecks}/${r.screenChecks})",
                    when {
                        r.screenChecks == 0 -> p.text3
                        r.screenKeptOn -> p.accent
                        else -> p.crit
                    }
                )
                Spacer(Modifier.height(8.dp))
                SoftText(
                    "Puanlar ölçülen hızın sabit referansa oranıdır (1000 = referans). Kararlılık puanı " +
                        "hız koruma oranı ile sürdürülen ölçümlerin sapmasından hesaplanır; formül " +
                        "aşağıdaki açıklamada yazar.",
                    size = 12,
                    color = p.text3
                )
            }

            ResultActions(
                filePath = bench.marathonSavedFile?.absolutePath,
                shareText = bench.shareText("Maraton"),
                onShareCard = {
                    val card = ShareCard.render(
                        context = context,
                        suite = "Maraton",
                        score = r.total,
                        subtitle = "${r.device} · ${r.config.effectiveMinutes} dk",
                        rows = listOf(
                            "Tek çekirdek" to n1(r.singleScore),
                            "Çok çekirdek" to n1(r.multiScore),
                            "Bellek" to n1(r.memoryScore),
                            "Depolama" to n1(r.storageScore),
                            "GPU" to n1(r.gpuScore),
                            "Görsel/arayüz" to n1(r.uxScore),
                            "Kararlılık" to n1(r.stabilityScore),
                            "Hız koruma" to (r.retentionPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"),
                            "Isıl tepe" to temp(r.peakTempC),
                            "Süre" to duration(r.durationSeconds)
                        ),
                        footer = "Puanlar ölçülen hızın sabit referansa oranıdır (1000 = referans). " +
                            "Sürdürülen turlar, sıcaklık eğrisi ve ham metrikler uygulamada görünür."
                    )
                    scope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            ShareCard.share(context, card, "Maraton sonucunu paylaş")
                        }
                    }
                }
            )

            Spacer(Modifier.height(14.dp))
        }

        SoftDisclosure(
            title = "Maraton nasıl ölçüyor?",
            subtitle = "Sıra, süre garantisi, kararlılık formülü ve sınırlar"
        ) {
            SoftText(
                "Sıra: bilinen-cevap doğrulaması → tek çekirdek (7 iş yükü) → çok çekirdek → bellek → " +
                    "128 MB depolama → Vulkan GPU → ekranda görsel sahne → sürdürülen tur döngüsü. " +
                    "Sürdürülen döngü, duvar saati hedef süreye ulaşana kadar tek/çok çekirdek yükünü " +
                    "dönüşümlü uygular ve yaklaşık saniyede bir sıcaklık/frekans kaydeder.",
                size = 12,
                color = p.text3
            )
            Spacer(Modifier.height(8.dp))
            SoftText(
                "Kararlılık = 1000 × (son üçte bir ortancası / ilk üçte bir ortancası) × " +
                    "(1 − sürdürülen ölçümlerin CV'si). %100 koruma ve sıfır sapma 1000 puandır; " +
                    "1100 ile sınırlanır. En az 6 sürdürülen örnek yoksa kararlılık puanı üretilmez.",
                size = 12,
                color = p.text3
            )
            Spacer(Modifier.height(8.dp))
            SoftText(
                "Sınırlar: ortam sıcaklığı sabitlenemez, diğer uygulamalar rootsuz askıya alınamaz, " +
                    "Vulkan sürücüsü reddederse GLES 3.1 yedek yolu kullanılır ve raporda yazar. " +
                    "Bu değerler Geekbench veya AnTuTu'nun kendi puanları değildir; aynı iş yükü " +
                    "sınıflarını kendi referans sabitlerimizle ölçen bağımsız puanlardır.",
                size = 12,
                color = p.text3
            )
        }

        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun StabilityBody(r: com.alican.devicescope.bench.MarathonResult) {
    val p = LocalSoft.current
    val retention = r.retentionPercent
    SoftKeyValue(
        "Hız koruma oranı",
        retention?.let { String.format(Locale.US, "%.1f%%", it) } ?: "yetersiz örnek",
        when {
            retention == null -> p.text3
            retention >= 90 -> p.accent
            retention >= 80 -> p.warm
            else -> p.crit
        }
    )
    SoftKeyValue(
        "Kararlılık puanı",
        n1(r.stabilityScore),
        if (r.stabilityScore >= 850) p.accent else if (r.stabilityScore >= 650) p.warm else p.crit
    )
    SoftKeyValue(
        "Sürdürülen sapma (CV)",
        String.format(Locale.US, "%.2f%%", r.stabilityCv),
        cvColor(r.stabilityCv)
    )
    SoftKeyValue(
        "Sıcaklık",
        "${temp(r.startTempC)} → ${temp(r.peakTempC)} → ${temp(r.endTempC)}",
        p.tempColor(r.peakTempC)
    )
    SoftKeyValue(
        "Sürdürülen tur",
        "${r.rounds.size} tur · dönüşümlü tek/çok çekirdek",
        p.text3
    )
    if (r.rounds.size > 4) {
        Spacer(Modifier.height(12.dp))
        SoftText("Sürdürülen hız indeksi", size = 12, color = p.text3)
        Spacer(Modifier.height(6.dp))
        Sparkline(r.rounds.map { it.index }, p.accent)
    }
    if (r.tempSamples.size > 4) {
        Spacer(Modifier.height(12.dp))
        SoftText("CPU sıcaklığı (°C)", size = 12, color = p.text3)
        Spacer(Modifier.height(6.dp))
        Sparkline(r.tempSamples.map { it.second.toDouble() }, p.hot)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.throttled) SoftChip("ısıl kısıtlama", p.warm) else SoftChip("koruma ≥ %85", p.accent)
        if (r.screenKeptOn) SoftChip("ekran açık", p.accent) else SoftChip("ekran kontrolü eksik", p.text3)
    }
    Spacer(Modifier.height(8.dp))
    SoftText(
        "Oran, ilk üçte birlik dilimin ortancasına göre son üçte birlik dilimin ortancasıdır. " +
            "Düşük oran cihazın yük altında hız kıstığını gösterir; sıcaklık eğrisi bunu doğrular.",
        size = 12,
        color = p.text3
    )
}


// ---------------------------------------------------------------------- Mikyas

@Composable
private fun MikyasView(bench: BenchController) {
    val p = LocalSoft.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ScreenScroll {
        SoftCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            SoftText("Mikyas", size = 22, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            SoftText(
                "İşlemci, bellek ve depolama hızı. 7 C++ iş yükü, 1 ısınma turu, 3 ölçüm ortancası.",
                size = 13,
                color = p.text2
            )
            Spacer(Modifier.height(16.dp))
            if (bench.mikyasRunning) {
                ProgressCard(bench.mikyasLabel, bench.mikyasProgress)
            } else {
                SoftButton(
                    if (bench.mikyas == null) "Testi başlat  ·  ≈2 dk" else "Yeniden ölç  ·  ≈2 dk",
                    onClick = { bench.runMikyas() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        bench.error?.let {
            Spacer(Modifier.height(10.dp))
            EmptyNote("Hata: $it")
        }

        val r = bench.mikyas
        if (r != null) {
            val previous = remember(r.timestamp) { ResultStore.previousMikyas(context, r) }

            Spacer(Modifier.height(12.dp))
            SoftHeroScore(
                label = "MİKYAS PUANI",
                score = r.total,
                subtitle = "${r.device} · ${duration(r.durationSeconds)}\n" +
                    "Maksimum sapma %.2f%% · %d ölçüm".format(Locale.US, r.maxCv, r.repeats),
                fraction = (r.total / 1600.0).toFloat(),
                badge = if (r.verified) "Bilinen-cevap doğrulandı" else "Doğrulama hatası",
                badgeColor = if (r.verified) p.accent else p.crit
            )

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Kategoriler",
                subtitle = "Her kategori kendi içinde geometrik ortalama",
                defaultOpen = true,
                trailing = null
            ) {
                ScoreRow("Tek çekirdek (CPU)", r.singleScore, p.accent)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Çok çekirdek (CPU)", r.multiScore, p.cool)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Bellek", r.memoryScore, p.warm)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Depolama", r.storageScore, p.hot)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Tekrarlanabilirlik",
                subtitle = "Aynı cihazda tekrarlar arası sapma, hedef ≤ %3",
                trailing = String.format(Locale.US, "%.2f%%", r.maxCv)
            ) {
                RepeatabilityBody(r, previous)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Tek çekirdek ölçümleri",
                subtitle = "İş yükü · ortalama · sapma",
                trailing = "${r.single.size}"
            ) {
                MeasurementTable(r.single)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Çok çekirdek ölçümleri",
                subtitle = "${r.cores} çekirdek · ${r.multi.firstOrNull()?.threads ?: 0} iş parçacığı",
                trailing = "${r.multi.size}"
            ) {
                MeasurementTable(r.multi)
            }

            r.memory?.let { m ->
                Spacer(Modifier.height(10.dp))
                SoftDisclosure(
                    title = "Bellek",
                    subtitle = "Kopyalama bant genişliği ve sağlama toplamı",
                    trailing = String.format(Locale.US, "%.2f%%", m.cvPercent)
                ) {
                    SoftKeyValue("Bant genişliği", m.raw, p.accent)
                    SoftKeyValue("Sapma (CV)", String.format(Locale.US, "%.2f%%", m.cvPercent), cvColor(m.cvPercent))
                    SoftKeyValue(
                        "Sağlama toplamı",
                        if (m.checksumStable) "eşleşti" else "EŞLEŞMEDİ",
                        if (m.checksumStable) p.accent else p.crit
                    )
                }
            }

            r.storage?.let { s ->
                Spacer(Modifier.height(10.dp))
                SoftDisclosure(
                    title = "Depolama",
                    subtitle = "${s.sizeMb} MB · fsync'li sıralı yazma/okuma",
                    trailing = String.format(Locale.US, "%.0f MB/s", s.writeMbPerSec)
                ) {
                    SoftKeyValue("Sıralı yazma", String.format(Locale.US, "%.0f MB/s", s.writeMbPerSec), p.accent)
                    SoftKeyValue("Sıralı okuma", String.format(Locale.US, "%.0f MB/s", s.readMbPerSec), p.cool)
                    SoftKeyValue("Yazma sapması", String.format(Locale.US, "%.2f%%", s.cvWrite), cvColor(s.cvWrite))
                    SoftKeyValue("Okuma sapması", String.format(Locale.US, "%.2f%%", s.cvRead), cvColor(s.cvRead))
                }
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Sistem izleme",
                subtitle = "Test boyunca 0,5 sn'de bir sıcaklık, frekans ve batarya",
                trailing = "${r.monitor.cpuTemps.size} örnek"
            ) {
                MonitorBody(r.monitor)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Bu puan nasıl doğrulandı?",
                subtitle = "Bilinen-cevap testleri, ısınma turu, ortanca",
                trailing = if (r.verified) "geçti" else "hata"
            ) {
                SoftKeyValue(
                    "Bilinen-cevap testleri",
                    if (r.verified) "tümü geçti (${r.single.size + r.multi.size + 2} ölçüm)"
                    else "başarısız: ${r.failedWorkloads}",
                    if (r.verified) p.accent else p.crit
                )
                SoftKeyValue("Maks. sapma (CV)", String.format(Locale.US, "%.2f%%", r.maxCv), cvColor(r.maxCv))
                SoftKeyValue("Isınma turu", "1 (atıldı)", p.text2)
                SoftKeyValue("Ölçüm turu", "${r.repeats} (ortanca)", p.text2)
                Spacer(Modifier.height(8.dp))
                SoftText(
                    "Puanlar, her iş yükünün ölçülen hızının sabit bir referans hıza oranıdır (1000 = referans). " +
                        "Referans sabitleri geçicidir; asıl kanıt her satırdaki ham metrik ve sapma yüzdesidir.",
                    size = 12,
                    color = p.text3
                )
            }

            ResultActions(
                filePath = bench.mikyasSavedFile?.absolutePath,
                shareText = bench.shareText("Mikyas"),
                onShareCard = {
                    val card = ShareCard.render(
                        context = context,
                        suite = "Mikyas",
                        score = r.total,
                        subtitle = r.device,
                        rows = listOf(
                            "Tek çekirdek" to n1(r.singleScore),
                            "Çok çekirdek" to n1(r.multiScore),
                            "Bellek" to n1(r.memoryScore),
                            "Depolama" to n1(r.storageScore),
                            "Maks. sapma" to String.format(Locale.US, "%.2f%%", r.maxCv),
                            "Isıl tepe" to temp(r.peakTempC),
                            "Süre" to duration(r.durationSeconds)
                        ),
                        footer = "Puanlar ölçülen hızın sabit referansa oranıdır (1000 = referans). " +
                            "Ham metrikler ve sapma yüzdeleri uygulamada görünür."
                    )
                    scope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            ShareCard.share(context, card, "Mikyas sonucunu paylaş")
                        }
                    }
                }
            )

            Spacer(Modifier.height(14.dp))
        }

        SoftDisclosure(
            title = "Mikyas nasıl ölçüyor?",
            subtitle = "Yöntem, doğrulama ve sınırlar"
        ) {
            SoftText(
                "7 C++ iş yükü tek ve çok çekirdekte ölçülür. Her iş yükü önce bilinen-cevaplı " +
                    "doğrulama testinden geçer, sonra 1 ısınma turu atılır ve 3 ölçümün ortancası alınır. " +
                    "Hedef: aynı cihazda tekrarlar arası sapma ≤ %3.",
                size = 12,
                color = p.text3
            )
        }

        Spacer(Modifier.height(14.dp))
    }
}

// --------------------------------------------------------------------- Kinetik

@Composable
private fun KinetikView(bench: BenchController) {
    val p = LocalSoft.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ScreenScroll {
        SoftCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            SoftText("Kinetik", size = 22, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            SoftText(
                "GPU, arayüz akıcılığı ve 60 sn ısıl dayanım. Gerçek Vulkan, gerekirse GLES 3.1.",
                size = 13,
                color = p.text2
            )
            Spacer(Modifier.height(16.dp))
            if (bench.kinetikRunning) {
                ProgressCard(bench.kinetikLabel, bench.kinetikProgress)
            } else {
                SoftButton(
                    if (bench.kinetik == null) "Testi başlat  ·  ≈80 sn" else "Yeniden ölç  ·  ≈80 sn",
                    onClick = { bench.runKinetik() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        bench.error?.let {
            Spacer(Modifier.height(10.dp))
            EmptyNote("Hata: $it")
        }

        val r = bench.kinetik
        if (r != null) {
            Spacer(Modifier.height(12.dp))
            SoftHeroScore(
                label = "KİNETİK PUANI",
                score = r.total,
                subtitle = "${r.device} · ${r.gpuApi} · ${duration(r.durationSeconds)}",
                fraction = (r.total / 1400.0).toFloat(),
                badge = if (r.gpuApi == "Vulkan") "Vulkan doğrulandı" else "GLES yedek yol",
                badgeColor = if (r.gpuApi == "Vulkan") p.accent else p.warm
            )

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Kategoriler",
                subtitle = "Ağırlıklar: CPU %40 · GPU %30 · bellek+disk %15 · arayüz %15",
                defaultOpen = true
            ) {
                ScoreRow("GPU", r.gpuScore, p.accent)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Arayüz akıcılığı", r.uxScore, p.cool)
                Spacer(Modifier.height(12.dp))
                ScoreRow("Isıl dayanım", r.sustainedScore, p.warm)
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "GPU",
                subtitle = "Compute ve çevrimdışı sahne",
                trailing = r.gpuApi
            ) {
                SoftKeyValue("API", r.gpuApi, if (r.gpuApi == "Vulkan") p.accent else p.warm)
                SoftKeyValue("Aygıt", r.gpu.renderer, p.text2)
                SoftKeyValue("Sürücü", r.gpu.glVersion, p.text2)
                SoftKeyValue(
                    "Compute",
                    r.gpu.computeGops?.let { String.format(Locale.US, "%.1f Gop/s", it) } ?: "ölçülemedi",
                    if (r.gpu.computeGops != null) p.accent else p.warm
                )
                SoftKeyValue(
                    "Sahne",
                    r.gpu.sceneFps?.let { String.format(Locale.US, "%.0f fps (1024²)", it) } ?: "ölçülemedi",
                    if (r.gpu.sceneFps != null) p.cool else p.warm
                )
                r.gpuNote?.let {
                    Spacer(Modifier.height(6.dp))
                    SoftText(it, size = 12, color = p.warm)
                }
            }

            r.ux?.let { ux ->
                Spacer(Modifier.height(10.dp))
                SoftDisclosure(
                    title = "Arayüz akıcılığı",
                    subtitle = "4,5 sn gerçek kare ölçümü",
                    trailing = String.format(Locale.US, "%.1f fps", ux.avgFps)
                ) {
                    SoftKeyValue("Ortalama", String.format(Locale.US, "%.1f fps", ux.avgFps), p.accent)
                    SoftKeyValue("Ortanca kare", String.format(Locale.US, "%.2f ms", ux.medianFrameMs), p.text2)
                    SoftKeyValue("95. yüzdelik", String.format(Locale.US, "%.2f ms", ux.p95FrameMs), p.text2)
                    SoftKeyValue("En kötü kare", String.format(Locale.US, "%.2f ms", ux.worstFrameMs), p.text2)
                    SoftKeyValue("Takılma", String.format(Locale.US, "%.1f%%", ux.jankPercent), cvColor(ux.jankPercent))
                    SoftKeyValue("Kare sayısı", "${ux.frames}", p.text3)
                }
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Açılış süresi",
                subtitle = "Süreç başlangıcı → ilk kare",
                trailing = r.launchMs?.let { "$it ms" } ?: "—"
            ) {
                SoftKeyValue(
                    "DeviceScope açılışı",
                    r.launchMs?.let { "$it ms (soğuk başlangıç)" } ?: "ölçülmedi",
                    if (r.launchMs == null) p.text3 else if (r.launchMs <= 1200) p.accent else p.warm
                )
                Spacer(Modifier.height(6.dp))
                SoftText(
                    "Süreç oluşturulma anı Android'in kendi sayaçından, bitiş anı ilk Choreographer " +
                        "kare geri çağrısından okunur; tahmin değildir.",
                    size = 12,
                    color = p.text3
                )
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Isıl dayanım",
                subtitle = "60 sn tam yük sonrası hız koruma oranı",
                trailing = r.sustainedRetention?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
            ) {
                val retention = r.sustainedRetention
                SoftKeyValue(
                    "Hız koruma oranı",
                    retention?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                    when {
                        retention == null -> p.text3
                        retention >= 85 -> p.accent
                        retention >= 65 -> p.warm
                        else -> p.crit
                    }
                )
                if (r.sustainedSamples.size > 4) {
                    Spacer(Modifier.height(12.dp))
                    SoftText("Hız (saniyelik)", size = 12, color = p.text3)
                    Spacer(Modifier.height(6.dp))
                    Sparkline(r.sustainedSamples.map { it.second }, p.accent)
                }
                if (r.tempSamples.size > 4) {
                    Spacer(Modifier.height(12.dp))
                    SoftText("Sıcaklık (°C)", size = 12, color = p.text3)
                    Spacer(Modifier.height(6.dp))
                    Sparkline(r.tempSamples.map { it.second.toDouble() }, p.hot)
                }
                Spacer(Modifier.height(8.dp))
                SoftText(
                    "Oran %100 ise cihaz yük altında hız kaybetmedi. Düşük oran ısıl kısıtlamayı gösterir.",
                    size = 12,
                    color = p.text3
                )
            }

            Spacer(Modifier.height(10.dp))
            SoftDisclosure(
                title = "Sistem izleme",
                subtitle = "Test boyunca 0,5 sn'de bir sıcaklık, frekans ve batarya",
                trailing = "${r.monitor.cpuTemps.size} örnek"
            ) {
                MonitorBody(r.monitor)
            }

            ResultActions(
                filePath = bench.kinetikSavedFile?.absolutePath,
                shareText = bench.shareText("Kinetik"),
                onShareCard = {
                    val card = ShareCard.render(
                        context = context,
                        suite = "Kinetik",
                        score = r.total,
                        subtitle = "${r.device} · ${r.gpuApi}",
                        rows = listOf(
                            "GPU" to n1(r.gpuScore),
                            "Arayüz" to n1(r.uxScore),
                            "Isıl dayanım" to n1(r.sustainedScore),
                            "Compute" to (r.gpu.computeGops?.let { String.format(Locale.US, "%.1f Gop/s", it) } ?: "—"),
                            "Sahne" to (r.gpu.sceneFps?.let { String.format(Locale.US, "%.0f fps", it) } ?: "—"),
                            "Koruma oranı" to (r.sustainedRetention?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"),
                            "Açılış" to (r.launchMs?.let { "$it ms" } ?: "—")
                        ),
                        footer = "GPU ölçümü ${r.gpuApi} üzerinde yapıldı. Puanlar ölçülen hızın sabit " +
                            "referansa oranıdır (1000 = referans); ham Gop/s ve fps değerleri uygulamada görünür."
                    )
                    scope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            ShareCard.share(context, card, "Kinetik sonucunu paylaş")
                        }
                    }
                }
            )

            Spacer(Modifier.height(14.dp))
        }

        SoftDisclosure(
            title = "Kinetik nasıl ölçüyor?",
            subtitle = "GPU, arayüz, ısı ve sınırlar"
        ) {
            SoftText(
                "GPU: gerçek Vulkan — 16 MiB SSBO üzerinde 96 tur tamsayı ALU compute ve " +
                    "1024×1024 çevrimdışı sahne (piksel başına 64 sin/cos). Gölgelendiriciler derleme " +
                    "sırasında SPIR-V'ye çevrilip doğrulanır; sürücü Vulkan'ı reddederse OpenGL ES 3.1'e " +
                    "düşer ve bunu açıkça yazar. Arayüz: 4,5 sn gerçek kare ölçümü. " +
                    "Isıl dayanım: 60 sn tam yük, hız koruma oranı.",
                size = 12,
                color = p.text3
            )
            Spacer(Modifier.height(8.dp))
            SoftText(
                "Puanlar, her iş yükünün ölçülen hızının sabit bir referans hıza oranıdır (1000 = referans). " +
                    "Referans sabitleri geçicidir; asıl kanıt her satırdaki ham metrik ve sapma yüzdesidir.",
                size = 12,
                color = p.text3
            )
        }

        Spacer(Modifier.height(14.dp))
    }
}

// ------------------------------------------------------------------ comparison

@Composable
private fun CompareView(bench: BenchController) {
    val p = LocalSoft.current
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val runs = remember(refresh, bench.mikyas?.timestamp, bench.kinetik?.timestamp) {
        ResultStore.summaries(context)
    }

    ScreenScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftText("Kayıtlı sonuçlar", size = 15, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SoftLink("Yenile") { refresh++ }
        }
        Spacer(Modifier.height(6.dp))

        if (runs.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            EmptyNote("Henüz kayıtlı sonuç yok. Mikyas veya Kinetik çalıştırınca burada listelenir.")
            Spacer(Modifier.height(12.dp))
            SoftDisclosure(title = "Kıyas ne yapıyor?") {
                SoftText(
                    "Her test sonucu cihazın kendi deposuna JSON olarak yazılır. Bu ekran o dosyaları " +
                        "okur; farklı cihazların sonuçları aynı listede görünür ama cihaz adı ayrı yazılır. " +
                        "Yüzde, aynı testin en iyi sonucuna göre farktır.",
                    size = 12,
                    color = p.text3
                )
            }
            return@ScreenScroll
        }

        listOf("Maraton", "Mikyas", "Kinetik").forEach { suite ->
            val mine = runs.filter { it.suite == suite }
            if (mine.isEmpty()) return@forEach
            val best = mine.maxOf { it.total }
            SoftSectionTitle(suite, "${mine.size} kayıt · en iyi ${n1(best)}")
            SoftCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                mine.sortedByDescending { it.total }.forEachIndexed { index, run ->
                    if (index > 0) SoftDivider()
                    val delta = if (best > 0) (run.total - best) / best * 100.0 else 0.0
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SoftText(
                                "#${index + 1}",
                                size = 12,
                                color = if (index == 0) p.accent else p.text3,
                                weight = FontWeight.SemiBold,
                                mono = true
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                SoftText(run.device, size = 13, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                SoftText(
                                    SimpleDateFormat("d MMM yyyy · HH:mm", Locale("tr", "TR"))
                                        .format(Date(run.timestamp)) +
                                        (run.api?.let { " · $it" } ?: ""),
                                    size = 11,
                                    color = p.text3,
                                    maxLines = 1
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                SoftText(n1(run.total), size = 16, color = p.accent, weight = FontWeight.Bold, mono = true)
                                if (index > 0) {
                                    SoftText(pct(delta), size = 11, color = if (delta >= 0) p.accent else p.warm, mono = true)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SoftBar(
                            progress = (run.total / (best * 1.08).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f),
                            color = if (index == 0) p.accent else p.cool,
                            height = 6.dp
                        )
                        val detail = buildList {
                            run.single?.let { add("tek ${n1(it)}") }
                            run.multi?.let { add("çok ${n1(it)}") }
                            run.gpu?.let { add("gpu ${n1(it)}") }
                            run.ux?.let { add("arayüz ${n1(it)}") }
                            run.sustained?.let { add("ısı ${n1(it)}") }
                            run.stability?.let { add("kararlılık ${n1(it)}") }
                        }
                        if (detail.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            SoftText(detail.joinToString("  ·  "), size = 11, color = p.text3, maxLines = 1)
                        }
                    }
                }
            }
        }

        // explicit run-to-run comparison for the current device
        val mikyasRuns = runs.filter { it.suite == "Mikyas" }.sortedByDescending { it.timestamp }
        if (mikyasRuns.size >= 2) {
            val last = mikyasRuns[0]
            val prev = mikyasRuns[1]
            SoftSectionTitle("Son iki Mikyas", "tekrarlanabilirlik kanıtı")
            SoftCard(Modifier.fillMaxWidth()) {
                CompareRow("Toplam", prev.total, last.total)
                CompareRow("Tek çekirdek", prev.single ?: 0.0, last.single ?: 0.0)
                CompareRow("Çok çekirdek", prev.multi ?: 0.0, last.multi ?: 0.0)
                CompareRow("Bellek", prev.memory ?: 0.0, last.memory ?: 0.0)
                CompareRow("Depolama", prev.storage ?: 0.0, last.storage ?: 0.0)
                Spacer(Modifier.height(8.dp))
                SoftText(
                    "Fark, aynı cihazda iki ayrı çalıştırma arasındaki orandır. %3'ün altı hedeflenen " +
                        "tekrarlanabilirlik aralığıdır.",
                    size = 12,
                    color = p.text3
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        SoftDisclosure(title = "Kıyas ne yapıyor?") {
            SoftText(
                "Her test sonucu cihazın kendi deposuna JSON olarak yazılır. Bu ekran o dosyaları " +
                    "okur; farklı cihazların sonuçları aynı listede görünür ama cihaz adı ayrı yazılır. " +
                    "Yüzde, aynı testin en iyi sonucuna göre farktır.",
                size = 12,
                color = p.text3
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun CompareRow(label: String, before: Double, after: Double) {
    val p = LocalSoft.current
    val delta = if (before > 0) (after - before) / before * 100.0 else 0.0
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        SoftText(label, size = 12, color = p.text2, modifier = Modifier.weight(1f))
        SoftText(n1(before), size = 12, color = p.text3, mono = true)
        SoftText("  →  ", size = 12, color = p.text3)
        SoftText(n1(after), size = 13, color = p.accent, mono = true, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        SoftText(
            pct(delta),
            size = 12,
            color = when {
                kotlin.math.abs(delta) <= 3.0 -> p.accent
                kotlin.math.abs(delta) <= 6.0 -> p.warm
                else -> p.crit
            },
            mono = true
        )
    }
}

// ------------------------------------------------------------------ components

@Composable
private fun RepeatabilityBody(r: MikyasResult, previous: ResultStore.RunSummary?) {
    val p = LocalSoft.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        SoftText("Maks. sapma (CV)", size = 13, color = p.text2, modifier = Modifier.weight(1f))
        SoftText(
            String.format(Locale.US, "%.2f%%", r.maxCv),
            size = 17,
            color = cvColor(r.maxCv),
            weight = FontWeight.Bold,
            mono = true
        )
    }
    Spacer(Modifier.height(8.dp))
    SoftBar(progress = (r.maxCv / 5.0).toFloat().coerceIn(0f, 1f), color = cvColor(r.maxCv), height = 8.dp)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.maxCv <= 3.0) SoftChip("≤ %3 kararlı", p.accent) else SoftChip("> %3 kararsız", p.warm)
        if (r.allStable) SoftChip("sağlama eşleşti", p.accent) else SoftChip("sağlama hatası", p.crit)
    }

    if (previous != null) {
        Spacer(Modifier.height(12.dp))
        SoftDivider()
        SoftText("Önceki çalıştırmaya göre", size = 12, color = p.text3, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        CompareRow("Toplam", previous.total, r.total)
        previous.single?.let { CompareRow("Tek çekirdek", it, r.singleScore) }
        previous.multi?.let { CompareRow("Çok çekirdek", it, r.multiScore) }
    } else {
        Spacer(Modifier.height(10.dp))
        SoftText(
            "Bu cihazda önceki bir Mikyas kaydı yok. İkinci çalıştırmadan sonra burada " +
                "çalıştırmalar arası fark görünür — gerçek tekrarlanabilirlik kanıtı odur.",
            size = 12,
            color = p.text3
        )
    }
}

@Composable
private fun MonitorBody(m: MonitorSamples) {
    val p = LocalSoft.current
    SoftKeyValue("CPU sıcaklığı", "${temp(m.minCpu)} → ${temp(m.peakCpu)}", p.tempColor(m.peakCpu))
    SoftKeyValue(
        "Batarya sıcaklığı",
        m.batteryTemps.takeIf { it.isNotEmpty() }?.let { temp(it.maxOrNull()) } ?: "—",
        p.tempColor(m.peakBattery)
    )
    SoftKeyValue(
        "Çekirdek frekansı",
        if (m.minFreq != null && m.maxFreq != null) "${m.minFreq} – ${m.maxFreq} MHz" else "—",
        p.text2
    )
    SoftKeyValue(
        "Batarya seviyesi",
        run {
            val start = m.batteryStart
            val end = m.batteryEnd
            if (start != null && end != null) "$start% → $end% (${end - start} puan)" else "—"
        },
        p.text2
    )
    SoftKeyValue("Örnek sayısı", "${m.cpuTemps.size}", p.text3)
    Spacer(Modifier.height(6.dp))
    SoftText(
        "Değerler test sırasında 0,5 saniyede bir okunur. Sensör yoksa satır boş kalır, uydurulmaz.",
        size = 12,
        color = p.text3
    )
}

@Composable
private fun ScoreRow(label: String, value: Double, color: Color) {
    val p = LocalSoft.current
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            SoftText(label, size = 13, color = p.text2, modifier = Modifier.weight(1f))
            SoftText(n1(value), size = 17, color = color, weight = FontWeight.Bold, mono = true)
        }
        Spacer(Modifier.height(7.dp))
        SoftBar(progress = (value / 3000.0).toFloat().coerceIn(0f, 1f), color = color, height = 7.dp)
    }
}

@Composable
private fun MeasurementTable(list: List<Measurement>) {
    val p = LocalSoft.current
    list.forEachIndexed { index, m ->
        if (index > 0) SoftDivider()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                SoftText(m.workload.title, size = 13, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                SoftText(
                    "${m.raw} · CV ${String.format(Locale.US, "%.1f%%", m.cvPercent)}" +
                        if (m.checksumStable) "" else " · SAĞLAMA HATASI",
                    size = 11,
                    color = if (m.checksumStable && m.cvPercent <= 3.0) p.text3
                    else if (!m.checksumStable) p.crit else p.warm,
                    maxLines = 1
                )
            }
            SoftText(n1(m.index), size = 14, color = p.accent, weight = FontWeight.SemiBold, mono = true)
        }
    }
}

@Composable
private fun ProgressCard(label: String, progress: Float) {
    val p = LocalSoft.current
    Column(Modifier.fillMaxWidth()) {
        SoftText(label, size = 13, color = p.text2, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        SoftBar(progress = progress, color = p.accent, height = 9.dp)
        Spacer(Modifier.height(6.dp))
        SoftText("${(progress * 100).toInt()}%", size = 11, color = p.text3, mono = true)
    }
}

@Composable
private fun ResultActions(filePath: String?, shareText: String?, onShareCard: (() -> Unit)? = null) {
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton(
                if (copied) "Kopyalandı" else "Metin olarak paylaş",
                onClick = {
                    shareText?.let {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, it)
                        }
                        context.startActivity(
                            Intent.createChooser(intent, "Sonucu paylaş")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                primary = false,
                modifier = Modifier.weight(1f)
            )
            if (onShareCard != null) {
                SoftButton(
                    "Kart olarak paylaş",
                    onClick = { onShareCard() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (filePath != null) {
            SoftButton("JSON yolunu kopyala", onClick = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DeviceScope JSON", filePath))
                copied = true
            }, primary = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(2.dp))
            SoftText("Kayıt: $filePath", size = 11, color = LocalSoft.current.text3, mono = true, maxLines = 2)
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, color: Color) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0.0001 } ?: 1.0

    SoftWell(Modifier.fillMaxWidth().height(72.dp)) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stepX = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - ((v - min) / span * size.height).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                fill,
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent))
            )
            drawCircle(
                color = color,
                radius = 3.2f,
                center = Offset(size.width, size.height - ((values.last() - min) / span * size.height).toFloat())
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SoftText(String.format(Locale.US, "%.1f", min), size = 11, color = LocalSoft.current.text3, mono = true)
        SoftText(String.format(Locale.US, "%.1f", max), size = 11, color = LocalSoft.current.text3, mono = true)
    }
}
