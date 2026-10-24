package com.alican.devicescope.bench

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a [ValidationReport] into the Markdown summary the spec asks for:
 * per-test n/median/mean/SD/CV/95% CI/outliers, which tests are trustworthy,
 * which are not, why, and what to change.
 *
 * The writer never upgrades a warning into a pass. If a measurement could not be
 * taken, the report states that instead of filling the table with a guess.
 */
object ValidationReportWriter {

    fun markdown(report: ValidationReport): String = buildString {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(report.timestamp))

        appendLine("# DeviceScope doğrulama raporu")
        appendLine()
        appendLine("- Cihaz: **${report.device}**")
        appendLine("- ABI / SDK: `${report.abi}` / ${report.sdk}")
        appendLine("- Tarih: $stamp")
        appendLine("- Süre: ${String.format(Locale.US, "%.1f", report.durationSeconds)} sn")
        appendLine("- Paket: `${report.fingerprint.packageName}` · imza `${report.fingerprint.signaturePrefix}`")
        appendLine()

        // ---------------------------------------------------------- verdict
        val passed = report.checks.count { it.pass == true }
        val failed = report.checks.count { it.pass == false }
        val observed = report.checks.count { it.pass == null }
        appendLine("## Özet")
        appendLine()
        appendLine("| Sonuç | Adet |")
        appendLine("|---|---|")
        appendLine("| Geçti | $passed |")
        appendLine("| Başarısız | $failed |")
        appendLine("| Bilgi (eşik yok) | $observed |")
        appendLine()

        if (failed == 0) {
            appendLine("Ölçülebilen tüm eşikler geçildi. Bu, cihazın hızlı olduğu anlamına gelmez;")
            appendLine("yalnızca ölçümün kendi doğruluk kurallarını sağladığı anlamına gelir.")
        } else {
            appendLine("**${failed} kontrol başarısız.** Aşağıda her biri için ölçüm, beklenen değer ve neden listelendi.")
        }
        appendLine()

        // ------------------------------------------------------ failed list
        if (report.failed.isNotEmpty()) {
            appendLine("### Başarısız kontroller ve önerilen düzeltme")
            appendLine()
            for (c in report.failed) {
                appendLine("- **${c.group} · ${c.label}** — ölçülen: `${c.measured}`, beklenen: `${c.expected}`")
                val reason = failureReason(c)
                appendLine("  - Neden: $reason")
                appendLine("  - Düzeltme: ${fixFor(c)}")
            }
            appendLine()
        }

        // ---------------------------------------------------------- stats
        appendLine("## Ölçüm istatistikleri")
        appendLine()
        if (report.stats.isEmpty()) {
            appendLine("_İstatistik üretilemedi._")
        } else {
            appendLine("| Seri | n | Ortanca | Ortalama | SS | CV % | %95 GA | Aykırı | Birim |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            for (s in report.stats) {
                appendLine(
                    "| ${s.label} | ${s.n} | ${num(s.median)} | ${num(s.mean)} | ${num(s.sd)} | " +
                        "${num(s.cvPercent, 2)} | ${num(s.ciLow)} – ${num(s.ciHigh)} | ${s.outliers} | ${s.unit} |"
                )
            }
        }
        appendLine()

        // ------------------------------------------------------- groups
        for (group in report.checks.map { it.group }.distinct()) {
            val rows = report.checks.filter { it.group == group }
            if (rows.isEmpty()) continue
            appendLine("## $group")
            appendLine()
            appendLine("| Kontrol | Ölçülen | Beklenen | Sonuç | Not |")
            appendLine("|---|---|---|---|---|")
            for (c in rows) {
                val verdict = when (c.pass) {
                    true -> "✅ geçti"
                    false -> "❌ başarısız"
                    null -> "ℹ️ bilgi"
                }
                appendLine("| ${c.label} | ${c.measured} | ${c.expected} | $verdict | ${c.note} |")
            }
            appendLine()
        }

