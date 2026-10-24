// Twin of kernel_o2.cpp: the kernel body is identical line for line, only the
// namespace differs so both symbols can live in one shared library. The two
// translation units are compiled with -O2 and -O3 respectively.
#include <cstdint>
#include <cstddef>

namespace o3k {

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

} // namespace o3k
