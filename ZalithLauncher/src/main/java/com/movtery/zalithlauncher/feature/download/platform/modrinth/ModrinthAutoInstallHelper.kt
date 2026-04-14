package com.movtery.zalithlauncher.feature.download.platform.modrinth

import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.enums.DependencyType
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.install.InstallHelper
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModVersionItem
import com.movtery.zalithlauncher.feature.download.item.ModrinthDependencyRef
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.utils.InstalledDependencyUtils
import com.movtery.zalithlauncher.feature.download.utils.VersionTypeUtils
import com.movtery.zalithlauncher.utils.ZHTools
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet

object ModrinthAutoInstallHelper {
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
            ?: throw IllegalArgumentException("Modrinth auto install requires ModVersionItem")

        val targetDir = if (targetPath.isDirectory) {
            targetPath
        } else {
            targetPath.parentFile ?: targetPath
        }

        val installedIndex = InstalledDependencyUtils.buildInstalledIndex(targetDir)

        val installPlan = resolveInstallPlan(
            api = api,
            rootInfoItem = infoItem,
            rootVersion = rootVersion,
            installedIndex = installedIndex
        )

        for ((_, entry) in installPlan) {
            val outputFile = File(targetDir, entry.versionItem.fileName)
            InstallHelper.downloadFile(entry.versionItem, outputFile, progressKey)
        }
    }

    @Throws(Throwable::class)
    private fun resolveInstallPlan(
        api: ApiHandler,
        rootInfoItem: InfoItem,
        rootVersion: ModVersionItem,
        installedIndex: InstalledDependencyUtils.InstalledIndex
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
        installedIndex: InstalledDependencyUtils.InstalledIndex,
        isRoot: Boolean
    ) {
        if (!visited.add(currentInfoItem.projectId)) return

        if (
            isRoot || !InstalledDependencyUtils.isAlreadyInstalled(
                installedIndex,
                currentVersion.fileName,
                currentVersion.fileHash
            )
        ) {
            resolved[currentInfoItem.projectId] = ResolvedInstallEntry(
                infoItem = currentInfoItem,
                versionItem = currentVersion
            )
        }

        val requiredDependencies = currentVersion.dependencyRefs.filter {
            it.dependencyType == DependencyType.REQUIRED
        }

        for (dependencyRef in requiredDependencies) {
            val dependencyInfo = ModrinthCommonUtils.getInfo(
                api,
                currentInfoItem.classify,
                dependencyRef.projectId
            ) ?: continue

            val dependencyVersion = resolveDependencyVersion(
                api = api,
                dependencyRef = dependencyRef,
                dependencyInfo = dependencyInfo,
                parentVersion = currentVersion
            ) ?: continue

            if (
                InstalledDependencyUtils.isAlreadyInstalled(
                    installedIndex,
                    dependencyVersion.fileName,
                    dependencyVersion.fileHash
                )
            ) {
                continue
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
    }

    @Throws(Throwable::class)
    private fun resolveDependencyVersion(
        api: ApiHandler,
        dependencyRef: ModrinthDependencyRef,
        dependencyInfo: InfoItem,
        parentVersion: ModVersionItem
    ): ModVersionItem? {
        dependencyRef.versionId?.let { exactVersionId ->
            val exactVersion = getVersionById(api, exactVersionId)
            if (exactVersion != null && matchesParentMc(exactVersion, parentVersion) && matchesParentLoader(exactVersion, parentVersion)) {
                return exactVersion
            }
        }

        return findBestDependencyVersionFallback(
            api = api,
            dependencyInfo = dependencyInfo,
            parentVersion = parentVersion
        )
    }

    @Throws(Throwable::class)
    private fun getVersionById(api: ApiHandler, versionId: String): ModVersionItem? {
        val versionObject = api.get("version/$versionId", JsonObject::class.java) ?: return null
        val files = versionObject.getAsJsonArray("files") ?: return null
        if (files.size() == 0) return null

        val filesJsonObject = files.get(0).asJsonObject
        val projectId = versionObject.get("project_id").asString

        val infoItem = ModrinthCommonUtils.getInfo(api, Classify.MOD, projectId) ?: return null

        return ModVersionItem(
            infoItem.projectId,
            versionObject.get("name").asString,
            versionObject.get("downloads").asLong,
            ZHTools.getDate(versionObject.get("date_published").asString),
            ModrinthCommonUtils.getMcVersions(versionObject.getAsJsonArray("game_versions")),
            VersionTypeUtils.getVersionType(versionObject.get("version_type").asString),
            filesJsonObject.get("filename").asString,
            ModrinthCommonUtils.getSha1Hash(filesJsonObject),
            filesJsonObject.get("url").asString,
            getModLoaders(versionObject),
            dependencies = emptyList(),
            dependencyRefs = emptyList()
        )
    }

    @Throws(Throwable::class)
    private fun findBestDependencyVersionFallback(
        api: ApiHandler,
        dependencyInfo: InfoItem,
        parentVersion: ModVersionItem
    ): ModVersionItem? {
        val versions = ModrinthModHelper.getModVersions(api, dependencyInfo, false)
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

    private fun getModLoaders(versionObject: JsonObject): List<ModLoader> {
        val loadersArray = versionObject.getAsJsonArray("loaders") ?: return emptyList()
        val modLoaders = ArrayList<ModLoader>()

        loadersArray.forEach {
            val loaderName = it.asString
            ModLoader.values().firstOrNull { loader ->
                loader != ModLoader.ALL && loader.modrinthName == loaderName
            }?.let { loader ->
                modLoaders.add(loader)
            }
        }

        return modLoaders
    }
}