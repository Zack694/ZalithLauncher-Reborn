package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

class PanfrostRenderer : RendererInterface {
    override fun getRendererId(): String = "gallium_panfrost"

    override fun getUniqueIdentifier(): String = "9b2808c4-11af-4c72-a9c6-94c940396475"

    override fun getRendererName(): String = "Panfrost (Mali)"
    override fun getRendererDescription(): String =
        "Only used for certain Mali GPU devices that support Panfrost"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libOSMesa_2300d.so"
}