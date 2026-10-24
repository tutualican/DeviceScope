// Validation probes for the DeviceScope benchmark suites.
//
// These do not produce scores. They exist so the app can prove its own
// measurements are trustworthy:
//   * noise floor of an empty task and the real clock resolution
//   * problem-size scaling (cache effects) and thread scaling
//   * core affinity (little vs big) on the very thread that measures
//   * memory bandwidth across the cache hierarchy
//   * storage throughput with the page cache bypassed (O_DIRECT) or not
//   * the same kernel compiled at -O2 and -O3 (compiler elision check)
//
// Every function returns raw counters ([bytes/ops, nanoseconds, checksum]) so the
// Kotlin side does all statistics; nothing is rounded here.

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <vector>
#include <string>
#include <algorithm>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sched.h>
#include <time.h>

namespace o2k { uint64_t mix(uint32_t *data, size_t n, int rounds); }
namespace o3k { uint64_t mix(uint32_t *data, size_t n, int rounds); }

namespace {

uint64_t now_ns() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000000000ULL + (uint64_t) ts.tv_nsec;
}

uint64_t g_sink = 0;

// ------------------------------------------------------------- noise floor
// A task that provably does almost nothing. Its duration is the measurement
// noise floor: any real workload must be far above it.
uint64_t emptyTask(int iters) {
    uint32_t v = 1;
    for (int i = 0; i < iters; i++) {
        v = v * 1664525u + 1013904223u;
        __asm__ __volatile__("" : "+r"(v));   // keep the loop alive, no memory access
    }
    g_sink ^= v;
    return (uint64_t) iters;
}

// ------------------------------------------------------- timer resolution
// Smallest non-zero delta the clock can report on this device.
uint64_t timerResolution() {
    uint64_t best = UINT64_MAX;
    uint64_t prev = now_ns();
    for (int i = 0; i < 200000; i++) {
        uint64_t cur = now_ns();
        if (cur != prev) {
            uint64_t d = cur - prev;
            if (d > 0 && d < best) best = d;
            prev = cur;
            if (best == 1) break;
        }
    }
    return best == UINT64_MAX ? 0 : best;
}

// ------------------------------------------------------- size scaling probe
// id 0 = AES-like integer mix over 1 MiB * scale
// id 1 = bulk integer mix over 4 Mi elements * scale
// id 2 = sequential memory scan over 8 MiB * scale
uint64_t scaleProbe(int id, int scale, int iters, uint64_t &bytesOut) {
    if (scale < 1) scale = 1;
    uint64_t acc = 0;

    if (id == 0) {
        size_t size = (1u << 20) * (size_t) scale;
        std::vector<uint8_t> buf(size, 0x5A);
        for (int it = 0; it < iters; it++) {
            uint32_t v = 0x12345678u;
            for (size_t i = 0; i < size; i += 4) {
                uint32_t x;
                memcpy(&x, buf.data() + i, 4);
                x ^= x << 13; x ^= x >> 17; x ^= x << 5;
                x += v;
                memcpy(buf.data() + i, &x, 4);
                v = x;
            }
            acc += v;
        }
        bytesOut = (uint64_t) size * (uint64_t) iters;
        return acc;
    }

    if (id == 1) {
        size_t n = (size_t) (1u << 22) * (size_t) scale;   // uint32 elements
        std::vector<uint32_t> v(n);
        for (size_t i = 0; i < n; i++) v[i] = (uint32_t) (i * 2654435761u);
        for (int it = 0; it < iters; it++) {
            uint32_t carry = (uint32_t) it;
            for (size_t i = 0; i < n; i++) {
                uint32_t x = v[i];
                x ^= x << 13; x ^= x >> 17; x ^= x << 5;
                x = x * 2654435761u + carry;
                x ^= x >> 11;
                v[i] = x;
                carry = x;
            }
            acc += carry;
        }
        bytesOut = (uint64_t) n * 4ULL * (uint64_t) iters;
        return acc;
    }

    // id 2: read-only scan, isolates memory hierarchy from ALU
    size_t size = (8u << 20) * (size_t) scale;
    std::vector<uint8_t> buf(size, 0x33);
    for (int it = 0; it < iters; it++) {
        uint64_t s = 0;
        const uint64_t *p = (const uint64_t *) buf.data();
        size_t words = size / 8;
        for (size_t i = 0; i < words; i++) s += p[i];
        acc += s;
    }
    bytesOut = (uint64_t) size * (uint64_t) iters;
    return acc;
}

