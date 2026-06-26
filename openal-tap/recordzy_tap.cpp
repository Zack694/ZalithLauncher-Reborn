#include "recordzy_tap.h"

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>

#define RZ_EXPORT __attribute__((visibility("default"), used, retain))

namespace {

// ~2 seconds of mixing headroom at 48 kHz (in frames).
constexpr size_t kCapFrames = 48000u * 2u;
// How far behind the newest written frame the reader stays, so multiple devices
// have time to mix into the same frames before they're consumed (~25 ms).
constexpr uint64_t kLagFrames = 1200u;
constexpr int kMaxDevices = 8;

std::mutex g_mtx;

// Accumulator ring (interleaved int32 per channel) for summing all devices on a
// shared timeline; cleared as it's read.
int32_t *g_acc = nullptr;
int g_ch = 0;
uint64_t g_readPos = 0;   // frames consumed by the reader
uint64_t g_frontier = 0;  // newest frame position written by any device

const void *g_devs[kMaxDevices] = {nullptr};
uint64_t g_devPos[kMaxDevices] = {0};
int g_devN = 0;

std::atomic<int> g_rate{0};
std::atomic<int> g_chans{0};
std::atomic<int> g_active{0};

inline int16_t floatToS16(float f) {
    if (f >= 1.0f) return 32767;
    if (f <= -1.0f) return -32768;
    return static_cast<int16_t>(f * 32767.0f);
}

} // namespace

extern "C" {

RZ_EXPORT void recordzy_tap_set_active(int active) {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (active) {
        g_readPos = 0;
        g_frontier = 0;
        g_devN = 0;
        for (int i = 0; i < kMaxDevices; i++) {
            g_devs[i] = nullptr;
            g_devPos[i] = 0;
        }
        if (g_acc && g_ch > 0) {
            std::memset(g_acc, 0, kCapFrames * static_cast<size_t>(g_ch) * sizeof(int32_t));
        }
    }
    g_active.store(active, std::memory_order_release);
}

// Mixes each OpenAL device's freshly rendered PCM onto a shared timeline. All
// active devices (e.g. the game plus a voice-chat context) are summed rather
// than appended, so the result is a single real-time (1x) stream containing
// everything that was played - and we don't have to guess which device is the
// "right" one.
RZ_EXPORT void recordzy_tap_feed(const void *dev, const void *pcm, unsigned numSamples,
                                 unsigned frameStep, unsigned freq, unsigned /*bytesPerSample*/,
                                 int isFloat) {
    if (!g_active.load(std::memory_order_relaxed) || pcm == nullptr || numSamples == 0) {
        return;
    }
    unsigned ch = frameStep ? frameStep : 2u;

    std::lock_guard<std::mutex> lk(g_mtx);
    g_rate.store(static_cast<int>(freq), std::memory_order_relaxed);

    if (g_acc == nullptr) {
        g_ch = static_cast<int>(ch);
        g_acc = static_cast<int32_t *>(
                std::calloc(kCapFrames * static_cast<size_t>(g_ch), sizeof(int32_t)));
        if (g_acc == nullptr) return;
    }
    if (static_cast<int>(ch) != g_ch) {
        return; // different channel layout than the primary stream; skip it
    }
    g_chans.store(g_ch, std::memory_order_relaxed);

    int slot = -1;
    for (int i = 0; i < g_devN; i++) {
        if (g_devs[i] == dev) { slot = i; break; }
    }
    if (slot < 0) {
        if (g_devN >= kMaxDevices) return;
        slot = g_devN++;
        g_devs[slot] = dev;
        g_devPos[slot] = g_frontier; // a new device joins the timeline at "now"
    }

    uint64_t base = g_devPos[slot];
    if (base < g_readPos) base = g_readPos; // don't write into already-consumed frames

    const auto *si = static_cast<const int16_t *>(pcm);
    const auto *sf = static_cast<const float *>(pcm);
    for (unsigned f = 0; f < numSamples; f++) {
        uint64_t pos = base + f;
        if (pos - g_readPos >= kCapFrames) break; // ring full; drop the rest
        size_t idx = static_cast<size_t>(pos % kCapFrames) * g_ch;
        for (int c = 0; c < g_ch; c++) {
            int v = isFloat ? floatToS16(sf[f * ch + c]) : si[f * ch + c];
            g_acc[idx + c] += v;
        }
    }
    g_devPos[slot] = base + numSamples;
    if (g_devPos[slot] > g_frontier) g_frontier = g_devPos[slot];
}

RZ_EXPORT int recordzy_tap_read(short *out, int maxSamples) {
    if (out == nullptr || maxSamples <= 0) return 0;
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_acc == nullptr || g_ch <= 0) return 0;

    uint64_t ready = (g_frontier > g_readPos + kLagFrames)
            ? (g_frontier - kLagFrames - g_readPos) : 0;
    if (ready == 0) return 0;
    size_t maxFrames = static_cast<size_t>(maxSamples) / static_cast<size_t>(g_ch);
    size_t n = (ready < maxFrames) ? static_cast<size_t>(ready) : maxFrames;

    int outIdx = 0;
    for (size_t f = 0; f < n; f++) {
        size_t idx = static_cast<size_t>((g_readPos + f) % kCapFrames) * g_ch;
        for (int c = 0; c < g_ch; c++) {
            int v = g_acc[idx + c];
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            out[outIdx++] = static_cast<short>(v);
            g_acc[idx + c] = 0; // clear for reuse
        }
    }
    g_readPos += n;
    return outIdx;
}

RZ_EXPORT int recordzy_tap_samplerate(void) {
    return g_rate.load(std::memory_order_acquire);
}

RZ_EXPORT int recordzy_tap_channels(void) {
    return g_chans.load(std::memory_order_acquire);
}

RZ_EXPORT int recordzy_tap_device_count(void) {
    std::lock_guard<std::mutex> lk(g_mtx);
    return g_devN;
}

} // extern "C"
