#include <EGL/egl.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

//
// Created by maks on 17.09.2022.
//

static const char* g_LogTag = "GLBridge";
static EGLDisplay g_EglDisplay = EGL_NO_DISPLAY;

#ifndef EGL_PLATFORM_ANDROID_KHR
#define EGL_PLATFORM_ANDROID_KHR 0x3141
#endif
#ifndef EGL_EXTENSIONS
#define EGL_EXTENSIONS 0x3055
#endif
#ifndef EGL_VERSION
#define EGL_VERSION 0x3054
#endif
#ifndef EGL_VENDOR
#define EGL_VENDOR 0x3053
#endif
#ifndef EGL_OPENGL_BIT
#define EGL_OPENGL_BIT 0x0008
#endif
#ifndef EGL_CONTEXT_MAJOR_VERSION_KHR
#define EGL_CONTEXT_MAJOR_VERSION_KHR 0x3098
#endif
#ifndef EGL_CONTEXT_MINOR_VERSION_KHR
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#endif
#ifndef EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR
#define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR 0x30FD
#endif
#ifndef EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR
#define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR 0x00000002
#endif
#ifndef EGL_STENCIL_SIZE
#define EGL_STENCIL_SIZE 0x3026
#endif
#ifndef EGL_SWAP_BEHAVIOR
#define EGL_SWAP_BEHAVIOR 0x3093
#endif
#ifndef EGL_BUFFER_DESTROYED
#define EGL_BUFFER_DESTROYED 0x3095
#endif

static void gl_log(int prio, const char* fmt, ...) {
    char buffer[2048];
    memset(buffer, 0, sizeof(buffer));
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer) - 1, fmt ? fmt : "", ap);
    va_end(ap);
    __android_log_print(prio, g_LogTag, "%s", buffer);
    fprintf(stderr, "%s: %s\n", g_LogTag, buffer);
    fflush(stderr);
}

static bool env_enabled(const char* name) {
    const char* value = getenv(name);
    return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0 && strcmp(value, "false") != 0;
}

static bool env_is(const char* name, const char* expected) {
    const char* value = getenv(name);
    return value != NULL && expected != NULL && strcmp(value, expected) == 0;
}

static bool should_force_desktop_gl(void) {
    return env_enabled("ZALITH_EGL_FORCE_DESKTOP_GL")
           || env_enabled("ZALITH_MESA_DESKTOP_GL")
           || env_enabled("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL")
           || env_enabled("DROIDBRIDGE_MESA_DESKTOP_GL")
           || (env_enabled("ZALITH_MESA")
               && (env_is("ZALITH_MESA_MODE", "freedreno_kgsl")
                   || env_is("POJAV_RENDERER_MESA_MODE", "freedreno_kgsl")))
           || (env_enabled("DROIDBRIDGE_MESA")
               && (env_is("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl")
                   || env_is("POJAV_RENDERER_MESA_MODE", "freedreno_kgsl")));
}

static bool should_use_safe_android_swaps(void) {
    return should_force_desktop_gl()
           || env_enabled("ZALITH_MESA_SAFE_SWAPS")
           || env_enabled("DROIDBRIDGE_MESA_SAFE_SWAPS");
}

static void force_destroyed_swap_behavior(EGLSurface surface) {
    if (surface == EGL_NO_SURFACE || surface == NULL || eglSurfaceAttrib_p == NULL) return;

    if (eglSurfaceAttrib_p(g_EglDisplay, surface, EGL_SWAP_BEHAVIOR, EGL_BUFFER_DESTROYED)) {
        gl_log(ANDROID_LOG_INFO, "Mesa surface: forced EGL_SWAP_BEHAVIOR=EGL_BUFFER_DESTROYED");
    } else {
        gl_log(ANDROID_LOG_WARN, "Mesa surface: eglSurfaceAttrib(EGL_BUFFER_DESTROYED) failed: %04x", eglGetError_p());
    }
}

static __thread gl_render_window_t* currentBundle;

