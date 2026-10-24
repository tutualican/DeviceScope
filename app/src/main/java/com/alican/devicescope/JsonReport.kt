package com.alican.devicescope

object JsonReport {

    fun esc(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun num(v: Float?, decimals: Int = 1): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "null"
        return String.format(java.util.Locale.US, "%.${decimals}f", v)
    }

    fun sections(list: List<Section>): String {
        val sb = StringBuilder()
        sb.append('[')
        list.forEachIndexed { si, section ->
            if (si > 0) sb.append(',')
            sb.append('{')
            sb.append("\"title\":").append(esc(section.title)).append(',')
            sb.append("\"items\":[")
            section.items.forEachIndexed { ii, item ->
                if (ii > 0) sb.append(',')
                sb.append('{')
                sb.append("\"label\":").append(esc(item.label)).append(',')
                sb.append("\"value\":").append(esc(item.value))
                if (item.detail != null) sb.append(",\"detail\":").append(esc(item.detail))
                if (item.source != null) sb.append(",\"source\":").append(esc(item.source))
                sb.append(",\"flag\":").append(esc(item.flag.name))
                sb.append('}')
            }
            sb.append("]}")
        }
        sb.append(']')
        return sb.toString()
    }

    fun thermal(t: ThermalSnapshot): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"battery\":").append(num(t.batteryC)).append(',')
        sb.append("\"cpu\":").append(num(t.cpuC)).append(',')
        sb.append("\"gpu\":").append(num(t.gpuC)).append(',')
        sb.append("\"skin\":").append(num(t.skinC)).append(',')
        sb.append("\"felt\":").append(num(t.feltC)).append(',')
        sb.append("\"feltEstimated\":").append(t.feltEstimated).append(',')
        sb.append("\"feltFormula\":").append(esc(t.feltFormula)).append(',')
        sb.append("\"timestamp\":").append(t.timestamp).append(',')

        sb.append("\"categoryMax\":{")
        t.categoryMax.entries.sortedBy { it.key.ordinal }.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append(esc(e.key.name)).append(':').append(num(e.value))
        }
        sb.append("},")

        sb.append("\"zones\":[")
        t.zones.forEachIndexed { i, z ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"type\":").append(esc(z.type)).append(',')
            sb.append("\"temp\":").append(num(z.tempC)).append(',')
            sb.append("\"category\":").append(esc(z.category.name))
            sb.append('}')
        }
        sb.append("],")

        sb.append("\"coreMhz\":[")
        t.coreMhz.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(v) }
        sb.append("],")

        sb.append("\"coreMaxMhz\":[")
        t.coreMaxMhz.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(v) }
        sb.append("]")

        sb.append('}')
        return sb.toString()
    }

    fun state(
        device: List<Section>,
        history: List<Section>,
        snap: ThermalSnapshot
    ): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"meta\":{")
        sb.append("\"app\":\"DeviceScope\",")
        sb.append("\"device\":").append(esc(Meta.deviceName())).append(',')
        sb.append("\"generated\":").append(System.currentTimeMillis())
        sb.append("},")
        sb.append("\"device\":").append(sections(device)).append(',')
        sb.append("\"history\":").append(sections(history)).append(',')
        sb.append("\"thermal\":").append(thermal(snap))
        sb.append('}')
        return sb.toString()
    }
}

object Meta {
    fun deviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
}
