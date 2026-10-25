package com.alican.devicescope.bench

import com.alican.devicescope.Condition
import com.alican.devicescope.ConditionsSnapshot
import com.alican.devicescope.Equilibrium
import java.util.Locale

/**
 * Serialises a marathon result.
 *
 * The file is self-describing: it carries the app version, the workload
 * (algorithm) version, every pre-flight condition, the equilibrium window, the
 * post-load cooling curve, the energy reading and the contamination flags. Two
 * runs can therefore be compared only when their conditions and versions match —
 * a raw score alone is never presented as comparable.
 *
 * Schema version 4. Older files (no `workloadVersion`) are read by the UI but
 * marked as "eski sürüm" instead of being silently compared.
 */
object MarathonJson {

    const val SCHEMA_VERSION = 4

    fun encode(r: MarathonResult): String = buildString {
        append('{')
        append("\"suite\":\"Maraton\",")
        append("\"schemaVersion\":").append(SCHEMA_VERSION).append(',')
        append("\"workloadVersion\":").append(r.workloadVersion).append(',')
        append("\"appVersion\":").append(esc(r.appVersion)).append(',')
        append("\"timestamp\":").append(r.timestamp).append(',')
        append("\"durationSeconds\":").append(n(r.durationSeconds, 1)).append(',')
        append("\"targetMinutes\":").append(r.config.effectiveMinutes).append(',')
        append("\"warmupSeconds\":").append(r.config.warmupSeconds).append(',')
        append("\"roundSeconds\":").append(n(r.config.roundSeconds, 2)).append(',')
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

        append("\"conditions\":")
        conditions(r.conditions)
        append(',')

        append("\"equilibrium\":")
        equilibrium(r.equilibrium)
        append(',')

        append("\"stability\":")
        stability(r)
        append(',')

        append("\"gpu\":{")
        append("\"api\":").append(esc(r.gpu?.api)).append(',')
        append("\"renderer\":").append(esc(r.gpu?.renderer)).append(',')
        append("\"driverVersion\":").append(esc(r.gpu?.glVersion)).append(',')
        append("\"computeGops\":").append(n(r.gpu?.computeGops, 2)).append(',')
        append("\"sceneFps\":").append(n(r.gpu?.sceneFps, 1)).append(',')
        append("\"note\":").append(esc(r.gpu?.fallbackNote ?: r.gpu?.error))
        append("},")

        append("\"scene\":")
        scene(r)
        append(',')

        append("\"ux\":")
        ux(r)
        append(',')

        append("\"monitor\":")
        monitor(r)
        append(',')

        append("\"energy\":")
        energy(r)
        append(',')

        append("\"battery\":{")
        append("\"startPercent\":").append(r.batteryStartPercent?.toString() ?: "null").append(',')
        append("\"endPercent\":").append(r.batteryEndPercent?.toString() ?: "null").append(',')
        append("\"lowWarning\":").append(r.lowBatteryWarning)
        append("},")

        append("\"verification\":{")
        append("\"passed\":").append(r.verified).append(',')
        append("\"failedWorkloads\":[").append(r.failedWorkloads.joinToString(",")).append("],")
        append("\"checksumsStable\":").append((r.single + r.multi).all { it.checksumStable }).append(',')
        append("\"screenKeptOn\":").append(r.screenKeptOn).append(',')
        append("\"screenChecks\":").append(r.screenChecks).append(',')
        append("\"screenOffChecks\":").append(r.screenOffChecks).append(',')
        append("\"thermalThrottleObserved\":").append(r.thermalThrottleObserved)
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
            append("\"sizeMb\":").append(storage.sizeMb).append(',')
            append("\"index\":").append(n(storage.index, 1))
            append('}')
        }
        append(',')

        append("\"rounds\":[")
        r.rounds.forEachIndexed { index, s ->
            if (index > 0) append(',')
            append('{')
            append("\"elapsed\":").append(n(s.elapsedMs / 1000.0, 1)).append(',')
            append("\"single\":").append(s.singleCore).append(',')
            append("\"index\":").append(n(s.index, 1)).append(',')
            append("\"seconds\":").append(n(s.roundSeconds, 2)).append(',')
            append("\"cpu\":").append(n(s.cpuTempC?.toDouble(), 1)).append(',')
            append("\"cpuAvg\":").append(n(s.cpuAvgC?.toDouble(), 1)).append(',')
            append("\"battery\":").append(n(s.batteryTempC?.toDouble(), 1)).append(',')
            append("\"freqMhz\":").append(s.freqMhz).append(',')
            append("\"coreFreqMhz\":[").append(s.coreFreqMhz.joinToString(",")).append("],")
            append("\"thermalStatus\":").append(s.thermalStatus?.toString() ?: "null").append(',')
            append("\"headroom\":").append(n(s.headroom?.toDouble(), 2)).append(',')
            append("\"otherBusyCores\":").append(n(s.otherBusyCores, 2)).append(',')
            append("\"contaminated\":").append(s.contaminated).append(',')
            append("\"contamination\":").append(esc(s.contamination)).append(',')
            append("\"watts\":").append(n(s.watts, 2)).append(',')
            append("\"currentMa\":").append(n(s.currentMa, 1))
            append('}')
        }
        append("],")