// ------------------------------------------------------ memory hierarchy
// Sequential transform + reduction over an arbitrary working set. Running this at
// 32 KiB, 256 KiB, 4 MiB and 128 MiB exposes the L1/L2/L3/RAM steps.
// One buffer only: the transform is in place, so a 128 MiB probe allocates
// 128 MiB instead of 256 MiB and still reads it twice and writes it once.
uint64_t memProbe(size_t workingSet, int passes, uint64_t &bytesOut) {
    if (workingSet < 4096) workingSet = 4096;
    size_t words = workingSet / 8;
    std::vector<uint64_t> a(words);
    for (size_t i = 0; i < words; i++) a[i] = i * 0x9E3779B97F4A7C15ULL;

    uint64_t acc = 0;
    for (int p = 0; p < passes; p++) {
        for (size_t i = 0; i < words; i++) a[i] ^= (uint64_t) p + 1ULL;
        uint64_t s = 0;
        for (size_t i = 0; i < words; i++) s += a[i];
        acc += s;
    }
    // one write + one read per pass
    bytesOut = (uint64_t) workingSet * 2ULL * (uint64_t) passes;
    return acc;
}

// --------------------------------------------------------------- affinity
bool pinToCore(int core) {
    if (core < 0) return true;              // no pinning requested
    cpu_set_t set;
    memset(&set, 0, sizeof(set));
    unsigned long *bits = (unsigned long *) &set;
    unsigned long stride = 8 * sizeof(unsigned long);
    bits[(size_t) core / stride] |= 1UL << ((size_t) core % stride);
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

// ---------------------------------------------------------------- storage
// sizeMb of I/O in blockKb blocks, sequential or random, optionally O_DIRECT so
// the page cache is bypassed. Returns -1 for bytes when the mode is unsupported.
uint64_t storageProbe(const char *path, int sizeMb, int blockKb, bool randomIO,
                      bool direct, uint64_t &bytesOut) {
    bytesOut = 0;
    size_t block = (size_t) blockKb * 1024;
    size_t total = (size_t) sizeMb * 1024 * 1024;
    if (block < 4096) block = 4096;
    if (total < block) total = block;

    int flags = O_RDWR | O_CREAT | O_TRUNC;
    if (direct) flags |= O_DIRECT;

    void *buf = nullptr;
    if (posix_memalign(&buf, 4096, block) != 0 || buf == nullptr) return 0;
    memset(buf, 0xA5, block);

    int fd = open(path, flags, 0600);
    if (fd < 0) {
        free(buf);
        return 0;                       // caller reports "mode unsupported"
    }

    uint64_t acc = 0;
    size_t blocks = total / block;
    if (blocks == 0) blocks = 1;

    std::vector<size_t> order(blocks);
    for (size_t i = 0; i < blocks; i++) order[i] = i;
    if (randomIO) {
        uint64_t seed = 88172645463325252ULL;
        for (size_t i = blocks; i > 1; i--) {
            seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17;
            size_t j = (size_t) (seed % i);
            std::swap(order[i - 1], order[j]);
        }
    }

    // write
    for (size_t i = 0; i < blocks; i++) {
        off_t off = (off_t) (order[i] * block);
        ssize_t w = pwrite(fd, buf, block, off);
        if (w != (ssize_t) block) { close(fd); free(buf); return 0; }
        acc += (uint64_t) w;
    }
    fsync(fd);

    // read back, dropping the page cache first when we are allowed to
#ifndef POSIX_FADV_DONTNEED
#define POSIX_FADV_DONTNEED 4
#endif
    if (!direct) posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);

    for (size_t i = 0; i < blocks; i++) {
        off_t off = (off_t) (order[i] * block);
        ssize_t r = pread(fd, buf, block, off);
        if (r != (ssize_t) block) { close(fd); free(buf); return 0; }
        acc += ((const uint8_t *) buf)[0];
    }

    close(fd);
    unlink(path);
    free(buf);
    bytesOut = (uint64_t) total * 2ULL;      // written + read
    return acc;
}

