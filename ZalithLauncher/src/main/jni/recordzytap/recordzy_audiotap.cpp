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
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstdio>
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
int g_lastError = 0; // 0 ok, 2 shadowhook init, 3 symbol not found, 4 hook failed, 5 alloc
std::atomic<bool> g_capturing{false};
std::atomic<int> g_sampleRate{0};
std::atomic<int> g_channels{0};
std::atomic<long long> g_hookCalls{0};      // times the proxy ran (hook is alive)
std::atomic<long long> g_totalCaptured{0};  // int16 samples buffered into the ring

// Lock-free single-producer (audio mixer thread) / single-consumer (Java pump
// thread) ring buffer of interleaved int16 samples. wr/rd are free-running
// counters; indices are taken modulo capacity.
int16_t *g_ring = nullptr;
size_t g_ringCap = 0; // in int16 samples
std::atomic<size_t> g_wr{0};
std::atomic<size_t> g_rd{0};

// Find a mapped library's load base + on-disk path via /proc/self/maps. Unlike
// dlopen / dl_iterate_phdr (which only see the *caller's* linker namespace),
// this finds libraries loaded in other namespaces - the game's OpenAL lives in
// the JRE's namespace, invisible to our app namespace.
bool find_module(const char *soname, uintptr_t *base, char *pathOut, size_t pathCap) {
    FILE *f = std::fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[1024];
    bool found = false;
    while (std::fgets(line, sizeof(line), f)) {
        uintptr_t start, end, off;
        char perms[8];
        char path[896];
        path[0] = '\0';
        int n = std::sscanf(line, "%lx-%lx %7s %lx %*x:%*x %*u %895[^\n]",
                            &start, &end, perms, &off, path);
        if (n < 5) continue;
        char *p = path;
        while (*p == ' ') p++;
        if (p[0] != '/') continue;
        // Match the basename (ignore any " (deleted)" suffix the kernel appends).
        if (std::strstr(p, "/libopenal.so") == nullptr) continue;
        if (std::strstr(p, soname) == nullptr) continue;
        if (off != 0) continue; // the mapping of the ELF header (file offset 0)
        *base = start;
        // Copy just the path, dropping a trailing " (deleted)" if present.
        char *del = std::strstr(p, " (deleted)");
        if (del) *del = '\0';
        std::strncpy(pathOut, p, pathCap - 1);
        pathOut[pathCap - 1] = '\0';
        found = true;
        break;
    }
    std::fclose(f);
    return found;
}

// Look up a symbol's virtual address in an ELF file's .symtab or .dynsym.
uintptr_t elf_sym_value(const char *path, const char *name) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < (off_t) sizeof(Elf64_Ehdr)) {
        close(fd);
        return 0;
    }
    void *map = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return 0;

    uintptr_t result = 0;
    auto *b = static_cast<char *>(map);
    auto *eh = reinterpret_cast<Elf64_Ehdr *>(b);
    if (std::memcmp(eh->e_ident, ELFMAG, SELFMAG) == 0 &&
        eh->e_ident[EI_CLASS] == ELFCLASS64 && eh->e_shoff != 0) {
        auto *sh = reinterpret_cast<Elf64_Shdr *>(b + eh->e_shoff);
        for (int i = 0; i < eh->e_shnum && result == 0; i++) {
            if ((sh[i].sh_type == SHT_SYMTAB || sh[i].sh_type == SHT_DYNSYM) &&
                sh[i].sh_link < eh->e_shnum && sh[i].sh_entsize != 0) {
                auto *syms = reinterpret_cast<Elf64_Sym *>(b + sh[i].sh_offset);
                size_t count = sh[i].sh_size / sh[i].sh_entsize;
                const char *strs = b + sh[sh[i].sh_link].sh_offset;
                for (size_t s = 0; s < count; s++) {
                    if (syms[s].st_value != 0 &&
                        std::strcmp(strs + syms[s].st_name, name) == 0) {
                        result = static_cast<uintptr_t>(syms[s].st_value);
                        break;
                    }
                }
            }
        }
    }
    munmap(map, static_cast<size_t>(st.st_size));
    return result;
}

