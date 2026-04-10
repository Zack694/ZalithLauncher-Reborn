package com.movtery.zalithlauncher.launch

import android.content.Context
import android.content.SharedPreferences
import net.kdt.pojavlaunch.Logger
import com.movtery.zalithlauncher.feature.version.Version
import java.io.File

object GraphicsBackendHelper {
    private const val PREFS_NAME = "graphics_backend_prefs"
    private const val KEY_FORCE_OPENGL_PREFIX = "force_opengl_"

    private const val VULKAN_OK = 0
    private const val VULKAN_MISSING_PUSH_DESCRIPTOR = 1
    private const val VULKAN_MISSING_FILLMODE_NON_SOLID = 2
    private const val VULKAN_NO_DEVICE = 3
    private const val VULKAN_INSTANCE_FAILED = 4
    private const val VULKAN_DEVICE_ENUM_FAILED = 5
    private const val VULKAN_UNKNOWN_ERROR = 100

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Keep this key broad enough that changing the isolated path does not lose the cached result.
     * The Vulkan capability is a device/driver property, not a per-folder property.
     */
    private fun forceKey(versionName: String): String {
        return KEY_FORCE_OPENGL_PREFIX + versionName
    }

    fun markVulkanFailed(context: Context, versionName: String, gameDir: File) {
        prefs(context).edit().putBoolean(forceKey(versionName), true).apply()
        Logger.appendToLog("GraphicsBackend: cached Vulkan failure for $versionName at ${gameDir.absolutePath}")
    }

    fun clearVulkanFailed(context: Context, versionName: String, gameDir: File) {
        prefs(context).edit().remove(forceKey(versionName)).apply()
        Logger.appendToLog("GraphicsBackend: cleared cached Vulkan failure for $versionName at ${gameDir.absolutePath}")
    }

    /**
     * Native probe must check at minimum:
     * - Vulkan loader / instance creation
     * - physical device availability
     * - VK_KHR_push_descriptor
     * - fillModeNonSolid
     *
     * Return one of the constants above.
     */
    @JvmStatic
    private external fun nativeProbeMinecraft26Vulkan(): Int

    /**
     * Optional native detail string for logging, e.g.
     * "Missing VK_KHR_push_descriptor on Mali-G615 MC2"
     */
    @JvmStatic
    private external fun nativeGetMinecraft26VulkanProbeMessage(): String?

    fun shouldForceOpenGL(
        context: Context,
        minecraftVersion: Version,
        gameDir: File
    ): Boolean {
        val versionName = minecraftVersion.getVersionName()
        if (!is26_2OrNewer(versionName)) {
            Logger.appendToLog("GraphicsBackend: $versionName is below 26.2, no OpenGL force needed")
            return false
        }

        val probeResult = runCatching { nativeProbeMinecraft26Vulkan() }
            .onFailure {
                Logger.appendToLog("GraphicsBackend: Vulkan probe crashed/failed: ${it.message}")
            }
            .getOrDefault(VULKAN_UNKNOWN_ERROR)

        val probeMessage = runCatching { nativeGetMinecraft26VulkanProbeMessage() }.getOrNull()

        return when (probeResult) {
            VULKAN_OK -> {
                Logger.appendToLog(
                    "GraphicsBackend: Vulkan probe passed for $versionName" +
                            (probeMessage?.let { " ($it)" } ?: "")
                )
                clearVulkanFailed(context, versionName, gameDir)
                false
            }

            VULKAN_MISSING_PUSH_DESCRIPTOR -> {
                Logger.appendToLog(
                    "GraphicsBackend: Vulkan probe failed for $versionName - missing VK_KHR_push_descriptor" +
                            (probeMessage?.let { " ($it)" } ?: "")
                )
                markVulkanFailed(context, versionName, gameDir)
                true
            }

            else -> {
                Logger.appendToLog(
                    "GraphicsBackend: Vulkan probe did not qualify for OpenGL fallback for $versionName " +
                            "(code=$probeResult)" +
                            (probeMessage?.let { " ($it)" } ?: "")
                )
                clearVulkanFailed(context, versionName, gameDir)
                false
            }
        }
    }

    fun applyPreferredBackendIfNeeded(
        context: Context,
        minecraftVersion: Version,
        gameDir: File
    ) {
        val versionName = minecraftVersion.getVersionName()
        if (!shouldForceOpenGL(context, minecraftVersion, gameDir)) {
            Logger.appendToLog("GraphicsBackend: no OpenGL force needed for $versionName")
            return
        }

        val optionsFile = File(gameDir, "options.txt")
        Logger.appendToLog("GraphicsBackend: forcing OpenGL for $versionName")
        Logger.appendToLog("GraphicsBackend: target options file = ${optionsFile.absolutePath}")

        val original = if (optionsFile.exists()) {
            runCatching { optionsFile.readText() }.getOrDefault("")
        } else {
            optionsFile.parentFile?.mkdirs()
            ""
        }

        val updated = when {
            original.contains("""preferredGraphicsBackend:"vulkan"""") -> {
                original.replace(
                    """preferredGraphicsBackend:"vulkan"""",
                    """preferredGraphicsBackend:"opengl""""
                )
            }

            Regex("""(?m)^preferredGraphicsBackend:.*$""").containsMatchIn(original) -> {
                original.replace(
                    Regex("""(?m)^preferredGraphicsBackend:.*$"""),
                    """preferredGraphicsBackend:"opengl""""
                )
            }

            original.isBlank() -> {
                """preferredGraphicsBackend:"opengl"""" + "\n"
            }

            original.endsWith("\n") -> {
                original + """preferredGraphicsBackend:"opengl"""" + "\n"
            }

            else -> {
                original + "\n" + """preferredGraphicsBackend:"opengl"""" + "\n"
            }
        }

        val backendLineBefore = original.lineSequence()
            .firstOrNull { it.startsWith("preferredGraphicsBackend:") }
        Logger.appendToLog("GraphicsBackend: before patch = ${backendLineBefore ?: "<missing>"}")

        runCatching {
            optionsFile.writeText(updated)
        }.onSuccess {
            val backendLineAfter = runCatching { optionsFile.readText() }
                .getOrDefault(updated)
                .lineSequence()
                .firstOrNull { it.startsWith("preferredGraphicsBackend:") }

            Logger.appendToLog("GraphicsBackend: after patch = ${backendLineAfter ?: "<missing>"}")
            Logger.appendToLog("GraphicsBackend: options.txt patched successfully")
        }.onFailure { e ->
            Logger.appendToLog("GraphicsBackend: failed to patch options.txt: ${e.message}")
        }
    }

    private fun is26_2OrNewer(versionName: String): Boolean {
        return Regex("""^26\.(2|[3-9]|\d{2,}).*""").matches(versionName)
    }
}