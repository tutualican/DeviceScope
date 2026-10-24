package com.alican.devicescope

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import android.view.WindowManager
import java.io.File
import kotlin.math.sqrt

class DeviceInfoCollector(private val ctx: Context) {

    fun collect(): List<Section> = listOf(
        identity(),
        soc(),
        cpu(),
        gpu(),
        memory(),
        storage(),
        battery(),
        screen(),
        cameras(),
        board(),
        software()
    )

    // ---------------------------------------------------------------- identity

    private fun identity(): Section {
        val items = ArrayList<Item>()
        items += Item("Üretici", Build.MANUFACTURER.orDash(), source = "Build.MANUFACTURER")
        items += Item("Marka", Build.BRAND.orDash(), source = "Build.BRAND")
        items += Item("Pazarlama adı", Build.MODEL.orDash(), source = "Build.MODEL")
        items += Item("Kod adı (cihaz)", Build.DEVICE.orDash(), source = "Build.DEVICE")
        items += Item("Ürün", Build.PRODUCT.orDash(), source = "Build.PRODUCT")
        items += Item("Model kodu", modelCode(), source = "Build.MODEL + ro.product.*")
        items += Item("Cihaz tipi", deviceType(), source = "ro.build.characteristics")
        items += Item("SIM / operatör", carrier(), source = "TelephonyManager (izin gerekmez)")
        items += Item("Seri numarası", serial(), flag = if (Sys.prop("ro.serialno") == null) Flag.RESTRICTED else Flag.OK,
            detail = "Android 10+ seri numarası uygulamalara kapatıldı", source = "Build.getSerial / ro.serialno")
        return Section("Cihaz Kimliği", items)
    }

    private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "—"

    private fun modelCode(): String {
        val candidates = listOf(
            Sys.prop("ro.product.model"),
            Sys.prop("ro.product.vendor.model"),
            Sys.prop("ro.product.odm.model"),
            Sys.prop("ro.product.system.model")
        ).filterNotNull().distinct()
        return candidates.joinToString(" / ").ifBlank { Build.MODEL.orDash() }
    }

    private fun deviceType(): String {
        val chars = Sys.prop("ro.build.characteristics") ?: return "—"
        return chars.split(",").joinToString(", ") { c ->
            when (c.trim()) {
                "phone" -> "Telefon"
                "tablet" -> "Tablet"
                "nosdcard" -> "SD'siz"
                "emulator" -> "Emülatör"
                "default" -> "Varsayılan"
                else -> c.trim()
            }
        }
    }