bool gl_init() {
    gl_log(ANDROID_LOG_INFO, "gl_init renderer=%s zalithMesa=%s droidBridgeMesa=%s mesaMode=%s POJAVEXEC_EGL=%s ZALITH_MESA_EGL=%s",
           getenv("POJAV_RENDERER") ? getenv("POJAV_RENDERER") : "<null>",
           getenv("ZALITH_MESA") ? getenv("ZALITH_MESA") : "<null>",
           getenv("DROIDBRIDGE_MESA") ? getenv("DROIDBRIDGE_MESA") : "<null>",
           getenv("POJAV_RENDERER_MESA_MODE") ? getenv("POJAV_RENDERER_MESA_MODE") : "<null>",
           getenv("POJAVEXEC_EGL") ? getenv("POJAVEXEC_EGL") : "<null>",
           getenv("ZALITH_MESA_EGL") ? getenv("ZALITH_MESA_EGL") : "<null>");

    dlsym_EGL();

    const bool prefer_platform_display = env_enabled("ZALITH_MESA")
            || env_enabled("DROIDBRIDGE_MESA")
            || env_enabled("ZALITH_MESA_EGL_PLATFORM_DISPLAY")
            || env_enabled("DROIDBRIDGE_MESA_EGL_PLATFORM_DISPLAY");

    if (prefer_platform_display && eglGetPlatformDisplay_p != NULL) {
        EGLDisplay platform_display = eglGetPlatformDisplay_p(EGL_PLATFORM_ANDROID_KHR, EGL_DEFAULT_DISPLAY, NULL);
        if (platform_display != EGL_NO_DISPLAY && eglInitialize_p(platform_display, 0, 0) == EGL_TRUE) {
            g_EglDisplay = platform_display;
            gl_log(ANDROID_LOG_INFO, "eglGetPlatformDisplay(EGL_PLATFORM_ANDROID_KHR) initialized provider=%s",
                   zalith_egl_get_loaded_name());
            return true;
        }
        gl_log(ANDROID_LOG_WARN, "eglGetPlatformDisplay initialization failed: %04x provider=%s",
               eglGetError_p(), zalith_egl_get_loaded_name());
    }

    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        gl_log(ANDROID_LOG_ERROR, "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        gl_log(ANDROID_LOG_ERROR, "eglInitialize_p() failed: %04x provider=%s",
               eglGetError_p(), zalith_egl_get_loaded_name());
        return false;
    }
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if (currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if (!result_width || !result_height) goto zero;
    return;

    zero:
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        gl_log(ANDROID_LOG_ERROR, "gl_init_context called without initialized EGL display");
        return NULL;
    }

    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    memset(bundle, 0, sizeof(gl_render_window_t));
    const bool desktop_gl = should_force_desktop_gl();
    const EGLint requested_renderable_type = desktop_gl ? EGL_OPENGL_BIT : EGL_OPENGL_ES2_BIT;
    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_RED_SIZE, 8,
                    EGL_ALPHA_SIZE, desktop_gl ? 0 : 8,
                    EGL_DEPTH_SIZE, 24,
                    EGL_STENCIL_SIZE, desktop_gl ? 8 : 0,
                    EGL_SURFACE_TYPE,
                    EGL_WINDOW_BIT|EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE,
                    requested_renderable_type,
                    EGL_NONE
                    };
    EGLint num_configs = 0;

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE)
    {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "eglChooseConfig_p() failed: %04x",
                            eglGetError_p());
        free(bundle);
        return NULL;
    }

    if (num_configs == 0)
    {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s",
                            "eglChooseConfig_p() found no matching config");
        free(bundle);
        return NULL;
    }

    eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        EGLBoolean bindResult;

        if (desktop_gl || !strncmp(getenv("POJAV_RENDERER"), "opengles3_desktopgl", 19))
        {
            printf("EGLBridge: Binding to OpenGL\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
        } else {
            printf("EGLBridge: Binding to OpenGL ES\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) printf("EGLBridge: bind failed: %04x\n", eglGetError_p());
    }

    if (desktop_gl) {
        const EGLint ctx_33_compat[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
                EGL_CONTEXT_MINOR_VERSION_KHR, 3,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR,
                EGL_NONE
        };
        const EGLint ctx_default[] = { EGL_NONE };
        bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, ctx_33_compat);
        if (bundle->context == EGL_NO_CONTEXT) {
            gl_log(ANDROID_LOG_WARN, "desktop GL 3.3 compatibility context failed: %04x", eglGetError_p());
            bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, ctx_default);
        }
    } else {
        const char* libgl_es_env = getenv("LIBGL_ES");
        int libgl_es = libgl_es_env != NULL && libgl_es_env[0] != '\0'
                ? (int) strtol(libgl_es_env, NULL, 0)
                : 2;
        if (libgl_es < 1 || libgl_es > INT16_MAX) libgl_es = 2;
        const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
        bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, egl_context_attributes);
    }

    if (bundle->context == EGL_NO_CONTEXT)
    {
        gl_log(ANDROID_LOG_ERROR, "eglCreateContext_p() finished with error: %04x desktop_gl=%d",
               eglGetError_p(), desktop_gl ? 1 : 0);
        free(bundle);
        return NULL;
    }
    gl_log(ANDROID_LOG_INFO, "eglCreateContext_p() success context=%p desktop_gl=%d", bundle->context, desktop_gl ? 1 : 0);
    return bundle;
}