// ---------------------------------------------------------- compiler check
uint64_t kernelRun(bool o2, size_t n, int rounds, uint64_t &opsOut) {
    std::vector<uint32_t> data(n);
    for (size_t i = 0; i < n; i++) data[i] = (uint32_t) (i * 2246822519u);
    uint64_t acc = o2 ? o2k::mix(data.data(), n, rounds)
                      : o3k::mix(data.data(), n, rounds);
    opsOut = (uint64_t) n * (uint64_t) rounds;
    // touch the result so neither build can drop the loop
    return acc ^ data[(size_t) (acc % n)];
}

// ------------------------------------------------------------- /proc info
std::string readFile(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return {};
    char buf[128] = {0};
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    std::string s(buf, n);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ')) s.pop_back();
    return s;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_alican_devicescope_bench_Validate_nEmpty(JNIEnv *, jclass, jint iters) {
    if (iters <= 0) iters = 1;
    uint64_t t0 = now_ns();
    emptyTask(iters);
    uint64_t t1 = now_ns();
    return (jlong) (t1 - t0);
}

JNIEXPORT jlong JNICALL
Java_com_alican_devicescope_bench_Validate_nTimerRes(JNIEnv *, jclass) {
    return (jlong) timerResolution();
}

JNIEXPORT jlongArray JNICALL
Java_com_alican_devicescope_bench_Validate_nScale(JNIEnv *env, jclass, jint id, jint scale, jint iters) {
    if (iters <= 0) iters = 1;
    uint64_t bytes = 0;
    uint64_t t0 = now_ns();
    uint64_t sum = scaleProbe(id, scale, iters, bytes);
    uint64_t t1 = now_ns();
    jlong v[3] = {(jlong) bytes, (jlong) (t1 - t0), (jlong) sum};
    jlongArray out = env->NewLongArray(3);
    if (out) env->SetLongArrayRegion(out, 0, 3, v);
    return out;
}

JNIEXPORT jlongArray JNICALL
Java_com_alican_devicescope_bench_Validate_nMem(JNIEnv *env, jclass, jlong workingSet, jint passes) {
    if (passes <= 0) passes = 1;
    uint64_t bytes = 0;
    uint64_t t0 = now_ns();
    uint64_t sum = memProbe((size_t) workingSet, passes, bytes);
    uint64_t t1 = now_ns();
    jlong v[3] = {(jlong) bytes, (jlong) (t1 - t0), (jlong) sum};
    jlongArray out = env->NewLongArray(3);
    if (out) env->SetLongArrayRegion(out, 0, 3, v);
    return out;
}

JNIEXPORT jlongArray JNICALL
Java_com_alican_devicescope_bench_Validate_nPin(JNIEnv *env, jclass, jint core, jint id, jint iters) {
    if (iters <= 0) iters = 1;
    bool pinned = pinToCore(core);
    uint64_t bytes = 0;
    uint64_t t0 = now_ns();
    uint64_t sum = scaleProbe(id, 1, iters, bytes);
    uint64_t t1 = now_ns();
    // always restore the full mask so later measurements are not stuck on one core
    if (core >= 0) {
        cpu_set_t all;
        memset(&all, 0, sizeof(all));
        long n = sysconf(_SC_NPROCESSORS_ONLN);
        if (n < 1) n = 8;
        unsigned long *bits = (unsigned long *) &all;
        unsigned long stride = 8 * sizeof(unsigned long);
        for (int i = 0; i < n; i++) bits[(size_t) i / stride] |= 1UL << ((size_t) i % stride);
        sched_setaffinity(0, sizeof(all), &all);
    }
    jlong v[4] = {(jlong) bytes, (jlong) (t1 - t0), (jlong) sum, pinned ? 1 : 0};
    jlongArray out = env->NewLongArray(4);
    if (out) env->SetLongArrayRegion(out, 0, 4, v);
    return out;
}

JNIEXPORT jlongArray JNICALL
Java_com_alican_devicescope_bench_Validate_nStorage(JNIEnv *env, jclass, jstring path,
                                                    jint sizeMb, jint blockKb,
                                                    jboolean randomIO, jboolean direct) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::string s = p ? p : "";
    if (p) env->ReleaseStringUTFChars(path, p);

    uint64_t bytes = 0;
    uint64_t t0 = now_ns();
    uint64_t sum = storageProbe(s.c_str(), sizeMb, blockKb, randomIO, direct, bytes);
    uint64_t t1 = now_ns();
    jlong v[3] = {(jlong) bytes, (jlong) (t1 - t0), (jlong) sum};
    jlongArray out = env->NewLongArray(3);
    if (out) env->SetLongArrayRegion(out, 0, 3, v);
    return out;
}

