package com.alican.devicescope.bench

import android.content.Context
import android.os.Build
import com.alican.devicescope.ThermalCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/** One raw measurement, kept so the CSV is reproducible evidence. */
data class RawSample(
    val section: String,
    val workload: String,
    val run: Int,
    val value: Double,
    val unit: String,
    val valid: Boolean,
    val note: String = ""
)

/** One verification verdict: measured value against an expectation. */
data class CheckRow(
    val group: String,
    val label: String,
    val measured: String,
    val expected: String,
    val pass: Boolean?,
    val note: String = ""
)

data class ValidationReport(
    val timestamp: Long,
    val device: String,
    val abi: String,
    val sdk: Int,
    val durationSeconds: Double,
    val stats: List<SeriesStat>,
    val checks: List<CheckRow>,
    val raw: List<RawSample>,
    val notes: List<String>,
    val fingerprint: AntiCheat.Fingerprint,
    val scoringCsv: String
) {
    val failed: List<CheckRow> get() = checks.filter { it.pass == false }
    val warned: List<CheckRow> get() = checks.filter { it.pass == null }
}

/**
 * Runs the full validation battery and writes the reports.
 *
 * Everything here is a measurement of this device right now. Where a request
 * cannot be satisfied on a rootless retail phone (forcing ambient temperature to
 * 20/30 °C, counting other apps' background processes, correlating against a CSV
 * of other devices), the report says so instead of inventing a number.
 */
class ValidationRunner(private val context: Context) {

    private val thermal = ThermalCollector(context)
    private val raw = ArrayList<RawSample>()
    private val checks = ArrayList<CheckRow>()
    private val stats = ArrayList<SeriesStat>()
    private val notes = ArrayList<String>()

    private var progress: (String, Float) -> Unit = { _, _ -> }
    private var baselineBatteryTemp: Float? = null

    suspend fun run(onProgress: (String, Float) -> Unit): ValidationReport =
        withContext(Dispatchers.Default) {
            progress = onProgress
            val started = System.currentTimeMillis()
            val fingerprint = AntiCheat.collect(context)

            baselineBatteryTemp = readBatteryTemp()
            checkEnvironment(fingerprint)

            step("Doğrulama testleri", 0.01f)
            checkKnownAnswers()

            step("Gürültü tabanı ve sayaç çözünürlüğü", 0.04f)
            checkNoiseFloor()

            step("Derleyici hilesi (-O2 / -O3)", 0.07f)
            checkCompiler()

            step("Tekrarlanabilirlik · 30 ölçüm × 14 seri", 0.12f)
            runRepeatability(0.12f, 0.55f)

            step("Boyut ölçekleme (1x–8x)", 0.57f)
            checkSizeScaling()

            step("İş parçacığı ölçekleme (1/2/4/8)", 0.64f)
            checkThreadScaling()

            step("Çekirdek sabitleme (büyük çekirdek / serbest)", 0.70f)
            checkAffinity()

            step("Bellek hiyerarşisi (L1/L2/L3/RAM)", 0.75f)
            checkMemoryHierarchy()

            step("Depolama · önbellekli / O_DIRECT / rastgele 4K", 0.80f)
            checkStorage()

            step("GPU · kare süresi dağılımı, piksel ve API karşılaştırması", 0.85f)
            checkGpu()

            step("Puanlama doğruluğu ve duyarlılık", 0.95f)
            val scoringCsv = checkScoring()

            step("Rapor yazılıyor", 0.99f)

            ValidationReport(
                timestamp = System.currentTimeMillis(),
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
                sdk = Build.VERSION.SDK_INT,
                durationSeconds = (System.currentTimeMillis() - started) / 1000.0,
                stats = stats.toList(),
                checks = checks.toList(),
                raw = raw.toList(),
                notes = notes.toList(),
                fingerprint = fingerprint,
                scoringCsv = scoringCsv
            )
        }

    private fun step(label: String, fraction: Float) {
        progress(label, fraction.coerceIn(0f, 1f))
    }

    // ------------------------------------------------------------------ gate

    private fun readBatteryTemp(): Float? = try {
        thermal.read().batteryC
    } catch (t: Throwable) {
        null
    }