    private fun carrier(): String {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val name = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
            val sim = tm?.simOperatorName?.takeIf { it.isNotBlank() }
            val country = tm?.networkCountryIso?.uppercase()?.takeIf { it.isNotBlank() }
            listOfNotNull(name, sim, country).distinct().joinToString(" · ").ifBlank { "SIM yok / bilinmiyor" }
        } catch (t: Throwable) {
            "Okunamadı"
        }
    }

    private fun serial(): String {
        return try {
            val s = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
            if (s.isNullOrBlank() || s == Build.UNKNOWN) "Erişim yok" else s
        } catch (t: Throwable) {
            "Erişim yok (izin yok)"
        }
    }

    // --------------------------------------------------------------------- soc

    private fun soc(): Section {
        val items = ArrayList<Item>()

        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else Sys.prop("ro.soc.manufacturer")

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else Sys.prop("ro.soc.model")

        items += Item(
            "SoC üreticisi",
            socManufacturer?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: chipsetGuess() ?: "—",
            source = "Build.SOC_MANUFACTURER / ro.soc.manufacturer"
        )
        items += Item(
            "SoC modeli",
            socModel?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: "—",
            source = "Build.SOC_MODEL / ro.soc.model"
        )

        val platform = Sys.prop("ro.board.platform") ?: Sys.prop("ro.hardware") ?: Sys.prop("ro.boot.hardware")
        items += Item("Platform (yonga)", platform ?: "—", source = "ro.board.platform · ro.hardware")

        val machine = Sys.read("/sys/devices/soc0/machine")
        if (machine != null) items += Item("SoC makine tanımı", machine, source = "/sys/devices/soc0/machine")

        val socId = Sys.read("/sys/devices/soc0/soc_id")
        if (socId != null) items += Item("SoC kimliği (soc_id)", socId, source = "/sys/devices/soc0/soc_id")

        val revision = Sys.read("/sys/devices/soc0/revision")
        if (revision != null) items += Item("SoC revizyonu", revision, source = "/sys/devices/soc0/revision")

        val impl = Sys.read("/proc/cpuinfo")
            ?.let { Regex("(?m)^CPU implementer\\s*:\\s*(0x[0-9a-fA-F]+)").find(it)?.groupValues?.get(1) }
        if (impl != null) items += Item("CPU implementer", impl, source = "/proc/cpuinfo")

        val part = Sys.read("/proc/cpuinfo")
            ?.let { Regex("(?m)^CPU part\\s*:\\s*(0x[0-9a-fA-F]+)").find(it)?.groupValues?.get(1) }
        if (part != null) items += Item("CPU part", part, source = "/proc/cpuinfo")

        items += Item("Çekirdek sayısı", "${Runtime.getRuntime().availableProcessors()} çekirdek", source = "Runtime")
        return Section("Yonga Seti (SoC)", items)
    }

    private fun chipsetGuess(): String? {
        val hardware = Sys.prop("ro.hardware") ?: return null
        return when {
            hardware.contains("qcom", true) || hardware.contains("kona", true) ||
                hardware.contains("taro", true) || hardware.contains("lahaina", true) ||
                hardware.contains("kalama", true) || hardware.contains("pineapple", true) -> "Qualcomm"
            hardware.contains("mt", true) || hardware.contains("mediatek", true) -> "MediaTek"
            hardware.contains("exynos", true) || hardware.contains("universal", true) -> "Samsung"
            hardware.contains("kirin", true) || hardware.contains("hi3", true) -> "HiSilicon"
            hardware.contains("gs", true) || hardware.contains("tensor", true) -> "Google"
            hardware.contains("rtd", true) -> "Realtek"
            hardware.contains("spd", true) || hardware.contains("ums", true) -> "Unisoc"
            else -> null
        }
    }

    // --------------------------------------------------------------------- cpu

    data class Cluster(val count: Int, val maxKhz: Long, val minKhz: Long)

    private fun cpu(): Section {
        val items = ArrayList<Item>()
        val cores = Runtime.getRuntime().availableProcessors()
        val cpuinfo = Sys.readCached("/proc/cpuinfo") ?: ""

        val hardware = Regex("(?m)^Hardware\\s*:\\s*(.+)$").find(cpuinfo)?.groupValues?.get(1)?.trim()
        val processor = Regex("(?m)^Processor\\s*:\\s*(.+)$").find(cpuinfo)?.groupValues?.get(1)?.trim()
        val modelName = Regex("(?m)^model name\\s*:\\s*(.+)$").find(cpuinfo)?.groupValues?.get(1)?.trim()
        val architecture = Regex("(?m)^CPU architecture\\s*:\\s*(.+)$").find(cpuinfo)?.groupValues?.get(1)?.trim()

        hardware?.let { items += Item("Donanım (çip)", it, source = "/proc/cpuinfo → Hardware") }
        (modelName ?: processor)?.let { items += Item("İşlemci adı", it, source = "/proc/cpuinfo") }
        architecture?.let { items += Item("Mimari", "$it-bit", source = "/proc/cpuinfo → CPU architecture") }

        items += Item("ABI", Build.SUPPORTED_ABIS.joinToString(", "), source = "Build.SUPPORTED_ABIS")
        items += Item("Çekirdek sayısı", "$cores", source = "Runtime.availableProcessors()")

        val clusters = clusters(cores)
        if (clusters.isNotEmpty()) {
            val labels = clusterLabels(clusters)
            clusters.forEachIndexed { index, c ->
                items += Item(
                    labels[index],
                    "${c.count} × çekirdek · ${Fmt.round2(c.maxKhz / 1_000_000.0)} GHz",
                    detail = "min ${Fmt.round2(c.minKhz / 1_000_000.0)} GHz · max ${Fmt.round2(c.maxKhz / 1_000_000.0)} GHz",
                    source = "/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq"
                )
            }
        }

        val governor = Sys.read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        if (governor != null) items += Item("CPU governor", governor, source = "cpufreq/scaling_governor")

        val flags = Regex("(?m)^Features\\s*:\\s*(.+)$").find(cpuinfo)?.groupValues?.get(1)?.trim()
        if (flags != null) {
            val wanted = listOf("asimd", "fp16", "sve", "sve2", "bf16", "i8mm", "aes", "sha2", "crc32")
            val present = wanted.filter { flags.split(Regex("\\s+")).contains(it) }
            items += Item(
                "Komut setleri",
                present.joinToString(", ").ifBlank { "—" },
                detail = "toplam ${flags.split(Regex("\\s+")).size} bayrak",
                source = "/proc/cpuinfo → Features"
            )
        }

        caches()?.let { items += it }
        return Section("İşlemci (CPU)", items)
    }

    fun clusters(cores: Int = Runtime.getRuntime().availableProcessors()): List<Cluster> {
        val maxList = (0 until cores).map { i ->
            Sys.read("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull() ?: 0L
        }.filter { it > 0L }
        if (maxList.isEmpty()) return emptyList()

        val minMap = HashMap<Long, Long>()
        (0 until cores).forEach { i ->
            val max = Sys.read("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull() ?: return@forEach
            val min = Sys.read("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")?.toLongOrNull() ?: 0L
            minMap.putIfAbsent(max, min)
        }

        return maxList.groupBy { it }
            .map { (max, list) -> Cluster(list.size, max, minMap[max] ?: 0L) }
            .sortedByDescending { it.maxKhz }
    }

    private fun clusterLabels(clusters: List<Cluster>): List<String> = when (clusters.size) {
        1 -> listOf("Çekirdek kümesi")
        2 -> listOf("Performans (big)", "Verimlilik (little)")
        3 -> listOf("Prime çekirdek", "Performans (mid)", "Verimlilik (little)")
        else -> clusters.indices.map { i ->
            when (i) {
                0 -> "Prime çekirdek"
                clusters.lastIndex -> "Verimlilik (little)"
                else -> "Performans kümesi $i"
            }
        }
    }

    private fun caches(): Item? {
        val parts = ArrayList<String>()
        for (i in 0..4) {
            val base = "/sys/devices/system/cpu/cpu0/cache/index$i"
            val level = Sys.read("$base/level") ?: continue
            val size = Sys.read("$base/size") ?: continue
            val type = Sys.read("$base/type") ?: ""
            parts.add("L$level ${type.lowercase()} $size")
        }
        if (parts.isEmpty()) return null
        return Item("Önbellek", parts.joinToString(" · "), source = "/sys/devices/system/cpu/cpu0/cache")
    }

    // --------------------------------------------------------------------- gpu

    private fun gpu(): Section {
        val items = ArrayList<Item>()
        val info = GpuProbe.query()

        items += Item(
            "GPU modeli",
            info.renderer?.takeIf { it.isNotBlank() } ?: "—",
            source = "OpenGL ES sorgusu (EGL)"
        )
        info.vendor?.takeIf { it.isNotBlank() }?.let {
            items += Item("GPU üreticisi", it, source = "glGetString(GL_VENDOR)")
        }
        info.glVersion?.takeIf { it.isNotBlank() }?.let {
            items += Item("OpenGL ES sürümü", it, source = "glGetString(GL_VERSION)")
        }
        info.glslVersion?.takeIf { it.isNotBlank() }?.let {
            items += Item("GLSL sürümü", it, source = "glGetString(GL_SHADING_LANGUAGE_VERSION)")
        }

        if (info.renderer.isNullOrBlank()) {
            val fallback = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_model",
                "/sys/kernel/gpu/gpu_model"
            ).firstNotNullOfOrNull { Sys.read(it) }
            if (fallback != null) {
                items += Item("GPU modeli (sürücü)", fallback, source = "GPU sysfs")
            }
        }

        val (cur, max) = GpuProbe.gpuClock()
        if (max != null) items += Item("GPU maksimum saat", Fmt.mhzFromKhz(max), source = "GPU sysfs devfreq")
        if (cur != null) items += Item("GPU anlık saat", Fmt.mhzFromKhz(cur), source = "GPU sysfs devfreq")

        val gles = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.deviceConfigurationInfo.glEsVersion
        } catch (t: Throwable) {
            null
        }
        gles?.let { items += Item("Desteklenen GLES", it, source = "ActivityManager.deviceConfigurationInfo") }

        val gpuFreqList = Sys.read("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies")
        if (gpuFreqList != null) {
            val freqs = Regex("\\d+").findAll(gpuFreqList)
                .mapNotNull { it.value.toLongOrNull() }
                .map { it / 1_000_000.0 }
                .sorted()
                .joinToString(", ") { Fmt.round2(it) }
            if (freqs.isNotBlank()) items += Item("GPU frekans adımları", "$freqs GHz", source = "kgsl gpu_available_frequencies")
        }

        return Section("Grafik Birimi (GPU)", items)
    }

    // ------------------------------------------------------------------ memory

    private fun memory(): Section {
        val items = ArrayList<Item>()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val meminfo = parseMeminfo()
        val total = meminfo["MemTotal"]?.times(1024L) ?: mi.totalMem
        val available = mi.availMem
        val used = total - available

        items += Item("Toplam RAM", Fmt.gb(total), source = "/proc/meminfo → MemTotal")
        items += Item("Kullanılabilir", Fmt.gb(available), source = "ActivityManager.MemoryInfo")
        items += Item(
            "Kullanımda",
            "${Fmt.gb(used)} (${Fmt.percent(used.toDouble() / total * 100)})",
            source = "hesaplanmış: toplam − kullanılabilir"
        )
        meminfo["SwapTotal"]?.times(1024L)?.takeIf { it > 0 }?.let {
            items += Item("Takas (swap/zram)", Fmt.gb(it), source = "/proc/meminfo → SwapTotal")
        }
        meminfo["SwapFree"]?.times(1024L)?.let {
            items += Item("Boş takas", Fmt.gb(it), source = "/proc/meminfo → SwapFree")
        }
        items += Item("Düşük bellek eşiği", Fmt.bytes(mi.threshold), source = "MemoryInfo.threshold")
        items += Item("Low-RAM cihazı", if (am.isLowRamDevice) "Evet" else "Hayır", source = "ActivityManager")
        return Section("Bellek (RAM)", items)
    }

    private fun parseMeminfo(): Map<String, Long> {
        val text = Sys.read("/proc/meminfo") ?: return emptyMap()
        val map = HashMap<String, Long>()
        Regex("(?m)^(\\w+):\\s+(\\d+)\\s*kB").findAll(text).forEach {
            map[it.groupValues[1]] = it.groupValues[2].toLongOrNull() ?: 0L
        }
        return map
    }

    // ----------------------------------------------------------------- storage

    private fun storage(): Section {
        val items = ArrayList<Item>()
        try {
            val data = StatFs(Environment.getDataDirectory().absolutePath)
            val total = data.totalBytes
            val free = data.availableBytes
            val used = total - free
            items += Item("Toplam dahili depolama", Fmt.gb(total), source = "StatFs(/data)")
            items += Item(
                "Kullanılan",
                "${Fmt.gb(used)} (${Fmt.percent(used.toDouble() / total * 100)})",
                source = "StatFs(/data)"
            )
            items += Item("Boş", Fmt.gb(free), source = "StatFs(/data)")
            items += Item("Blok boyutu", "${data.blockSizeLong} B", source = "StatFs.blockSizeLong")
        } catch (_: Throwable) {
        }

        val sys = StatFs("/sys")
        items += Item("Sistem bölümü boyutu", Fmt.gb(sys.totalBytes), source = "StatFs(/sys)")

        // Storage chip identification
        val blocks = Sys.listDir("/sys/block").map { it.name }
        val mmc = blocks.firstOrNull { it.startsWith("mmcblk") }
        val scsi = blocks.firstOrNull { it.startsWith("sd") }

        if (mmc != null) {
            items += Item("Depolama veri yolu", "eMMC / SD (${mmc})", source = "/sys/block")
            val cid = Sys.read("/sys/block/$mmc/device/cid")
            if (cid != null) {
                items += Item("eMMC CID", cid, source = "/sys/block/$mmc/device/cid")
                val mid = cid.take(2).toIntOrNull(16)
                if (mid != null) {
                    items += Item(
                        "Depolama üreticisi (CID)",
                        emmcVendor(mid),
                        source = "CID MID byte (JEDEC)"
                    )
                }
            }
            Sys.read("/sys/block/$mmc/device/name")?.let {
                items += Item("Depolama modeli", it, source = "/sys/block/$mmc/device/name")
            }
            Sys.read("/sys/block/$mmc/device/fwrev")?.let {
                items += Item("Depolama firmware", it, source = "device/fwrev")
            }
        }

        if (scsi != null) {
            val model = Sys.read("/sys/block/$scsi/device/model")
            val vendor = Sys.read("/sys/block/$scsi/device/vendor")
            val rev = Sys.read("/sys/block/$scsi/device/rev")
            items += Item("Depolama veri yolu", "UFS / SCSI ($scsi)", source = "/sys/block")
            if (vendor != null || model != null) {
                items += Item(
                    "Depolama aygıtı",
                    listOfNotNull(vendor, model).joinToString(" ").trim().ifBlank { "—" },
                    source = "/sys/block/$scsi/device/{vendor,model}"
                )
            }
            rev?.let { items += Item("Depolama revizyonu", it, source = "/sys/block/$scsi/device/rev") }
        }

        if (mmc == null && scsi == null) {
            items += Item(
                "Depolama çipi bilgisi",
                "Kernel düğümü görünmüyor",
                flag = Flag.RESTRICTED,
                detail = "Üretici yazılımı bu cihazda /sys/block erişimini kısıtlamış",
                source = "/sys/block"
            )
        }

        return Section("Depolama", items)
    }

    private fun emmcVendor(mid: Int): String = when (mid) {
        0x15 -> "Samsung"
        0x45 -> "SanDisk"
        0x90 -> "SK Hynix"
        0x13 -> "Micron"
        0x11 -> "Toshiba / Kioxia"
        0x70 -> "Kingston"
        0x88 -> "Foresee"
        0xFE -> "Kingston"
        0x1B -> "SK Hynix"
        0x03 -> "SanDisk"
        else -> "Bilinmeyen (MID 0x%02X)".format(mid)
    }

    // ----------------------------------------------------------------- battery

    fun battery(): Section {
        val items = ArrayList<Item>()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val (sysPath, sysValues) = readBatterySysfs()

        val level = intentInt(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intentInt(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val tempTenths = intentInt(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else null

        val voltageMv = intentInt(BatteryManager.EXTRA_VOLTAGE, -1)
        val currentNow = try {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (t: Throwable) {
            Long.MIN_VALUE
        }
        val chargeCounter = try {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } catch (t: Throwable) {
            0L
        }

        items += Item("Şarj seviyesi", "$percent%", source = "BatteryManager / ACTION_BATTERY_CHANGED")
        items += Item(
            "Anlık sıcaklık",
            Fmt.tempText(tempC),
            detail = "pil hücresi sıcaklığı (gerçek sensör)",
            source = "EXTRA_TEMPERATURE"
        )

        val voltage = if (voltageMv > 0) voltageMv / 1000.0 else null
        voltage?.let { items += Item("Gerilim", "${Fmt.round2(it)} V", source = "EXTRA_VOLTAGE") }

        if (currentNow != Long.MIN_VALUE && currentNow != 0L) {
            val amps = currentNow / 1_000_000.0
            val watts = voltage?.let { Math.abs(amps) * it }
            items += Item(
                if (amps >= 0) "Şarj akımı" else "Deşarj akımı",
                "${Fmt.round2(Math.abs(amps))} A" + (watts?.let { " · ${Fmt.round2(it)} W" } ?: ""),
                source = "BATTERY_PROPERTY_CURRENT_NOW"
            )
        }

        if (chargeCounter > 0) {
            items += Item("Kalan yük", "${Fmt.round1(chargeCounter / 1000.0)} mAh", source = "BATTERY_PROPERTY_CHARGE_COUNTER")
        }

        items += Item("Güç kaynağı", pluggedText(intentInt(BatteryManager.EXTRA_PLUGGED, 0)), source = "EXTRA_PLUGGED")
        items += Item("Durum", statusText(intentInt(BatteryManager.EXTRA_STATUS, -1)), source = "EXTRA_STATUS")
        items += Item("Sağlık (sistem)", healthText(intentInt(BatteryManager.EXTRA_HEALTH, -1)), source = "EXTRA_HEALTH")

        val technology = batteryIntent()?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        (sysValues["technology"] ?: technology)?.let {
            items += Item("Kimya", it, source = "EXTRA_TECHNOLOGY")
        }

        // Real battery identity from sysfs, when the vendor exposes it
        val manufacturer = sysValues["manufacturer"] ?: sysValues["manufacturer_name"]
        val modelName = sysValues["model_name"] ?: sysValues["model"]
        if (manufacturer != null) {
            items += Item("Pil üreticisi", manufacturer, source = "$sysPath/manufacturer")
        } else {
            items += Item(
                "Pil üreticisi",
                "Okunamıyor",
                flag = Flag.RESTRICTED,
                detail = "Android pil hücresinin markasını API ile sunmuyor; bu cihazda sysfs düğümü de kapalı",
                source = "/sys/class/power_supply/battery/manufacturer"
            )
        }
        modelName?.let { items += Item("Pil modeli", it, source = "$sysPath/model_name") }

        // Capacity / wear
        val full = sysValues["charge_full"]?.toLongOrNull()
        val design = sysValues["charge_full_design"]?.toLongOrNull()
        val cycle = (sysValues["cycle_count"] ?: sysValues["battery_cycle"] ?: sysValues["bsoh"])?.toLongOrNull()

        if (full != null && design != null && design > 0) {
            val healthPct = full.toDouble() / design * 100.0
            items += Item(
                "Pil sağlığı",
                Fmt.percent(healthPct),
                detail = "tasarım ${design / 1000} mAh → şu anki tam şarj ${full / 1000} mAh",
                flag = if (healthPct >= 80) Flag.OK else Flag.ESTIMATE,
                source = "charge_full / charge_full_design"
            )
        } else if (design != null) {
            items += Item("Pil tasarım kapasitesi", "${design / 1000} mAh", source = "$sysPath/charge_full_design")
        }

        if (cycle != null && cycle > 0) {
            items += Item("Şarj döngüsü", "$cycle", source = "$sysPath/cycle_count")
        } else {
            items += Item(
                "Şarj döngüsü",
                "Kayıt yok",
                flag = Flag.MISSING,
                detail = "Bu cihazın BMS'i döngü sayacını uygulamalara açmıyor",
                source = "$sysPath/cycle_count"
            )
        }

        Sys.read("$sysPath/serial_number")?.let {
            items += Item("Pil seri no", it, source = "$sysPath/serial_number")
        }

        return Section("Batarya", items)
    }

    private fun readBatterySysfs(): Pair<String, Map<String, String>> {
        val dirs = Sys.listDir("/sys/class/power_supply")
        val batteryDir = dirs.firstOrNull { d ->
            Sys.read("${d.path}/type")?.equals("Battery", true) == true
        } ?: dirs.firstOrNull { it.name.contains("batt", true) }

        val base = batteryDir?.path ?: "/sys/class/power_supply/battery"
        val keys = listOf(
            "manufacturer", "manufacturer_name", "model_name", "model",
            "charge_full", "charge_full_design", "cycle_count", "battery_cycle",
            "bsoh", "technology", "serial_number", "temp", "health", "status",
            "voltage_now", "current_now", "capacity"
        )
        val values = HashMap<String, String>()
        keys.forEach { k ->
            Sys.read("$base/$k")?.let { values[k] = it }
        }
        return base to values
    }

    private fun batteryIntent(): Intent? = try {
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (t: Throwable) {
        null
    }

    private fun intentInt(key: String, def: Int): Int = batteryIntent()?.getIntExtra(key, def) ?: def

    private fun pluggedText(v: Int): String = when (v) {
        0 -> "Pilde"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC adaptör"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Kablosuz"
        else -> "Bilinmiyor ($v)"
    }

    private fun statusText(v: Int): String = when (v) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Şarj oluyor"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Deşarj oluyor"
        BatteryManager.BATTERY_STATUS_FULL -> "Dolu"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Şarj olmuyor"
        else -> "Bilinmiyor"
    }

    private fun healthText(v: Int): String = when (v) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "İyi"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Aşırı sıcak"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Ölü"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Aşırı gerilim"
        BatteryManager.BATTERY_HEALTH_COLD -> "Soğuk"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Arıza"
        else -> "Bilinmiyor"
    }

    // ------------------------------------------------------------------ screen

    private fun screen(): Section {
        val items = ArrayList<Item>()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display: Display = wm.defaultDisplay
        val metrics = ctx.resources.displayMetrics

        val mode = display.mode
        val width = if (mode != null) mode.physicalWidth else metrics.widthPixels
        val height = if (mode != null) mode.physicalHeight else metrics.heightPixels
        val refresh = if (mode != null) mode.refreshRate else display.refreshRate

        items += Item("Çözünürlük", "$width × $height px", source = "Display.Mode.physical*")
        items += Item("En-boy oranı", String.format(java.util.Locale.US, "%.2f : 1", height.toDouble() / width), source = "hesaplanmış")
        items += Item("Yenileme hızı", "${Fmt.round1(refresh.toDouble())} Hz", source = "Display.Mode.refreshRate")
        items += Item("Yoğunluk", "${metrics.densityDpi} dpi (${Fmt.round2(metrics.density.toDouble())}×)", source = "DisplayMetrics")

        val xdpi = metrics.xdpi.toDouble()
        val ydpi = metrics.ydpi.toDouble()
        if (xdpi > 0 && ydpi > 0) {
            val wIn = width / xdpi
            val hIn = height / ydpi
            val diag = sqrt(wIn * wIn + hIn * hIn)
            items += Item(
                "Fiziksel boyut",
                "${Fmt.round2(diag)} inç",
                detail = "yaklaşık ${Fmt.round2(wIn)} × ${Fmt.round2(hIn)} inç",
                flag = Flag.ESTIMATE,
                source = "DisplayMetrics.xdpi/ydpi (üretici raporu)"
            )
        }

        val modes = display.supportedModes
        if (modes != null && modes.size > 1) {
            val rates = modes.map { Fmt.round1(it.refreshRate.toDouble()) }.distinct().sorted()
            items += Item("Desteklenen hızlar", rates.joinToString(", ") { "$it Hz" }, source = "Display.supportedModes")
        }

        try {
            val hdr = display.hdrCapabilities
            val types = hdr.supportedHdrTypes.map { hdrName(it) }
            if (types.isNotEmpty()) {
                items += Item("HDR desteği", types.joinToString(", "), source = "Display.HdrCapabilities")
            }
        } catch (_: Throwable) {
        }

        items += Item("Geniş renk gamı", if (display.isWideColorGamut) "Var" else "Yok", source = "Display.isWideColorGamut")

        val panel = Edid.read()
        if (panel != null) {
            if (panel.manufacturerName != null) {
                items += Item(
                    "Panel üreticisi",
                    panel.manufacturerName,
                    detail = "EDID PNP kodu: ${panel.manufacturerCode}",
                    source = panel.path
                )
            }
            panel.productCode?.let { items += Item("Panel ürün kodu", it, source = panel.path) }
            if (panel.week != null && panel.year != null) {
                items += Item("Panel üretim tarihi", "Hafta ${panel.week} / ${panel.year}", source = "EDID byte 16-17")
            }
            panel.descriptor?.let { items += Item("Panel tanımı", it, source = "EDID descriptor") }
        } else {
            items += Item(
                "Panel üreticisi",
                "Okunamıyor",
                flag = Flag.RESTRICTED,
                detail = "Bu cihazda DSI panelinin EDID verisi uygulamalara kapalı",
                source = "/sys/class/drm/*/edid"
            )
        }

        return Section("Ekran / Panel", items)
    }

    private fun hdrName(t: Int): String = when (t) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "Tip $t"
    }

    // ----------------------------------------------------------------- cameras

    private fun cameras(): Section {
        val items = ArrayList<Item>()
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            return Section("Kamera", listOf(Item("Kamera", "Servis yok", flag = Flag.RESTRICTED)))
        }

        val ids = try {
            cm.cameraIdList
        } catch (t: Throwable) {
            emptyArray()
        }

        if (ids.isEmpty()) {
            items += Item("Kamera", "Bulunamadı", flag = Flag.MISSING)
            return Section("Kamera", items)
        }

        items += Item("Kamera sayısı", "${ids.size}", source = "CameraManager.cameraIdList")

        ids.forEach { id ->
            val c = try {
                cm.getCameraCharacteristics(id)
            } catch (t: Throwable) {
                null
            } ?: return@forEach

            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "Arka"
                CameraCharacteristics.LENS_FACING_FRONT -> "Ön"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "Harici"
                else -> "Bilinmiyor"
            }

            val pixel = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.isNotEmpty() == true
            val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

            val mpText = pixel?.let { Fmt.mp(it.width, it.height) } ?: "—"
            val title = "Kamera $id · $facing · $mpText"

            val detail = buildString {
                active?.let { append("aktif ${it.width()}×${it.height()} px · ") }
                physical?.let { append("sensör ${Fmt.round2(it.width.toDouble())}×${Fmt.round2(it.height.toDouble())} mm · ") }
                aperture?.firstOrNull()?.let { append("f/${Fmt.round1(it.toDouble())} · ") }
                focal?.firstOrNull()?.let {
                    append("${Fmt.round1(it.toDouble())} mm")
                    val a = aperture?.firstOrNull()
                    if (a != null && a > 0f && physical != null) {
                        // 35mm equivalent focal length
                        val diag35 = 43.27
                        val diagSensor = sqrt(
                            physical.width.toDouble() * physical.width +
                                physical.height.toDouble() * physical.height
                        )
                        if (diagSensor > 0) {
                            append(" (~${Fmt.round1(it.toDouble() * diag35 / diagSensor)} mm eşdeğer)")
                        }
                    }
                    append(" · ")
                }
                iso?.let { append("ISO ${it.lower}–${it.upper} · ") }
                append(if (flash) "flaş var" else "flaş yok")
                append(" · OIS ").append(if (ois) "var" else "yok")
                append(" · seviye ").append(hardwareLevelName(level))
            }

            items += Item(title, mpText, detail = detail, source = "CameraCharacteristics")
        }

        items += Item(
            "Kamera modülü üreticisi",
            "Okunamıyor",
            flag = Flag.RESTRICTED,
            detail = "Sensör/modül tedarikçisi (Sony, Samsung ISOCELL, OmniVision…) Android kamera API'sinde yok; kernel aygıt ağacında tutulur ve uygulamaya kapalıdır",
            source = "CameraCharacteristics (vendor alanı yok)"
        )

        return Section("Kamera", items)
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "—"
    }

    // ------------------------------------------------------------------- board

    private fun board(): Section {
        val items = ArrayList<Item>()

        items += Item("Anakart (board)", Build.BOARD.orDash(), source = "Build.BOARD")
        items += Item("Donanım", Build.HARDWARE.orDash(), source = "Build.HARDWARE")

        val odm = listOf(
            "ro.product.odm.manufacturer",
            "ro.product.vendor.manufacturer",
            "ro.product.system_ext.manufacturer",
            "ro.product.system.manufacturer"
        ).mapNotNull { k -> Sys.prop(k)?.let { k to it } }

        if (odm.isNotEmpty()) {
            items += Item(
                "Bileşen üreticileri (bölüm bölüm)",
                odm.joinToString("\n") { (k, v) -> "${partLabel(k)}: $v" },
                source = "ro.product.*.manufacturer"
            )
        }

        val odmModel = listOf("ro.product.odm.model", "ro.product.vendor.model", "ro.product.system.model")
            .mapNotNull { Sys.prop(it) }
            .distinct()
        if (odmModel.isNotEmpty()) {
            items += Item("Bölüm model kodları", odmModel.joinToString(" · "), source = "ro.product.*.model")
        }

        Sys.read("/sys/devices/soc0/family")?.let {
            items += Item("SoC ailesi", it, source = "/sys/devices/soc0/family")
        }
        Sys.read("/sys/devices/soc0/revision")?.let {
            items += Item("Donanım revizyonu", it, source = "/sys/devices/soc0/revision")
        }
        Sys.read("/sys/devices/soc0/platform_version")?.let {
            items += Item("Platform sürümü", it, source = "/sys/devices/soc0/platform_version")
        }

        // Device tree model — the closest thing to an OEM/ODM signed board name
        val dtModel = Sys.read("/proc/device-tree/model") ?: Sys.read("/sys/firmware/devicetree/base/model")
        if (dtModel != null) {
            items += Item("Aygıt ağacı modeli", dtModel.replace("\u0000", "").trim(), source = "/proc/device-tree/model")
        }

        val cmdline = Sys.read("/proc/cmdline")
        if (cmdline != null) {
            val boardKeys = Regex("androidboot\\.(\\w+)=([^\\s]+)").findAll(cmdline)
                .filter { it.groupValues[1] in listOf("hardware", "hardware.sku", "board_id", "product.hardware", "revision", "serialno", "bootloader", "baseband") }
                .map { "${it.groupValues[1]} = ${it.groupValues[2]}" }
                .toList()
            if (boardKeys.isNotEmpty()) {
                items += Item(
                    "Önyükleme parametreleri",
                    boardKeys.joinToString("\n"),
                    source = "/proc/cmdline"
                )
            }
        }

        items += Item(
            "Anakart üretim tarihi",
            "Cihazda kayıtlı değil",
            flag = Flag.MISSING,
            detail = "Üretim tarihi fabrika veritabanında tutulur; Android işletim sistemi bu alanı cihaza yazmaz, hiçbir uygulama okuyamaz",
            source = "—"
        )

        return Section("Anakart / Üretici", items)
    }

    private fun partLabel(key: String): String = when {
        key.contains("odm") -> "ODM (montaj)"
        key.contains("vendor") -> "Vendor"
        key.contains("system_ext") -> "System Ext"
        key.contains("system") -> "Sistem"
        else -> key
    }

    // ---------------------------------------------------------------- software

    private fun software(): Section {
        val items = ArrayList<Item>()

        items += Item("Android sürümü", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", source = "Build.VERSION")
        items += Item("Güvenlik yaması", Build.VERSION.SECURITY_PATCH.ifBlank { "—" }, source = "Build.VERSION.SECURITY_PATCH")
        items += Item("Derleme numarası", Build.DISPLAY.orDash(), source = "Build.DISPLAY")
        items += Item("Derleme kimliği", Build.ID.orDash(), source = "Build.ID")
        items += Item("Derleme tarihi", Fmt.date(Build.TIME), source = "Build.TIME (ROM derleme zamanı)")
        items += Item("Yapı tipi", "${Build.TYPE} / ${Build.TAGS}", source = "Build.TYPE · Build.TAGS")
        items += Item("Bootloader", Build.BOOTLOADER.orDash(), source = "Build.BOOTLOADER")
        items += Item("Baseband", Build.getRadioVersion()?.takeIf { it.isNotBlank() } ?: "—", source = "Build.getRadioVersion()")
        items += Item("Çekirdek", System.getProperty("os.version") ?: "—", source = "System.getProperty(os.version)")

        Sys.readCached("/proc/version")?.let {
            items += Item("Çekirdek derleme bilgisi", it, source = "/proc/version")
        }

        items += Item("Parmak izi", Build.FINGERPRINT.orDash(), source = "Build.FINGERPRINT")

        Sys.read("/sys/fs/selinux/enforce")?.let {
            items += Item("SELinux", if (it.trim() == "1") "Enforcing" else "Permissive", source = "/sys/fs/selinux/enforce")
        }

        val gms = try {
            ctx.packageManager.getPackageInfo("com.google.android.gms", 0).versionName
        } catch (t: Throwable) {
            null
        }
        if (gms != null) items += Item("Google Play Hizmetleri", gms, source = "PackageManager")

        val webview = try {
            ctx.packageManager.getPackageInfo("com.google.android.webview", 0).versionName
        } catch (t: Throwable) {
            null
        }
        if (webview != null) items += Item("Android System WebView", webview, source = "PackageManager")

        val root = listOf("/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su")
            .any { File(it).exists() }
        items += Item("Root (kabuk)", if (root) "Var" else "Yok", source = "bilinen su yolları")
        items += Item(
            "Uygulama imzası",
            "debug",
            detail = "Bu yapı yerel olarak derlenmiş debug APK'dır",
            flag = Flag.ESTIMATE,
            source = "BuildConfig"
        )

        return Section("Yazılım", items)
    }
}
