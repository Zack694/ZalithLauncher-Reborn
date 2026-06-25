// RecordZy audio tap.
//
// Route (b): a lightweight runtime inline-hook on OpenAL-soft's internal mixer
// output, so we capture exactly the PCM Minecraft sends to the speakers (game
// sounds + simple-voicechat playback), with no MediaProjection / permission
// prompt and without rebuilding libopenal.so.
//
// The launcher ships an UN-stripped OpenAL-soft using the OpenSL ES backend.
// Its mixer thread calls:
//
//     void ALCdevice::renderSamples(void *outBuffer, uint numSamples, size_t frameStep)
//       mangled: _ZN9ALCdevice13renderSamplesEPvjm
//
// which fills `outBuffer` with `numSamples` frames of interleaved device-format
// PCM (16-bit signed for the OpenSL backend) before it is enqueued to Android.
// We inline-hook it with ShadowHook (UNIQUE mode), let the original run, then
// copy the freshly rendered PCM into a lock-free SPSC ring buffer that the Java
// side drains and feeds to an AAC encoder. `renderSamples` is a LOCAL symbol, so
// we resolve it from .symtab via shadowhook_dlsym_symtab().
//
// If anything fails (lib not loaded, symbol not found, hook fails) we report
// failure and the recorder falls back to video-only. Nothing here can affect
// normal gameplay.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <atomic>

#include "shadowhook.h"

#define LOG_TAG "RecordZyTap"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *OPENAL_SO = "libopenal.so";
constexpr const char *RENDER_SYM = "_ZN9ALCdevice13renderSamplesEPvjm";

// alcGetIntegerv(device, param, size, values) - exported; used to read the
// device sample rate from the same ALCdevice* the mixer passes us.
constexpr int ALC_FREQUENCY = 0x1007;
using alcGetIntegerv_t = void (*)(void *, int, int, int *);

using render_t = void (*)(void *, void *, uint32_t, size_t);

void *g_stub = nullptr;
render_t g_orig = nullptr;
alcGetIntegerv_t g_alcGetIntegerv = nullptr;

bool g_shadowhookReady = false;
bool g_hookInstalled = false;
std::atomic<bool> g_capturing{false};
std::atomic<int> g_sampleRate{0};
std::atomic<int> g_channels{0};

// Lock-free single-producer (audio mixer thread) / single-consumer (Java pump
// thread) ring buffer of interleaved int16 samples. wr/rd are free-running
// counters; indices are taken modulo capacity.
int16_t *g_ring = nullptr;
size_t g_ringCap = 0; // in int16 samples
std::atomic<size_t> g_wr{0};
std::atomic<size_t> g_rd{0};

void *resolve_render_samples() {
    void *handle = shadowhook_dlopen(OPENAL_SO);
    if (handle == nullptr) {
        LOGW("%s not loaded yet; cannot install audio tap", OPENAL_SO);
        return nullptr;
    }
    // renderSamples is a LOCAL symbol -> look in .symtab first.
    void *addr = shadowhook_dlsym_symtab(handle, RENDER_SYM);
    if (addr == nullptr) {
        addr = shadowhook_dlsym(handle, RENDER_SYM); // fallback: any table
    }
    if (g_alcGetIntegerv == nullptr) {
        g_alcGetIntegerv = reinterpret_cast<alcGetIntegerv_t>(
                shadowhook_dlsym(handle, "alcGetIntegerv"));
    }
    shadowhook_dlclose(handle);
    return addr;
}

void proxy_renderSamples(void *dev, void *out, uint32_t numSamples, size_t frameStep) {
    // Let OpenAL render the frame as usual first (UNIQUE mode: call orig direct).
    if (g_orig) {
        g_orig(dev, out, numSamples, frameStep);
    }
    if (!g_capturing.load(std::memory_order_relaxed) || g_ring == nullptr) {
        return;
    }

    int ch = static_cast<int>(frameStep);
    if (ch <= 0) ch = 2;

    if (g_sampleRate.load(std::memory_order_relaxed) == 0) {
        int freq = 0;
        if (g_alcGetIntegerv) {
            g_alcGetIntegerv(dev, ALC_FREQUENCY, 1, &freq);
        }
        if (freq <= 0) freq = 48000;
        g_channels.store(ch, std::memory_order_relaxed);
        g_sampleRate.store(freq, std::memory_order_release);
    }

    const size_t total = static_cast<size_t>(numSamples) * static_cast<size_t>(ch);
    const auto *src = static_cast<const int16_t *>(out);

    size_t wr = g_wr.load(std::memory_order_relaxed);
    size_t rd = g_rd.load(std::memory_order_acquire);
    size_t freeSpace = g_ringCap - (wr - rd);
    if (total > freeSpace) {
        // Consumer fell behind: drop this chunk rather than block the audio
        // thread or overwrite unread data. A brief audio gap is acceptable.
        return;
    }
    size_t head = wr % g_ringCap;
    size_t firstPart = g_ringCap - head;
    if (firstPart >= total) {
        std::memcpy(g_ring + head, src, total * sizeof(int16_t));
    } else {
        std::memcpy(g_ring + head, src, firstPart * sizeof(int16_t));
        std::memcpy(g_ring, src + firstPart, (total - firstPart) * sizeof(int16_t));
    }
    g_wr.store(wr + total, std::memory_order_release);
}

