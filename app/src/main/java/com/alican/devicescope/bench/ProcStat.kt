package com.alican.devicescope.bench

import com.alican.devicescope.Sys

/**
 * CPU accounting from /proc, used to notice rounds that were polluted.
 *
 * /proc/stat is aggregate: it cannot name the process that stole the CPU, but it
 * can prove that *something* else was busy. During a round we know how many
 * jiffies our own process consumed and how many were busy on the whole device;
 * the difference is "other busy cores". When that exceeds a threshold, or the
 * screen went off mid-round, the round is marked contaminated and excluded from
 * the stability calculation instead of silently lowering the score.
 *
 * On Android 8+ /proc/stat and /proc/self/stat are readable without root; if
 * SELinux hides them the reader returns null and no round is marked (the result
 * says the check was unavailable rather than inventing contamination).
 */
object ProcStat {

    data class Load(
        val totalJiffies: Long,
        val idleJiffies: Long,
        val ownJiffies: Long
    )

    fun read(): Load? {
        val stat = Sys.read("/proc/stat") ?: return null
        val line = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val parts = line.trim().split(Regex("\\s+"))
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        // user nice system idle iowait irq softirq steal
        val total = values.take(8).sum()
        val idle = values[3] + (values.getOrNull(4) ?: 0L)

        val self = Sys.read("/proc/self/stat") ?: return null
        val close = self.lastIndexOf(')')
        if (close < 0) return null
        // After "comm)", fields start at state (field 3). utime is field 14,
        // stime 15 => indexes 11 and 12 in this suffix.
        val fields = self.substring(close + 1).trim().split(Regex("\\s+"))
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return Load(total, idle, utime + stime)
    }
}
