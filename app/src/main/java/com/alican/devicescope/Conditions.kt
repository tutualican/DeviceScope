package com.alican.devicescope

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.alican.devicescope.bench.WORKLOAD_VERSION

/**
 * Pre-flight conditions.
 *
 * A benchmark number is only comparable when the conditions were comparable. The
 * collector reads everything the platform exposes without root and writes it into
 * the result file next to the scores, so two runs can be compared condition by
 * condition instead of trusting a number in isolation.
 *
 * States:
 *  - OK    the condition is measured and within the preferred range
 *  - WARN  measurable, but it will make comparisons less clean
 *  - BLOCK the run should not start this way (the UI requires an explicit
 *          "yine de başlat" confirmation, which is recorded as forced=true)
 *
 * Nothing is guessed: an unavailable sensor becomes a WARN with "ölçülemedi",
 * never a made-up OK.
 */
enum class ConditionState { OK, WARN, BLOCK }

data class Condition(
    val key: String,
    val label: String,
    val value: String,
    val state: ConditionState,
    val detail: String? = null,
    val source: String
)

data class EnergyReading(
    val currentMa: Double?,
    val voltageV: Double?,
    val watts: Double?,
    val capacityMah: Double?,
    val chargeCounterMah: Double?,
    val source: String
)

data class Equilibrium(
    val windowSeconds: Int,
    val samples: List<Pair<Long, Float>>,
    val spanC: Float?,
    val startC: Float?,
    val endC: Float?,
    val slopePerMinC: Double?,
    val passed: Boolean,
    val measurable: Boolean,
    val note: String
)

data class ConditionsSnapshot(
    val batteryPercent: Int?,
    val batteryTempC: Float?,
    val charging: Boolean?,
    val powerSave: Boolean?,
    val airplane: Boolean?,
    val wifi: Boolean?,
    val mobile: Boolean?,
    val brightnessPercent: Int?,
    val autoBrightness: Boolean?,
    val screenTimeoutSec: Int?,
    val interactive: Boolean?,
    val thermalStatus: Int?,
    val thermalStatusLabel: String,
    val thermalHeadroom: Float?,
    val cpuZone: String?,
    val batteryZone: String?,
    val thermalAggregation: String,
    val capacityMah: Double?,
    val currentMa: Double?,
    val voltageV: Double?,
    val watts: Double?,
    val appVersion: String,
    val workloadVersion: Int,
    val items: List<Condition>,
    val forced: Boolean = false
) {
    val blocking: List<Condition> get() = items.filter { it.state == ConditionState.BLOCK }
    val warnings: List<Condition> get() = items.filter { it.state == ConditionState.WARN }
    val canStart: Boolean get() = blocking.isEmpty()
}

class ConditionCollector(private val ctx: Context) {