bool ensure_hook() {
    if (g_hookInstalled) return true;

    if (!g_shadowhookReady) {
        // libshadowhook.so is a link-time dependency of this module, so the
        // native API is available; init it directly (idempotent).
        shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
        g_shadowhookReady = true;
    }

    void *addr = resolve_render_samples();
    if (addr == nullptr) {
        LOGW("could not resolve %s in %s", RENDER_SYM, OPENAL_SO);
        return false;
    }

    g_stub = shadowhook_hook_func_addr(addr, reinterpret_cast<void *>(proxy_renderSamples),
                                       reinterpret_cast<void **>(&g_orig));
    if (g_stub == nullptr) {
        int e = shadowhook_get_errno();
        LOGE("shadowhook_hook_func_addr failed at %p (errno=%d %s)", addr, e,
             shadowhook_to_errmsg(e));
        return false;
    }
    g_hookInstalled = true;
    LOGI("Audio tap installed at %p", addr);
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeStart(JNIEnv *, jclass) {
    if (!ensure_hook()) {
        return JNI_FALSE;
    }
    if (g_ring == nullptr) {
        // ~2 s of headroom at 48 kHz stereo; plenty for the pump to keep up.
        g_ringCap = 48000 * 2 * 2;
        g_ring = static_cast<int16_t *>(std::malloc(g_ringCap * sizeof(int16_t)));
        if (g_ring == nullptr) {
            g_ringCap = 0;
            return JNI_FALSE;
        }
    }
    g_wr.store(0, std::memory_order_relaxed);
    g_rd.store(0, std::memory_order_relaxed);
    g_sampleRate.store(0, std::memory_order_relaxed);
    g_channels.store(0, std::memory_order_relaxed);
    g_capturing.store(true, std::memory_order_release);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeStop(JNIEnv *, jclass) {
    // Keep the hook installed (toggling inline hooks repeatedly is risky); just
    // stop draining into the ring. Idle overhead is one relaxed atomic load.
    g_capturing.store(false, std::memory_order_release);
}

JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetSampleRate(JNIEnv *, jclass) {
    return g_sampleRate.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetChannels(JNIEnv *, jclass) {
    return g_channels.load(std::memory_order_acquire);
}

// Copies up to maxSamples int16 samples into `out`; returns the count copied.
JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeRead(JNIEnv *env, jclass,
                                                                         jshortArray out,
                                                                         jint maxSamples) {
    if (g_ring == nullptr || out == nullptr || maxSamples <= 0) {
        return 0;
    }
    size_t rd = g_rd.load(std::memory_order_relaxed);
    size_t wr = g_wr.load(std::memory_order_acquire);
    size_t avail = wr - rd;
    if (avail == 0) return 0;
    size_t want = static_cast<size_t>(maxSamples);
    size_t n = avail < want ? avail : want;

    jshort *dst = env->GetShortArrayElements(out, nullptr);
    if (dst == nullptr) return 0;
    size_t head = rd % g_ringCap;
    size_t firstPart = g_ringCap - head;
    if (firstPart >= n) {
        std::memcpy(dst, g_ring + head, n * sizeof(int16_t));
    } else {
        std::memcpy(dst, g_ring + head, firstPart * sizeof(int16_t));
        std::memcpy(dst + firstPart, g_ring, (n - firstPart) * sizeof(int16_t));
    }
    env->ReleaseShortArrayElements(out, dst, 0);

    g_rd.store(rd + n, std::memory_order_release);
    return static_cast<jint>(n);
}

} // extern "C"
