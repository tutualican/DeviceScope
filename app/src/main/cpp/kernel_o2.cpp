// Same ALU kernel compiled at -O2, used to detect compiler elision.
//
// If -O3 produced a wildly different time for identical work, something was
// removed or vectorised differently. The Kotlin validator compares o2 vs o3 and
// reports the ratio; a ratio far from 1.0 is a warning, not a score.
#include <cstdint>
#include <cstddef>

namespace o2k {

uint64_t mix(uint32_t *data, size_t n, int rounds) {
    uint64_t acc = 0;
    for (int r = 0; r < rounds; r++) {
        for (size_t i = 0; i < n; i++) {
            uint32_t v = data[i] + (uint32_t) i;
            v ^= v << 13;
            v ^= v >> 17;
            v ^= v << 5;
            v = v * 2654435761u + (uint32_t) r;
            v ^= v >> 11;
            data[i] = v;
            acc += v;
        }
    }
    return acc;
}

} // namespace o2k
