package com.movtery.zalithlauncher.feature.download.platform.curseforge

import android.content.Context
import com.movtery.zalithlauncher.feature.download.InfoCache
import com.movtery.zalithlauncher.feature.download.enums.DependencyType
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.install.InstallHelper
import com.movtery.zalithlauncher.feature.download.item.DependenciesInfoItem
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModVersionItem
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.utils.InstalledDependencyUtils
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.CurseForgeInstalledIndex
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

object CurseForgeAutoInstallHelper {
    private data class ResolvedInstallEntry(
        val infoItem: InfoItem,
        val versionItem: ModVersionItem
    )

    @Throws(Throwable::class)
    fun installModWithDependencies(
        api: ApiHandler,
        infoItem: InfoItem,
        version: VersionItem,
        targetPath: File,
        progressKey: String,
        context: Context
    ) {
        val rootVersion = version as? ModVersionItem
            ?: throw IllegalArgumentException("CurseForge auto install requires ModVersionItem")

        val targetDir = resolveTargetDirectory(targetPath)
        var installedIndex = context?.let {
            InstalledDependencyUtils.buildInstalledIndex(it, targetDir)
        }

        val installPlan = resolveInstallPlan(
            api = api,
            rootInfoItem = infoItem,
            rootVersion = rootVersion,
            installedIndex = installedIndex
        )

        Logging.i("CurseForgeAutoInstall", "Resolved ${installPlan.size} CurseForge file(s) for ${infoItem.title}")

        for ((_, entry) in installPlan) {
            val outputFile = File(targetDir, entry.versionItem.fileName)
            InstallHelper.downloadFile(entry.versionItem, outputFile, progressKey) { downloadedFile ->
                if (context != null) {
                    InstalledDependencyUtils.removeOldVersionsOfSameMod(
                        context = context,
                        modsDir = targetDir,
                        downloadedFile = downloadedFile
                    )

                    CurseForgeInstalledIndex.saveInstalled(
                        context,
                        downloadedFile,
                        entry.infoItem,
                        entry.versionItem
                    )

                    installedIndex = InstalledDependencyUtils.buildInstalledIndex(context, targetDir)
                }
            }
        }
    }

    private fun resolveTargetDirectory(targetPath: File): File {
        return if (targetPath.isDirectory) targetPath else targetPath.parentFile ?: targetPath
    }

    @Throws(Throwable::class)
    private fun resolveInstallPlan(
        api: ApiHandler,
        rootInfoItem: InfoItem,
        rootVersion: ModVersionItem,
        installedIndex: InstalledDependencyUtils.InstalledIndex?
    ): LinkedHashMap<String, ResolvedInstallEntry> {
        val resolved = LinkedHashMap<String, ResolvedInstallEntry>()
        val visited = LinkedHashSet<String>()

        resolveRecursive(
            api = api,
            currentInfoItem = rootInfoItem,
            currentVersion = rootVersion,
            resolved = resolved,
            visited = visited,
            installedIndex = installedIndex,
            isRoot = true
        )

        return resolved
    }

    @Throws(Throwable::class)
    private fun resolveRecursive(
        api: ApiHandler,
        currentInfoItem: InfoItem,
        currentVersion: ModVersionItem,
        resolved: LinkedHashMap<String, ResolvedInstallEntry>,
        visited: LinkedHashSet<String>,
        installedIndex: InstalledDependencyUtils.InstalledIndex?,
        isRoot: Boolean
    ) {
        if (!visited.add(currentInfoItem.projectId)) return

        val alreadyInstalled = installedIndex != null &&
                InstalledDependencyUtils.isAlreadyInstalled(
                    installedIndex,
                    currentVersion.fileName,
                    currentVersion.fileHash
                )

        if (isRoot || !alreadyInstalled) {
            resolved[currentInfoItem.projectId] = ResolvedInstallEntry(currentInfoItem, currentVersion)
        }

        currentVersion.dependencies
            .asSequence()
            .filter { it.dependencyType == DependencyType.REQUIRED }
            .forEach { dependency ->
                resolveDependency(api, dependency, currentVersion, resolved, visited, installedIndex)
            }
    }

