package com.alican.devicescope.bench

import android.content.Context
import com.alican.devicescope.ThermalCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Measurement engine shared by Mikyas and Kinetik.
 *
 * Guarantees:
 *  - known-answer verification before measuring, so a broken build cannot score
 *  - warm-up rounds that are discarded
 *  - adaptive iteration count so each sample lasts ~260 ms regardless of device
 *  - repeated samples with the median reported (one outlier cannot swing a score)
 *  - checksums compared across repeats, proving the work really executed
 *  - coefficient of variation reported so unstable runs are visible
 */
class Engine(context: Context) {

    private val thermal = ThermalCollector(context)

    /** Runs the known-answer suite. Returns failing workload ids (empty = all good). */
    suspend fun verify(): List<Int> = withContext(Dispatchers.Default) {
        Workload.values().filterNot { Native.verify(it.id) }.map { it.id }
    }

    private fun runOnce(w: Workload, iterations: Int): LongArray = Native.run(w.id, iterations)

    /** Finds an iteration count whose single sample lasts roughly [targetMs]. */
    private suspend fun calibrate(w: Workload, targetMs: Long): Int = withContext(Dispatchers.Default) {
        var iters = 1
        var elapsed = 0L
        var guard = 0
        while (elapsed < targetMs * 1_000_000L && iters < 8192 && guard < 24) {
            guard++
            val r = runOnce(w, iters)
            elapsed = r[2]
            if (elapsed <= 0L) return@withContext 1
            if (elapsed >= targetMs * 1_000_000L) break
            val factor = if (elapsed < 2_000_000L) 8.0
            else (targetMs * 1_000_000.0 / elapsed).coerceIn(1.2, 6.0)
            iters = (iters * factor).toInt().coerceAtLeast(iters + 1)
        }
        iters.coerceIn(1, 8192)
    }

    suspend fun measure(
        w: Workload,
        threads: Int = 1,
        warmup: Int = 1,
        repeats: Int = 3,
        targetMs: Long = 260
    ): Measurement = withContext(Dispatchers.Default) {
        val totalIters = calibrate(w, targetMs)
        val perThread = (totalIters / threads).coerceAtLeast(1)

        repeat(warmup) {
            if (threads == 1) runOnce(w, perThread) else runParallel(w, perThread, threads)
        }

        val times = ArrayList<Double>(repeats)
        val checksums = ArrayList<Long>(repeats)
        var unitsTotal = 0L

        repeat(repeats) {
            if (threads == 1) {
                val r = runOnce(w, perThread)
                unitsTotal = r[0]
                checksums.add(r[1])
                times.add(r[2] / 1e9)
            } else {
                val batch = runParallel(w, perThread, threads)
                unitsTotal = batch.units
                checksums.add(batch.checksum)
                times.add(batch.seconds)
            }
        }

        val medianSeconds = Stats.median(times)
        val throughput = if (medianSeconds > 0) unitsTotal / medianSeconds else 0.0
        val index = if (throughput > 0) throughput / w.refUnitsPerSec * 1000.0 else 0.0

        Measurement(
            workload = w,
            threads = threads,
            iterations = perThread,
            medianSeconds = medianSeconds,
            throughput = throughput,
            unit = w.unit,
            index = index,
            cvPercent = Stats.cvPercent(times),
            checksumStable = checksums.distinct().size == 1,
            repeats = repeats,
            warmupRepeats = warmup
        )
    }

    private class Batch(val units: Long, val checksum: Long, val seconds: Double)

    private fun runParallel(w: Workload, perThread: Int, threads: Int): Batch {
        val pool = Executors.newFixedThreadPool(threads)
        val checksum = java.util.concurrent.atomic.AtomicLong(0L)
        val units = java.util.concurrent.atomic.AtomicLong(0L)
        val start = System.nanoTime()
        try {
            val futures = (0 until threads).map {
                pool.submit {
                    val r = runOnce(w, perThread)
                    units.addAndGet(r[0])
                    checksum.addAndGet(r[1])
                }
            }
            futures.forEach { it.get(300, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        val seconds = (System.nanoTime() - start) / 1e9
        return Batch(units.get(), checksum.get(), seconds)
    }

    // ----------------------------------------------------------------- storage

    data class StorageResult(
        val writeMbPerSec: Double,
        val readMbPerSec: Double,
        val cvWrite: Double,
        val cvRead: Double,
        val sizeMb: Long,
        val index: Double
    )

    /**
     * Real storage throughput: sequential write followed by fsync, then
     * sequential read, on the app's private cache directory.
     * Reference constants: 900 MB/s write, 1800 MB/s read (UFS 3.x class).
     */
    suspend fun measureStorage(
        context: Context,
        sizeMb: Long = 128,
        repeats: Int = 3
    ): StorageResult = withContext(Dispatchers.IO) {
        val dir: File = context.cacheDir
        val file = File(dir, "dsbench-storage.tmp")
        val size = sizeMb * 1024 * 1024
        val buffer = ByteArray(1 shl 20)
        java.util.Random(42).nextBytes(buffer)

        val writes = ArrayList<Double>()
        val reads = ArrayList<Double>()

        repeat(repeats) {
            file.delete()
            var written = 0L
            var t0 = System.nanoTime()
            FileOutputStream(file).use { out ->
                while (written < size) {
                    out.write(buffer)
                    written += buffer.size
                }
                out.flush()
                out.fd.sync()
            }
            var dt = (System.nanoTime() - t0) / 1e9
            if (dt > 0) writes.add(size / dt / 1e6)

            t0 = System.nanoTime()
            var read = 0L
            FileInputStream(file).use { input ->
                val chunk = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    read += n
                }
            }
            dt = (System.nanoTime() - t0) / 1e9
            if (dt > 0) reads.add(read / dt / 1e6)
        }

        file.delete()

        val write = Stats.median(writes)
        val read = Stats.median(reads)
        val index = Stats.geoMean(listOf(write / 900.0 * 1000.0, read / 1800.0 * 1000.0))

        StorageResult(
            writeMbPerSec = write,
            readMbPerSec = read,
            cvWrite = Stats.cvPercent(writes),
            cvRead = Stats.cvPercent(reads),
            sizeMb = sizeMb,
            index = index
        )
    }

    fun thermalCollector(): ThermalCollector = thermal
}
