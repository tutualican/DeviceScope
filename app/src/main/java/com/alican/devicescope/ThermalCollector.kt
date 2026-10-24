package com.alican.devicescope

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

enum class Category(val label: String) {
    CPU("İşlemci"),
    GPU("Ekran kartı"),
    BATTERY("Batarya"),
    CHARGER("Şarj devresi"),
    MODEM("Modem / RF"),
    DISPLAY("Ekran"),
    SKIN("Dış yüzey"),
    MEMORY("Bellek"),
    CAMERA("Kamera"),
    AMBIENT("Ortam / kart"),
    OTHER("Diğer")
}

data class Zone(
    val type: String,
    val tempC: Float,
    val category: Category,
    val source: String
)

data class ThermalSnapshot(
    val batteryC: Float?,
    val cpuC: Float?,
    val gpuC: Float?,
    val skinC: Float?,
    val feltC: Float?,
    val feltEstimated: Boolean,
    val feltFormula: String,
    val hottest: Zone?,
    val zones: List<Zone>,
    val categoryMax: Map<Category, Float>,
    val coreMhz: List<Long>,
    val coreMaxMhz: List<Long>,
    val timestamp: Long
)

/**
 * Reads every thermal interface the platform exposes to an unprivileged app:
 *   /sys/class/thermal/thermal_zone*  (SoC tsens / thermal framework)
 *   /sys/class/hwmon/hwmon*           (board sensors, when present)
 *   BatteryManager                    (battery cell sensor)
 *   /sys/class/kgsl|devfreq           (GPU junction, Qualcomm/Mali)
 *
 * Nothing is invented: if a component has no readable sensor, it stays null
 * and the app says so.
 */
class ThermalCollector(private val ctx: Context) {

