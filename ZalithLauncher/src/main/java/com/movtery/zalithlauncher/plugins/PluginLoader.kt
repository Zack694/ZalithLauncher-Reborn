package com.movtery.zalithlauncher.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager
import com.movtery.zalithlauncher.plugins.renderer.RendererPlugin
import com.movtery.zalithlauncher.plugins.renderer.RendererPluginManager
import com.movtery.zalithlauncher.renderer.RendererInterface
import com.movtery.zalithlauncher.renderer.Renderers
import com.movtery.zalithlauncher.utils.path.PathManager
import org.apache.commons.io.FileUtils

/**
 * Centralized plugin loading.
 *
 * By default, plugins are loaded once and then cached. Call [refreshAllPlugins]
 * after the user returns from installing a new plugin so the launcher rescans
 * installed apps and local plugin folders immediately.
 */
object PluginLoader {
    private var isInitialized: Boolean = false

    private const val PACKAGE_FLAGS =
        PackageManager.GET_META_DATA or PackageManager.GET_SHARED_LIBRARY_FILES

    @JvmStatic
    @SuppressLint("QueryPermissionsNeeded")
    fun loadAllPlugins(context: Context, force: Boolean = false) {
        if (isInitialized && !force) return
        isInitialized = true

        if (force) {
            clearCachedPluginsAndRenderers(context)
        }

        DriverPluginManager.initDriver(context, force)
        loadInstalledApkPlugins(context)
        loadLocalRendererPlugins(context)
        registerRendererPlugins()
    }

    /**
     * Forces a full plugin refresh.
     *
     * Use this after returning from a browser, package installer, or settings
     * screen where the user may have installed a renderer plugin such as Mobile Glues.
     */
    @JvmStatic
    fun refreshAllPlugins(context: Context) {
        loadAllPlugins(context, true)
    }

    /**
     * Clears cached plugin state before rebuilding it.
     */
    private fun clearCachedPluginsAndRenderers(context: Context) {
        RendererPluginManager.clearPlugin()
        Renderers.init(true)
        DriverPluginManager.initDriver(context, true)
    }

    /**
     * Loads plugin metadata from installed APKs.
     *
     * We scan all installed applications instead of only apps with a launcher activity.
     * Some renderer plugins may not expose a visible MAIN activity, so restricting the
     * scan to queryIntentActivities can miss newly installed plugins until the app restarts.
     */
    @SuppressLint("QueryPermissionsNeeded")
    private fun loadInstalledApkPlugins(context: Context) {
        val applications: List<ApplicationInfo> = context.packageManager
            .getInstalledApplications(PACKAGE_FLAGS)

        applications.forEach { applicationInfo ->
            DriverPluginManager.parsePlugin(applicationInfo)
            RendererPluginManager.parseApkPlugin(context, applicationInfo)
        }
    }

    /**
     * Loads renderer plugins from the local plugin directory.
     *
     * Invalid plugin folders are deleted automatically.
     */
    private fun loadLocalRendererPlugins(context: Context) {
        PathManager.DIR_INSTALLED_RENDERER_PLUGIN.listFiles()?.forEach { file ->
            val isValidPlugin =
                file.isDirectory && RendererPluginManager.parseLocalPlugin(context, file)

            if (!isValidPlugin) {
                FileUtils.deleteQuietly(file)
            }
        }
    }

    /**
     * Registers parsed renderer plugins with the global renderer registry.
     *
     * Any plugin renderer that fails to register is removed from the plugin list.
     */
    private fun registerRendererPlugins() {
        if (!RendererPluginManager.isAvailable()) return

        val failedToLoadList = mutableListOf<RendererPlugin>()

        RendererPluginManager.getRendererList().forEach { rendererPlugin ->
            val isSuccess = Renderers.addRenderer(
                object : RendererInterface {
                    override fun getRendererId(): String = rendererPlugin.id

                    override fun getUniqueIdentifier(): String = rendererPlugin.uniqueIdentifier

                    override fun getRendererName(): String = rendererPlugin.displayName
                    override fun getRendererDescription(): String =
                        "Adreno Only"

                    override fun getRendererEnv(): Lazy<Map<String, String>> =
                        lazy { rendererPlugin.env }

                    override fun getDlopenLibrary(): Lazy<List<String>> =
                        lazy { rendererPlugin.dlopen }

                    override fun getRendererLibrary(): String = rendererPlugin.glName

                    override fun getRendererEGL(): String = rendererPlugin.eglName
                }
            )

            if (!isSuccess) {
                failedToLoadList.add(rendererPlugin)
            }
        }

        if (failedToLoadList.isNotEmpty()) {
            RendererPluginManager.removeRenderer(failedToLoadList)
        }
    }
}