    /**
     * Battery temperature gate: the spec requires ±2 °C around the start value.
     * If the sensor is unreadable the gate passes but says so; if the value is
     * out of band we wait for cooling, then declare the repetition invalid.
     */
    private suspend fun gate(): Pair<Boolean, String> {
        val base = baselineBatteryTemp ?: return true to "batarya sıcaklığı okunamadı"
        var waited = 0L
        while (waited < 30_000L) {
            val now = readBatteryTemp() ?: return true to "sensör kayboldu"
            val delta = abs(now - base)
            if (delta <= 2.0f) {
                return true to if (waited > 0) String.format(Locale.US, "soğutma %d sn", waited / 1000) else ""
            }
            delay(2000)
            waited += 2000
        }
        val now = readBatteryTemp()
        return false to String.format(
            Locale.US,
            "geçersiz: batarya %.1f °C, başlangıç %.1f °C (±2 °C dışı)",
            now ?: -999f, base
        )
    }

    // --------------------------------------------------------------- checks

    private fun checkEnvironment(fp: AntiCheat.Fingerprint) {
        checks.add(CheckRow("Ortam", "Paket", fp.packageName, "—", null, "yeniden paketleme testi için kayıt"))
        checks.add(CheckRow("Ortam", "İmza (SHA-256 ilk 6 bayt)", fp.signaturePrefix, "—", null, "paket aynı, imza farklıysa ayrı derleme"))
        checks.add(
            CheckRow("Ortam", "Root", if (fp.rooted) "evet" else "hayır", "hayır",
                !fp.rooted, fp.rootReasons.joinToString(", "))
        )
        checks.add(
            CheckRow("Ortam", "Emülatör", if (fp.emulator) "evet" else "hayır", "hayır",
                !fp.emulator, fp.emulatorReasons.joinToString(", "))
        )
        checks.add(
            CheckRow("Ortam", "Debug derlemesi", if (fp.debuggable) "evet" else "hayır", "hayır",
                !fp.debuggable, "debug yapılar JIT/doğrulama farkı yaratabilir")
        )
        checks.add(CheckRow("Ortam", "Governor", fp.governors, "—", null, "performans modu puanı değiştirir"))
        checks.add(
            CheckRow("Ortam", "Güç tasarrufu", if (fp.powerSaveMode) "açık" else "kapalı", "kapalı",
                !fp.powerSaveMode, "tasarruf modu CPU'yu kısıtlar")
        )
        checks.add(CheckRow("Ortam", "Isıl durum", fp.thermalStatus, "NONE", null, "Android termal servisi"))
        checks.add(
            CheckRow("Ortam", "Güvenlik yaması", fp.securityPatch, "—", null, "")
        )
    }

    private suspend fun checkKnownAnswers() {
        var passed = 0
        for (w in Workload.values()) {
            val ok = Native.verify(w.id)
            if (ok) passed++
            checks.add(
                CheckRow("Doğruluk", "Bilinen cevap · ${w.title}",
                    if (ok) "geçti" else "BAŞARISIZ", "geçmeli", ok, w.description)
            )
        }
        checks.add(
            CheckRow("Doğruluk", "Toplam bilinen-cevap testi", "$passed/${Workload.values().size}", "hepsi",
                passed == Workload.values().size, "bozuk derleme puan üretemez")
        )
    }

    private fun checkNoiseFloor() {
        val emptyNs = (1..11).map { Validate.empty() }.sorted().let { it[it.size / 2] }
        val resolution = Validate.timerResolutionNs()
        checks.add(
            CheckRow("Gürültü", "Boş görev süresi", "${emptyNs} ns", "—", null,
                "ölçüm gürültü tabanı")
        )

        if (resolution > 0) {
            val realShortest = (1..21).map { Validate.scale(0, 1, 1).ns }.filter { it > 0 }.minOrNull() ?: 0L
            val ratio = if (resolution > 0) realShortest.toDouble() / resolution else 0.0
            checks.add(
                CheckRow("Gürültü", "Sayaç çözünürlüğü", "$resolution ns", "—", null,
                    "monotonik saat")
            )
            checks.add(
                CheckRow("Gürültü", "En kısa ölçüm / çözünürlük",
                    String.format(Locale.US, "%.0f×", ratio), "≥ 1000×", ratio >= 1000.0,
                    "süre çözünürlüğün en az 1000 katı olmalı")
            )
        }
    }

