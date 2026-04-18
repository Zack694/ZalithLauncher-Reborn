package com.movtery.zalithlauncher.renderer

// Launcher renderer implementations DNA Mobile
interface RendererInterface {
    /** Internal renderer ID used by the launcher. */
    fun getRendererId(): String

    /** Unique renderer identifier. */
    fun getUniqueIdentifier(): String

    /** User-facing renderer name. */
    fun getRendererName(): String

    /** User-facing renderer description. */
    fun getRendererDescription(): String

    /** Environment variables required by this renderer. */
    fun getRendererEnv(): Lazy<Map<String, String>>

    /** Native libraries that should be loaded with dlopen before use. */
    fun getDlopenLibrary(): Lazy<List<String>>

    /** Main native library name or path for this renderer. */
    fun getRendererLibrary(): String

    /** EGL library name for this renderer, if required. */
    fun getRendererEGL(): String? = null
}