//
// Created by maks on 06.01.2025.
//

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <environ/environ.h>

#include <dlfcn.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern void* maybe_load_vulkan(void);

#define TAG "LwjglLinkerHook"
#define ZALITH_OPENGL_PROXY_SONAME "libGLZalith.so"
#define ZALITH_OPENGL_PROXY_ALT_SONAME "libGLZalithMesa.so"
#define DROIDBRIDGE_OPENGL_PROXY_SONAME "libGLDroidBridge.so"
#define DROIDBRIDGE_OPENGL_PROXY_ALT_SONAME "libGLDroidBridgeMesa.so"

static const char* basename_or_self(const char* filename) {
    if (filename == NULL) return "";

    const char* base = strrchr(filename, '/');
    return base != NULL ? base + 1 : filename;
}

/**
 * Returns true when the requested library name is a Vulkan loader soname.
 * Accepts both direct names and full paths.
 */
static bool is_vulkan_loader_name(const char* filename) {
    const char* base = basename_or_self(filename);
    return strcmp(base, "libvulkan.so") == 0 ||
           strcmp(base, "libvulkan.so.1") == 0;
}

/**
 * Renderer proxy names used to route LWJGL's OpenGL load to the same EGL/Mesa
 * provider selected by the launcher-side renderer environment.
 *
 * The Zalith names are preferred for new configs. DroidBridge/Mojo names are
 * accepted as compatibility aliases so existing renderer plugin configs can be
 * reused while the backend is ported incrementally.
 */
static bool is_renderspec_opengl_proxy_name(const char* filename) {
    if (filename == NULL) return false;

    const char* base = basename_or_self(filename);

    if (strcmp(base, ZALITH_OPENGL_PROXY_SONAME) == 0 ||
        strcmp(base, ZALITH_OPENGL_PROXY_ALT_SONAME) == 0 ||
        strcmp(base, DROIDBRIDGE_OPENGL_PROXY_SONAME) == 0 ||
        strcmp(base, DROIDBRIDGE_OPENGL_PROXY_ALT_SONAME) == 0 ||
        strcmp(base, "libGLMojo.so") == 0) {
        return true;
    }

    /*
     * Be tolerant of LWJGL name-mapping differences, such as GLZalith,
     * /full/path/libGLZalith.so, or accidental liblib... wrapping.
     */
    return strstr(base, "GLZalith") != NULL ||
           strstr(base, "ZalithMesa") != NULL ||
           strstr(base, "GLDroidBridge") != NULL ||
           strstr(base, "DroidBridgeMesa") != NULL ||
           strstr(base, "GLMojo") != NULL;
}

static const char* first_non_empty(const char* a, const char* b, const char* c,
                                   const char* d, const char* e) {
    if (a != NULL && a[0] != '\0') return a;
    if (b != NULL && b[0] != '\0') return b;
    if (c != NULL && c[0] != '\0') return c;
    if (d != NULL && d[0] != '\0') return d;
    if (e != NULL && e[0] != '\0') return e;
    return NULL;
}

static void* try_dlopen_with_log(const char* library, int mode) {
    if (library == NULL || library[0] == '\0') return NULL;

    dlerror();
    void* handle = dlopen(library, mode);
    if (handle != NULL) {
        printf("LWJGL linkerhook: Zalith RenderSpec using %s handle=%p\n", library, handle);
        return handle;
    }

    const char* err = dlerror();
    printf("LWJGL linkerhook: Zalith RenderSpec failed to open %s: %s\n",
           library,
           err != NULL ? err : "unknown");
    return NULL;
}

/**
 * Acquire the GL provider handle for LWJGL's OpenGL module.
 *
 * Renderer configs can request this path with:
 * -Dorg.lwjgl.opengl.libname=libGLZalith.so
 *
 * The hook then uses the launcher/Mesa EGL environment instead of letting LWJGL
 * load an unrelated system OpenGL provider.
 */
