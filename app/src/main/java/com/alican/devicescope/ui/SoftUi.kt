package com.alican.devicescope.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alican.devicescope.Flag
import com.alican.devicescope.Item
import kotlin.math.min

/**
 * Design tokens: soft depth (one light source, one soft shadow) plus a very
 * faint glass sheen. This is the calmer successor to the old double-shadow
 * neumorphism: same "moulded from one material" base, far less visual noise.
 *
 * Palette notes
 *  - backgrounds are nearly neutral, surfaces differ by ~4 % luminance, so the
 *    hierarchy comes from type and spacing rather than from colour blocks.
 *  - accent is the system blue; [aiGradient] is reserved for headline scores so
 *    the eye only meets colour where a number matters.
 */
data class SoftPalette(
    val dark: Boolean,
    val bg: Color,
    val bgDeep: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val shadowDark: Color,
    val shadowLight: Color,
    val stroke: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val cool: Color,
    val warm: Color,
    val hot: Color,
    val crit: Color
) {
    val wellTop: Color get() = if (dark) Color(0xFF101014) else Color(0xFFECECF1)
    val wellBottom: Color get() = if (dark) Color(0xFF1C1C22) else Color(0xFFFBFBFD)

    /** Apple-Intelligence-like spectrum, used only for headline numbers. */
    val aiGradient: List<Color>
        get() = if (dark) {
            listOf(Color(0xFFBF9BFF), Color(0xFF6EA8FF), Color(0xFF4FE3C1), Color(0xFFFFC978))
        } else {
            listOf(Color(0xFF8B5CF6), Color(0xFF2F7CF6), Color(0xFF12B5A5), Color(0xFFE8A33D))
        }

    fun tempColor(c: Float?): Color = when {
        c == null -> text3
        c < 35f -> cool
        c < 45f -> accent
        c < 55f -> warm
        c < 70f -> hot
        else -> crit
    }

    fun flagColor(flag: Flag): Color = when (flag) {
        Flag.OK -> accent
        Flag.ESTIMATE -> warm
        Flag.RESTRICTED -> hot
        Flag.MISSING -> crit
    }

    fun flagLabel(flag: Flag): String = when (flag) {
        Flag.OK -> "Doğrulandı"
        Flag.ESTIMATE -> "Tahmin"
        Flag.RESTRICTED -> "Kısıtlı"
        Flag.MISSING -> "Kayıt yok"
    }
}

object SoftColors {
    val Dark = SoftPalette(
        dark = true,
        bg = Color(0xFF08080A),
        bgDeep = Color(0xFF111116),
        surface = Color(0xFF15151A),
        surfaceAlt = Color(0xFF1B1B21),
        shadowDark = Color(0xFF000000),
        shadowLight = Color(0xFF2A2A33),
        stroke = Color(0x17FFFFFF),
        text = Color(0xFFF5F5F7),
        text2 = Color(0xFFA6A6B0),
        text3 = Color(0xFF6E6E79),
        accent = Color(0xFF4C9BFF),
        cool = Color(0xFF64D2FF),
        warm = Color(0xFFFFD60A),
        hot = Color(0xFFFF9F0A),
        crit = Color(0xFFFF453A)
    )

    val Light = SoftPalette(
        dark = false,
        bg = Color(0xFFF5F5F7),
        bgDeep = Color(0xFFEDEDF2),
        surface = Color(0xFFFFFFFF),
        surfaceAlt = Color(0xFFFAFAFC),
        shadowDark = Color(0xFFD3D3DC),
        shadowLight = Color(0xFFFFFFFF),
        stroke = Color(0xFFE6E6EC),
        text = Color(0xFF1D1D1F),
        text2 = Color(0xFF6E6E73),
        text3 = Color(0xFF9A9AA1),
        accent = Color(0xFF0071E3),
        cool = Color(0xFF0A84FF),
        warm = Color(0xFFB26A00),
        hot = Color(0xFFB25000),
        crit = Color(0xFFD70015)
    )
}

val LocalSoft = compositionLocalOf { SoftColors.Dark }

@Composable
fun SoftTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) SoftColors.Dark else SoftColors.Light
    CompositionLocalProvider(LocalSoft provides palette, content = content)
}

// ------------------------------------------------------------- text helpers

/**
 * One text primitive for the whole app. Sizes follow a 5-step scale so screens
 * stop inventing 8/9/10 sp labels that made the old layout feel crowded.
 */
@Composable
fun SoftText(
    text: String,
    size: Int = 14,
    color: Color = LocalSoft.current.text,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontFeatureSettings = "tnum",
            lineHeight = (size * 1.35f).sp,
            letterSpacing = if (size >= 28) (-0.6).sp else 0.sp
        )
    )
}

/** Large screen title. One per screen: gives the page an obvious anchor. */
@Composable
fun SoftTitle(text: String, subtitle: String? = null) {
    val p = LocalSoft.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        SoftText(text, size = 30, weight = FontWeight.Bold)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            SoftText(subtitle, size = 13, color = p.text2)
        }
    }
}

// ----------------------------------------------------------------- surfaces

