package com.alican.devicescope.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alican.devicescope.DeviceInfoCollector
import com.alican.devicescope.HistoryCollector
import com.alican.devicescope.Section
import com.alican.devicescope.ServerState
import com.alican.devicescope.ThermalCollector
import com.alican.devicescope.ThermalSnapshot
import com.alican.devicescope.bench.BenchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) {
    DEVICE("Cihaz"),
    THERMAL("Sıcaklık"),
    HISTORY("Geçmiş"),
    REMOTE("Uzaktan"),
    TEST("Test")
}

class AppState(val context: Context) {

    var dark by mutableStateOf(true)
    var device by mutableStateOf<List<Section>>(emptyList())
    var history by mutableStateOf<List<Section>>(emptyList())
    var loading by mutableStateOf(true)
    var thermal by mutableStateOf<ThermalSnapshot?>(null)
    var thermalError by mutableStateOf<String?>(null)

    var serverRunning by mutableStateOf(ServerState.isRunning)
    var serverUrl by mutableStateOf<String?>(null)
    var serverError by mutableStateOf<String?>(null)

    private val thermalCollector = ThermalCollector(context)

    fun loadStatic(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                try {
                    DeviceInfoCollector(context).collect()
                } catch (t: Throwable) {
                    emptyList()
                }
            }
            val h = withContext(Dispatchers.IO) {
                try {
                    HistoryCollector(context).collect()
                } catch (t: Throwable) {
                    emptyList()
                }
            }
            device = d
            history = h
            loading = false
        }
    }

    fun readThermal() {
        try {
            thermal = thermalCollector.read()
            thermalError = null
        } catch (t: Throwable) {
            thermalError = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun refreshServer(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            serverRunning = ServerState.isRunning
            serverUrl = if (serverRunning) ServerState.url(context) else null
            serverError = ServerState.lastError
        }
    }
}

@Composable
fun DeviceScopeApp(state: AppState, bench: BenchController) {
    var tab by remember { mutableStateOf(Tab.DEVICE) }
    val scope = rememberCoroutineScope()
    val palette = if (state.dark) SoftColors.Dark else SoftColors.Light

    LaunchedEffect(Unit) {
        state.loadStatic(scope)
    }

    SoftTheme(dark = state.dark) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.bg,
                            if (state.dark) Color(0xFF0C0D11) else Color(0xFFEFF0F5)
                        )
                    )
                )
        ) {
            // Ambient "intelligence" glow: two soft orbs, no images, no chrome.
            SoftOrb(
                size = 300.dp,
                colors = listOf(
                    palette.aiGradient[0],
                    palette.aiGradient[1]
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp)
            )
            SoftOrb(
                size = 220.dp,
                colors = listOf(
                    palette.aiGradient[2],
                    palette.aiGradient[3]
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 10.dp)
            )

            Column(Modifier.fillMaxSize()) {
                AppHeader(state, onToggleTheme = { state.dark = !state.dark })

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.DEVICE -> DeviceScreen(state)
                        Tab.THERMAL -> ThermalScreen(state)
                        Tab.HISTORY -> HistoryScreen(state)
                        Tab.REMOTE -> RemoteScreen(state)
                        Tab.TEST -> TestScreen(state, bench)
                    }
                }

                SoftBottomBar(selected = tab, onSelect = { tab = it })
            }
        }
    }
}

@Composable
private fun AppHeader(state: AppState, onToggleTheme: () -> Unit) {
    val p = LocalSoft.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                SoftText("Device", size = 22, weight = FontWeight.Bold)
                SoftText("Scope", size = 22, color = p.accent, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(2.dp))
            SoftText(
                android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                    " · Android " + android.os.Build.VERSION.RELEASE,
                size = 11,
                color = p.text3,
                maxLines = 1
            )
        }
        // Live indicator: one tap target, no decorative extras.
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(p.surface)
                .clickable { onToggleTheme() },
            contentAlignment = Alignment.Center
        ) {
            SoftText(if (state.dark) "☀" else "☾", size = 15, color = p.text2)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(p.surface),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (state.serverRunning) p.accent else p.text3.copy(alpha = 0.45f))
            )
        }
    }
}

/**
 * Floating tab bar. Replaces the old full-width striped bar: one pill, one
 * active surface, thin glyphs. Five labels still fit without truncation.
 */