void *resolve_render_samples() {
    uintptr_t base = 0;
    char path[896];
    if (find_module(OPENAL_SO, &base, path, sizeof(path))) {
        uintptr_t rv = elf_sym_value(path, RENDER_SYM);
        if (rv == 0) {
            LOGW("symbol %s not found in %s", RENDER_SYM, path);
            return nullptr;
        }
        if (g_alcGetIntegerv == nullptr) {
            uintptr_t av = elf_sym_value(path, "alcGetIntegerv");
            if (av != 0) {
                g_alcGetIntegerv = reinterpret_cast<alcGetIntegerv_t>(base + av);
            }
        }
        LOGI("Resolved %s via maps: base=%p +0x%lx", OPENAL_SO,
             reinterpret_cast<void *>(base), (unsigned long) rv);
        return reinterpret_cast<void *>(base + rv);
    }

    // Fallback: ShadowHook's resolver (works only if libopenal is in our namespace).
    void *handle = shadowhook_dlopen(OPENAL_SO);
    if (handle != nullptr) {
        void *addr = shadowhook_dlsym_symtab(handle, RENDER_SYM);
        if (addr == nullptr) addr = shadowhook_dlsym(handle, RENDER_SYM);
        if (g_alcGetIntegerv == nullptr) {
            g_alcGetIntegerv = reinterpret_cast<alcGetIntegerv_t>(
                    shadowhook_dlsym(handle, "alcGetIntegerv"));
        }
        shadowhook_dlclose(handle);
        return addr;
    }
    LOGW("%s not found in /proc/self/maps or namespace", OPENAL_SO);
    return nullptr;
}

void proxy_renderSamples(void *dev, void *out, uint32_t numSamples, size_t frameStep) {
    // Let OpenAL render the frame as usual first (UNIQUE mode: call orig direct).
    if (g_orig) {
        g_orig(dev, out, numSamples, frameStep);
    }
    g_hookCalls.fetch_add(1, std::memory_order_relaxed);
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
    g_totalCaptured.fetch_add(static_cast<long long>(total), std::memory_order_relaxed);
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
        g_lastError = 3;
        LOGW("could not resolve %s in %s", RENDER_SYM, OPENAL_SO);
        return false;
    }

    g_stub = shadowhook_hook_func_addr(addr, reinterpret_cast<void *>(proxy_renderSamples),
                                       reinterpret_cast<void **>(&g_orig));
    if (g_stub == nullptr) {
        int e = shadowhook_get_errno();
        g_lastError = 4;
        LOGE("shadowhook_hook_func_addr failed at %p (errno=%d %s)", addr, e,
             shadowhook_to_errmsg(e));
        return false;
    }
    g_hookInstalled = true;
    g_lastError = 0;
    LOGI("Audio tap installed at %p", addr);
    return true;
}

} // namespace

extern "C" {

// Provide our own JNI_OnLoad so that loading librecordzytap.so does NOT end up
// invoking libshadowhook.so's JNI_OnLoad (resolved through the dependency),
// which returns JNI_ERR because we vendor only the .so, not ShadowHook's Java
// class. ShadowHook's C core (shadowhook_init / hook) needs no Java side.
JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}

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
            g_lastError = 5;
            return JNI_FALSE;
        }
    }
    g_wr.store(0, std::memory_order_relaxed);
    g_rd.store(0, std::memory_order_relaxed);
    g_sampleRate.store(0, std::memory_order_relaxed);
    g_channels.store(0, std::memory_order_relaxed);
    g_hookCalls.store(0, std::memory_order_relaxed);
    g_totalCaptured.store(0, std::memory_order_relaxed);
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

// Diagnostics: 0 ok, 2 shadowhook init, 3 symbol not found, 4 hook failed, 5 alloc.
JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetStatus(JNIEnv *, jclass) {
    return g_lastError;
}

// Number of times the hooked mixer function ran (proves the hook is firing).
JNIEXPORT jlong JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetHookCalls(JNIEnv *, jclass) {
    return static_cast<jlong>(g_hookCalls.load(std::memory_order_relaxed));
}

// Total int16 samples buffered into the ring since the last start.
JNIEXPORT jlong JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetCapturedSamples(JNIEnv *,
                                                                                       jclass) {
    return static_cast<jlong>(g_totalCaptured.load(std::memory_order_relaxed));
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
