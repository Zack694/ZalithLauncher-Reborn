package com.movtery.zalithlauncher.feature.download.item

import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.enums.VersionType
import java.util.Date

/**
 * Version information for a mod.
 *
 * @param dependencies Human-friendly dependency information used by the UI.
 * @param dependencyRefs Exact Modrinth dependency references used for installation.
 */
class ModVersionItem(
    projectId: String,
    title: String,
    downloadCount: Long,
    uploadDate: Date,
    mcVersions: List<String>,
    versionType: VersionType,
    fileName: String,
    fileHash: String?,
    fileUrl: String,
    modloaders: List<ModLoader>,
    val dependencies: List<DependenciesInfoItem>,
    val dependencyRefs: List<ModrinthDependencyRef> = emptyList()
) : ModLikeVersionItem(
    projectId,
    title,
    downloadCount,
    uploadDate,
    mcVersions,
    versionType,
    fileName,
    fileHash,
    fileUrl,
    modloaders
) {
    override fun toString(): String {
        return "ModVersionItem(" +
                "projectId='$projectId', " +
                "title='$title', " +
                "downloadCount=$downloadCount, " +
                "uploadDate=$uploadDate, " +
                "mcVersions=$mcVersions, " +
                "versionType=$versionType, " +
                "fileName='$fileName', " +
                "fileHash='$fileHash', " +
                "fileUrl='$fileUrl', " +
                "modloaders=$modloaders, " +
                "dependencies=$dependencies, " +
                "dependencyRefs=$dependencyRefs" +
                ")"
    }
}