package com.alican.devicescope.bench

/**
 * Cross-device rank correlation from a user-supplied CSV.
 *
 * The spec asks for a Spearman correlation between reference scores (Geekbench,
 * AnTuTu) and this app's scores. Points are not required to be equal, only the
 * ordering. That is only definable when the same CSV contains BOTH columns for
 * the same devices, so this parser pairs columns by their semantic tag
 * (single / multi / total / cpu / gpu / mem / ux ...) and refuses to compute
 * anything for fewer than five devices.
 *
 * Nothing is inferred: if a tag has only one column, the pair is reported as
 * "eksik" and no correlation is printed.
 */
object CrossDevice {

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val delimiter: Char
    )

    data class PairResult(
        val tag: String,
        val referenceColumn: String,
        val oursColumn: String,
        val n: Int,
        val spearman: Double?,
        val target: Double,
        val note: String,
        val deviations: List<Deviation>
    ) {
        val pass: Boolean? get() = spearman?.let { it >= target && n >= 5 }
    }

    data class Deviation(
        val device: String,
        val reference: Double,
        val ours: Double,
        val referenceRank: Double,
        val oursRank: Double,
        val rankDelta: Double
    )

    private val tags = mapOf(
        "single" to listOf("single", "tek", "sc"),
        "multi" to listOf("multi", "cok", "mc"),
        "total" to listOf("total", "toplam"),
        "cpu" to listOf("cpu"),
        "gpu" to listOf("gpu"),
        "mem" to listOf("mem", "memory", "bellek"),
        "ux" to listOf("ux", "ui"),
        "storage" to listOf("storage", "disk", "depolama")
    )

    private val deviceNames = listOf("device", "cihaz", "model", "name", "isim")

    /** Detects the delimiter and splits a CSV body, handling quoted fields. */
    fun parse(text: String): Table {
        val clean = text.removePrefix("\uFEFF")
        val lines = clean.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return Table(emptyList(), emptyList(), ',')

        val header = lines.first()
        val delimiter = when {
            header.count { it == ';' } > header.count { it == ',' } -> ';'
            header.count { it == '\t' } > header.count { it == ',' } -> '\t'
            else -> ','
        }
        val headers = splitLine(header, delimiter).map { it.trim() }
        val rows = lines.drop(1).map { splitLine(it, delimiter) }
            .filter { it.any { cell -> cell.isNotBlank() } }
        return Table(headers, rows, delimiter)
    }

    private fun splitLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> {
                    out.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        out.add(current.toString())
        return out
    }

    private fun parseNumber(raw: String, delimiter: Char): Double? {
        var s = raw.trim().replace("\u00A0", "").replace(" ", "")
        if (s.isEmpty()) return null
        if (delimiter == ';') {
            // Turkish Excel export: "." groups thousands, "," is the decimal mark.
            if (s.contains(',')) {
                s = s.replace(".", "").replace(',', '.')
            } else if (s.count { it == '.' } > 1) {
                s = s.replace(".", "")
            }
        } else {
            // Comma-separated file: a comma inside a number can only be a
            // thousands separator (a decimal comma would make the field ambiguous
            // and must be quoted with a different delimiter).
            s = s.replace(",", "")
        }
        return s.toDoubleOrNull()
    }

    private fun normalize(header: String): String =
        header.lowercase()
            .replace("ı", "i").replace("ş", "s").replace("ğ", "g")
            .replace("ü", "u").replace("ö", "o").replace("ç", "c")
            .replace("-", "_").replace(" ", "_")

    private fun tagOf(header: String): String? {
        val h = normalize(header)
        // exact-ish matches first, so "gpu_temp" does not win over "gpu_score"
        for ((tag, aliases) in tags) {
            if (aliases.any { alias -> h == alias || h.endsWith("_" + alias) || h.startsWith(alias + "_") }) {
                return tag
            }
        }
        return null
    }

    private fun isReferenceColumn(header: String): Boolean {
        val h = normalize(header)
        return listOf("geekbench", "gb", "antutu", "ref", "referans", "anTuTu".lowercase())
            .any { h.startsWith(it) || h.contains("_" + it) }
    }

    /**
     * Pairs a reference column with one of this app's columns for every tag that
     * has both, then computes Spearman on the devices where both values exist.
     */
    fun correlate(table: Table, targets: Map<String, Double> = defaultTargets): List<PairResult> {
        if (table.headers.isEmpty()) return emptyList()

        val deviceIndex = table.headers.indexOfFirst { h ->
            val n = normalize(h)
            deviceNames.any { n == it || n.contains(it) }
        }

        val byTag = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        table.headers.forEachIndexed { index, header ->
            val tag = tagOf(header) ?: return@forEachIndexed
            if (index == deviceIndex) return@forEachIndexed
            byTag.getOrPut(tag) { ArrayList() }.add(index to header)
        }

        val results = ArrayList<PairResult>()
        for ((tag, columns) in byTag) {
            val reference = columns.firstOrNull { isReferenceColumn(it.second) }
            val ours = columns.firstOrNull { !isReferenceColumn(it.second) }
            if (reference == null || ours == null) {
                results.add(
                    PairResult(
                        tag = tag,
                        referenceColumn = reference?.second ?: "—",
                        oursColumn = ours?.second ?: "—",
                        n = 0,
                        spearman = null,
                        target = targets[tag] ?: 0.9,
                        note = "Eksik sütun: aynı CSV'de hem referans hem DeviceScope puanı olmalı " +
                            "(ör. `geekbench_$tag` ve `mikyas_$tag`).",
                        deviations = emptyList()
                    )
                )
                continue
            }

            val devices = ArrayList<String>()
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for (row in table.rows) {
                val device = if (deviceIndex >= 0) row.getOrNull(deviceIndex)?.trim().orEmpty()
                else "cihaz ${devices.size + 1}"
                val x = row.getOrNull(reference.first)?.let { parseNumber(it, table.delimiter) }
                val y = row.getOrNull(ours.first)?.let { parseNumber(it, table.delimiter) }
                if (x == null || y == null) continue
                devices.add(device.ifBlank { "cihaz ${devices.size + 1}" })
                xs.add(x)
                ys.add(y)
            }

            val target = targets[tag] ?: 0.9
            if (xs.size < 5) {
                results.add(
                    PairResult(tag, reference.second, ours.second, xs.size, null, target,
                        "Yetersiz veri: n=${xs.size}, Spearman için en az 5 cihaz gerekir.", emptyList())
                )
                continue
            }

            val rho = StatsExt.spearman(xs, ys)
            val rx = StatsExt.ranks(xs)
            val ry = StatsExt.ranks(ys)
            val deviations = devices.indices.map { i ->
                Deviation(devices[i], xs[i], ys[i], rx[i], ry[i], ry[i] - rx[i])
            }.sortedByDescending { kotlin.math.abs(it.rankDelta) }.take(5)

            results.add(
                PairResult(
                    tag = tag,
                    referenceColumn = reference.second,
                    oursColumn = ours.second,
                    n = xs.size,
                    spearman = rho,
                    target = target,
                    note = when {
                        rho.isNaN() -> "Hesaplanamadı: sütunlardan biri sabit."
                        rho >= target -> "Hedefin üzerinde."
                        else -> "Hedefin altında: sıralama farkı büyük cihazlar aşağıda."
                    },
                    deviations = deviations
                )
            )
        }
        return results
    }

    /** Targets from the spec: total > 0.9, categories > 0.8. */
    val defaultTargets: Map<String, Double> = mapOf(
        "single" to 0.9,
        "multi" to 0.9,
        "total" to 0.9,
        "cpu" to 0.8,
        "gpu" to 0.8,
        "mem" to 0.8,
        "ux" to 0.8,
        "storage" to 0.8
    )

    /** Human-readable summary for sharing/storing next to the validation report. */
    fun render(results: List<PairResult>): String = buildString {
        appendLine("tag,reference_column,ours_column,n,spearman,target,pass")
        for (r in results) {
            appendLine(
                listOf(
                    r.tag, r.referenceColumn, r.oursColumn, r.n.toString(),
                    r.spearman?.let { String.format(java.util.Locale.US, "%.4f", it) } ?: "",
                    String.format(java.util.Locale.US, "%.2f", r.target),
                    r.pass?.toString() ?: ""
                ).joinToString(",")
            )
        }
    }
}
