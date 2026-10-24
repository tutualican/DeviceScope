package com.alican.devicescope

/**
 * Display panel identification via EDID.
 *
 * On many phones the DSI panel exposes an EDID blob under /sys/class/drm/.
 * When present, EDID gives *real* panel vendor (PNP id), product code,
 * manufacture week/year and the panel descriptor string. When SELinux hides it,
 * we report that honestly instead of guessing.
 */
object Edid {

    data class PanelInfo(
        val path: String,
        val manufacturerCode: String?,
        val manufacturerName: String?,
        val productCode: String?,
        val serial: String?,
        val week: Int?,
        val year: Int?,
        val descriptor: String?
    )

    private val candidatePaths: List<String> by lazy {
        val list = ArrayList<String>()
        for (dir in Sys.listDir("/sys/class/drm")) {
            val edid = "${dir.path}/edid"
            if (Sys.isReadable(edid)) list.add(edid)
        }
        list.add("/sys/class/graphics/fb0/edid")
        list.add("/sys/devices/virtual/graphics/fb0/edid")
        list
    }

    fun read(): PanelInfo? {
        for (path in candidatePaths) {
            val bytes = Sys.readBytes(path) ?: continue
            if (bytes.size < 128) continue
            if (bytes[0] != 0x00.toByte() || bytes[1] != 0xFF.toByte() || bytes[7] != 0x00.toByte()) continue

            val mfgCode = manufacturerCode(bytes)
            val product = ((bytes[11].toInt() and 0xFF) shl 8) or (bytes[10].toInt() and 0xFF)
            val serial = (bytes[12].toLong() and 0xFF) or
                ((bytes[13].toLong() and 0xFF) shl 8) or
                ((bytes[14].toLong() and 0xFF) shl 16) or
                ((bytes[15].toLong() and 0xFF) shl 24)
            val week = bytes[16].toInt() and 0xFF
            val year = (bytes[17].toInt() and 0xFF) + 1990

            return PanelInfo(
                path = path,
                manufacturerCode = mfgCode,
                manufacturerName = mfgCode?.let { Fmt.pnp(it) },
                productCode = "0x%04X".format(product),
                serial = if (serial == 0L) null else "0x%08X".format(serial),
                week = week.takeIf { it in 1..54 },
                year = year.takeIf { it in 1990..2100 },
                descriptor = descriptor(bytes)
            )
        }
        return null
    }

    private fun manufacturerCode(b: ByteArray): String? {
        val v = ((b[8].toInt() and 0xFF) shl 8) or (b[9].toInt() and 0xFF)
        val c1 = ((v shr 10) and 0x1F)
        val c2 = ((v shr 5) and 0x1F)
        val c3 = (v and 0x1F)
        if (c1 < 1 || c2 < 1 || c3 < 1) return null
        return buildString {
            append(('A'.code + c1 - 1).toChar())
            append(('A'.code + c2 - 1).toChar())
            append(('A'.code + c3 - 1).toChar())
        }
    }

    private fun descriptor(b: ByteArray): String? {
        var offset = 54
        while (offset + 18 <= 128) {
            if ((b[offset].toInt() and 0xFF) == 0 &&
                (b[offset + 1].toInt() and 0xFF) == 0 &&
                (b[offset + 2].toInt() and 0xFF) == 0
            ) {
                val tag = b[offset + 3].toInt() and 0xFF
                if (tag == 0xFC || tag == 0xFE) {
                    val sb = StringBuilder()
                    for (i in 5 until 18) {
                        val c = b[offset + i].toInt() and 0xFF
                        if (c == 0x0A || c == 0x00) break
                        if (c in 0x20..0x7E) sb.append(c.toChar())
                    }
                    val text = sb.toString().trim()
                    if (text.isNotEmpty()) return text
                }
            }
            offset += 18
        }
        return null
    }
}