    @Throws(Throwable::class)
    private fun resolveDependency(
        api: ApiHandler,
        dependency: DependenciesInfoItem,
        parentVersion: ModVersionItem,
        resolved: LinkedHashMap<String, ResolvedInstallEntry>,
        visited: LinkedHashSet<String>,
        installedIndex: InstalledDependencyUtils.InstalledIndex?
    ) {
        val dependencyInfo = resolveDependencyInfo(api, dependency) ?: return
        val dependencyVersion = resolveDependencyVersion(api, dependencyInfo, parentVersion) ?: return

        val alreadyInstalled = installedIndex != null &&
                InstalledDependencyUtils.isAlreadyInstalled(
                    installedIndex,
                    dependencyVersion.fileName,
                    dependencyVersion.fileHash
                )

        if (alreadyInstalled) {
            Logging.i("CurseForgeAutoInstall", "Skipping already installed dependency ${dependencyInfo.title}")
            return
        }

        resolveRecursive(
            api = api,
            currentInfoItem = dependencyInfo,
            currentVersion = dependencyVersion,
            resolved = resolved,
            visited = visited,
            installedIndex = installedIndex,
            isRoot = false
        )
    }

    @Throws(Throwable::class)
    private fun resolveDependencyInfo(api: ApiHandler, dependency: DependenciesInfoItem): InfoItem? {
        InfoCache.DependencyInfoCache.get(dependency.projectId)?.let { return it }

        val response = CurseForgeCommonUtils.searchModFromID(api, dependency.projectId) ?: return null
        val hit = response.getAsJsonObject("data") ?: return null
        return CurseForgeCommonUtils.getInfoItem(hit, dependency.classify)
    }

    private fun resolveDependencyVersion(
        api: ApiHandler,
        dependencyInfo: InfoItem,
        parentVersion: ModVersionItem
    ): ModVersionItem? {
        val versions = CurseForgeModHelper.getModVersions(api, dependencyInfo, false)
            ?.filterIsInstance<ModVersionItem>()
            ?.filter { it.fileName.isNotBlank() }
            ?: return null

        return versions
            .sortedWith(
                compareByDescending<ModVersionItem> { scoreCompatibility(it, parentVersion) }
                    .thenByDescending { scoreReleaseType(it) }
                    .thenByDescending { it.uploadDate.time }
            )
            .firstOrNull { scoreCompatibility(it, parentVersion) > 0 }
    }

    private fun scoreCompatibility(candidate: ModVersionItem, parentVersion: ModVersionItem): Int {
        var score = 0

        val mcScore = scoreMcMatch(candidate, parentVersion)
        if (mcScore == 0) return 0
        score += mcScore * 100

        val loaderScore = scoreLoaderMatch(candidate, parentVersion)
        if (loaderScore == 0) return 0
        score += loaderScore * 10

        return score
    }

    private fun scoreMcMatch(candidate: ModVersionItem, parentVersion: ModVersionItem): Int {
        if (candidate.mcVersions.isEmpty() || parentVersion.mcVersions.isEmpty()) return 1
        return if (candidate.mcVersions.any { it in parentVersion.mcVersions }) 2 else 0
    }

    private fun scoreLoaderMatch(candidate: ModVersionItem, parentVersion: ModVersionItem): Int {
        val parentLoaders = normalizeLoaders(parentVersion.modloaders)
        val candidateLoaders = normalizeLoaders(candidate.modloaders)

        if (parentLoaders.isEmpty() || candidateLoaders.isEmpty()) return 1
        return if (candidateLoaders.any { it in parentLoaders }) 2 else 0
    }

    private fun scoreReleaseType(candidate: ModVersionItem): Int {
        return when (candidate.versionType.name.uppercase(Locale.ROOT)) {
            "RELEASE", "STABLE" -> 3
            "BETA" -> 2
            "ALPHA" -> 1
            else -> 0
        }
    }

    private fun normalizeLoaders(loaders: List<ModLoader>): List<ModLoader> {
        return loaders.filter { it != ModLoader.ALL }
    }
}