package com.alican.devicescope.bench

/**
 * JNI bridge to the validation probes (app/src/main/cpp/validate.cpp).
 *
 * These calls never produce a score: they return raw counters so the Kotlin side
 * owns every statistic and nothing is rounded inside native code.
 */
object Validate {

    fun available(): Boolean = Native.available()

    /** Duration of a task that does essentially nothing: the noise floor. */
    fun empty(iters: Int = 1_000_000): Long {
        if (!Native.available()) return 0L
        return try { nEmpty(iters) } catch (t: Throwable) { 0L }
    }

    /** Smallest non-zero delta the monotonic clock can report, in ns. */
    fun timerResolutionNs(): Long {
        if (!Native.available()) return 0L
        return try { nTimerRes() } catch (t: Throwable) { 0L }
    }

    class Probe(val bytes: Long, val ns: Long, val checksum: Long) {
        val seconds: Double get() = ns / 1e9
        val mbPerSec: Double get() = if (ns > 0) bytes / (ns / 1e9) / 1e6 else 0.0
        val gbPerSec: Double get() = if (ns > 0) bytes / (ns / 1e9) / 1e9 else 0.0
    }

    /** id 0 = 1 MiB*scale AES-like mix, 1 = bulk integer mix, 2 = memory scan. */
    fun scale(id: Int, scale: Int, iters: Int): Probe {
        if (!Native.available()) return Probe(0, 0, 0)
        val r = try { nScale(id, scale, iters) } catch (t: Throwable) { null } ?: return Probe(0, 0, 0)
        return Probe(r[0], r[1], r[2])
    }

    fun memory(workingSetBytes: Long, passes: Int): Probe {
        if (!Native.available()) return Probe(0, 0, 0)
        val r = try { nMem(workingSetBytes, passes) } catch (t: Throwable) { null } ?: return Probe(0, 0, 0)
        return Probe(r[0], r[1], r[2])
    }

    class Pinned(val probe: Probe, val pinned: Boolean)

    fun pin(core: Int, id: Int, iters: Int): Pinned {
        if (!Native.available()) return Pinned(Probe(0, 0, 0), false)
        val r = try { nPin(core, id, iters) } catch (t: Throwable) { null }
            ?: return Pinned(Probe(0, 0, 0), false)
        return Pinned(Probe(r[0], r[1], r[2]), r[3] == 1L)
    }

    fun currentCpu(): Int {
        if (!Native.available()) return -1
        return try { nCurrentCpu() } catch (t: Throwable) { -1 }
    }

    fun storage(path: String, sizeMb: Int, blockKb: Int, random: Boolean, direct: Boolean): Probe {
        if (!Native.available()) return Probe(0, 0, 0)
        val r = try { nStorage(path, sizeMb, blockKb, random, direct) } catch (t: Throwable) { null }
            ?: return Probe(0, 0, 0)
        return Probe(r[0], r[1], r[2])
    }

    /** Same kernel compiled at -O2 (o2 = true) or -O3 (o2 = false). */
    fun kernel(o2: Boolean, n: Int = 1 shl 16, rounds: Int = 8): LongArray {
        if (!Native.available()) return longArrayOf(0, 0, 0)
        return try {
            nKernel(o2, n, rounds) ?: longArrayOf(0, 0, 0)
        } catch (t: Throwable) {
            longArrayOf(0, 0, 0)
        }
    }

    data class CoreInfo(val index: Int, val maxKhz: Long, val curKhz: Long, val governor: String)

    fun cpuInfo(): List<CoreInfo> {
        if (!Native.available()) return emptyList()
        val rows = try { nCpuInfo() } catch (t: Throwable) { null } ?: return emptyList()
        return rows.mapNotNull { row ->
            val p = row.split('|')
            if (p.size < 4) null else CoreInfo(
                index = p[0].toIntOrNull() ?: return@mapNotNull null,
                maxKhz = p[1].toLongOrNull() ?: 0L,
                curKhz = p[2].toLongOrNull() ?: 0L,
                governor = p[3]
            )
        }
    }

    private external fun nEmpty(iters: Int): Long
    private external fun nTimerRes(): Long
    private external fun nScale(id: Int, scale: Int, iters: Int): LongArray?
    private external fun nMem(workingSet: Long, passes: Int): LongArray?
    private external fun nPin(core: Int, id: Int, iters: Int): LongArray?
    private external fun nStorage(path: String, sizeMb: Int, blockKb: Int, random: Boolean, direct: Boolean): LongArray?
    private external fun nKernel(o2: Boolean, n: Int, rounds: Int): LongArray?
    private external fun nCpuInfo(): Array<String>?
    private external fun nCurrentCpu(): Int
}