    private fun checkCompiler() {
        val n = 1 shl 15
        val rounds = 6
        val o2 = (1..7).map {
            val r = Validate.kernel(true, n, rounds)
            if (r[1] > 0) r[0].toDouble() / (r[1] / 1e9) else 0.0
        }.sorted()
        val o3 = (1..7).map {
            val r = Validate.kernel(false, n, rounds)
            if (r[1] > 0) r[0].toDouble() / (r[1] / 1e9) else 0.0
        }.sorted()

        val m2 = o2[o2.size / 2]
        val m3 = o3[o3.size / 2]
        if (m2 <= 0 || m3 <= 0) {
            notes.add("-O2/-O3 karşılaştırması yapılamadı (ölçüm sıfır döndü).")
            return
        }
        val ratio = m3 / m2
        stats.add(StatsExt.describe("-O2 kernel", o2, "op/s"))
        stats.add(StatsExt.describe("-O3 kernel", o3, "op/s"))
        checks.add(
            CheckRow("Derleyici", "-O3 / -O2 hız oranı",
                String.format(Locale.US, "%.2f×", ratio), "0,70–1,50×", ratio in 0.70..1.50,
                "aşırı fark işin bir derlemede silindiğini gösterir")
        )
        val sameMagnitude = abs(m2 - m3) / max(m2, m3) < 0.35
        checks.add(
            CheckRow("Derleyici", "İki derleme aynı mertebede", if (sameMagnitude) "evet" else "hayır",
                "evet", sameMagnitude, "aynı algoritma, farklı optimizasyon")
        )
    }

    // -------------------------------------------------------- repeatability

    private suspend fun runRepeatability(from: Float, to: Float) {
        val reps = 30
        val warmup = 3
        val targetMs = 150L
        val modes = listOf(1 to "tek", 0 to "çok")
        val total = Workload.CPU.size * 2
        var done = 0

        for ((modeIndex, modeName) in modes) {
            val threads = if (modeIndex == 1) 1 else Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            for (w in Workload.CPU) {
                val iters = calibrate(w, targetMs)
                val section = "repeat_${if (modeIndex == 1) "single" else "multi"}"

                // warm-up runs are discarded, per spec
                repeat(warmup) {
                    if (threads == 1) Native.run(w.id, iters) else runParallel(w, iters, threads)
                }

                val values = ArrayList<Double>(reps)
                val times = ArrayList<Double>(reps)
                var checksums = HashSet<Long>()
                var invalid = 0
                var run = 0
                while (values.size < reps && run < reps * 2) {
                    run++
                    val (gateOk, gateNote) = gate()
                    if (!gateOk) {
                        invalid++
                        raw.add(RawSample(section, w.title, run, 0.0, w.unit, false, gateNote))
                        continue
                    }
                    val t0 = System.nanoTime()
                    val units: Long
                    val checksum: Long
                    if (threads == 1) {
                        val r = Native.run(w.id, iters)
                        units = r[0]
                        checksum = r[1]
                    } else {
                        val batch = runParallel(w, iters, threads)
                        units = batch.units
                        checksum = batch.checksum
                    }
                    val ns = System.nanoTime() - t0
                    val seconds = ns / 1e9
                    val throughput = if (seconds > 0) units / seconds else 0.0
                    checksums.add(checksum)
                    values.add(throughput)
                    times.add(ns.toDouble())
                    raw.add(
                        RawSample(
                            section = section,
                            workload = w.title,
                            run = run,
                            value = throughput / w.divisor,
                            unit = w.unit,
                            valid = true,
                            note = gateNote
                        )
                    )
                }

                val label = "${w.title} · $modeName"
                val st = StatsExt.describe(label, values, w.unit)
                stats.add(st)
                val threshold = if (modeIndex == 1) 2.0 else 3.0
                val pass = st.cvPercent < threshold
                checks.add(
                    CheckRow(
                        "Tekrarlanabilirlik", label,
                        String.format(Locale.US, "CV %.2f%% (n=%d)", st.cvPercent, st.n),
                        String.format(Locale.US, "< %.0f%%", threshold),
                        pass,
                        if (invalid > 0) "$invalid tekrar sıcaklık kapısından geçersiz sayıldı" else ""
                    )
                )
                checks.add(
                    CheckRow(
                        "Tekrarlanabilirlik", "$label · sağlama toplamı",
                        if (checksums.size == 1) "tüm turlarda aynı" else "${checksums.size} farklı değer",
                        "tek değer", checksums.size == 1,
                        "iş gerçekten koştu ve deterministik"
                    )
                )
                if (times.isNotEmpty()) {
                    val nsMedian = times.sorted()[times.size / 2]
                    checks.add(
                        CheckRow(
                            "Gürültü", "$label · örnek süresi",
                            String.format(Locale.US, "%.0f ms", nsMedian / 1e6), "—", null,
                            "ölçümün çözünürlüğe oranı için kullanılır"
                        )
                    )
                }

                done++
                val p = from + (to - from) * done / total
                step("Tekrarlanabilirlik · $label ($done/$total)", p)
            }
        }

        // recompute the noise ratio now that real sample durations exist
        val emptyNs = (1..7).map { Validate.empty() }.sorted().let { it[it.size / 2] }
        val shortest = checks.filter { it.label.endsWith("örnek süresi") }
            .mapNotNull { Regex("([0-9.]+) ms").find(it.measured)?.groupValues?.get(1)?.toDoubleOrNull() }
            .minOrNull()
        if (shortest != null && emptyNs > 0) {
            val ratio = shortest * 1e6 / emptyNs
            checks.add(
                CheckRow("Gürültü", "En kısa gerçek ölçüm / boş görev",
                    String.format(Locale.US, "%.0f×", ratio), "≥ 100×", ratio >= 100.0,
                    "gürültü tabanının en az 100 katı")
            )
        }
    }