JNIEXPORT jlongArray JNICALL
Java_com_alican_devicescope_bench_Validate_nKernel(JNIEnv *env, jclass, jboolean o2, jint n, jint rounds) {
    if (n <= 0) n = 1024;
    if (rounds <= 0) rounds = 1;
    uint64_t ops = 0;
    uint64_t t0 = now_ns();
    uint64_t sum = kernelRun(o2, (size_t) n, rounds, ops);
    uint64_t t1 = now_ns();
    jlong v[3] = {(jlong) ops, (jlong) (t1 - t0), (jlong) sum};
    jlongArray out = env->NewLongArray(3);
    if (out) env->SetLongArrayRegion(out, 0, 3, v);
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_com_alican_devicescope_bench_Validate_nCpuInfo(JNIEnv *env, jclass) {
    std::vector<std::string> rows;
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    if (cores < 1) cores = 1;
    for (int i = 0; i < cores; i++) {
        char base[160];
        snprintf(base, sizeof(base), "/sys/devices/system/cpu/cpu%d/cpufreq", i);
        std::string maxPath = std::string(base) + "/cpuinfo_max_freq";
        std::string curPath = std::string(base) + "/scaling_cur_freq";
        std::string govPath = std::string(base) + "/scaling_governor";
        std::string maxK = readFile(maxPath.c_str());
        std::string curK = readFile(curPath.c_str());
        std::string gov = readFile(govPath.c_str());
        char row[256];
        snprintf(row, sizeof(row), "%d|%s|%s|%s", i,
                 maxK.empty() ? "0" : maxK.c_str(),
                 curK.empty() ? "0" : curK.c_str(),
                 gov.empty() ? "?" : gov.c_str());
        rows.emplace_back(row);
    }

    jobjectArray out = env->NewObjectArray((jsize) rows.size(),
                                           env->FindClass("java/lang/String"), nullptr);
    if (!out) return nullptr;
    for (size_t i = 0; i < rows.size(); i++) {
        env->SetObjectArrayElement(out, (jsize) i, env->NewStringUTF(rows[i].c_str()));
    }
    return out;
}

} // extern "C"

extern "C" JNIEXPORT jint JNICALL
Java_com_alican_devicescope_bench_Validate_nCurrentCpu(JNIEnv *, jclass) {
    int cpu = sched_getcpu();
    return (jint) cpu;
}