    private val thermal = ThermalCollector(ctx)
    private val battery = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivity = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun batteryPercent(): Int? = try {
        battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (_: Throwable) {
        null
    }

    fun batteryTempC(): Float? = try {
        thermal.read().batteryC
    } catch (_: Throwable) {
        null
    }

    fun isCharging(): Boolean? = try {
        val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, Int.MIN_VALUE) ?: Int.MIN_VALUE
        when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> battery?.isCharging
        }
    } catch (_: Throwable) {
        null
    }

    /** Signed current in mA, normalised from the platform's micro-amperes. */
    fun currentMa(): Double? = try {
        val raw = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == null || raw == Int.MIN_VALUE) null else normalizeCurrent(raw)
    } catch (_: Throwable) {
        null
    }

    /**
     * Some vendors report CURRENT_NOW in mA instead of the documented µA. Values
     * under 20 000 cannot be micro-amperes on a phone, so they are taken as mA.
     */
    private fun normalizeCurrent(raw: Int): Double {
        val v = raw.toDouble()
        return if (kotlin.math.abs(v) >= 20_000) v / 1000.0 else v
    }

    fun voltageV(): Double? = try {
        val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (mv == Int.MIN_VALUE || mv <= 0) null else mv / 1000.0
    } catch (_: Throwable) {
        null
    }

    fun capacityMah(): Double? = try {
        var out: Double? = null
        for (name in listOf("charge_full_design", "charge_full", "energy_full_design", "energy_full")) {
            val raw = Sys.read("/sys/class/power_supply/battery/$name")?.toDoubleOrNull() ?: continue
            out = if (name.startsWith("energy")) {
                val voltUv = Sys.read("/sys/class/power_supply/battery/voltage_now")?.toDoubleOrNull()
                if (voltUv != null && voltUv > 0) raw / (voltUv / 1000.0) else null
            } else {
                raw / 1000.0
            }
            if (out != null && out in 100.0..30_000.0) break else out = null
        }
        out
    } catch (_: Throwable) {
        null
    }

    fun chargeCounterMah(): Double? = try {
        val raw = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (raw == null || raw == Int.MIN_VALUE) null else raw / 1000.0
    } catch (_: Throwable) {
        null
    }

    fun energy(): EnergyReading {
        val current = currentMa()
        val voltage = voltageV()
        val watts = if (current != null && voltage != null) kotlin.math.abs(current) / 1000.0 * voltage else null
        return EnergyReading(
            currentMa = current,
            voltageV = voltage,
            watts = watts,
            capacityMah = capacityMah(),
            chargeCounterMah = chargeCounterMah(),
            source = "BatteryManager.CURRENT_NOW + EXTRA_VOLTAGE"
        )
    }

    fun snapshot(forced: Boolean = false): ConditionsSnapshot {
        val percent = batteryPercent()
        val temp = try {
            thermal.read()
        } catch (_: Throwable) {
            null
        }
        val charging = isCharging()
        val powerSave = try {
            power?.isPowerSaveMode
        } catch (_: Throwable) {
            null
        }

        val airplane = readGlobalInt(Settings.Global.AIRPLANE_MODE_ON)?.let { it == 1 }
        val caps = try {
            connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        } catch (_: Throwable) {
            null
        }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val mobile = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val interactive = try {
            power?.isInteractive
        } catch (_: Throwable) {
            null
        }

        val brightnessRaw = readSystemInt(Settings.System.SCREEN_BRIGHTNESS)
        val brightness = brightnessRaw?.takeIf { it in 0..255 }?.let { (it / 255.0 * 100.0).toInt() }
        val autoBrightness = readSystemInt(Settings.System.SCREEN_BRIGHTNESS_MODE)
            ?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }
        val timeoutSec = readSystemInt(Settings.System.SCREEN_OFF_TIMEOUT)?.div(1000)

        val thermalStatus = temp?.thermalStatus
        val headroom = temp?.thermalHeadroom
        val energy = energy()

        val items = ArrayList<Condition>()

        items += Condition(
            key = "batteryPercent",
            label = "Pil seviyesi",
            value = percent?.let { "%$it" } ?: "ölçülemedi",
            state = when {
                percent == null -> ConditionState.WARN
                percent < 20 -> ConditionState.BLOCK
                percent < 50 -> ConditionState.WARN
                else -> ConditionState.OK
            },
            detail = when {
                percent == null -> "BatteryManager kapasiteyi bildirmiyor"
                percent < 20 -> "Test sırasında cihaz kapanabilir; %50 üstü önerilir"
                percent < 50 -> "Karşılaştırma için %50 üstü önerilir; başlangıç seviyesi kaydedildi"
                else -> "Önerilen aralıkta"
            },
            source = "BatteryManager.BATTERY_PROPERTY_CAPACITY"
        )

        items += Condition(
            key = "charging",
            label = "Şarj durumu",
            value = charging?.let { if (it) "takılı" else "takılı değil" } ?: "ölçülemedi",
            state = when (charging) {
                true -> ConditionState.BLOCK
                false -> ConditionState.OK
                null -> ConditionState.WARN
            },
            detail = if (charging == true) {
                "Şarj ısı ve güç bütçesi ekler; ölçüm için kabloyu çıkarın"
            } else {
                "Batarya deşarjındayken ölçülür; watt değeri bu durumda anlamlıdır"
            },
            source = "ACTION_BATTERY_CHANGED / EXTRA_STATUS"
        )

        items += Condition(
            key = "powerSave",
            label = "Güç tasarrufu",
            value = powerSave?.let { if (it) "açık" else "kapalı" } ?: "ölçülemedi",
            state = when (powerSave) {
                true -> ConditionState.BLOCK
                false -> ConditionState.OK
                null -> ConditionState.WARN
            },
            detail = if (powerSave == true) "Güç tasarrufu CPU/GPU frekanslarını kısıtlar" else "Kısıtlama yok",
            source = "PowerManager.isPowerSaveMode"
        )

        items += Condition(
            key = "airplane",
            label = "Bağlantı",
            value = when {
                airplane == true -> "uçak modu"
                wifi == true -> "Wi-Fi"
                mobile == true -> "mobil veri"
                airplane == null -> "ölçülemedi"
                else -> "bağlı değil"
            },
            state = when {
                airplane == true -> ConditionState.OK
                airplane == null -> ConditionState.WARN
                else -> ConditionState.WARN
            },
            detail = if (airplane == true) {
                "Uçak modu: arka plan ağ trafiği yok"
            } else {
                "Ağ açık; bildirim ve senkronizasyon trafiği turları kirletebilir"
            },
            source = "Settings.Global.AIRPLANE_MODE_ON + ConnectivityManager"
        )

        items += Condition(
            key = "brightness",
            label = "Parlaklık",
            value = when {
                autoBrightness == true -> "otomatik"
                brightness != null -> "%$brightness"
                else -> "ölçülemedi"
            },
            state = if (autoBrightness == true) ConditionState.WARN else ConditionState.OK,
            detail = if (autoBrightness == true) {
                "Otomatik parlaklık test sırasında değişebilir; test modu %100'e sabitler"
            } else {
                "Test modu pencereyi %100 parlaklığa alır; başlangıç değeri kaydedildi"
            },
            source = "Settings.System.SCREEN_BRIGHTNESS"
        )

        items += Condition(
            key = "screenTimeout",
            label = "Ekran zaman aşımı",
            value = timeoutSec?.let { "${it / 60} dk ${it % 60} sn" } ?: "ölçülemedi",
            state = if (timeoutSec == null) ConditionState.WARN else ConditionState.OK,
            detail = "Test modu ekranı açık tutar (FLAG_KEEP_SCREEN_ON)",
            source = "Settings.System.SCREEN_OFF_TIMEOUT"
        )

        items += Condition(
            key = "screenOn",
            label = "Ekran durumu",
            value = interactive?.let { if (it) "açık" else "kapalı" } ?: "ölçülemedi",
            state = when (interactive) {
                false -> ConditionState.BLOCK
                true -> ConditionState.OK
                null -> ConditionState.WARN
            },
            detail = if (interactive == false) "Görsel sahne kapalı ekranda kare üretemez" else "Görsel sahne ölçülebilir",
            source = "PowerManager.isInteractive"
        )

        items += Condition(
            key = "thermalStatus",
            label = "Platform ısıl durumu",
            value = temp?.thermalStatusLabel ?: "ölçülemedi",
            state = when {
                thermalStatus == null -> ConditionState.WARN
                thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> ConditionState.BLOCK
                thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> ConditionState.WARN
                else -> ConditionState.OK
            },
            detail = if (thermalStatus == null) {
                "API 29 altında PowerManager ısıl durumu okunamıyor"
            } else {
                "Doğrudan platformdan okunur; hızdan tahmin edilmez"
            },
            source = "PowerManager.getCurrentThermalStatus()"
        )

        items += Condition(
            key = "thermalHeadroom",
            label = "Isıl boşluk",
            value = headroom?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "ölçülemedi",
            state = when {
                headroom == null -> ConditionState.WARN
                headroom < 0.15f -> ConditionState.BLOCK
                headroom < 0.35f -> ConditionState.WARN
                else -> ConditionState.OK
            },
            detail = "0'a yakın = kısıtlamaya yakın; API 30+",
            source = "PowerManager.getThermalHeadroom(0)"
        )

        items += Condition(
            key = "batteryTemp",
            label = "Batarya sıcaklığı",
            value = temp?.batteryC?.let { String.format(java.util.Locale.US, "%.1f °C", it) } ?: "ölçülemedi",
            state = if (temp?.batteryC == null) ConditionState.WARN else ConditionState.OK,
            detail = "Isıl denge kontrolünün başlangıç değeri",
            source = temp?.batteryZone ?: "kaynak yok"
        )

        val snap = ConditionsSnapshot(
            batteryPercent = percent,
            batteryTempC = temp?.batteryC,
            charging = charging,
            powerSave = powerSave,
            airplane = airplane,
            wifi = wifi,
            mobile = mobile,
            brightnessPercent = brightness,
            autoBrightness = autoBrightness,
            screenTimeoutSec = timeoutSec,
            interactive = interactive,
            thermalStatus = thermalStatus,
            thermalStatusLabel = temp?.thermalStatusLabel ?: "Bilinmiyor",
            thermalHeadroom = headroom,
            cpuZone = temp?.cpuZone,
            batteryZone = temp?.batteryZone,
            thermalAggregation = "CPU en sıcak zone; ortalama ayrıca kaydedilir",
            capacityMah = energy.capacityMah,
            currentMa = energy.currentMa,
            voltageV = energy.voltageV,
            watts = energy.watts,
            appVersion = AppInfo.versionName(ctx),
            workloadVersion = WORKLOAD_VERSION,
            items = items,
            forced = forced
        )

        return snap
    }

    private fun readGlobalInt(key: String): Int? = try {
        Settings.Global.getInt(ctx.contentResolver, key)
    } catch (_: Throwable) {
        null
    }

    private fun readSystemInt(key: String): Int? = try {
        Settings.System.getInt(ctx.contentResolver, key)
    } catch (_: Throwable) {
        null
    }
}

