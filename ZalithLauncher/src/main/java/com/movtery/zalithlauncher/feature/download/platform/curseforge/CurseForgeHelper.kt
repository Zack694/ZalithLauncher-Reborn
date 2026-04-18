package com.movtery.zalithlauncher.feature.download.platform.curseforge

import android.content.Context
import android.widget.Toast
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.context.ContextExecutor
import com.movtery.zalithlauncher.feature.download.Filters
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.install.InstallHelper
import com.movtery.zalithlauncher.feature.download.install.UnpackWorldZipHelper
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModLoaderWrapper
import com.movtery.zalithlauncher.feature.download.item.ScreenshotItem
import com.movtery.zalithlauncher.feature.download.item.SearchResult
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.platform.AbstractPlatformHelper
import com.movtery.zalithlauncher.feature.download.platform.curseforge.CurseForgeCommonUtils.Companion.CURSEFORGE_MODPACK_CLASS_ID
import com.movtery.zalithlauncher.feature.download.platform.curseforge.CurseForgeCommonUtils.Companion.CURSEFORGE_MOD_CLASS_ID
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils
import com.movtery.zalithlauncher.task.Task
import java.io.File

class CurseForgeHelper : AbstractPlatformHelper(PlatformUtils.createCurseForgeApi()) {
    override fun copy(): AbstractPlatformHelper = CurseForgeHelper()

    override fun getWebUrl(infoItem: InfoItem): String? {
        val section = when (infoItem.classify) {
            Classify.ALL -> return null
            Classify.MOD -> "mc-mods"
            Classify.MODPACK -> "modpacks"
            Classify.RESOURCE_PACK -> "texture-packs"
            Classify.WORLD -> "worlds"
            Classify.SHADER_PACK -> "shaders"
        }
        return "https://www.curseforge.com/minecraft/$section/${infoItem.slug}"
    }

    override fun getScreenshots(projectId: String): List<ScreenshotItem> {
        return CurseForgeCommonUtils.getScreenshots(api, projectId)
    }

    @Throws(Throwable::class)
    override fun searchMod(filters: Filters, lastResult: SearchResult): SearchResult? {
        return CurseForgeModHelper.modLikeSearch(api, lastResult, filters, CURSEFORGE_MOD_CLASS_ID, Classify.MOD)
    }

    @Throws(Throwable::class)
    override fun searchModPack(filters: Filters, lastResult: SearchResult): SearchResult? {
        return CurseForgeModHelper.modLikeSearch(api, lastResult, filters, CURSEFORGE_MODPACK_CLASS_ID, Classify.MODPACK)
    }

    @Throws(Throwable::class)
    override fun searchResourcePack(filters: Filters, lastResult: SearchResult): SearchResult? {
        return CurseForgeCommonUtils.getResults(api, lastResult, filters, 12, Classify.RESOURCE_PACK)
    }

    @Throws(Throwable::class)
    override fun searchWorld(filters: Filters, lastResult: SearchResult): SearchResult? {
        return CurseForgeCommonUtils.getResults(api, lastResult, filters, 17, Classify.WORLD)
    }

    @Throws(Throwable::class)
    override fun searchShaderPack(filters: Filters, lastResult: SearchResult): SearchResult? {
        return CurseForgeCommonUtils.getResults(api, lastResult, filters, 6552, Classify.SHADER_PACK)
    }

    @Throws(Throwable::class)
    override fun getModVersions(infoItem: InfoItem, force: Boolean): List<VersionItem>? {
        return CurseForgeModHelper.getModVersions(api, infoItem, force)
    }

    @Throws(Throwable::class)
    override fun getModPackVersions(infoItem: InfoItem, force: Boolean): List<VersionItem>? {
        return CurseForgeModHelper.getModPackVersions(api, infoItem, force)
    }

    @Throws(Throwable::class)
    override fun getResourcePackVersions(infoItem: InfoItem, force: Boolean): List<VersionItem>? {
        return CurseForgeCommonUtils.getVersions(api, infoItem, force)
    }

    @Throws(Throwable::class)
    override fun getWorldVersions(infoItem: InfoItem, force: Boolean): List<VersionItem>? {
        return CurseForgeCommonUtils.getVersions(api, infoItem, force)
    }

    @Throws(Throwable::class)
    override fun getShaderPackVersions(infoItem: InfoItem, force: Boolean): List<VersionItem>? {
        return CurseForgeCommonUtils.getVersions(api, infoItem, force)
    }

    @Throws(Throwable::class)
    override fun installMod(
        context: Context,
        infoItem: InfoItem,
        version: VersionItem,
        targetPath: File,
        progressKey: String
    ) {
        Task.runTask {
            CurseForgeAutoInstallHelper.installModWithDependencies(
                api,
                infoItem,
                version,
                targetPath,
                progressKey,
                context
            )
        }.execute()
    }

    @Throws(Throwable::class)
    override fun installModPack(version: VersionItem, customName: String): ModLoaderWrapper? {
        return CurseForgeModPackInstallHelper.startInstall(api, version, customName)
    }

    @Throws(Throwable::class)
    override fun installResourcePack(infoItem: InfoItem, version: VersionItem, targetPath: File, progressKey: String) {
        InstallHelper.downloadFile(version, targetPath, progressKey)
    }

    @Throws(Throwable::class)
    override fun installWorld(infoItem: InfoItem, version: VersionItem, targetPath: File, progressKey: String) {
        InstallHelper.downloadFile(version, targetPath, progressKey) { file ->
            targetPath.parentFile?.let { parentDir ->
                runCatching {
                    UnpackWorldZipHelper.unpackFile(file, parentDir)
                }.getOrElse {
                    ContextExecutor.showToast(R.string.download_install_unpack_world_error, Toast.LENGTH_SHORT)
                }
            }
        }
    }

    @Throws(Throwable::class)
    override fun installShaderPack(infoItem: InfoItem, version: VersionItem, targetPath: File, progressKey: String) {
        InstallHelper.downloadFile(version, targetPath, progressKey)
    }
}