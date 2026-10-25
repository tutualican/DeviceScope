package com.alican.devicescope.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alican.devicescope.Category
import com.alican.devicescope.Fmt
import com.alican.devicescope.HistoryCollector
import com.alican.devicescope.ThermalSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun num(v: Double?, digits: Int = 0): String =
    if (v == null) "—" else String.format(java.util.Locale.US, "%.${digits}f", v)

// --------------------------------------------------------------------- device

@Composable
fun DeviceScreen(state: AppState) {
    val p = LocalSoft.current
    ScreenScroll {
        SoftTitle("Cihaz", "Donanım envanteri · salt okunur")

        SoftCard(Modifier.fillMaxWidth()) {
            SoftText(
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                size = 22,
                weight = FontWeight.Bold,
                maxLines = 2
            )
            Spacer(Modifier.height(6.dp))
            SoftText(
                "${android.os.Build.DEVICE} · ${android.os.Build.BOARD} · API ${android.os.Build.VERSION.SDK_INT}",
                size = 12,
                color = p.text3,
                maxLines = 2
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else null
                if (soc != null) SoftChip(soc, p.accent)
                SoftChip("${Runtime.getRuntime().availableProcessors()} çekirdek", p.cool)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftStat(
                label = "Android",
                value = android.os.Build.VERSION.RELEASE,
                color = p.accent,
                modifier = Modifier.weight(1f)
            )
            SoftStat(
                label = "API",
                value = android.os.Build.VERSION.SDK_INT.toString(),
                modifier = Modifier.weight(1f)
            )
            SoftStat(
                label = "Mimari",
                value = android.os.Build.SUPPORTED_ABIS.firstOrNull()
                    ?.replace("arm64-v8a", "arm64")
                    ?.replace("armeabi-v7a", "arm32")
                    ?.replace("x86_64", "x64")
                    ?: "—",
                modifier = Modifier.weight(1f)
            )
        }

        if (state.loading) {
            Spacer(Modifier.height(12.dp))
            LoadingCard("Donanım bilgileri okunuyor…")
            return@ScreenScroll
        }

        Spacer(Modifier.height(12.dp))
        SectionList(state.device)

        Spacer(Modifier.height(8.dp))
        LegendCard()
    }
}

// -------------------------------------------------------------------- thermal

@Composable
fun ThermalScreen(state: AppState) {
    val p = LocalSoft.current

    LaunchedEffect(Unit) {
        while (true) {
            // Sensor files are read off the main thread so the UI never stutters.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { state.readThermal() }
            delay(1500)
        }
    }

    val snap: ThermalSnapshot? = state.thermal
    val hottest = listOfNotNull(snap?.cpuC, snap?.batteryC, snap?.feltC).maxOrNull()

    ScreenScroll {
        SoftTitle("Sıcaklık", "1,5 sn'de bir yenilenen canlı sensörler")

        // One dominant reading, then the supporting gauges. The old screen led
        // with three equal rings and no obvious place to look first.
        SoftCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SoftText("En sıcak bileşen", size = 12, color = p.text3)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        SoftText(
                            if (hottest == null) "—" else String.format(java.util.Locale.US, "%.1f", hottest),
                            size = 44,
                            weight = FontWeight.Bold,
                            color = p.tempColor(hottest)
                        )
                        Spacer(Modifier.width(3.dp))
                        SoftText("°C", size = 15, color = p.text3, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                val status = when {
                    hottest == null -> "sensör bekleniyor"
                    hottest < 40f -> "serin"
                    hottest < 50f -> "normal"
                    hottest < 65f -> "ılık"
                    else -> "yüksek"
                }
                SoftChip(status, p.tempColor(hottest))
            }
            Spacer(Modifier.height(14.dp))
            SoftBar(
                progress = ((hottest ?: 0f) / 100f).coerceIn(0f, 1f),
                color = p.tempColor(hottest),
                height = 10.dp
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftGauge(
                label = "İşlemci",
                value = snap?.cpuC,
                unit = "°C",
                sub = if (snap?.cpuC == null) "sensör yok" else "SoC",
                color = p.tempColor(snap?.cpuC),
                modifier = Modifier.weight(1f)
            )
            SoftGauge(
                label = "Batarya",
                value = snap?.batteryC,
                unit = "°C",
                sub = if (snap?.batteryC == null) "sensör yok" else "pil hücresi",
                color = p.tempColor(snap?.batteryC),
                modifier = Modifier.weight(1f)
            )
            SoftGauge(
                label = "Hissedilen",
                value = snap?.feltC,
                unit = "°C",
                sub = when {
                    snap == null -> "—"
                    snap.feltEstimated -> "tahmini"
                    else -> "yüzey"
                },
                color = p.tempColor(snap?.feltC),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        SoftDisclosure(
            title = "Bileşenler",
            subtitle = "Kategori bazında en yüksek okunan sensör"
        ) {
            Category.values().forEachIndexed { index, cat ->
                if (index > 0) SoftDivider()
                val v = snap?.categoryMax?.get(cat)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SoftText(cat.label, size = 13, color = p.text2)
                    Spacer(Modifier.weight(1f))
                    SoftText(
                        Fmt.tempText(v),
                        size = 15,
                        color = p.tempColor(v),
                        weight = FontWeight.SemiBold,
                        mono = true
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SoftDisclosure(
            title = "Çekirdek frekansları",
            subtitle = "Anlık değer / üst sınır",
            trailing = snap?.coreMhz?.size?.let { "$it CPU" }
        ) {
            val mhz = snap?.coreMhz ?: emptyList()
            val max = snap?.coreMaxMhz ?: emptyList()
            if (mhz.isEmpty()) {
                SoftText("Frekans bilgisi okunamadı.", size = 12, color = p.text3)
            } else {
                mhz.forEachIndexed { i, cur ->
                    val mx = max.getOrNull(i) ?: 0L
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SoftText("CPU $i", size = 11, color = p.text3, mono = true)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            SoftBar(
                                progress = if (mx > 0) cur.toFloat() / mx else 0f,
                                color = if (mx > 0 && cur > mx * 0.85) p.warm else p.accent,
                                height = 7.dp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        SoftText(
                            if (mx > 0) "$cur MHz" else "—",
                            size = 11,
                            color = p.text2,
                            mono = true
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SoftDisclosure(
            title = "Ham sensör bölgeleri",
            subtitle = "Sürücünün bildirdiği her termal bölge",
            trailing = snap?.zones?.size?.let { "$it" }
        ) {
            val zones = snap?.zones ?: emptyList()
            if (zones.isEmpty()) {
                SoftText("Okunabilir termal bölge bulunamadı.", size = 12, color = p.text3)
            } else {
                zones.forEachIndexed { index, z ->
                    if (index > 0) SoftDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            SoftText(z.type, size = 12, color = p.text2, mono = true, maxLines = 1)
                            SoftText(z.category.label, size = 11, color = p.text3, maxLines = 1)
                        }
                        SoftText(
                            Fmt.tempText(z.tempC),
                            size = 13,
                            color = p.tempColor(z.tempC),
                            weight = FontWeight.SemiBold,
                            mono = true
                        )
                    }
                }
            }
        }

        state.thermalError?.let {
            Spacer(Modifier.height(10.dp))
            EmptyNote("Termal okuma hatası: $it")
        }

        SoftDisclosure(
            title = "Hissedilen sıcaklık nasıl hesaplanıyor?",
            subtitle = if (snap?.feltEstimated == true) "Tahmini formül" else "Yüzey sensörü"
        ) {
            SoftText(
                snap?.feltFormula ?: "Ölçüm bekleniyor…",
                size = 12,
                color = p.text2
            )
            Spacer(Modifier.height(8.dp))
            SoftText(
                "Liste, cihazın /sys/class/thermal ve /sys/class/hwmon arayüzlerinde uygulamaya " +
                    "açık gerçek sensörleri gösterir. Bir bileşen yoksa o cihazda erişilebilir sensör yok demektir.",
                size = 12,
                color = p.text3
            )
        }

        Spacer(Modifier.height(14.dp))
    }
}

// -------------------------------------------------------------------- history

@Composable
fun HistoryScreen(state: AppState) {
    ScreenScroll {
        SoftTitle("Geçmiş", "Fabrika çıkışı, ilk kullanım ve güncelleme izleri")

        if (state.loading || state.history.isEmpty()) {
            LoadingCard("Geçmiş kayıtları taranıyor…")
            return@ScreenScroll
        }

        SectionList(state.history)

        val raw = remember(state.history) {
            try {
                HistoryCollector(state.context).rawDateProps()
            } catch (t: Throwable) {
                emptyList()
            }
        }
        if (raw.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            SoftDisclosure(
                title = "Ham sistem tarihleri",
                subtitle = "Yorum katılmamış özellik değerleri",
                trailing = "${raw.size}"
            ) {
                raw.forEachIndexed { index, item ->
                    if (index > 0) SoftDivider()
                    SoftItemRow(item)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        LegendCard()
        Spacer(Modifier.height(14.dp))
    }
}