/** Raised card: one soft shadow, hairline border, faint vertical sheen. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalSoft.current
    Box(
        modifier
            .shadow(
                elevation = if (p.dark) 14.dp else 10.dp,
                shape = shape,
                clip = false,
                ambientColor = p.shadowDark.copy(alpha = if (p.dark) 0.55f else 0.30f),
                spotColor = p.shadowDark.copy(alpha = if (p.dark) 0.55f else 0.30f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (p.dark) p.surfaceAlt else p.surface,
                        if (p.dark) p.surface else p.surfaceAlt
                    )
                ),
                shape
            )
            .border(1.dp, p.stroke, shape)
    ) {
        Column(Modifier.fillMaxWidth().padding(padding), content = content)
    }
}

/** Inset area: used for URLs, keys and traces. */
@Composable
fun SoftWell(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val p = LocalSoft.current
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(p.wellTop, p.wellBottom)),
                shape
            )
            .border(1.dp, p.stroke, shape),
        content = content
    )
}

@Composable
fun SoftButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val p = LocalSoft.current
    val shape = RoundedCornerShape(18.dp)
    val base = when {
        danger -> p.crit
        primary -> p.accent
        else -> p.surface
    }
    val textColor = when {
        danger || primary -> Color.White
        else -> p.text
    }
    Box(
        modifier
            .clip(shape)
            .clickable(enabled = enabled) { onClick() }
            .then(
                if (primary || danger) Modifier
                    .shadow(
                        elevation = 10.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = base.copy(alpha = 0.35f),
                        spotColor = base.copy(alpha = 0.35f)
                    )
                else Modifier
            )
            .background(if (enabled) base else base.copy(alpha = 0.38f), shape)
            .then(if (primary || danger) Modifier else Modifier.border(1.dp, p.stroke, shape))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        SoftText(
            label,
            size = 15,
            color = if (enabled) textColor else textColor.copy(alpha = 0.5f),
            weight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** Quiet text button used for "show all" affordances. */
@Composable
fun SoftLink(label: String, color: Color = LocalSoft.current.accent, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 2.dp, vertical = 6.dp)
    ) {
        SoftText(label, size = 13, color = color, weight = FontWeight.SemiBold)
    }
}

@Composable
fun SoftChip(text: String, color: Color = LocalSoft.current.accent) {
    val p = LocalSoft.current
    Box(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (p.dark) 0.14f else 0.10f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        SoftText(text, size = 11, color = color, weight = FontWeight.Medium, maxLines = 1)
    }
}

/** Hairline separator, faded at both ends so lists do not look ruled. */
@Composable
fun SoftDivider() {
    val p = LocalSoft.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, p.stroke, Color.Transparent)
                )
            )
    )
}

/**
 * Section label. Deliberately quiet: 13 sp, secondary colour, sentence case.
 * The old version shouted in uppercase accent colour on every block.
 */
@Composable
fun SoftSectionTitle(text: String, trailing: String? = null) {
    val p = LocalSoft.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SoftText(text, size = 13, color = p.text2, weight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            SoftText(trailing, size = 12, color = p.text3, mono = true)
        }
    }
}

/**
 * Collapsible card. Progressive disclosure is the main tool that keeps these
 * screens readable: every measurement stays reachable, but only what answers the
 * user's first question is visible by default.
 */
@Composable
fun SoftDisclosure(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    defaultOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalSoft.current
    var open by remember(title) { mutableStateOf(defaultOpen) }

    SoftCard(Modifier.fillMaxWidth().animateContentSize(), padding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                SoftText(title, size = 15, weight = FontWeight.SemiBold, maxLines = 2)
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    SoftText(subtitle, size = 12, color = p.text3, maxLines = 2)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                SoftText(trailing, size = 13, color = p.text2, mono = true, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            SoftChevron(open = open, color = p.text3)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SoftChevron(open: Boolean, color: Color) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        rotate(rotation) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.22f, h * 0.38f)
                lineTo(w * 0.5f, h * 0.64f)
                lineTo(w * 0.78f, h * 0.38f)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

// ------------------------------------------------------------------ contents

@Composable
fun SoftItemRow(item: Item) {
    val p = LocalSoft.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftText(item.label, size = 13, color = p.text2, maxLines = 2, modifier = Modifier.weight(1f))
            if (item.flag != Flag.OK) {
                Spacer(Modifier.width(8.dp))
                SoftChip(p.flagLabel(item.flag), p.flagColor(item.flag))
            }
        }
        Spacer(Modifier.height(4.dp))
        SoftText(item.value, size = 16, weight = FontWeight.SemiBold)
        item.detail?.let {
            Spacer(Modifier.height(3.dp))
            SoftText(it, size = 12, color = p.text3)
        }
        item.source?.let {
            Spacer(Modifier.height(4.dp))
            SoftText(it, size = 11, color = p.text3.copy(alpha = 0.85f), mono = true)
        }
    }
}

