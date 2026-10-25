package com.alican.devicescope.bench

import java.util.Locale

/**
 * Serialises a marathon result.
 *
 * The JSON mirrors the short-suite files (`suite`, `scores`, `gpu`, `monitor`)
 * so the comparison screen and the browser panel can read every run with one
 * parser, and adds the sustained series so the charts are drawn from the samples
 * that were actually recorded rather than from a summary.
 */
object MarathonJson {

    fun encode(r: MarathonResult): String = buildString {
        append('{')
        append("\"suite\":\"Maraton\",")
        append("\"version\":3,")
        append("\"timestamp\":").append(r.timestamp).append(',')
        append("\"durationSeconds\":").append(n(r.durationSeconds, 1)).append(',')
        append("\"targetMinutes\":").append(r.config.effectiveMinutes).append(',')
        append("\"device\":").append(esc(r.device)).append(',')
        append("\"abi\":").append(esc(r.abi)).append(',')
        append("\"cores\":").append(r.cores).append(',')

        append("\"scores\":{")
        append("\"single\":").append(n(r.singleScore, 1)).append(',')
        append("\"multi\":").append(n(r.multiScore, 1)).append(',')
        append("\"memory\":").append(n(r.memoryScore, 1)).append(',')
        append("\"storage\":").append(n(r.storageScore, 1)).append(',')
        append("\"gpu\":").append(n(r.gpuScore, 1)).append(',')
        append("\"ux\":").append(n(r.uxScore, 1)).append(',')
        append("\"stability\":").append(n(r.stabilityScore, 1)).append(',')
        append("\"total\":").append(n(r.total, 1))
        append("},")

        append("\"weights\":{")
        append(MarathonRunner.WEIGHTS.entries.joinToString(",") { (k, v) ->
            "\"$k\":${n(v, 2)}"
        })
        append("},")

        append("\"stability\":{")
        append("\"retentionPercent\":").append(n(r.retentionPercent, 1)).append(',')
        append("\"cvPercent\":").append(n(r.stabilityCv, 2)).append(',')
        append("\"throttled\":").append(r.throttled).append(',')
        append("\"rounds\":").append(r.rounds.size).append(',')
        append("\"peakTempC\":").append(n(r.peakTempC?.toDouble(), 1)).append(',')
        append("\"startTempC\":").append(n(r.startTempC?.toDouble(), 1)).append(',')
        append("\"endTempC\":").append(n(r.endTempC?.toDouble(), 1))
        append("},")

        append("\"gpu\":{")
        append("\"api\":").append(esc(r.gpu?.api)).append(',')
        append("\"renderer\":").append(esc(r.gpu?.renderer)).append(',')
        append("\"driverVersion\":").append(esc(r.gpu?.glVersion)).append(',')
        append("\"computeGops\":").append(n(r.gpu?.computeGops, 2)).append(',')
        append("\"sceneFps\":").append(n(r.gpu?.sceneFps, 1)).append(',')
        append("\"note\":").append(esc(r.gpu?.fallbackNote ?: r.gpu?.error))
        append("},")

        append("\"scene\":")
        val scene = r.scene
        if (scene == null) {
            append("null")
        } else {
            append('{')
            append("\"avgFps\":").append(n(scene.avgFps, 1)).append(',')
            append("\"medianFrameMs\":").append(n(scene.medianFrameMs, 2)).append(',')
            append("\"p95FrameMs\":").append(n(scene.p95FrameMs, 2)).append(',')
            append("\"worstFrameMs\":").append(n(scene.worstFrameMs, 2)).append(',')
            append("\"jankPercent\":").append(n(scene.jankPercent, 2)).append(',')
            append("\"frames\":").append(scene.frames).append(',')
            append("\"durationMs\":").append(scene.durationMs)
            append('}')
        }
        append(',')

        append("\"ux\":")
        val ux = r.uxNumeric
        if (ux == null) {
            append("null")
        } else {
            append('{')
            append("\"avgFps\":").append(n(ux.avgFps, 1)).append(',')
            append("\"medianFrameMs\":").append(n(ux.medianFrameMs, 2)).append(',')
            append("\"p95FrameMs\":").append(n(ux.p95FrameMs, 2)).append(',')
            append("\"jankPercent\":").append(n(ux.jankPercent, 2)).append(',')
            append("\"frames\":").append(ux.frames)
            append('}')
        }
        append(',')

        append("\"monitor\":{")
        append("\"cpuPeakC\":").append(n(r.monitor.peakCpu?.toDouble(), 1)).append(',')
        append("\"cpuMinC\":").append(n(r.monitor.minCpu?.toDouble(), 1)).append(',')
        append("\"batteryPeakC\":").append(n(r.monitor.peakBattery?.toDouble(), 1)).append(',')
        append("\"freqMaxMhz\":").append(r.monitor.maxFreq?.toString() ?: "null").append(',')
        append("\"freqMinMhz\":").append(r.monitor.minFreq?.toString() ?: "null").append(',')
        append("\"batteryStart\":").append(r.monitor.batteryStart?.toString() ?: "null").append(',')
        append("\"batteryEnd\":").append(r.monitor.batteryEnd?.toString() ?: "null").append(',')
        append("\"sampleCount\":").append(r.monitor.cpuTemps.size)
        append("},")

        append("\"verification\":{")
        append("\"passed\":").append(r.verified).append(',')
        append("\"failedWorkloads\":[").append(r.failedWorkloads.joinToString(",")).append("],")
        append("\"checksumsStable\":").append((r.single + r.multi).all { it.checksumStable }).append(',')
        append("\"screenKeptOn\":").append(r.screenKeptOn).append(',')
        append("\"screenChecks\":").append(r.screenChecks).append(',')
        append("\"screenOffChecks\":").append(r.screenOffChecks)
        append("},")

        append("\"single\":[").append(r.single.joinToString(",") { measurement(it) }).append("],")
        append("\"multi\":[").append(r.multi.joinToString(",") { measurement(it) }).append("],")
        append("\"memory\":").append(r.memory?.let { measurement(it) } ?: "null").append(',')

        append("\"storage\":")
        val storage = r.storage
        if (storage == null) {
            append("null")
        } else {
            append('{')
            append("\"writeMbPerSec\":").append(n(storage.writeMbPerSec, 1)).append(',')
            append("\"readMbPerSec\":").append(n(storage.readMbPerSec, 1)).append(',')
            append("\"cvWrite\":").append(n(storage.cvWrite, 2)).append(',')
            append("\"cvRead\":").append(n(storage.cvRead, 2)).append(',')
            append("\"sizeMb\":").append(storage.sizeMb)
            append('}')
        }
        append(',')

        // Sustained series, seconds relative to the start of the run so the
        // browser can draw the x axis without knowing the device clock.
        val base = r.rounds.firstOrNull()?.timestamp ?: r.timestamp
        append("\"rounds\":[")
        r.rounds.forEachIndexed { index, s ->
            if (index > 0) append(',')
            append('{')
            append("\"t\":").append(n((s.timestamp - base) / 1000.0, 1)).append(',')
            append("\"single\":").append(s.singleCore).append(',')
            append("\"index\":").append(n(s.index, 1)).append(',')
            append("\"cpu\":").append(n(s.cpuTempC?.toDouble(), 1)).append(',')
            append("\"battery\":").append(n(s.batteryTempC?.toDouble(), 1)).append(',')
            append("\"freqMhz\":").append(s.freqMhz)
            append('}')
        }
        append("],")

        append("\"temps\":[")
        r.tempSamples.forEachIndexed { index, (ts, value) ->
            if (index > 0) append(',')
            append('[').append(n((ts - base) / 1000.0, 1)).append(',')
            append(n(value.toDouble(), 1)).append(']')
        }
        append(']')

        append('}')
    }

    private fun measurement(m: Measurement): String = buildString {
        append('{')
        append("\"workload\":").append(esc(m.workload.title)).append(',')
        append("\"category\":").append(esc(m.workload.cat.label)).append(',')
        append("\"threads\":").append(m.threads).append(',')
        append("\"medianSeconds\":").append(n(m.medianSeconds, 6)).append(',')
        append("\"throughput\":").append(n(m.throughput, 2)).append(',')
        append("\"unit\":").append(esc(m.unit)).append(',')
        append("\"index\":").append(n(m.index, 2)).append(',')
        append("\"cvPercent\":").append(n(m.cvPercent, 3)).append(',')
        append("\"checksumStable\":").append(m.checksumStable).append(',')
        append("\"repeats\":").append(m.repeats)
        append('}')
    }

    private fun esc(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun n(v: Double?, decimals: Int = 3): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "null"
        return String.format(Locale.US, "%.${decimals}f", v)
    }
}
