// RecordZy audio tap - app side (Option A).
//
// The game's audio runs through our custom libopenal.so build (see
// openal-tap/), which has the tap compiled in and exports a small C API:
//   recordzy_tap_set_active / recordzy_tap_read / recordzy_tap_samplerate /
//   recordzy_tap_channels.
//
// This library finds the loaded libopenal.so via /proc/self/maps (works across
// the JRE's linker namespace), resolves those exported symbols from its .dynsym,
// and calls them - no inline hooking, no ShadowHook. If the loaded OpenAL is a
// stock build without the tap, resolution fails gracefully and recording falls
// back to video-only.

#include <jni.h>
#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <atomic>

#define LOG_TAG "RecordZyTap"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *OPENAL_SO = "libopenal.so";

using tap_read_t = int (*)(short *, int);
using tap_int_t = int (*)(void);
using tap_active_t = void (*)(int);

tap_read_t g_read = nullptr;
tap_int_t g_samplerate = nullptr;
tap_int_t g_channels = nullptr;
tap_active_t g_setActive = nullptr;

bool g_resolved = false;
int g_lastError = 0; // 0 ok, 3 lib/symbol not found
char g_diag[1024] = {0};

std::atomic<long long> g_readCalls{0};
std::atomic<long long> g_totalRead{0};

// Find a mapped library's load base + on-disk path via /proc/self/maps; sees
// libraries in other linker namespaces (the game's OpenAL lives in the JRE's).
bool find_module(uintptr_t *base, char *pathOut, size_t pathCap) {
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
        const char *bn = std::strrchr(p, '/');
        bn = bn ? bn + 1 : p;
        if (std::strstr(bn, "openal") == nullptr && std::strstr(bn, "OpenAL") == nullptr) {
            continue;
        }
        if (off != 0) continue;
        *base = start;
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

bool resolve() {
    if (g_resolved) return true;
    uintptr_t base = 0;
    char path[896];
    if (!find_module(&base, path, sizeof(path))) {
        g_lastError = 3;
        std::snprintf(g_diag, sizeof(g_diag), "libopenal.so not mapped in process");
        LOGW("%s", g_diag);
        return false;
    }
    uintptr_t r = elf_sym_value(path, "recordzy_tap_read");
    uintptr_t sr = elf_sym_value(path, "recordzy_tap_samplerate");
    uintptr_t ch = elf_sym_value(path, "recordzy_tap_channels");
    uintptr_t ac = elf_sym_value(path, "recordzy_tap_set_active");
    if (r == 0 || sr == 0 || ch == 0 || ac == 0) {
        g_lastError = 3;
        std::snprintf(g_diag, sizeof(g_diag),
                      "%s has no RecordZy tap (read=%d rate=%d ch=%d active=%d) - is this the "
                      "custom OpenAL build? path=%s",
                      OPENAL_SO, r != 0, sr != 0, ch != 0, ac != 0, path);
        LOGW("%s", g_diag);
        return false;
    }
    g_read = reinterpret_cast<tap_read_t>(base + r);
    g_samplerate = reinterpret_cast<tap_int_t>(base + sr);
    g_channels = reinterpret_cast<tap_int_t>(base + ch);
    g_setActive = reinterpret_cast<tap_active_t>(base + ac);
    g_resolved = true;
    g_lastError = 0;
    std::snprintf(g_diag, sizeof(g_diag), "tap API resolved in %s base=%p", path,
                  reinterpret_cast<void *>(base));
    LOGI("%s", g_diag);
    return true;
}

} // namespace

extern "C" {

// Own JNI_OnLoad so loading this lib never triggers another lib's loader hook.
JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeStart(JNIEnv *, jclass) {
    if (!resolve()) {
        return JNI_FALSE;
    }
    g_readCalls.store(0, std::memory_order_relaxed);
    g_totalRead.store(0, std::memory_order_relaxed);
    g_setActive(1);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeStop(JNIEnv *, jclass) {
    if (g_setActive) g_setActive(0);
}

JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetSampleRate(JNIEnv *, jclass) {
    return g_samplerate ? g_samplerate() : 0;
}

JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetChannels(JNIEnv *, jclass) {
    return g_channels ? g_channels() : 0;
}

JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetStatus(JNIEnv *, jclass) {
    return g_lastError;
}

// Liveness indicator: number of reads that returned audio.
JNIEXPORT jlong JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetHookCalls(JNIEnv *, jclass) {
    return static_cast<jlong>(g_readCalls.load(std::memory_order_relaxed));
}

JNIEXPORT jlong JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetCapturedSamples(JNIEnv *,
                                                                                       jclass) {
    return static_cast<jlong>(g_totalRead.load(std::memory_order_relaxed));
}

JNIEXPORT jstring JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeGetDiag(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_diag);
}

JNIEXPORT jint JNICALL
Java_com_movtery_zalithlauncher_recorder_audio_OpenALAudioTap_nativeRead(JNIEnv *env, jclass,
                                                                         jshortArray out,
                                                                         jint maxSamples) {
    if (g_read == nullptr || out == nullptr || maxSamples <= 0) {
        return 0;
    }
    jint cap = env->GetArrayLength(out);
    if (maxSamples > cap) maxSamples = cap;
    jshort *dst = env->GetShortArrayElements(out, nullptr);
    if (dst == nullptr) return 0;
    int n = g_read(dst, maxSamples);
    env->ReleaseShortArrayElements(out, dst, 0);
    if (n > 0) {
        g_readCalls.fetch_add(1, std::memory_order_relaxed);
        g_totalRead.fetch_add(n, std::memory_order_relaxed);
    }
    return n;
}

} // extern "C"
