package com.alican.devicescope

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Deep history screen.
 *
 * The honest truth first: Android exposes very few manufacturing dates.
 * What really exists and is readable is marked OK. Anything the platform
 * genuinely does not store is marked MISSING with the reason — it is far more
 * useful than a fabricated date.
 */
class HistoryCollector(private val ctx: Context) {

    fun collect(): List<Section> {
        val info = DeviceInfoCollector(ctx)
        return listOf(
            firstBoot(),
            batteryHistory(info),
            hardwareHistory(),
            uptime()
        )
    }

    // --------------------------------------------------------------- first boot

    private fun firstBoot(): Section {
        val items = ArrayList<Item>()

        val firstBootMs = Sys.prop("ro.runtime.firstboot")?.toLongOrNull()

        if (firstBootMs != null && firstBootMs > 0) {
            val days = Fmt.days(System.currentTimeMillis() - firstBootMs)
            items += Item(
                "Cihazın ilk açılışı",
                Fmt.date(firstBootMs),
                detail = "Bu andan itibaren $days gün geçti. Değer, cihaz fabrika kurulumunda sistem tarafından yazılan kalıcı özelliktir; bu uygulamayla ilgisi yoktur.",
                source = "ro.runtime.firstboot (sistem özelliği)"
            )
        } else {
            items += Item(
                "Cihazın ilk açılışı",
                "Kayıt yok",
                flag = Flag.MISSING,
                detail = "Üretici ro.runtime.firstboot özelliğini yazmamış",
                source = "ro.runtime.firstboot"
            )
        }

        val btime = Sys.read("/proc/stat")
            ?.let { Regex("(?m)^btime\\s+(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (btime != null) {
            items += Item(
                "Son önyükleme (açılış)",
                Fmt.date(btime * 1000),
                detail = "çekirdek önyükleme zamanı",
                source = "/proc/stat → btime"
            )
        }

        items += Item(
            "ROM derleme tarihi",
            Fmt.date(Build.TIME),
            detail = "yazılımın paketlendiği tarih — cihazın üretim tarihi değildir",
            source = "Build.TIME"
        )

        Sys.read("/proc/version")?.let { version ->
            val date = Regex("(\\w{3}\\s+\\w{3}\\s+\\d+\\s+[\\d:]+\\s+\\w+\\s+\\d{4})").find(version)?.value
            items += Item(
                "Çekirdek derleme tarihi",
                date ?: version.take(60),
                source = "/proc/version"
            )
        }

        items += Item(
            "Güvenlik yaması seviyesi",
            Build.VERSION.SECURITY_PATCH.ifBlank { "—" },
            detail = "yazılımın hangi güvenlik yamasını içerdiği",
            source = "Build.VERSION.SECURITY_PATCH"
        )

        items += Item(
            "Anakart üretim tarihi",
            "Cihazda saklanmıyor",
            flag = Flag.MISSING,
            detail = "Üretim tarihi yalnızca üreticinin fabrika veritabanında bulunur. Android bu bilgiyi cihaza yazmaz; root olsa bile bu alan yoktur, çünkü hiç var olmamıştır.",
            source = "—"
        )

        return Section("İlk Kullanım", items)
    }

    // ----------------------------------------------------------------- battery

    private fun batteryHistory(info: DeviceInfoCollector): Section {
        val items = ArrayList<Item>()

        // Pull the same sysfs values the battery section uses
        val base = Sys.listDir("/sys/class/power_supply")
            .firstOrNull { Sys.read("${it.path}/type")?.equals("Battery", true) == true }
            ?.path

        val basePath = base ?: "/sys/class/power_supply/battery"

        val cycle = Sys.read("$basePath/cycle_count")?.toLongOrNull()
        val full = Sys.read("$basePath/charge_full")?.toLongOrNull()
        val design = Sys.read("$basePath/charge_full_design")?.toLongOrNull()

        if (cycle != null) {
            items += Item(
                "Pil şarj döngüsü",
                "$cycle döngü",
                detail = "Bir döngü ≈ tam bir şarj-deşarj. Üretici tipik olarak 800–1000 döngüde %80 kapasite öngörür.",
                source = "$basePath/cycle_count"
            )
        } else {
            items += Item(
                "Pil şarj döngüsü",
                "Kayıt yok",
                flag = Flag.MISSING,
                detail = "Bu cihazın pil yönetim çipi döngü sayacını uygulamalara açmıyor",
                source = "$basePath/cycle_count"
            )
        }

        if (full != null && design != null && design > 0) {
            val health = full.toDouble() / design * 100.0
            val lostMah = (design - full) / 1000
            items += Item(
                "Pil kapasite kaybı",
                "${Fmt.percent(100.0 - health)} (${lostMah} mAh)",
                detail = "tasarım ${design / 1000} mAh → ölçülen tam şarj ${full / 1000} mAh",
                flag = Flag.OK,
                source = "charge_full vs charge_full_design"
            )

            // Rough age estimate from wear — explicitly an estimate
            if (cycle == null) {
                val estCycles = ((design - full).toDouble() / design) * 5000.0
                if (estCycles > 50) {
                    items += Item(
                        "Tahmini döngü sayısı",
                        "~${estCycles.toInt()}",
                        detail = "Kapasite kaybından geri hesaplandı; gerçek döngü sayısı değildir",
                        flag = Flag.ESTIMATE,
                        source = "kapasite kaybı modeli"
                    )
                }
            }
        }

        items += Item(
            "Pil değişim tarihi",
            "Kayıt yok",
            flag = Flag.MISSING,
            detail = "Android pilin ne zaman değiştiğini hiçbir yerde tutmaz. Batarya, takıldığında sisteme tarih yazmaz. Bu bilgiyi yalnızca yetkili servis kaydı verir.",
            source = "—"
        )

        Sys.read("$basePath/manufacturer")?.let {
            items += Item("Pil üreticisi", it, source = "$basePath/manufacturer")
        }
        Sys.read("$basePath/serial_number")?.let {
            items += Item("Pil seri numarası", it, source = "$basePath/serial_number")
        }
        Sys.read("$basePath/technology")?.let {
            items += Item("Pil kimyası", it, source = "$basePath/technology")
        }

        val designCap = info.battery().items.firstOrNull { it.label.contains("tasarım") }
        if (designCap == null && design == null) {
            items += Item(
                "Pil tasarım kapasitesi",
                "Okunamıyor",
                flag = Flag.RESTRICTED,
                detail = "Bu cihazda charge_full_design düğümü kapalı",
                source = "$basePath/charge_full_design"
            )
        }

        return Section("Batarya Geçmişi", items)
    }

    // ---------------------------------------------------------------- hardware

    private fun hardwareHistory(): Section {
        val items = ArrayList<Item>()

        items += Item(
            "Panel üretim tarihi",
            Edid.read()?.let { p ->
                if (p.week != null && p.year != null) "Hafta ${p.week} / ${p.year}" else "EDID'de tarih yok"
            } ?: "Okunamıyor",
            flag = if (Edid.read()?.year != null) Flag.OK else Flag.RESTRICTED,
            detail = "EDID varsa panelin gerçek üretim haftası okunur; bu cihazda panel EDID'i kapalıysa gösterilemez",
            source = "/sys/class/drm/*/edid"
        )

        Edid.read()?.manufacturerName?.let { name ->
            items += Item("Panel üreticisi", name, detail = "EDID PNP kodu", source = "EDID")
        }

        val socRevision = Sys.read("/sys/devices/soc0/revision")
        val socId = Sys.read("/sys/devices/soc0/soc_id")
        if (socRevision != null || socId != null) {
            items += Item(
                "SoC silikon revizyonu",
                listOfNotNull(socId?.let { "soc_id $it" }, socRevision?.let { "rev $it" }).joinToString(" · "),
                detail = "Silikon revizyonu üretim partisini dolaylı gösterir, tarih vermez",
                source = "/sys/devices/soc0/*"
            )
        }

        val cid = Sys.listDir("/sys/block")
            .firstOrNull { it.name.startsWith("mmcblk") }
            ?.let { Sys.read("${it.path}/device/cid") }
        if (cid != null) {
            items += Item("eMMC CID", cid, detail = "Üretici + seri no; tarih içermez", source = "/sys/block/mmcblk0/device/cid")
        }

        items += Item(
            "Donanım üretim tarihi",
            "Cihazda saklanmıyor",
            flag = Flag.MISSING,
            detail = "Anakart/ekran/kamera üretim tarihleri fabrika ve tedarikçi kayıtlarında kalır. Android'de bunları tutan bir alan yoktur.",
            source = "—"
        )

        Sys.prop("ro.boot.hardware.sku")?.let {
            items += Item("Donanım SKU", it, source = "ro.boot.hardware.sku")
        }
        Sys.prop("ro.boot.hardware")?.let {
            items += Item("Önyükleme donanımı", it, source = "ro.boot.hardware")
        }

        return Section("Donanım Geçmişi", items)
    }

    // ------------------------------------------------------------------ uptime

    private fun uptime(): Section {
        val items = ArrayList<Item>()

        val uptimeSec = Sys.read("/proc/uptime")
            ?.split(" ")
            ?.firstOrNull()
            ?.toDoubleOrNull()
        if (uptimeSec != null) {
            items += Item(
                "Kesintisiz çalışma süresi",
                Fmt.duration((uptimeSec * 1000).toLong()),
                detail = "son açılıştan bu yana",
                source = "/proc/uptime"
            )
        }

        val btime = Sys.read("/proc/stat")
            ?.let { Regex("(?m)^btime\\s+(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (btime != null) {
            val days = Fmt.days(System.currentTimeMillis() - btime * 1000)
            items += Item("Son açılış", Fmt.date(btime * 1000), detail = "$days gün önce", source = "/proc/stat → btime")
        }

        val install = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime
        } catch (t: Throwable) {
            null
        }
        if (install != null) {
            items += Item(
                "Bu uygulamanın kurulumu",
                Fmt.date(install),
                detail = "yalnızca bu APK'nın ne zaman kurulduğu — cihazın geçmişi değil",
                source = "PackageManager.firstInstallTime"
            )
        }

        val filesDir = ctx.filesDir
        items += Item(
            "Uygulama verisi konumu",
            paths(filesDir),
            source = "Context.filesDir"
        )

        return Section("Çalışma Süresi", items)
    }

    private fun paths(f: File): String = f.absolutePath

    // ------------------------------------------------------------ system props

    /** Every ro.* property that looks like a date, for users who want the raw evidence. */
    fun rawDateProps(): List<Item> {
        val out = ArrayList<Item>()
        Sys.props().forEach { (k, v) ->
            if (k.startsWith("ro.") && (v.contains("202") || v.contains("201")) && v.length in 8..24) {
                out.add(Item(k, v, source = "getprop"))
            }
        }
        return out
    }
}