void gl_swap_surface(gl_render_window_t* bundle) {
    if (bundle->nativeSurface != NULL)
        ANativeWindow_release(bundle->nativeSurface);

    if (bundle->surface != NULL)
        eglDestroySurface_p(g_EglDisplay, bundle->surface);

    if (bundle->newNativeSurface != NULL)
    {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        const EGLint safe_window_surface_attrs[] = {
                EGL_RENDER_BUFFER, EGL_BACK_BUFFER,
                EGL_NONE
        };
        bundle->surface = eglCreateWindowSurface_p(
                g_EglDisplay,
                bundle->config,
                bundle->nativeSurface,
                should_use_safe_android_swaps() ? safe_window_surface_attrs : NULL);
        if (bundle->surface != EGL_NO_SURFACE && should_use_safe_android_swaps()) {
            force_destroyed_swap_behavior(bundle->surface);
        }
    } else {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "No new native surface, switching to 1x1 pbuffer");
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
    }
}

void gl_make_current(gl_render_window_t* bundle) {

    if (bundle == NULL)
    {
        if (eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT))
        {
            currentBundle = NULL;
        }
        return;
    }

    bool hasSetMainWindow = false;
    if (pojav_environ->mainWindowBundle == NULL)
    {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Main window bundle is now %p", pojav_environ->mainWindowBundle);
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
    }

    if (bundle->surface == NULL)
        gl_swap_surface(bundle);

    if (eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context))
    {
        currentBundle = bundle;
    } else {
        if (hasSetMainWindow)
        {
            pojav_environ->mainWindowBundle->newNativeSurface = NULL;
            gl_swap_surface((gl_render_window_t*)pojav_environ->mainWindowBundle);
            pojav_environ->mainWindowBundle = NULL;
        }
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "eglMakeCurrent returned with error: %04x", eglGetError_p());
    }

}

void gl_swap_buffers() {
    if (currentBundle->state == STATE_RENDERER_NEW_WINDOW)
    {
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        gl_swap_surface(currentBundle);
        eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
        currentBundle->state = STATE_RENDERER_ALIVE;
    }

    if (currentBundle->surface != NULL)
        if (!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface) && eglGetError_p() == EGL_BAD_SURFACE)
        {
            eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            currentBundle->newNativeSurface = NULL;
            gl_swap_surface(currentBundle);
            eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
            __android_log_print(ANDROID_LOG_INFO, g_LogTag, "The window has died, awaiting window change");
        }

}

void gl_setup_window() {
    if (pojav_environ->mainWindowBundle != NULL)
    {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void gl_swap_interval(int swapInterval) {
    if (pojav_environ->force_vsync) swapInterval = 1;

    eglSwapInterval_p(g_EglDisplay, swapInterval);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    __android_log_print(ANDROID_LOG_INFO, g_LogTag, "GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}
