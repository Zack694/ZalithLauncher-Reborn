package com.movtery.zalithlauncher.feature.download.platform.curseforge

import com.movtery.zalithlauncher.feature.download.InfoCache
import com.movtery.zalithlauncher.feature.download.enums.DependencyType
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.install.InstallHelper
import com.movtery.zalithlauncher.feature.download.item.DependenciesInfoItem
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModVersionItem
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet

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
        progressKey: String
    ) {
        val rootVersion = version as? ModVersionItem
            ?: throw IllegalArgumentException("CurseForge auto install requires ModVersionItem")

        val installPlan = resolveInstallPlan(api, infoItem, rootVersion)

        for ((_, entry) in installPlan) {
            val targetDir = if (targetPath.isDirectory) {
                targetPath
            } else {
                targetPath.parentFile ?: targetPath
            }

            val outputFile = File(targetDir, entry.versionItem.fileName)
            InstallHelper.downloadFile(entry.versionItem, outputFile, progressKey)
        }
    }

    @Throws(Throwable::class)
    private fun resolveInstallPlan(
        api: ApiHandler,
        rootInfoItem: InfoItem,
        rootVersion: ModVersionItem
    ): LinkedHashMap<String, ResolvedInstallEntry> {
        val resolved = LinkedHashMap<String, ResolvedInstallEntry>()
        val visited = LinkedHashSet<String>()

        resolveRecursive(
            api = api,
            currentInfoItem = rootInfoItem,
            currentVersion = rootVersion,
            resolved = resolved,
            visited = visited
        )

        return resolved
    }

    @Throws(Throwable::class)
    private fun resolveRecursive(
        api: ApiHandler,
        currentInfoItem: InfoItem,
        currentVersion: ModVersionItem,
        resolved: LinkedHashMap<String, ResolvedInstallEntry>,
        visited: LinkedHashSet<String>
    ) {
        if (!visited.add(currentInfoItem.projectId)) return

        resolved[currentInfoItem.projectId] = ResolvedInstallEntry(
            infoItem = currentInfoItem,
            versionItem = currentVersion
        )

        val requiredDependencies = currentVersion.dependencies.filter {
            it.dependencyType == DependencyType.REQUIRED
        }

        for (dependency in requiredDependencies) {
            val dependencyInfo = resolveDependencyInfo(api, dependency) ?: continue
            val dependencyVersion = resolveDependencyVersion(
                api = api,
                dependencyInfo = dependencyInfo,
                parentVersion = currentVersion
            ) ?: continue

            resolveRecursive(
                api = api,
                currentInfoItem = dependencyInfo,
                currentVersion = dependencyVersion,
                resolved = resolved,
                visited = visited
            )
        }
    }

    @Throws(Throwable::class)
    private fun resolveDependencyInfo(
        api: ApiHandler,
        dependency: DependenciesInfoItem
    ): InfoItem? {
        val cached = InfoCache.DependencyInfoCache.get(dependency.projectId)
        if (cached != null) return cached

        val response = CurseForgeCommonUtils.searchModFromID(api, dependency.projectId) ?: return null
        val hit = response.getAsJsonObject("data") ?: return null
        return CurseForgeCommonUtils.getInfoItem(hit, dependency.classify)
    }

    @Throws(Throwable::class)
    private fun resolveDependencyVersion(
        api: ApiHandler,
        dependencyInfo: InfoItem,
        parentVersion: ModVersionItem
    ): ModVersionItem? {
        val versions = CurseForgeModHelper.getModVersions(api, dependencyInfo, false)
            ?.filterIsInstance<ModVersionItem>()
            ?: return null

        return versions
            .filter { matchesParentMc(it, parentVersion) && matchesParentLoader(it, parentVersion) }
            .sortedWith(
                compareByDescending<ModVersionItem> { scoreMcMatch(it, parentVersion) }
                    .thenByDescending { scoreExactLoaderMatch(it, parentVersion) }
                    .thenByDescending { it.uploadDate.time }
            )
            .firstOrNull()
    }

    private fun matchesParentMc(candidate: ModVersionItem, parentVersion: ModVersionItem): Boolean {
        if (candidate.mcVersions.isEmpty() || parentVersion.mcVersions.isEmpty()) return true
        return candidate.mcVersions.any { it in parentVersion.mcVersions }
    }

    private fun matchesParentLoader(candidate: ModVersionItem, parentVersion: ModVersionItem): Boolean {
        val parentLoaders = normalizeLoaders(parentVersion.modloaders)
        val candidateLoaders = normalizeLoaders(candidate.modloaders)

        if (parentLoaders.isEmpty() || candidateLoaders.isEmpty()) return true
        return candidateLoaders.any { it in parentLoaders }
    }

    private fun scoreMcMatch(candidate: ModVersionItem, parentVersion: ModVersionItem): Int {
        if (candidate.mcVersions.isEmpty() || parentVersion.mcVersions.isEmpty()) return 1
        return if (candidate.mcVersions.any { it in parentVersion.mcVersions }) 2 else 0
    }

    private fun scoreExactLoaderMatch(candidate: ModVersionItem, parentVersion: ModVersionItem): Int {
        val parentLoaders = normalizeLoaders(parentVersion.modloaders)
        val candidateLoaders = normalizeLoaders(candidate.modloaders)

        if (parentLoaders.isEmpty() || candidateLoaders.isEmpty()) return 1
        return if (candidateLoaders.any { it in parentLoaders }) 2 else 0
    }

    private fun normalizeLoaders(loaders: List<ModLoader>): List<ModLoader> {
        return loaders.filter { it != ModLoader.ALL }
    }
}