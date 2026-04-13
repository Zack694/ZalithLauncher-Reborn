package com.movtery.zalithlauncher.launch

import android.content.Context
import androidx.collection.ArrayMap
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.feature.accounts.AccountUtils
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome.Companion.getLibrariesHome
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.path.LibPath
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.AWTCanvasView
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.Runtime
import net.kdt.pojavlaunch.utils.JSONUtils
import net.kdt.pojavlaunch.value.MinecraftAccount
import org.jackhuang.hmcl.util.versioning.VersionNumber
import java.io.File

class LaunchArgs(
    private val context: Context,
    private val account: MinecraftAccount,
    private val gameDirPath: File,
    private val minecraftVersion: Version,
    private val versionInfo: JMinecraftVersionList.Version,
    private val versionFileName: String,
    private val runtime: Runtime,
    private val launchClassPath: String
) {
    companion object {
        @JvmStatic
        fun getCacioJavaArgs(isJava8: Boolean): List<String> {
            val args = mutableListOf<String>()

            args.add("-Djava.awt.headless=false")
            args.add("-Dcacio.managed.screensize=${AWTCanvasView.AWT_CANVAS_WIDTH}x${AWTCanvasView.AWT_CANVAS_HEIGHT}")
            args.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
            args.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
            args.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")

            if (isJava8) {
                args.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
                args.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
            } else {
                args.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
                args.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
                args.add("-javaagent:${LibPath.CACIO_17_AGENT.absolutePath}")
                args.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED")
                args.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED")
                args.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED")
                args.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED")
                args.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED")
                args.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED")
                args.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED")
                args.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED")
                args.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED")
                args.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED")
                args.add("--add-opens=java.base/java.util=ALL-UNNAMED")
                args.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
                args.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED")
                args.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED")
                args.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
                args.add("--add-opens=java.base/java.net=ALL-UNNAMED")
            }

            val cacioClassPath = StringBuilder()
            cacioClassPath.append("-Xbootclasspath/").append(if (isJava8) "p" else "a")

            val cacioFiles = if (isJava8) LibPath.CACIO_8 else LibPath.CACIO_17
            cacioFiles.listFiles()?.forEach { file ->
                if (file.name.endsWith(".jar")) {
                    cacioClassPath.append(":").append(file.absolutePath)
                }
            }

            args.add(cacioClassPath.toString())
            return args
        }
    }

    fun getAllArgs(): List<String> {
        val args = mutableListOf<String>()
        val lwjglComponent = Tools.resolveLWJGLComponentForLaunch(minecraftVersion, versionInfo)

        args.addAll(getLauncherJvmArgs(lwjglComponent))
        args.addAll(getVersionJvmArgs(lwjglComponent))
        args.add("-cp")
        args.add("${Tools.getLWJGLClassPathForLaunch(minecraftVersion, versionInfo)}:$launchClassPath")

        if (runtime.javaVersion > 8) {
            args.add("--add-exports")
            val pkg = versionInfo.mainClass.substring(0, versionInfo.mainClass.lastIndexOf('.'))
            args.add("$pkg/$pkg=ALL-UNNAMED")
        }

        args.add(versionInfo.mainClass)
        args.addAll(getMinecraftClientArgs())
        return args
    }

    private fun getLauncherJvmArgs(lwjglComponent: String): List<String> {
        val args = mutableListOf<String>()

        if (AccountUtils.isOtherLoginAccount(account)) {
            if (account.otherBaseUrl.contains("auth.mc-user.com")) {
                args.add("-javaagent:${LibPath.NIDE_8_AUTH.absolutePath}=${account.otherBaseUrl.replace("https://auth.mc-user.com:233/", "")}")
                args.add("-Dnide8auth.client=true")
            } else {
                args.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=${account.otherBaseUrl}")
            }
        }

        args.addAll(getCacioJavaArgs(runtime.javaVersion == 8))

        val isLegacyLog4j =
            VersionNumber.compare(VersionNumber.asVersion(versionInfo.id ?: "0.0").canonical, "1.12") < 0
        val configFilePath = if (isLegacyLog4j) LibPath.LOG4J_XML_1_7 else LibPath.LOG4J_XML_1_12
        args.add("-Dlog4j.configurationFile=${configFilePath.absolutePath}")

        val lwjglNativeDir = getLwjglNativeDir(lwjglComponent)
        val versionSpecificNativesDir = getVersionSpecificNativesDir()

        if (lwjglNativeDir.exists()) {
            args.add("-Dorg.lwjgl.librarypath=${lwjglNativeDir.absolutePath}")
        }

        if (versionSpecificNativesDir.exists()) {
            args.add("-Djna.boot.library.path=${versionSpecificNativesDir.absolutePath}")
        }

        args.add("-Djava.library.path=${buildNativeLibraryPath(lwjglComponent)}")
        return args
    }

    private fun getVersionJvmArgs(lwjglComponent: String): Array<String> {
        val resolvedVersionInfo = Tools.getVersionInfo(minecraftVersion, true)

        val varArgMap: MutableMap<String, String?> = ArrayMap()
        varArgMap["classpath_separator"] = ":"
        varArgMap["library_directory"] = getLibrariesHome()
        varArgMap["version_name"] = resolvedVersionInfo.id
        varArgMap["natives_directory"] = buildNativeLibraryPath(lwjglComponent)
        varArgMap["launcher_name"] = InfoDistributor.LAUNCHER_NAME
        varArgMap["launcher_version"] = ZHTools.getVersionName()
        varArgMap["classpath"] = "${Tools.getLWJGLClassPathForLaunch(minecraftVersion, versionInfo)}:$launchClassPath"

        val jvmArgs = mutableListOf<String>()
        resolvedVersionInfo.arguments?.jvm?.forEach { arg ->
            processJvmArg(arg, lwjglComponent)?.let(jvmArgs::add)
        }

        return JSONUtils.insertJSONValueList(jvmArgs.toTypedArray(), varArgMap)
    }

    private fun processJvmArg(arg: Any, lwjglComponent: String): String? = (arg as? String)?.let { argument ->
        when {
            argument == "\${classpath}" -> null
            argument.startsWith("-cp") -> null

            argument.startsWith("-Djava.library.path=") -> {
                "-Djava.library.path=\${natives_directory}"
            }

            argument.startsWith("-Dorg.lwjgl.librarypath=") ||
                    argument.startsWith("-Djna.boot.library.path=") -> {
                null
            }

            argument.startsWith("-DignoreList=") -> {
                "$argument,$versionFileName.jar"
            }

            argument.startsWith("-Djna.tmpdir=") ||
                    argument.startsWith("-Dorg.lwjgl.system.SharedLibraryExtractPath=") ||
                    argument.startsWith("-Dio.netty.native.workdir=") -> {
                val key = argument.substringBefore("=")
                "$key=${PathManager.DIR_CACHE.absolutePath}"
            }

            else -> argument
        }
    }

    private fun getLwjglNativeDir(lwjglComponent: String): File {
        return File(PathManager.DIR_FILE, "$lwjglComponent/natives/arm64-v8a")
    }

    private fun getVersionSpecificNativesDir(): File {
        return File(PathManager.DIR_CACHE, "natives/${minecraftVersion.getVersionName()}")
    }

    private fun buildNativeLibraryPath(lwjglComponent: String): String {
        val nativePathParts = mutableListOf<String>()

        val lwjglNativeDir = getLwjglNativeDir(lwjglComponent)
        if (lwjglNativeDir.exists()) {
            nativePathParts.add(lwjglNativeDir.absolutePath)
        }

        val versionSpecificNativesDir = getVersionSpecificNativesDir()
        if (versionSpecificNativesDir.exists()) {
            nativePathParts.add(versionSpecificNativesDir.absolutePath)
        }

        nativePathParts.add(PathManager.DIR_NATIVE_LIB)
        return nativePathParts.joinToString(":")
    }

    private fun getMinecraftClientArgs(): Array<String> {
        val verArgMap: MutableMap<String, String> = ArrayMap()
        verArgMap["auth_session"] = account.accessToken
        verArgMap["auth_access_token"] = account.accessToken
        verArgMap["auth_player_name"] = account.username
        verArgMap["auth_uuid"] = account.profileId.replace("-", "")
        verArgMap["auth_xuid"] = account.xuid
        verArgMap["assets_root"] = ProfilePathHome.getAssetsHome()
        verArgMap["assets_index_name"] = versionInfo.assets
        verArgMap["game_assets"] = ProfilePathHome.getAssetsHome()
        verArgMap["game_directory"] = gameDirPath.absolutePath
        verArgMap["user_properties"] = "{}"
        verArgMap["user_type"] = "msa"
        verArgMap["version_name"] = versionInfo.inheritsFrom ?: versionInfo.id

        setLauncherInfo(verArgMap)

        val gameArgs = mutableListOf<String>()
        versionInfo.arguments?.game?.forEach { arg ->
            if (arg is String) {
                gameArgs.add(arg)
            }
        }

        return JSONUtils.insertJSONValueList(
            splitAndFilterEmpty(
                versionInfo.minecraftArguments ?: Tools.fromStringArray(gameArgs.toTypedArray())
            ),
            verArgMap
        )
    }

    private fun setLauncherInfo(verArgMap: MutableMap<String, String>) {
        verArgMap["launcher_name"] = InfoDistributor.LAUNCHER_NAME
        verArgMap["launcher_version"] = ZHTools.getVersionName()
        verArgMap["version_type"] = minecraftVersion.getCustomInfo()
            .takeIf { it.isNotBlank() }
            ?: versionInfo.type
    }

    private fun splitAndFilterEmpty(arg: String): Array<String> {
        return arg.split(" ")
            .filter { it.isNotEmpty() }
            .toTypedArray()
    }
}