    private val batteryManager: BatteryManager? =
        ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    fun read(): ThermalSnapshot {
        val zones = ArrayList<Zone>()
        readThermalZones(zones)
        readHwmon(zones)
        dedupe(zones)

        val batteryC = batteryTemp()
        val cpuC = pick(zones, Category.CPU)
        val gpuC = pick(zones, Category.GPU) ?: gpuSysfsTemp()
        val skinC = pick(zones, Category.SKIN)

        val felt: Float?
        val estimated: Boolean
        val formula: String
        if (skinC != null) {
            felt = skinC
            estimated = false
            formula = "Cihazın dış yüzey sensöründen doğrudan okunuyor"
        } else if (batteryC != null && cpuC != null) {
            felt = Fmt.clampTemp((batteryC + (cpuC - batteryC) * 0.18).toDouble()).toFloat()
            estimated = true
            formula = "Tahmin: pil °C + (CPU °C − pil °C) × 0.18 — cihazda dış yüzey sensörü yok"
        } else if (batteryC != null) {
            felt = batteryC
            estimated = true
            formula = "Tahmin: yalnızca pil sıcaklığı okunabildiği için pil değeri kullanıldı"
        } else {
            felt = null
            estimated = true
            formula = "Hesaplanamadı: okunabilir sıcaklık sensörü yok"
        }

        val categoryMax = HashMap<Category, Float>()
        zones.forEach { z ->
            val cur = categoryMax[z.category]
            if (cur == null || z.tempC > cur) categoryMax[z.category] = z.tempC
        }
        batteryC?.let {
            val cur = categoryMax[Category.BATTERY]
            if (cur == null || it > cur) categoryMax[Category.BATTERY] = it
        }

        val cores = Runtime.getRuntime().availableProcessors()
        val coreMhz = (0 until cores).map { i ->
            Sys.read("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toLongOrNull()?.div(1000L) ?: 0L
        }
        val coreMaxMhz = (0 until cores).map { i ->
            Sys.read("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull()?.div(1000L) ?: 0L
        }

        return ThermalSnapshot(
            batteryC = batteryC,
            cpuC = cpuC,
            gpuC = gpuC,
            skinC = skinC,
            feltC = felt,
            feltEstimated = estimated,
            feltFormula = formula,
            hottest = zones.maxByOrNull { it.tempC },
            zones = zones.sortedByDescending { it.tempC },
            categoryMax = categoryMax,
            coreMhz = coreMhz,
            coreMaxMhz = coreMaxMhz,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun dedupe(zones: MutableList<Zone>) {
        val seen = HashSet<String>()
        zones.retainAll { seen.add("${it.type}|${it.source}") }
    }

    private fun readThermalZones(out: MutableList<Zone>) {
        for (dir in Sys.listDir("/sys/class/thermal")) {
            if (!dir.name.startsWith("thermal_zone")) continue
            val type = Sys.read("${dir.path}/type") ?: dir.name
            val raw = Sys.read("${dir.path}/temp")?.toLongOrNull() ?: continue
            val temp = normalize(raw) ?: continue
            out.add(Zone(type, temp, classify(type), "thermal"))
        }
    }

    private fun readHwmon(out: MutableList<Zone>) {
        for (dir in Sys.listDir("/sys/class/hwmon")) {
            val chip = Sys.read("${dir.path}/name") ?: dir.name
            for (f in Sys.listDir(dir.path)) {
                if (!f.name.startsWith("temp") || !f.name.endsWith("_input")) continue
                val raw = Sys.read(f.path)?.toLongOrNull() ?: continue
                val temp = normalize(raw) ?: continue
                val label = Sys.read("${dir.path}/${f.name.replace("_input", "_label")}") ?: f.name
                val name = if (label != f.name) "$chip · $label" else "$chip · $label"
                out.add(Zone(name, temp, classify("$chip $label"), "hwmon"))
            }
        }
    }

    /** Sysfs temperatures are millidegrees on Android; some vendors use tenths. */
    private fun normalize(raw: Long): Float? {
        val c: Float = when {
            raw >= 1000L -> raw / 1000f
            raw >= 100L -> raw / 10f
            raw <= -1000L -> raw / 1000f
            raw <= -100L -> raw / 10f
            else -> raw.toFloat()
        }
        if (c < -40f || c > 200f) return null
        return c
    }
    fun classify(type: String): Category {
        val t = type.lowercase()
        return when {
            listOf("skin", "shell", "back", "case", "frame", "surface", "housing").any { t.contains(it) } -> Category.SKIN
            listOf("batt", "bms", "fuel").any { t.contains(it) } -> Category.BATTERY
            listOf("gpu", "kgsl", "mali", "3d0", "graphics", "gpuss").any { t.contains(it) } -> Category.GPU
            listOf("chg", "charger", "usb", "vbus", "pmic", "smb", "dc-in", "dc_in", "wireless").any { t.contains(it) } -> Category.CHARGER
            listOf("modem", "mdm", "pa-", "pa_", "rf", "nr5g", "lte", "wtr").any { t.contains(it) } -> Category.MODEM
            listOf("disp", "panel", "lcd", "dsi", "oled", "ddic").any { t.contains(it) } -> Category.DISPLAY
            listOf("console", "cpu", "soc", "cluster", "tsens", "silver", "gold", "prime", "little", "big", "ap-", "ap_", "core").any { t.contains(it) } -> Category.CPU
            listOf("ddr", "mem", "lpddr", "ram").any { t.contains(it) } -> Category.MEMORY
            listOf("cam", "lens", "tof", "flash").any { t.contains(it) } -> Category.CAMERA
            listOf("ambient", "xo", "board", "quiet", "pm8", "pm6", "thermal-zone").any { t.contains(it) } -> Category.AMBIENT
            else -> Category.OTHER
        }
    }

    private fun pick(zones: List<Zone>, category: Category): Float? =
        zones.filter { it.category == category }.maxOfOrNull { it.tempC }

    private fun gpuSysfsTemp(): Float? {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/temp",
            "/sys/class/kgsl/kgsl-3d0/temp_min",
            "/sys/kernel/gpu/gpu_temperature"
        )
        for (p in candidates) {
            val raw = Sys.read(p)?.toLongOrNull() ?: continue
            normalize(raw)?.let { return it }
        }
        return null
    }

    private fun batteryTemp(): Float? {
        // 1) Sticky broadcast — most reliable and available everywhere
        try {
            val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenths != Int.MIN_VALUE) return tenths / 10f
        } catch (_: Throwable) {
        }
        // 2) Sysfs fallback
        for (d in Sys.listDir("/sys/class/power_supply")) {
            if (Sys.read("${d.path}/type")?.equals("Battery", true) != true) continue
            val raw = Sys.read("${d.path}/temp")?.toLongOrNull() ?: continue
            return raw / 10f
        }
        return null
    }
}