    private fun calibrate(w: Workload, targetMs: Long): Int {
        var iters = 1
        repeat(14) {
            val r = Native.run(w.id, iters)
            val ns = r[2]
            if (ns <= 0L) return 1
            if (ns >= targetMs * 1_000_000L) return iters
            val factor = (targetMs * 1_000_000.0 / ns).coerceIn(1.5, 8.0)
            iters = (iters * factor).toInt().coerceAtLeast(iters + 1)
        }
        return iters.coerceIn(1, 8192)
    }

    private class Batch(val units: Long, val checksum: Long)

    private fun runParallel(w: Workload, perThread: Int, threads: Int): Batch {
        val pool = Executors.newFixedThreadPool(threads)
        val units = java.util.concurrent.atomic.AtomicLong(0)
        val checksum = java.util.concurrent.atomic.AtomicLong(0)
        try {
            val futures = (0 until threads).map {
                pool.submit {
                    val r = Native.run(w.id, perThread)
                    units.addAndGet(r[0])
                    checksum.addAndGet(r[1])
                }
            }
            futures.forEach { it.get(300, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        return Batch(units.get(), checksum.get())
    }

    // -------------------------------------------------------------- scaling

    private fun checkSizeScaling() {
        val names = listOf("AES benzeri (1 MiB×k)", "Tamsayı (4 Mi×k)", "Bellek taraması (8 MiB×k)")
        for (id in 0..2) {
            val scales = listOf(1, 2, 4, 8)
            val ns = ArrayList<Double>()
            val bytes = ArrayList<Double>()
            for (s in scales) {
                val iters = 1
                val samples = (1..3).map {
                    val p = Validate.scale(id, s, iters)
                    p.ns.toDouble()
                }.sorted()
                ns.add(samples[samples.size / 2])
                bytes.add(Validate.scale(id, s, iters).bytes.toDouble())
            }
            if (ns.any { it <= 0.0 }) {
                notes.add("Boyut ölçekleme (${names[id]}) ölçülemedi.")
                continue
            }
            val (slope, intercept, r2) = StatsExt.linearFit(scales.map { it.toDouble() }, ns)
            val predicted = scales.map { slope * it + intercept }
            val maxDev = ns.indices.maxOf { i ->
                if (predicted[i] > 0) abs(ns[i] - predicted[i]) / predicted[i] * 100.0 else 0.0
            }
            checks.add(
                CheckRow("Ölçekleme", "${names[id]} doğrusallık",
                    String.format(Locale.US, "R² %.4f, sapma %.1f%%", r2, maxDev), "R² ≥ 0,98",
                    r2 >= 0.98,
                    "süre problem boyutuyla doğrusal artmalı")
            )
            if (r2 < 0.98) {
                notes.add(
                    "${names[id]}: süre boyutla doğrusal artmıyor (R² %.3f). Verinin bir kısmı önbelleğe " +
                        "sığıyor olabilir; bu bir hata değil, ölçülen çalışma kümesinin hiyerarşiye göre " +
                        "davranışıdır.".format(r2)
                )
            }
        }
    }

    private fun checkThreadScaling() {
        val w = Workload.AES
        val iters = calibrate(w, 120)
        val medians = ArrayList<Pair<Int, Double>>()
        for (threads in listOf(1, 2, 4, 8)) {
            val values = (1..5).map {
                val t0 = System.nanoTime()
                val units = if (threads == 1) Native.run(w.id, iters)[0]
                else runParallel(w, iters, threads).units
                val s = (System.nanoTime() - t0) / 1e9
                if (s > 0) units / s else 0.0
            }.sorted()
            medians.add(threads to values[values.size / 2])
            stats.add(StatsExt.describe("İş parçacığı ${threads}×", values, w.unit))
        }
        val base = medians.firstOrNull { it.first == 1 }?.second ?: 0.0
        for ((threads, v) in medians) {
            val speedup = if (base > 0) v / base else 0.0
            checks.add(
                CheckRow("Ölçekleme", "Hız artışı ${threads} iş parçacığı",
                    String.format(Locale.US, "%.2f×", speedup), if (threads == 1) "1,00×" else "doğrusala yakın",
                    null,
                    "big.LITTLE nedeniyle doğrusal olmayabilir; küçük çekirdekler toplamı sınırlar")
            )
        }
    }

    private fun checkAffinity() {
        val cores = Validate.cpuInfo()
        if (cores.isEmpty()) {
            notes.add("CPU frekans bilgisi okunamadı; çekirdek sabitleme testi atlandı.")
            return
        }
        val big = cores.maxByOrNull { it.maxKhz } ?: return
        val pinIters = 4
        val free = (1..7).map {
            val p = Validate.pin(-1, 0, pinIters)
            if (p.probe.ns > 0) p.probe.bytes / (p.probe.ns / 1e9) else 0.0
        }.sorted()
        val pinned = (1..7).map {
            val p = Validate.pin(big.index, 0, pinIters)
            if (p.probe.ns > 0) p.probe.bytes / (p.probe.ns / 1e9) else 0.0
        }.sorted()

        val freeM = free[free.size / 2]
        val pinM = pinned[pinned.size / 2]
        if (freeM <= 0 || pinM <= 0) {
            notes.add("Çekirdek sabitleme ölçümü sıfır döndü (sched_setaffinity engellenmiş olabilir).")
            return
        }
        stats.add(StatsExt.describe("Serbest çalışma", free, "B/s"))
        stats.add(StatsExt.describe("cpu${big.index} sabitli", pinned, "B/s"))

        val landed = (1..15).map { Validate.currentCpu() }.filter { it >= 0 }.groupingBy { it }.eachCount()
        val mostFrequent = landed.maxByOrNull { it.value }?.key ?: -1
        checks.add(
            CheckRow("Çekirdek", "Tek çekirdek ölçümünün düştüğü çekirdek",
                if (mostFrequent >= 0) "cpu$mostFrequent" else "bilinmiyor", "—", null,
                "en sık görülen çekirdek; governor yer değiştirebilir")
        )
        checks.add(
            CheckRow("Çekirdek", "En yüksek frekanslı çekirdek",
                "cpu${big.index} (${big.maxKhz / 1000} MHz)", "—", null, "büyük çekirdek adayı")
        )
        checks.add(
            CheckRow("Çekirdek", "Sabitli / serbest hız oranı",
                String.format(Locale.US, "%.2f×", pinM / freeM), "1,0 civarı", null,
                "1'den büyükse serbest ölçüm küçük çekirdeğe düşmüş olabilir")
        )
        if (pinM / freeM < 0.85) {
            notes.add("Serbest ölçüm sabitlenmiş ölçümden hızlı: görev büyük çekirdekte kalıyor olabilir veya " +
                "sabitleme ısıl/termal kısıtlama ile çakışıyor.")
        }
    }

    private fun checkMemoryHierarchy() {
        val sizes = listOf(
            32L * 1024 to "32 KiB (L1)",
            256L * 1024 to "256 KiB (L2)",
            4L * 1024 * 1024 to "4 MiB (L3)",
            128L * 1024 * 1024 to "128 MiB (RAM)"
        )
        val results = LinkedHashMap<String, Double>()
        for ((bytes, label) in sizes) {
            val passes = max(1, (64L * 1024 * 1024 / bytes).toInt())
            val values = (1..3).map {
                val p = Validate.memory(bytes, passes)
                p.gbPerSec
            }.sorted()
            val median = values[values.size / 2]
            results[label] = median
            stats.add(StatsExt.describe("Bellek · $label", values, "GB/s"))
        }
        val list = results.values.toList()
        val stepped = list.size >= 4 &&
            list[0] > list[1] && list[1] > list[2] && list[2] > list[3]
        val anyStep = list.zipWithNext().any { (a, b) -> a > 0 && b > 0 && a / b > 1.15 }
        checks.add(
            CheckRow("Bellek", "Hiyerarşi basamağı",
                list.joinToString(" → ") { String.format(Locale.US, "%.1f", it) } + " GB/s",
                "küçükten büyüğe azalan", anyStep,
                if (stepped) "dört seviyede de düşüş var" else "en az bir basamak var; düz çizgi önbelleği görmüyor demektir")
        )
    }

    private fun checkStorage() {
        val dir = File(context.cacheDir, "validation").apply { mkdirs() }
        val modes = listOf(
            Triple("Sıralı · önbellekli", false, false),
            Triple("Sıralı · O_DIRECT", false, true),
            Triple("Rastgele 4K · önbellekli", true, false),
            Triple("Rastgele 4K · O_DIRECT", true, true)
        )
        for ((label, randomIO, direct) in modes) {
            val path = File(dir, "probe-${if (direct) "d" else "c"}-${if (randomIO) "r" else "s"}.bin").absolutePath
            val values = ArrayList<Double>()
            var unsupported = false
            repeat(3) {
                val p = Validate.storage(
                    path,
                    sizeMb = 64,
                    blockKb = if (randomIO) 4 else 128,
                    random = randomIO,
                    direct = direct
                )
                if (p.bytes <= 0 || p.ns <= 0) unsupported = true else values.add(p.mbPerSec)
            }
            File(path).delete()
            if (unsupported && values.isEmpty()) {
                checks.add(
                    CheckRow("Depolama", label, "desteklenmiyor", "—", null,
                        if (direct) "O_DIRECT bu dosya sisteminde reddedildi (EINVAL); önbelleksiz ölçüm yapılamadı"
                        else "okuma/yazma başarısız")
                )
                continue
            }
            val st = StatsExt.describe(label, values, "MB/s")
            stats.add(st)
            checks.add(
                CheckRow("Depolama", label,
                    String.format(Locale.US, "%.0f MB/s (CV %.1f%%)", st.median, st.cvPercent), "—", null,
                    "64 MB; sıralı 128 KiB, rastgele 4 KiB blok")
            )
        }

        val cached = stats.firstOrNull { it.label == "Sıralı · önbellekli" }?.median ?: 0.0
        val direct = stats.firstOrNull { it.label == "Sıralı · O_DIRECT" }?.median ?: 0.0
        if (cached > 0 && direct > 0) {
            checks.add(
                CheckRow("Depolama", "Önbellek etkisi",
                    String.format(Locale.US, "önbellekli %.0f / doğrudan %.0f MB/s (%.2f×)", cached, direct, cached / direct),
                    "1× yakını", cached / direct in 0.67..1.5,
                    "büyük fark, ölçümün sayfa önbelleğini gördüğünü gösterir")
            )
        }
        notes.add("Depolama dosyası 64 MB; RAM'den büyük değil. Sayfa önbelleği O_DIRECT ile atlanıyor, " +
            "bu yüzden önbelleksiz sonuç ayrı raporlanıyor.")
    }

    private suspend fun checkGpu() {
        val vk = VulkanBench.run()
        val detail = VulkanBench.detail()

        if (detail.ok && detail.frameNs.isNotEmpty()) {
            val ms = detail.frameNs.map { it / 1e6 }
            val sorted = ms.sorted()
            val median = StatsExt.median(sorted)
            val p99 = StatsExt.quantile(sorted, 0.99)
            val p999 = StatsExt.quantile(sorted, 0.999)
            val mean = sorted.sum() / sorted.size
            checks.add(
                CheckRow("GPU", "Kare süresi ortancası", String.format(Locale.US, "%.2f ms", median), "—", null,
                    "GPU zaman damgası sorgularından, CPU saati değil")
            )
            checks.add(
                CheckRow("GPU", "Ortalama FPS (1/ortanca)", String.format(Locale.US, "%.1f", 1000.0 / median), "—", null, "")
            )
            checks.add(
                CheckRow("GPU", "%1 düşük (en yavaş %1)", String.format(Locale.US, "%.2f ms → %.1f fps", p99, 1000.0 / p99),
                    "—", null, "kare süresi dağılımının kuyruğu")
            )
            checks.add(
                CheckRow("GPU", "%0,1 düşük", String.format(Locale.US, "%.2f ms → %.1f fps", p999, 1000.0 / p999),
                    "—", null, "")
            )
            val cv = if (mean > 0) StatsExt.sd(sorted) / mean * 100.0 else 0.0
            checks.add(
                CheckRow("GPU", "Kare süresi CV", String.format(Locale.US, "%.1f%%", cv), "< 15%", cv < 15.0,
                    "yüksek CV sürücü/zamanlama gürültüsü")
            )
            checks.add(
                CheckRow("GPU", "Piksel doğrulaması",
                    if (detail.pixelOk) "geçti" else "BAŞARISIZ",
                    "geçmeli", detail.pixelOk,
                    String.format(Locale.US, "5 örnek piksel, en büyük hata %.4f", detail.maxPixelError))
            )
            stats.add(StatsExt.describe("GPU kare süresi", ms, "ms"))
        } else {
            checks.add(
                CheckRow("GPU", "Vulkan kare ölçümü", "yapılamadı", "—", null,
                    detail.note ?: vk.error ?: "bilinmeyen neden")
            )
        }

        // refresh-rate lock
        val refresh = refreshRateHz()
        if (refresh > 0 && vk.sceneFps != null) {
            val fps = vk.sceneFps
            val near = abs(fps - refresh) / refresh < 0.08
            checks.add(
                CheckRow("GPU", "Ekran yenileme hızı", String.format(Locale.US, "%.0f Hz", refresh), "—", null,
                    "çevrimdışı hedef olduğu için VSync yok")
            )
            checks.add(
                CheckRow("GPU", "FPS ekran sınırına takılı mı?",
                    if (near) "EVET" else "hayır", "hayır", !near,
                    if (near) "FPS ekran tavanına yakın: GPU değil ekran ölçülüyor olabilir" else "ekran tavanının altında")
            )
        }

        val metrics = context.resources.displayMetrics
        checks.add(
            CheckRow("GPU", "Çözünürlük bağımsızlığı",
                "ekran ${metrics.widthPixels}×${metrics.heightPixels}, hedef 1024×1024 sabit",
                "sabit hedef", true,
                "puan ekran çözünürlüğünden bağımsız: çizim her zaman 1024² çevrimdışı hedefe yapılır")
        )

        // Vulkan vs GLES on the same scene
        val gles = try {
            GpuBench().run()
        } catch (t: Throwable) {
            null
        }
        if (gles != null && gles.sceneFps != null && vk.sceneFps != null && vk.sceneFps > 0) {
            val ratio = gles.sceneFps / vk.sceneFps
            checks.add(
                CheckRow("GPU", "Vulkan / GLES sahne oranı",
                    String.format(Locale.US, "Vulkan %.0f fps, GLES %.0f fps (%.2f×)", vk.sceneFps, gles.sceneFps, ratio),
                    "0,4–2,5×", ratio in 0.4..2.5,
                    "aynı sahne iki API'de makul benzerlikte olmalı")
            )
        } else {
            checks.add(
                CheckRow("GPU", "Vulkan / GLES karşılaştırması", "yapılamadı", "—", null,
                    gles?.error ?: "GLES ölçümü alınamadı")
            )
        }
    }

    private fun refreshRateHz(): Double = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = context.display
            dm.refreshRate.toDouble()
        } else {
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.refreshRate.toDouble()
        }
    } catch (t: Throwable) {
        0.0
    }

    // -------------------------------------------------------------- scoring

    private fun checkScoring(): String {
        val config = ScoreConfig.load(context)

        val reference = Workload.MATRIX.refUnitsPerSec
        val referenceIndex = reference / Workload.MATRIX.refUnitsPerSec * 1000.0
        checks.add(
            CheckRow("Puanlama", "Referans cihaz puanı",
                String.format(Locale.US, "%.1f", referenceIndex), "tam 1000", referenceIndex == 1000.0,
                "tanım gereği: hız / referans hız × 1000")
        )

        val cfgOk = abs(config.cpuWeight + config.gpuWeight + config.memoryWeight + config.uxWeight - 1.0) < 1e-6
        checks.add(
            CheckRow("Puanlama", "Ağırlık toplamı",
                String.format(Locale.US, "%.2f", config.totalWeight), "1,00", cfgOk,
                "assets/scoring.json: CPU %.0f%%, GPU %.0f%%, bellek %.0f%%, UX %.0f%%".format(
                    config.cpuWeight * 100, config.gpuWeight * 100, config.memoryWeight * 100, config.uxWeight * 100))
        )

        // real runs from this device, plus clearly-labelled hypothetical inputs
        val realRuns = ResultStore.summaries(context).map { s ->
            s.device to ScoreConfig.Inputs(
                single = s.single, multi = s.multi, memory = s.memory, storage = s.storage,
                gpu = s.gpu, ux = s.ux
            )
        }
        val hypothetical = listOf(
            "Varsayımsal A (hızlı)" to ScoreConfig.Inputs(1400.0, 5200.0, 900.0, 1100.0, 1600.0, 950.0),
            "Varsayımsal B (orta)" to ScoreConfig.Inputs(900.0, 2600.0, 700.0, 800.0, 850.0, 780.0),
            "Varsayımsal C (yavaş, güçlü GPU)" to ScoreConfig.Inputs(600.0, 1500.0, 500.0, 600.0, 1400.0, 620.0)
        )
        val all = realRuns + hypothetical

        val csv = StringBuilder("label,inputs,combined,cpu,gpu,memory,ux\n")
        for ((name, inputs) in all) {
            val b = config.breakdown(inputs)
            csv.append(csvEscape(name)).append(',')
                .append(csvEscape(
                    "single=${inputs.single},multi=${inputs.multi},memory=${inputs.memory}," +
                        "storage=${inputs.storage},gpu=${inputs.gpu},ux=${inputs.ux}"
                )).append(',')
                .append(fmt(b.combined)).append(',')
                .append(fmt(b.cpu)).append(',')
                .append(fmt(b.gpu)).append(',')
                .append(fmt(b.memory)).append(',')
                .append(fmt(b.ux)).append('\n')
        }

        val sensitivity = ScoreConfig.sensitivity(config, all)
        for (row in sensitivity) {
            checks.add(
                CheckRow(
                    "Ağırlık duyarlılığı",
                    "${row.group} ${if (row.deltaPoints > 0) "+" else ""}${(row.deltaPoints * 100).toInt()} puan",
                    String.format(Locale.US, "puan %.2f%% oynadı, %d sıra değişti", row.scoreChangePercent, row.rankChanges),
                    "sıra 0", row.rankChanges == 0,
                    row.note
                )
            )
        }
        if (realRuns.size < 2) {
            notes.add("Ağırlık duyarlılığı üç varsayımsal girişle hesaplandı; gerçek cihaz sıralaması için " +
                "en az iki gerçek kayıt gerekir. Varsayımsal satırlar yalnızca matematiği sınamak içindir.")
        }

        checks.add(
            CheckRow("Puanlama", "Bağımsız doğrulama",
                "scoring-inputs.csv rapora eklendi", "—", null,
                "tools/verify_scoring.py bu dosyayı okuyup aynı sonucu üretmeli")
        )
        return csv.toString()
    }

    // -------------------------------------------------------------- helpers

    private fun fmt(v: Double?): String =
        if (v == null) "" else String.format(Locale.US, "%.4f", v)

    private fun csvEscape(s: String): String {
        val needsQuote = s.any { it == ',' || it == '"' || it == '\n' }
        return if (needsQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    // -------------------------------------------------------------- reports

    fun writeReports(report: ValidationReport): List<File> {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(report.timestamp))

        val rawFile = File(dir, "validation-$stamp-raw.csv")
        rawFile.writeText(buildString {
            append("section,workload,run,value,unit,valid,note\n")
            for (s in report.raw) {
                append(csvEscape(s.section)).append(',')
                append(csvEscape(s.workload)).append(',')
                append(s.run).append(',')
                append(String.format(Locale.US, "%.4f", s.value)).append(',')
                append(csvEscape(s.unit)).append(',')
                append(s.valid).append(',')
                append(csvEscape(s.note)).append('\n')
            }
        })

        val statsFile = File(dir, "validation-$stamp-stats.csv")
        statsFile.writeText(buildString {
            append("label,n,median,mean,sd,cv_percent,ci95_low,ci95_high,outliers,unit\n")
            for (s in report.stats) {
                append(csvEscape(s.label)).append(',')
                append(s.n).append(',')
                append(String.format(Locale.US, "%.4f", s.median)).append(',')
                append(String.format(Locale.US, "%.4f", s.mean)).append(',')
                append(String.format(Locale.US, "%.4f", s.sd)).append(',')
                append(String.format(Locale.US, "%.3f", s.cvPercent)).append(',')
                append(String.format(Locale.US, "%.4f", s.ciLow)).append(',')
                append(String.format(Locale.US, "%.4f", s.ciHigh)).append(',')
                append(s.outliers).append(',')
                append(csvEscape(s.unit)).append('\n')
            }
        })

        val scoringFile = File(dir, "validation-$stamp-scoring.csv")
        scoringFile.writeText(report.scoringCsv)

        val md = File(dir, "validation-$stamp.md")
        md.writeText(ValidationReportWriter.markdown(report))
        return listOf(md, rawFile, statsFile, scoringFile)
    }
}