/** Small metric tile: value first, label second. */
@Composable
fun SoftStat(
    label: String,
    value: String,
    unit: String? = null,
    color: Color = LocalSoft.current.text,
    modifier: Modifier = Modifier
) {
    val p = LocalSoft.current
    SoftCard(modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            SoftText(value, size = 22, weight = FontWeight.Bold, color = color, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                SoftText(unit, size = 11, color = p.text3, maxLines = 1)
            }
        }
        Spacer(Modifier.height(3.dp))
        SoftText(label, size = 11, color = p.text3, maxLines = 2)
    }
}

/** Circular gauge used for temperatures: thinner stroke, no heavy glow. */
@Composable
fun SoftGauge(
    label: String,
    value: Float?,
    unit: String,
    sub: String,
    color: Color,
    modifier: Modifier = Modifier,
    max: Float = 100f
) {
    val p = LocalSoft.current
    SoftCard(modifier, padding = 14.dp) {
        SoftText(label, size = 12, color = p.text2, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                drawArc(
                    color = if (p.dark) Color(0x22FFFFFF) else Color(0x11000000),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (value != null) {
                    val frac = (value / max).coerceIn(0.05f, 1f)
                    drawArc(
                        color = color,
                        startAngle = 135f,
                        sweepAngle = 270f * frac,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SoftText(
                    if (value == null) "—" else String.format(java.util.Locale.US, "%.1f", value),
                    size = 24,
                    color = color,
                    weight = FontWeight.Bold,
                    maxLines = 1
                )
                SoftText(unit, size = 10, color = p.text3, maxLines = 1)
            }
        }
        Spacer(Modifier.height(4.dp))
        SoftText(sub, size = 10, color = p.text3, maxLines = 2)
    }
}

/**
 * Headline score: a gradient ring around the number. This is the only place the
 * AI spectrum is used, so a score reads instantly without extra chrome.
 */
@Composable
fun SoftHeroScore(
    label: String,
    score: Double,
    subtitle: String,
    fraction: Float = 0.72f,
    badge: String? = null,
    badgeColor: Color = LocalSoft.current.accent
) {
    val p = LocalSoft.current
    SoftCard(Modifier.fillMaxWidth(), padding = 22.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 11.dp.toPx()
                    val inset = stroke / 2 + 3.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    val topLeft = Offset(inset, inset)
                    drawArc(
                        color = if (p.dark) Color(0x1FFFFFFF) else Color(0x0F000000),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    val brush = Brush.sweepGradient(
                        colors = listOf(
                            p.aiGradient[0], p.aiGradient[1], p.aiGradient[2], p.aiGradient[3], p.aiGradient[0]
                        )
                    )
                    drawArc(
                        brush = brush,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction.coerceIn(0.02f, 1f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SoftText(
                        String.format(java.util.Locale.US, "%.0f", score),
                        size = 36,
                        weight = FontWeight.Bold
                    )
                    SoftText(label, size = 10, color = p.text3, maxLines = 1)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                SoftText(subtitle, size = 13, color = p.text2, maxLines = 3)
                if (badge != null) {
                    Spacer(Modifier.height(10.dp))
                    SoftChip(badge, badgeColor)
                }
            }
        }
    }
}

/** Inset horizontal progress bar. */
@Composable
fun SoftBar(progress: Float, color: Color = LocalSoft.current.accent, height: Dp = 9.dp) {
    val p = LocalSoft.current
    val shape = RoundedCornerShape(height / 2)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(if (p.dark) Color(0x1FFFFFFF) else Color(0x0D000000), shape)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(shape)
                .background(color, shape)
        )
    }
}

@Composable
fun SoftBadge(text: String, color: Color) {
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun SoftKeyValue(label: String, value: String, color: Color = LocalSoft.current.text) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SoftText(label, size = 13, color = LocalSoft.current.text2, maxLines = 2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        SoftText(value, size = 13, color = color, weight = FontWeight.SemiBold, mono = true, maxLines = 2)
    }
}

/**
 * Segmented control: a single pill row instead of four stacked boxes.
 * Keeps the same information density on one line.
 */
@Composable
fun SoftSegmented(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalSoft.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (p.dark) Color(0x14FFFFFF) else Color(0x0A000000), RoundedCornerShape(18.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) p.surface else Color.Transparent)
                    .then(
                        if (active) Modifier.shadow(
                            elevation = 4.dp,
                            shape = RoundedCornerShape(14.dp),
                            clip = false,
                            ambientColor = p.shadowDark.copy(alpha = 0.35f),
                            spotColor = p.shadowDark.copy(alpha = 0.35f)
                        ) else Modifier
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                SoftText(
                    label,
                    size = 12,
                    color = if (active) p.text else p.text3,
                    weight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Decorative gradient orb. Two of these sit behind the header to give the page
 * the soft "intelligence glow" without any image asset.
 */
@Composable
fun SoftOrb(size: Dp, colors: List<Color>, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = colors.map { it.copy(alpha = 0.34f) } + Color.Transparent,
                center = center,
                radius = this.size.minDimension / 2f
            ),
            radius = this.size.minDimension / 2f
        )
    }
}

fun tempFraction(value: Float?, max: Float): Float = if (value == null) 0f else min(1f, value / max)