object AppInfo {
    fun versionName(ctx: Context): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}

/**
 * Waits until the battery temperature stops moving.
 *
 * The window is sampled at 5 s; the span is max-min over the whole window and the
 * slope is a least-squares fit in °C/min. A span of at most [maxSpanC] counts as
 * equilibrium. With no battery sensor the check cannot pass — it is reported as
 * "ölçülemedi" instead of being silently skipped.
 */
suspend fun measureEquilibrium(
    collector: ConditionCollector,
    windowSeconds: Int,
    sampleMs: Long = 5_000L,
    maxSpanC: Float = 0.3f,
    onTick: (String, Float) -> Unit = { _, _ -> }
): Equilibrium {
    val started = System.currentTimeMillis()
    val endAt = started + windowSeconds * 1000L
    val samples = ArrayList<Pair<Long, Float>>()
    while (System.currentTimeMillis() < endAt) {
        collector.batteryTempC()?.let { samples.add(System.currentTimeMillis() to it) }
        val fraction = ((System.currentTimeMillis() - started).toDouble() / (windowSeconds * 1000.0))
            .coerceIn(0.0, 1.0).toFloat()
        onTick("Isıl denge", fraction)
        kotlinx.coroutines.delay(sampleMs)
    }
    collector.batteryTempC()?.let { samples.add(System.currentTimeMillis() to it) }

    val values = samples.map { it.second }
    val span = if (values.size >= 2) values.max() - values.min() else null
    val slope = if (samples.size >= 3) {
        val t0 = samples.first().first.toDouble()
        val xs = samples.map { (it.first - t0) / 60_000.0 }
        val ys = samples.map { it.second.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - meanX) * (ys[i] - meanY)
            den += (xs[i] - meanX) * (xs[i] - meanX)
        }
        if (den > 0) num / den else null
    } else {
        null
    }

    val measurable = values.size >= 2
    val passed = measurable && span != null && span <= maxSpanC
    val note = when {
        !measurable -> "Batarya sıcaklığı okunamadı; denge doğrulanamadı (uyarı olarak kaydedildi)"
        passed -> String.format(
            java.util.Locale.US,
            "%d sn'de %.2f °C değişim (eşik %.1f °C) — denge sağlandı",
            windowSeconds, span, maxSpanC
        )
        else -> String.format(
            java.util.Locale.US,
            "%d sn'de %.2f °C değişim (eşik %.1f °C) — cihaz hâlâ ısınıyor/soğuyor",
            windowSeconds, span, maxSpanC
        )
    }

    return Equilibrium(
        windowSeconds = windowSeconds,
        samples = samples,
        spanC = span,
        startC = values.firstOrNull(),
        endC = values.lastOrNull(),
        slopePerMinC = slope,
        passed = passed,
        measurable = measurable,
        note = note
    )
}