        // -------------------------------------------------- reliability
        appendLine("## Hangi testler güvenilir, hangileri değil")
        appendLine()
        for (s in report.stats) {
            val verdict = when {
                s.n < 5 -> "güvenilmez (n=${s.n}, istatistik için çok az)"
                s.cvPercent < 2.0 -> "güvenilir (CV ${num(s.cvPercent, 2)}%)"
                s.cvPercent < 5.0 -> "kabul edilebilir (CV ${num(s.cvPercent, 2)}%)"
                else -> "güvenilmez (CV ${num(s.cvPercent, 2)}%, gürültü sinyalden büyük)"
            }
            appendLine("- **${s.label}**: $verdict")
        }
        appendLine()

        if (report.notes.isNotEmpty()) {
            appendLine("## Notlar")
            appendLine()
            for (n in report.notes) appendLine("- $n")
            appendLine()
        }

        // ------------------------------------------- environment honesty
        appendLine("## Ortam parmak izi")
        appendLine()
        appendLine("- Governor: `${report.fingerprint.governors}`")
        appendLine("- Güç tasarrufu: ${if (report.fingerprint.powerSaveMode) "AÇIK (puan düşer)" else "kapalı"}")
        appendLine("- Isıl durum: ${report.fingerprint.thermalStatus}")
        appendLine("- Root: ${if (report.fingerprint.rooted) "EVET — ${report.fingerprint.rootReasons.joinToString(", ")}" else "hayır"}")
        appendLine("- Emülatör: ${if (report.fingerprint.emulator) "EVET — ${report.fingerprint.emulatorReasons.joinToString(", ")}" else "hayır"}")
        appendLine("- Debug yapı: ${if (report.fingerprint.debuggable) "EVET" else "hayır"}")
        appendLine("- Güvenlik yaması: ${report.fingerprint.securityPatch}")
        appendLine()

        // ------------------------------------------------ not verifiable
        appendLine("## Bu cihazda doğrulanamayan istekler")
        appendLine()
        appendLine("Aşağıdaki maddeler rootless bir perakende telefonda fiziksel olarak yapılamaz.")
        appendLine("Rapor bunları sayı uydurmak yerine açıkça listeler:")
        appendLine()
        appendLine("- **20 °C / 30 °C sabit ortam sıcaklığı**: uygulama ortam sıcaklığını kontrol edemez.")
        appendLine("  Ölçülen başlangıç batarya sıcaklığı kaydedilir; testler bu sıcaklığa göre gruplanır.")
        appendLine("- **Diğer uygulamaların arka plan sayısı (0/5/15)**: Android, bir uygulamanın başka")
        appendLine("  uygulamaların süreçlerini saymasına izin vermez. Yalnızca kendi sürecinin yükü ölçülebilir.")
        appendLine("- **Yeniden paketleme (imza değiştirme) testi**: cihaz içinden kendi APK'sı yeniden")
        appendLine("  imzalanamaz. Paket adı ve imza özeti her koşuyla kaydedilir; iki APK karşılaştırması")
        appendLine("  kullanıcı tarafından yapılır.")
        appendLine("- **Geekbench/AnTuTu CSV korelasyonu**: korelasyon için aynı cihazların *hem* referans")
        appendLine("  puanları *hem* DeviceScope puanları gerekir. Tek cihazda n=1 olduğu için Spearman")
        appendLine("  tanımsızdır; en az 5–8 cihazın verisi gerekir.")
        appendLine("- **Sayfa önbelleğini tamamen atlatan depolama testi**: O_DIRECT bu dosya sisteminde")
        appendLine("  reddedilirse sonuç \"desteklenmiyor\" olarak raporlanır, tahmin edilmez.")
        appendLine("- **15 dakikalık ısıl dayanıklılık testi**: bu doğrulama paketi kapsamında değil;")
        appendLine("  ayrı bir uzun koşu gerektirir ve Tekrarlanabilirlik bölümü onu kapsamaz.")
        appendLine()

