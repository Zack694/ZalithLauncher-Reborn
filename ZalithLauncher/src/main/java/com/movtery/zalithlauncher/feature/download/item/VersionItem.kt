package com.movtery.zalithlauncher.feature.download.item

import com.movtery.zalithlauncher.feature.download.enums.VersionType
import java.util.Date

/**
 * Generic version information.
 *
 * @param projectId Unique project ID for this version.
 * @param title Version title.
 * @param downloadCount Total number of downloads for this version.
 * @param uploadDate Upload date.
 * @param mcVersions Supported Minecraft versions.
 * @param versionType Release state for this version.
 * @param fileName File name for this version.
 * @param fileHash File hash for this version.
 * @param fileUrl Download URL for this version.
 */
open class VersionItem(
    val projectId: String,
    val title: String,
    val downloadCount: Long,
    val uploadDate: Date,
    val mcVersions: List<String>,
    val versionType: VersionType,
    val fileName: String,
    val fileHash: String?,
    val fileUrl: String
) {
    override fun toString(): String {
        return "VersionItem(" +
                "projectId='$projectId', " +
                "title='$title', " +
                "downloadCount=$downloadCount, " +
                "uploadDate=$uploadDate, " +
                "mcVersions=$mcVersions, " +
                "versionType=$versionType, " +
                "fileName='$fileName'" +
                "fileHash='$fileHash'" +
                "fileUrl='$fileUrl'" +
                ")"
    }
}