@Composable
private fun SoftBottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val p = LocalSoft.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(p.surface.copy(alpha = if (p.dark) 0.94f else 1f))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.values().forEach { t ->
                val active = t == selected
                val tint by animateColorAsState(
                    if (active) p.accent else p.text3,
                    label = "tabTint"
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) p.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSelect(t) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TabGlyph(tab = t, color = tint)
                    Spacer(Modifier.height(4.dp))
                    SoftText(
                        t.label,
                        size = 10,
                        color = tint,
                        weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Hand-drawn 18 dp line glyphs: consistent stroke, no icon dependency. */
@Composable
private fun TabGlyph(tab: Tab, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.6.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round)
        when (tab) {
            Tab.DEVICE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.28f, h * 0.06f),
                    size = Size(w * 0.44f, h * 0.88f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
                    style = stroke
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.44f, h * 0.80f),
                    end = Offset(w * 0.56f, h * 0.80f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
            Tab.THERMAL -> {
                drawArc(
                    color = color,
                    startAngle = 40f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.10f),
                    size = Size(w * 0.68f, h * 0.80f),
                    style = stroke
                )
                drawCircle(color = color, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.62f))
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.26f),
                    end = Offset(w * 0.5f, h * 0.56f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
            Tab.HISTORY -> {
                drawCircle(
                    color = color,
                    radius = w * 0.40f,
                    center = center,
                    style = stroke
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.30f),
                    end = Offset(w * 0.5f, h * 0.52f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.52f),
                    end = Offset(w * 0.66f, h * 0.62f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
            Tab.REMOTE -> {
                // Three nested arcs opening upward plus a dot: a wifi mark.
                listOf(1.0f, 0.66f, 0.34f).forEach { f ->
                    val d = w * 0.86f * f
                    drawArc(
                        color = color,
                        startAngle = 215f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(w * 0.5f - d / 2f, h * 0.30f),
                        size = Size(d, d),
                        style = stroke
                    )
                }
                drawCircle(color = color, radius = w * 0.075f, center = Offset(w * 0.5f, h * 0.80f))
            }
            Tab.TEST -> {
                drawArc(
                    color = color,
                    startAngle = 160f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(w * 0.10f, h * 0.24f),
                    size = Size(w * 0.80f, h * 0.62f),
                    style = stroke
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.62f),
                    end = Offset(w * 0.70f, h * 0.36f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun ScreenScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
        content = content
    )
}

@Composable
fun LoadingCard(message: String) {
    val p = LocalSoft.current
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(p.accent.copy(alpha = 0.20f))
            )
            Spacer(Modifier.width(12.dp))
            SoftText(message, size = 13, color = p.text2)
        }
    }
}

/**
 * Section list with progressive disclosure: three rows are visible, the rest
 * wait behind one quiet link. A long hardware dump becomes a readable card
 * stack without losing a single field.
 */
@Composable
fun SectionList(sections: List<Section>, preview: Int = 3) {
    sections.forEach { section ->
        var open by remember(section.title) { mutableStateOf(false) }
        val items = if (open) section.items else section.items.take(preview)
        val hidden = section.items.size - items.size

        SoftCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            SoftText(section.title, size = 15, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { index, item ->
                if (index > 0) SoftDivider()
                SoftItemRow(item)
            }
            if (hidden > 0) {
                Spacer(Modifier.height(6.dp))
                SoftLink("${section.items.size} alanın tümünü göster") { open = true }
            } else if (open && section.items.size > preview) {
                Spacer(Modifier.height(6.dp))
                SoftLink("Daralt") { open = false }
            }
        }
    }
}

@Composable
fun LegendCard() {
    val p = LocalSoft.current
    SoftDisclosure(
        title = "Etiketler ne anlama geliyor?",
        subtitle = "Her alan kaynağıyla birlikte gelir; uydurulmuş değer yok.",
        defaultOpen = false
    ) {
        val rows = listOf(
            com.alican.devicescope.Flag.OK to "Platformdan veya gerçek sensörden doğrudan okundu.",
            com.alican.devicescope.Flag.ESTIMATE to "Gerçek verilerden hesaplandı; formülü açıklamada yazar.",
            com.alican.devicescope.Flag.RESTRICTED to "Donanımda var, Android rootsuz uygulamaya kapalı.",
            com.alican.devicescope.Flag.MISSING to "Cihaz bu bilgiyi hiçbir yerde saklamıyor; uydurulmadı."
        )
        rows.forEach { (flag, text) ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(p.flagColor(flag))
                )
                Spacer(Modifier.width(10.dp))
                SoftText("${p.flagLabel(flag)} — $text", size = 12, color = p.text3)
            }
        }
    }
}

@Composable
fun EmptyNote(text: String) {
    SoftCard(Modifier.fillMaxWidth()) {
        SoftText(text, size = 13, color = LocalSoft.current.text3)
    }
}

@Composable
fun CenteredNote(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SoftText(text, size = 12, color = LocalSoft.current.text3)
    }
}
