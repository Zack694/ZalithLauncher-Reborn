package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

class KryptonRenderer : RendererInterface {
    override fun getRendererId(): String = "opengles3"

    override fun getUniqueIdentifier(): String = "e7b90ed6-e518-4d4e-93dc-5c7133cd5b31"

    override fun getRendererName(): String = "Krypton Wrapper"
    override fun getRendererDescription(): String =
        "Only used for certain Mali GPU devices that support Panfrost"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        buildMap {
            put("LIBGL_USE_MC_COLOR", "1")
            put("LIBGL_GL", "31")
            put("LIBGL_ES", "3")
            put("LIBGL_NORMALIZE", "1")
            put("LIBGL_NOERROR", "1")
        }
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libng_gl4es.so"


}