        val base = r.rounds.firstOrNull()?.timestamp ?: r.timestamp
        append("\"temps\":[")
        r.tempSamples.forEachIndexed { index, (ts, value) ->
            if (index > 0) append(',')
            append('[').append(n((ts - base) / 1000.0, 1)).append(',')
            append(n(value.toDouble(), 1)).append(']')
        }
        append("],")

        append("\"cooldown\":[")
        r.cooldown.forEachIndexed { index, s ->
            if (index > 0) append(',')
            append('[').append(n((s.timestamp - base) / 1000.0, 1)).append(',')
            append(n(s.cpuC?.toDouble(), 1)).append(',')
            append(n(s.batteryC?.toDouble(), 1)).append(']')
        }
        append("],")
        append("\"coolingRatePerMinC\":").append(n(r.coolingRatePerMin, 2))

        append('}')
    }

    // ------------------------------------------------------------- conditions

    private fun StringBuilder.conditions(c: ConditionsSnapshot) {
        append('{')
        append("\"forced\":").append(c.forced).append(',')
        append("\"batteryPercent\":").append(c.batteryPercent?.toString() ?: "null").append(',')
        append("\"batteryTempC\":").append(n(c.batteryTempC?.toDouble(), 1)).append(',')
        append("\"charging\":").append(c.charging?.toString() ?: "null").append(',')
        append("\"powerSave\":").append(c.powerSave?.toString() ?: "null").append(',')
        append("\"airplane\":").append(c.airplane?.toString() ?: "null").append(',')
        append("\"wifi\":").append(c.wifi?.toString() ?: "null").append(',')
        append("\"mobile\":").append(c.mobile?.toString() ?: "null").append(',')
        append("\"brightnessPercent\":").append(c.brightnessPercent?.toString() ?: "null").append(',')
        append("\"autoBrightness\":").append(c.autoBrightness?.toString() ?: "null").append(',')
        append("\"screenTimeoutSec\":").append(c.screenTimeoutSec?.toString() ?: "null").append(',')
        append("\"interactive\":").append(c.interactive?.toString() ?: "null").append(',')
        append("\"thermalStatus\":").append(c.thermalStatus?.toString() ?: "null").append(',')
        append("\"thermalStatusLabel\":").append(esc(c.thermalStatusLabel)).append(',')
        append("\"thermalHeadroom\":").append(n(c.thermalHeadroom?.toDouble(), 2)).append(',')
        append("\"cpuZone\":").append(esc(c.cpuZone)).append(',')
        append("\"batteryZone\":").append(esc(c.batteryZone)).append(',')
        append("\"thermalAggregation\":").append(esc(c.thermalAggregation)).append(',')
        append("\"capacityMah\":").append(n(c.capacityMah, 0)).append(',')
        append("\"currentMa\":").append(n(c.currentMa, 1)).append(',')
        append("\"voltageV\":").append(n(c.voltageV, 3)).append(',')
        append("\"watts\":").append(n(c.watts, 2)).append(',')
        append("\"appVersion\":").append(esc(c.appVersion)).append(',')
        append("\"workloadVersion\":").append(c.workloadVersion).append(',')
        append("\"items\":[")
        c.items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            item(item)
        }
        append("]}")
    }

    private fun StringBuilder.item(i: Condition) {
        append('{')
        append("\"key\":").append(esc(i.key)).append(',')
        append("\"label\":").append(esc(i.label)).append(',')
        append("\"value\":").append(esc(i.value)).append(',')
        append("\"state\":").append(esc(i.state.name)).append(',')
        append("\"detail\":").append(esc(i.detail)).append(',')
        append("\"source\":").append(esc(i.source))
        append('}')
    }

    private fun StringBuilder.equilibrium(e: Equilibrium) {
        append('{')
        append("\"windowSeconds\":").append(e.windowSeconds).append(',')
        append("\"spanC\":").append(n(e.spanC?.toDouble(), 2)).append(',')
        append("\"startC\":").append(n(e.startC?.toDouble(), 1)).append(',')
        append("\"endC\":").append(n(e.endC?.toDouble(), 1)).append(',')
        append("\"slopePerMinC\":").append(n(e.slopePerMinC, 3)).append(',')
        append("\"passed\":").append(e.passed).append(',')
        append("\"measurable\":").append(e.measurable).append(',')
        append("\"note\":").append(esc(e.note)).append(',')
        append("\"samples\":[")
        val base = e.samples.firstOrNull()?.first ?: 0L
        e.samples.forEachIndexed { index, (ts, value) ->
            if (index > 0) append(',')
            append('[').append(n((ts - base) / 1000.0, 1)).append(',')
                .append(n(value.toDouble(), 2)).append(']')
        }
        append("]}")
    }

    // --------------------------------------------------------------- stability

    private fun StringBuilder.stability(r: MarathonResult) {
        val s = r.stability
        append('{')
        append("\"retentionPercent\":").append(n(s.retentionPercent, 1)).append(',')
        append("\"retentionRawPercent\":").append(n(s.retentionRawPercent, 1)).append(',')
        append("\"slowStart\":").append(s.slowStart).append(',')
        append("\"retentionScore\":").append(n(s.retentionScore, 1)).append(',')
        append("\"consistencyScore\":").append(n(s.consistencyScore, 1)).append(',')
        append("\"score\":").append(n(s.score, 1)).append(',')
        append("\"singleCv\":").append(n(s.singleCv, 2)).append(',')
        append("\"multiCv\":").append(n(s.multiCv, 2)).append(',')
        append("\"combinedCv\":").append(n(s.combinedCv, 2)).append(',')
        append("\"warmupExcludedRounds\":").append(s.warmupExcludedRounds).append(',')
        append("\"contaminatedRounds\":").append(s.contaminatedRounds).append(',')
        append("\"validSingleRounds\":").append(s.validSingleRounds).append(',')
        append("\"validMultiRounds\":").append(s.validMultiRounds).append(',')
        append("\"bestWindowIndex\":").append(n(s.bestWindowIndex, 1)).append(',')
        append("\"lastWindowIndex\":").append(n(s.lastWindowIndex, 1)).append(',')
        append("\"throttled\":").append(r.throttled).append(',')
        append("\"thermalThrottleObserved\":").append(r.thermalThrottleObserved).append(',')
        append("\"minHeadroom\":").append(n(r.minHeadroom?.toDouble(), 2)).append(',')
        append("\"peakTempC\":").append(n(r.peakTempC?.toDouble(), 1)).append(',')
        append("\"startTempC\":").append(n(r.startTempC?.toDouble(), 1)).append(',')
        append("\"endTempC\":").append(n(r.endTempC?.toDouble(), 1)).append(',')
        append("\"rounds\":").append(r.rounds.size).append(',')
        append("\"weights\":{\"retention\":").append(n(MarathonRunner.RETENTION_WEIGHT, 1))
            .append(",\"consistency\":").append(n(MarathonRunner.CONSISTENCY_WEIGHT, 1)).append('}')
        append('}')
    }

    // ------------------------------------------------------------------- parts

    private fun StringBuilder.scene(r: MarathonResult) {
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
    }

    private fun StringBuilder.ux(r: MarathonResult) {
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
    }

    private fun StringBuilder.monitor(r: MarathonResult) {
        val m = r.monitor
        append('{')
        append("\"cpuPeakC\":").append(n(m.peakCpu?.toDouble(), 1)).append(',')
        append("\"cpuMinC\":").append(n(m.minCpu?.toDouble(), 1)).append(',')
        append("\"batteryPeakC\":").append(n(m.peakBattery?.toDouble(), 1)).append(',')
        append("\"freqMaxMhz\":").append(m.maxFreq?.toString() ?: "null").append(',')
        append("\"freqMinMhz\":").append(m.minFreq?.toString() ?: "null").append(',')
        append("\"batteryStart\":").append(m.batteryStart?.toString() ?: "null").append(',')
        append("\"batteryEnd\":").append(m.batteryEnd?.toString() ?: "null").append(',')
        append("\"sampleCount\":").append(m.cpuTemps.size).append(',')
        append("\"aggregation\":\"cpuPeakC en sıcak CPU zone; cpuAvgSeries ortalama\"")
        append('}')
    }

    private fun StringBuilder.energy(r: MarathonResult) {
        val e = r.energy
        if (e == null) {
            append("null")
            return
        }
        append('{')
        append("\"avgWatts\":").append(n(e.avgWatts, 2)).append(',')
        append("\"peakWatts\":").append(n(e.peakWatts, 2)).append(',')
        append("\"avgCurrentMa\":").append(n(e.avgCurrentMa, 1)).append(',')
        append("\"voltageV\":").append(n(e.voltageV, 3)).append(',')
        append("\"capacityMah\":").append(n(e.capacityMah, 0)).append(',')
        append("\"energyWh\":").append(n(e.energyWh, 3)).append(',')
        append("\"wattsPer1000Points\":").append(n(e.wattsPer1000Points, 3)).append(',')
        append("\"samples\":").append(e.samples).append(',')
        append("\"source\":\"BatteryManager CURRENT_NOW x EXTRA_VOLTAGE (yaklaşık)\"")
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
