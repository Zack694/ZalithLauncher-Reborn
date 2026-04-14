package com.movtery.zalithlauncher.renderer

import android.content.Context
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.renderer.renderers.FreedrenoRenderer
import com.movtery.zalithlauncher.renderer.renderers.GL4ESRenderer
import com.movtery.zalithlauncher.renderer.renderers.KryptonRenderer
import com.movtery.zalithlauncher.renderer.renderers.PanfrostRenderer
import com.movtery.zalithlauncher.renderer.renderers.VirGLRenderer
import com.movtery.zalithlauncher.renderer.renderers.VulkanZinkRenderer
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools

/**
 * Central renderer manager for the launcher.
 *
 * Both built-in renderers and renderer plugins are registered here.
 */
object Renderers {
    private val renderers: MutableList<RendererInterface> = mutableListOf()
    private var compatibleRenderers: Pair<RenderersList, MutableList<RendererInterface>>? = null
    private var currentRenderer: RendererInterface? = null
    private var isInitialized: Boolean = false

    fun init(reset: Boolean = false) {
        if (isInitialized && !reset) return
        isInitialized = true

        if (reset) {
            renderers.clear()
            compatibleRenderers = null
            currentRenderer = null
        }

        addRenderers(
            KryptonRenderer(),
            GL4ESRenderer(),
            VulkanZinkRenderer(),
            VirGLRenderer(),
            FreedrenoRenderer(),
            PanfrostRenderer()
        )
    }

    /**
     * Returns all renderers compatible with the current device.
     */
    fun getCompatibleRenderers(context: Context): Pair<RenderersList, List<RendererInterface>> {
        compatibleRenderers?.let { return Pair(it.first, it.second.toList()) }

        val deviceHasVulkan = Tools.checkVulkanSupport(context.packageManager)
        // Currently, only 32-bit x86 devices do not have the Zink binary.
        val deviceHasZinkBinary = !(Architecture.is32BitsDevice() && Architecture.isx86Device())

        val compatibleList: MutableList<RendererInterface> = mutableListOf()
        renderers.forEach { renderer ->
            if (renderer.getRendererId().contains("vulkan") && !deviceHasVulkan) return@forEach
            if (renderer.getRendererId().contains("zink") && !deviceHasZinkBinary) return@forEach
            compatibleList.add(renderer)
        }

        val rendererIdentifiers: MutableList<String> = mutableListOf()
        val rendererNames: MutableList<String> = mutableListOf()
        compatibleList.forEach { renderer ->
            rendererIdentifiers.add(renderer.getUniqueIdentifier())
            rendererNames.add(renderer.getRendererName())
        }

        val rendererPair = Pair(RenderersList(rendererIdentifiers, rendererNames), compatibleList)
        compatibleRenderers = rendererPair
        return Pair(rendererPair.first, rendererPair.second.toList())
    }

    /**
     * Adds multiple renderers.
     */
    @JvmStatic
    fun addRenderers(vararg renderers: RendererInterface) {
        renderers.forEach { renderer ->
            addRenderer(renderer)
        }
    }

    /**
     * Adds a single renderer.
     */
    @JvmStatic
    fun addRenderer(renderer: RendererInterface): Boolean {
        return if (this.renderers.any { it.getUniqueIdentifier() == renderer.getUniqueIdentifier() }) {
            Logging.w(
                "Renderers",
                "The unique identifier of this renderer (${renderer.getRendererName()} - ${renderer.getUniqueIdentifier()}) conflicts with an already loaded renderer."
            )
            false
        } else {
            this.renderers.add(renderer)
            compatibleRenderers = null
            Logging.i(
                "Renderers",
                "Renderer loaded: ${renderer.getRendererName()} (${renderer.getRendererId()} - ${renderer.getUniqueIdentifier()})"
            )
            true
        }
    }

    /**
     * Sets the current renderer.
     *
     * @param context Used to resolve compatible renderers for the current device.
     * @param uniqueIdentifier The unique identifier of the renderer to select.
     * @param retryToFirstOnFailure If no matching renderer is found, fall back to the first
     * compatible renderer when true.
     */
    fun setCurrentRenderer(
        context: Context,
        uniqueIdentifier: String,
        retryToFirstOnFailure: Boolean = true
    ) {
        if (!isInitialized) throw IllegalStateException("Uninitialized renderer!")

        val compatibleList = getCompatibleRenderers(context).second
        currentRenderer = compatibleList.find { it.getUniqueIdentifier() == uniqueIdentifier } ?: run {
            if (retryToFirstOnFailure && compatibleList.isNotEmpty()) {
                val renderer = compatibleList[0]
                Logging.w(
                    "Renderers",
                    "Incompatible renderer $uniqueIdentifier will be replaced with ${renderer.getUniqueIdentifier()} (${renderer.getRendererName()})"
                )
                renderer
            } else {
                null
            }
        }
    }

    /**
     * Returns the currently selected renderer.
     */
    fun getCurrentRenderer(): RendererInterface {
        if (!isInitialized) throw IllegalStateException("Uninitialized renderer!")
        return currentRenderer ?: throw IllegalStateException("Current renderer not set")
    }

    /**
     * Returns true if a current renderer has been set.
     */
    fun isCurrentRendererValid(): Boolean = isInitialized && this.currentRenderer != null

    /**
     * Clears cached compatible/current renderer state without removing loaded renderers.
     */
    @JvmStatic
    fun invalidateCaches() {
        compatibleRenderers = null
        currentRenderer = null
    }

    /**
     * Refreshes cached renderer state after plugins have already been reloaded.
     *
     * This does not clear the renderer list, because doing so would remove renderer
     * plugins that were just registered by PluginLoader.
     */
    @JvmStatic
    fun reloadRenderers(
        context: Context,
        selectedRendererId: String,
        retryToFirstOnFailure: Boolean = true
    ) {
        if (!isInitialized) {
            init(false)
        }

        invalidateCaches()
        setCurrentRenderer(context, selectedRendererId, retryToFirstOnFailure)
    }
}
