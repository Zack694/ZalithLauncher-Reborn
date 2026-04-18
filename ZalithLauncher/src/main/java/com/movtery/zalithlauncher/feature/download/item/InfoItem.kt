package com.movtery.zalithlauncher.feature.download.item

import com.movtery.zalithlauncher.feature.download.enums.Category
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.enums.Platform
import java.util.Date

/**
 * Base project info class.
 * @param classify The category/type of this project.
 * @param platform The platform this project belongs to.
 * @param projectId The unique ID of this project.
 * @param slug The slug of this project.
 * @param author The author(s) of this project.
 * @param title The title of this project.
 * @param description The description of this project.
 * @param downloadCount The total download count of this project.
 * @param uploadDate The upload date of this project.
 * @param iconUrl The cover/icon URL of this project.
 * @param category The tags/categories of this project.
 */
open class InfoItem(
    val classify: Classify,
    val platform: Platform,
    val projectId: String,
    val slug: String,
    val author: Array<String>?,
    val title: String,
    val description: String,
    val downloadCount: Long,
    val uploadDate: Date,
    val iconUrl: String?,
    val category: List<Category>
) {
    fun copy() = InfoItem(
        classify, platform, projectId, slug, author, title, description, downloadCount, uploadDate, iconUrl, category
    )

    override fun toString(): String {
        return "InfoItem(" +
                "classify='$classify', " +
                "platform='$platform', " +
                "projectId='$projectId', " +
                "slug='$slug', " +
                "author=${author.contentToString()}, " +
                "title='$title', " +
                "description='$description', " +
                "downloadCount=$downloadCount, " +
                "uploadDate=$uploadDate, " +
                "iconUrl='$iconUrl', " +
                "category=$category" +
                ")"
    }
}