static void* acquire_renderspec_opengl_handle(int mode) {
    int dl_mode = mode;
    if ((dl_mode & RTLD_NOW) == 0 && (dl_mode & RTLD_LAZY) == 0) {
        dl_mode |= RTLD_NOW;
    }
    dl_mode |= RTLD_GLOBAL;

    const char* renderer = getenv("POJAV_RENDERER");
    const char* mesa_mode = getenv("ZALITH_MESA_MODE");
    const char* droidbridge_mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const char* mesa_driver = first_non_empty(
            getenv("ZALITH_MESA_DRIVER"),
            getenv("DROIDBRIDGE_MESA_DRIVER"),
            getenv("MESA_LOADER_DRIVER_OVERRIDE"),
            NULL,
            NULL
    );
    const char* renderer_mesa_mode = getenv("POJAV_RENDERER_MESA_MODE");

    printf("LWJGL linkerhook: Zalith RenderSpec request renderer=%s mesaMode=%s droidBridgeMesaMode=%s mesaDriver=%s rendererMesaMode=%s\n",
           renderer != NULL ? renderer : "",
           mesa_mode != NULL ? mesa_mode : "",
           droidbridge_mesa_mode != NULL ? droidbridge_mesa_mode : "",
           mesa_driver != NULL ? mesa_driver : "",
           renderer_mesa_mode != NULL ? renderer_mesa_mode : "");

    const char* preferred = first_non_empty(
            getenv("ZALITH_RENDERSPEC_EGL"),
            getenv("ZALITH_MESA_EGL"),
            getenv("POJAVEXEC_EGL"),
            getenv("POJAV_EGL_LIBRARY"),
            getenv("POJAVEXEC_EGL_LIBRARY")
    );

    void* handle = try_dlopen_with_log(preferred, dl_mode);
    if (handle != NULL) return handle;

    handle = try_dlopen_with_log("libEGL_mesa.so", dl_mode);
    if (handle != NULL) return handle;

    handle = try_dlopen_with_log("libEGL.so", dl_mode);
    if (handle != NULL) return handle;

    printf("LWJGL linkerhook: Zalith RenderSpec failed; returning NULL for OpenGL proxy\n");
    return NULL;
}

/**
 * Basically a verbatim implementation of ndlopen(), found at
 * https://github.com/PojavLauncherTeam/lwjgl3/blob/3.3.1/modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_DynamicLinkLoader.c#L11
 * but with our own additions for stuff like vulkanmod.
 */
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv *env,
                            __attribute__((unused)) jclass class,
                            jlong filename_ptr,
                            jint jmode) {
    const char* filename = (const char*) filename_ptr;
    int mode = (int)jmode;

    // Override vulkan loading to let us load vulkan ourselves.
    if (is_vulkan_loader_name(filename)) {
        printf("LWJGL linkerhook: intercepted Vulkan load for %s\n", filename);

        void* handle = maybe_load_vulkan();
        if (handle != NULL) {
            printf("LWJGL linkerhook: using custom/system Vulkan handle %p for %s\n",
                   handle,
                   filename);
            return (jlong) handle;
        }

        printf("LWJGL linkerhook: maybe_load_vulkan() returned NULL, falling back to dlopen(%s)\n",
               filename);
    }

    // This hook also serves the task of mitigating a bug: the idea is that since, on Android 10 and
    // earlier, the linker doesn't really do namespace nesting.
    // It is not a problem as most of the libraries are in the launcher path, but when you try to run
    // VulkanMod which loads shaderc outside of the default jni libs directory through this method,
    // it can't load it because the path is not in the allowed paths for the anonymous namesapce.
    // This method fixes the issue by being in libpojavexec, and thus being in the classloader namespace

    if (is_renderspec_opengl_proxy_name(filename)) {
        printf("LWJGL linkerhook: matched Zalith RenderSpec OpenGL proxy filename=%s\n",
               filename != NULL ? filename : "");

        void* handle = acquire_renderspec_opengl_handle(mode);
        if (handle != NULL) {
            return (jlong) handle;
        }
    }

    return (jlong) dlopen(filename, mode);
}

/**
 * Install the LWJGL dlopen hook. This allows us to mitigate linker bugs and add custom library overrides.
 */
void installLwjglDlopenHook() {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Installing LWJGL dlopen() hook");
    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if(dynamicLinkLoader == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod ndlopenMethod[] = {
            {"ndlopen", "(JI)J", &ndlopen_bugfix}
    };
    if((*env)->RegisterNatives(env, dynamicLinkLoader, ndlopenMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to register the hooked method");
        (*env)->ExceptionClear(env);
    }
}
