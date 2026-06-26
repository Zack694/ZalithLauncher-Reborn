#include "recordzy_tap.h"

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>

// used + retain: keep these from being dropped by the compiler or the linker's
// --gc-sections (they have no internal callers, only external resolvers), and
// visibility default + the libopenal.version edit get them into .dynsym.
#define RZ_EXPORT __attribute__((visibility("default"), used, retain))

namespace {

// ~2 seconds of headroom at 48 kHz stereo (int16 samples).
constexpr size_t kCap = 48000u * 2u * 2u;

int16_t *g_ring = nullptr;
std::atomic<size_t> g_wr{0};
std::atomic<size_t> g_rd{0};
std::atomic<int> g_rate{0};
std::atomic<int> g_chans{0};
std::atomic<int> g_active{0};

// Device locking: capture only the first OpenAL device that renders after
// start, so additional contexts (e.g. a voice-chat mod's own OpenAL output)
// can't double up the captured stream.
const void *g_lockedDev = nullptr;
const void *g_seenDevs[8] = {nullptr};
std::atomic<int> g_devCount{0};

inline int16_t floatToS16(float f) {
    if (f >= 1.0f) return 32767;
    if (f <= -1.0f) return -32768;
    return static_cast<int16_t>(f * 32767.0f);
}

} // namespace

extern "C" {

RZ_EXPORT void recordzy_tap_set_active(int active) {
    if (active) {
        g_wr.store(0, std::memory_order_relaxed);
        g_rd.store(0, std::memory_order_relaxed);
        g_lockedDev = nullptr;
        g_devCount.store(0, std::memory_order_relaxed);
        for (int i = 0; i < 8; i++) g_seenDevs[i] = nullptr;
    }
    g_active.store(active, std::memory_order_release);
}

RZ_EXPORT void recordzy_tap_feed(const void *dev, const void *pcm, unsigned numSamples,
                                 unsigned frameStep, unsigned freq, unsigned bytesPerSample,
                                 int isFloat) {
    if (!g_active.load(std::memory_order_relaxed) || pcm == nullptr || numSamples == 0) {
        return;
    }

    // Track distinct devices (for diagnostics) and lock onto the first one.
    int count = g_devCount.load(std::memory_order_relaxed);
    bool known = false;
    for (int i = 0; i < count && i < 8; i++) {
        if (g_seenDevs[i] == dev) { known = true; break; }
    }
    if (!known && count < 8) {
        g_seenDevs[count] = dev;
        g_devCount.store(count + 1, std::memory_order_relaxed);
    }
    if (g_lockedDev == nullptr) {
        g_lockedDev = dev;
    }
    if (dev != g_lockedDev) {
        return; // a different OpenAL output -> ignore so we don't double up
    }

    unsigned ch = frameStep ? frameStep : 2u;
    g_rate.store(static_cast<int>(freq), std::memory_order_relaxed);
    g_chans.store(static_cast<int>(ch), std::memory_order_relaxed);

    if (g_ring == nullptr) {
        g_ring = static_cast<int16_t *>(std::malloc(kCap * sizeof(int16_t)));
        if (g_ring == nullptr) return;
    }

    const size_t total = static_cast<size_t>(numSamples) * ch;
    if (total == 0 || total > kCap) return;

    size_t wr = g_wr.load(std::memory_order_relaxed);
    size_t rd = g_rd.load(std::memory_order_acquire);
    if (total > kCap - (wr - rd)) {
        return; // consumer fell behind; drop this chunk
    }

    const size_t head = wr % kCap;
    const size_t first = kCap - head;
    if (isFloat && bytesPerSample == 4) {
        const auto *src = static_cast<const float *>(pcm);
        for (size_t i = 0; i < total; i++) {
            g_ring[(wr + i) % kCap] = floatToS16(src[i]);
        }
    } else if (bytesPerSample == 2) {
        const auto *src = static_cast<const int16_t *>(pcm);
        if (first >= total) {
            std::memcpy(g_ring + head, src, total * sizeof(int16_t));
        } else {
            std::memcpy(g_ring + head, src, first * sizeof(int16_t));
            std::memcpy(g_ring, src + first, (total - first) * sizeof(int16_t));
        }
    } else {
        return; // 8/32-bit int formats not handled (Android OpenSL uses 16-bit)
    }
    g_wr.store(wr + total, std::memory_order_release);
}

RZ_EXPORT int recordzy_tap_read(short *out, int maxSamples) {
    if (g_ring == nullptr || out == nullptr || maxSamples <= 0) return 0;
    size_t rd = g_rd.load(std::memory_order_relaxed);
    size_t wr = g_wr.load(std::memory_order_acquire);
    size_t avail = wr - rd;
    if (avail == 0) return 0;
    size_t n = avail < static_cast<size_t>(maxSamples) ? avail : static_cast<size_t>(maxSamples);
    const size_t head = rd % kCap;
    const size_t first = kCap - head;
    if (first >= n) {
        std::memcpy(out, g_ring + head, n * sizeof(int16_t));
    } else {
        std::memcpy(out, g_ring + head, first * sizeof(int16_t));
        std::memcpy(out + first, g_ring, (n - first) * sizeof(int16_t));
    }
    g_rd.store(rd + n, std::memory_order_release);
    return static_cast<int>(n);
}

RZ_EXPORT int recordzy_tap_samplerate(void) {
    return g_rate.load(std::memory_order_acquire);
}

RZ_EXPORT int recordzy_tap_channels(void) {
    return g_chans.load(std::memory_order_acquire);
}

RZ_EXPORT int recordzy_tap_device_count(void) {
    return g_devCount.load(std::memory_order_acquire);
}

} // extern "C"
