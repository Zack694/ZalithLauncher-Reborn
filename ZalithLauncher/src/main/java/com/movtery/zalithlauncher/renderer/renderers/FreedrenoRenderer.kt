package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

// Renderers DNA Mobile
class FreedrenoRenderer : RendererInterface {
    override fun getRendererId(): String = "freedreno_kgsl"

    override fun getUniqueIdentifier(): String = "1ad7249f-5784-4f00-bc72-174b3578ee46"

    override fun getRendererName(): String = "Mesa Freedreno KGSL"
    override fun getRendererDescription(): String =
        "Mesa renderer for Adreno using the direct Freedreno/KGSL Mesa path instead of Zink/Turnip."

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf(
            // Zalith-native names for new backend code.
            "ZALITH_MESA" to "1",
            "ZALITH_MESA_MODE" to "freedreno_kgsl",
            "ZALITH_MESA_SAFE_SWAPS" to "1",
            "ZALITH_MESA_DRIVER" to "kgsl",
            "ZALITH_MESA_DESKTOP_GL" to "1",
            "ZALITH_EGL_FORCE_DESKTOP_GL" to "1",
            "ZALITH_EGL_NO_SYSTEM_FALLBACK" to "1",
            "ZALITH_RENDERSPEC_EGL" to "libEGL_mesa.so",
            "ZALITH_MESA_EGL" to "libEGL_mesa.so",

            // DroidBridge compatibility aliases understood by imported renderer configs.
            "DROIDBRIDGE_MESA" to "1",
            "DROIDBRIDGE_MESA_MODE" to "freedreno_kgsl",
            "DROIDBRIDGE_MESA_SAFE_SWAPS" to "1",
            "DROIDBRIDGE_MESA_DRIVER" to "kgsl",
            "DROIDBRIDGE_MESA_DESKTOP_GL" to "1",
            "DROIDBRIDGE_EGL_FORCE_DESKTOP_GL" to "1",
            "DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK" to "1",
            "DROIDBRIDGE_RENDERSPEC_EGL" to "libEGL_mesa.so",
            "DROIDBRIDGE_MESA_EGL" to "libEGL_mesa.so",

            // Pojav bridge variables consumed by native code.
            "POJAV_RENDERER_MESA_MODE" to "freedreno_kgsl",
            "MESA_LOADER_DRIVER_OVERRIDE" to "kgsl",
            "GALLIUM_DRIVER" to "",
            "EGL_PLATFORM" to "android",
            "FORCE_VSYNC" to "false",
            "LIBGL_ES" to "2",
            "MESA_GL_VERSION_OVERRIDE" to "3.3COMPAT",
            "MESA_GLSL_VERSION_OVERRIDE" to "330",
            "MESA_GLSL_CACHE_DISABLE" to "false",
            "MESA_SHADER_CACHE_DISABLE" to "false",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_NORMALIZE" to "1",
            "LIBGL_NOERROR" to "0",
            "allow_higher_compat_version" to "true",
            "force_glsl_extensions_warn" to "true",
            "allow_glsl_extension_directive_midshader" to "true",
            "POJAVEXEC_EGL" to "libEGL_mesa.so",
            "POJAV_EGL_LIBRARY" to "libEGL_mesa.so",
            "POJAVEXEC_EGL_LIBRARY" to "libEGL_mesa.so",
            "POJAV_RENDERER_LIBRARY" to "libEGL_mesa.so",
            "POJAVEXEC_RENDERER" to "libEGL_mesa.so",
            "LIB_MESA_NAME" to "libEGL_mesa.so",

            // KGSL direct path must not pre-load Turnip/Zink/OSMesa.
            "POJAV_USE_SYSTEM_VULKAN" to "",
            "POJAV_LOAD_TURNIP" to "",
            "DROIDBRIDGE_LOAD_TURNIP" to "",
            "DROIDBRIDGE_USE_CUSTOM_TURNIP" to "",
            "DROIDBRIDGE_CUSTOM_VULKAN_DRIVER" to "",
            "POJAV_CUSTOM_VULKAN_DRIVER" to "",
            "VK_ICD_FILENAMES" to "",
            "VK_DRIVER_FILES" to "",
            "ZINK_DEBUG" to "",
            "ZINK_DESCRIPTORS" to "",
            "OSMESA_LIB" to "",
            "POJAV_OSMESA_LIBRARY" to "",
            "OSMESA_LIBRARY" to "",
            "LIBGL_OSMESA" to ""
        )
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libGLZalith.so"

    override fun getRendererEGL(): String = "libEGL_mesa.so"
}