        appendLine("## Ham veri")
        appendLine()
        appendLine("Aynı klasörde: `-raw.csv` (her ölçüm), `-stats.csv` (istatistik tablosu),")
        appendLine("`-scoring.csv` (puanlama girdileri, `tools/verify_scoring.py` ile bağımsız doğrulama için).")
        appendLine()
        appendLine("_Bu rapor otomatik üretildi. Her sayı ölçümdür; ölçülemeyen alan boş bırakılmıştır._")
    }

    private fun failureReason(c: CheckRow): String = when {
        c.group == "Tekrarlanabilirlik" && c.label.contains("sağlama") ->
            "Aynı iş yükü farklı sağlama toplamı üretti; bu deterministik olmayan bir hesaplama veya " +
                "bellek bozulması demektir ve puan güvenilmez."
        c.group == "Tekrarlanabilirlik" ->
            "Ölçümler arası saçılma eşiğin üzerinde. Nedenler: termal kısılma, governor geçişi, " +
                "arka plan yükü veya çok kısa örnek süresi."
        c.group == "Gürültü" ->
            "Ölçülen süre, sayaç çözünürlüğüne veya boş görev gürültüsüne göre çok kısa; fark " +
                "ölçüm hatası kadar."
        c.group == "Derleyici" ->
            "İki optimizasyon seviyesi aynı iş için çok farklı süre verdi; işin bir kısmı " +
                "derleyici tarafından silinmiş olabilir."
        c.group == "Ölçekleme" ->
            "Süre problem boyutuyla doğrusal artmadı; çalışma kümesinin bir kısmı önbelleğe sığıyor."
        c.group == "Bellek" ->
            "Bant genişliği eğrisi düz; test önbellek hiyerarşisini göremiyor."
        c.group == "Depolama" -> "Okuma/yazma hızı beklenen aralıkta değil."
        c.group == "GPU" ->
            "GPU ölçümü doğrulama sınırının dışında; sürücü farkı, VSync veya piksel hatası olabilir."
        c.group == "Ortam" ->
            "Cihaz ortamı benchmark koşullarına uygun değil; puan başka cihazlarla karşılaştırılmamalı."
        c.group == "Puanlama" -> "Puanlama yapılandırması veya aritmetiği tutarsız."
        else -> "Ölçülen değer beklenen aralığın dışında."
    }

    private fun fixFor(c: CheckRow): String = when {
        c.label.contains("CV") || c.group == "Tekrarlanabilirlik" ->
            "Cihazı uçak moduna alın, kılıfı çıkarın, 5 dakika soğutun ve testi tekrarlayın. " +
                "Örnek süresini artırmak (daha çok yineleme) CV'yi düşürür."
        c.group == "Gürültü" ->
            "Her iş yükünün yineleme sayısını artırın; en kısa ölçüm 1 ms'nin altındaysa ölçüm anlamsız."
        c.group == "Derleyici" ->
            "Sonucun gerçekten tüketildiğinden emin olun (uçucu/black-box engeli) ve iki TU'yu " +
                "aynı kaynakla derleyin."
        c.group == "Ölçekleme" ->
            "Uyarı kabul edilebilir: çalışma kümesi önbelleğe sığıyorsa doğrusallık beklenmez. " +
                "Doğrusallık isteniyorsa daha büyük boyut seçin."
        c.group == "Bellek" ->
            "Blok boyutlarını (32 KiB → 256 MiB) ve geçiş sayısını artırın; eğri basamaklı olmalı."
        c.group == "Depolama" ->
            "O_DIRECT desteklenmiyorsa yalnızca önbellekli sonucu kullanın ve bunu raporda belirtin."
        c.group == "GPU" ->
            "VSync'i kapatın (çevrimdışı hedef zaten açık), sürücü güncellemesini kontrol edin; " +
                "piksel hatası sürücü derleyicisi kaynaklı olabilir."
        c.group == "Ortam" ->
            "Güç tasarrufunu kapatın, şarjı %50'nin üzerine çıkarın, cihazı serinletin."
        c.group == "Puanlama" ->
            "assets/scoring.json ağırlıkları toplamının 1.00 olduğunu doğrulayın."
        else -> "Testi serin bir cihazda tekrarlayın."
    }

    private fun num(v: Double, decimals: Int = 3): String =
        String.format(Locale.US, "%.${decimals}f", v)
}
