package com.movtery.zalithlauncher.feature.mod

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.log.Logging
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import java.io.File

object ModrinthUpdateChecker {
    enum class UpdateStatus {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        UNKNOWN
    }

    data class UpdateResult(
        val status: UpdateStatus,
        val installedVersion: String?,
        val latestVersion: String?,
        val projectId: String?,
        val projectSlug: String?,
        val projectTitle: String?,
        val downloadUrl: String?,
        val fileName: String?,
        val fileHash: String?,
        val reason: String?
    )

    private data class ProjectMatch(
        val project: JsonObject,
        val score: Int,
        val query: String
    )

    private val api = ApiHandler("https://api.modrinth.com/v2")

    @Throws(Throwable::class)
    fun checkForUpdate(
        context: Context,
        file: File,
        minecraftVersion: String? = null
    ): UpdateResult {
        val modInfo = ModJarIconHelper.read(context, file)
            ?: return unknownResult(reason = "Installed jar metadata could not be read.")

        val modId = modInfo.modId
        val displayName = modInfo.displayName
        if (modId.isNullOrBlank() && displayName.isNullOrBlank()) {
            return unknownResult(
                installedVersion = modInfo.version,
                projectTitle = modInfo.displayName,
                reason = "No mod id or display name was found in the installed jar."
            )
        }

        val projectMatch = findProject(modId, displayName, file.name)
            ?: return unknownResult(
                installedVersion = modInfo.version,
                projectTitle = modInfo.displayName,
                reason = "Could not confidently match this installed mod to a Modrinth project."
            )

        val latestCompatibleVersion = findLatestCompatibleVersion(
            projectId = projectMatch.project.get("project_id").asString,
            installedLoaders = modInfo.loaders,
            minecraftVersion = minecraftVersion
        ) ?: return unknownResult(
            installedVersion = modInfo.version,
            projectId = projectMatch.project.get("project_id").asString,
            projectSlug = projectMatch.project.get("slug")?.asString,
            projectTitle = projectMatch.project.get("title")?.asString,
            reason = "No compatible Modrinth version matched the installed loader and Minecraft version."
        )

        val latestVersionName = latestCompatibleVersion.get("version_number")?.asString
            ?: latestCompatibleVersion.get("name")?.asString

        val fileObject = latestCompatibleVersion.getAsJsonArray("files")
            ?.firstOrNull()
            ?.asJsonObject

        val latestFileName = fileObject?.get("filename")?.asString
        val downloadUrl = fileObject?.get("url")?.asString
        val fileHash = fileObject?.getAsJsonObject("hashes")?.get("sha1")?.asString
        val installedVersion = modInfo.version

        val status = determineStatus(
            installedVersion = installedVersion,
            latestVersion = latestVersionName,
            installedFileName = file.nameWithoutDisabledSuffix(),
            latestFileName = latestFileName?.removeDisabledSuffix()
        )

        Logging.i(
            "ModrinthUpdateChecker",
            "file=${file.name}, modId=$modId, displayName=$displayName, " +
                    "mcVersion=$minecraftVersion, latestVersion=$latestVersionName, " +
                    "latestFileName=$latestFileName, status=$status, project=${projectMatch.project.get("slug")?.asString}, " +
                    "matchScore=${projectMatch.score}, query=${projectMatch.query}"
        )

        return UpdateResult(
            status = status,
            installedVersion = installedVersion,
            latestVersion = latestVersionName,
            projectId = projectMatch.project.get("project_id").asString,
            projectSlug = projectMatch.project.get("slug")?.asString,
            projectTitle = projectMatch.project.get("title")?.asString,
            downloadUrl = downloadUrl,
            fileName = latestFileName,
            fileHash = fileHash,
            reason = null
        )
    }

    private fun unknownResult(
        installedVersion: String? = null,
        projectId: String? = null,
        projectSlug: String? = null,
        projectTitle: String? = null,
        reason: String
    ): UpdateResult {
        return UpdateResult(
            status = UpdateStatus.UNKNOWN,
            installedVersion = installedVersion,
            latestVersion = null,
            projectId = projectId,
            projectSlug = projectSlug,
            projectTitle = projectTitle,
            downloadUrl = null,
            fileName = null,
            fileHash = null,
            reason = reason
        )
    }

    private fun determineStatus(
        installedVersion: String?,
        latestVersion: String?,
        installedFileName: String,
        latestFileName: String?
    ): UpdateStatus {
        return when {
            latestVersion.isNullOrBlank() && latestFileName.isNullOrBlank() -> UpdateStatus.UNKNOWN
            !installedVersion.isNullOrBlank() && !latestVersion.isNullOrBlank() &&
                    normalizeVersion(installedVersion) == normalizeVersion(latestVersion) -> UpdateStatus.UP_TO_DATE
            latestFileName != null &&
                    normalizeVersion(installedFileName) == normalizeVersion(latestFileName) -> UpdateStatus.UP_TO_DATE
            !latestVersion.isNullOrBlank() || !latestFileName.isNullOrBlank() -> UpdateStatus.UPDATE_AVAILABLE
            else -> UpdateStatus.UNKNOWN
        }
    }

    private fun findProject(modId: String?, displayName: String?, fileName: String?): ProjectMatch? {
        val queries = buildQueries(modId, displayName, fileName)
        var bestMatch: ProjectMatch? = null

        for (query in queries) {
            val response = runCatching {
                api.get(
                    "search",
                    hashMapOf(
                        "query" to query,
                        "limit" to 10,
                        "index" to "relevance",
                        "facets" to """[["project_type:mod"]]"""
                    ),
                    JsonObject::class.java
                )
            }.onFailure {
                Logging.e("ModrinthUpdateChecker", "Failed to search Modrinth project for query=$query", it)
            }.getOrNull() ?: continue

            val hits = response.getAsJsonArray("hits") ?: continue
            for (element in hits) {
                val hit = element.asJsonObject
                val score = scoreHit(hit, modId, displayName, fileName)
                if (bestMatch == null || score > bestMatch.score) {
                    bestMatch = ProjectMatch(hit, score, query)
                }
            }
        }

        return bestMatch?.takeIf { it.score >= 60 }
    }

    private fun buildQueries(modId: String?, displayName: String?, fileName: String?): LinkedHashSet<String> {
        val queries = linkedSetOf<String>()
        if (!modId.isNullOrBlank()) queries.add(modId)
        if (!displayName.isNullOrBlank()) queries.add(displayName)

        fileName
            ?.nameWithoutJarSuffix()
            ?.stripVersionTokens()
            ?.takeIf { it.isNotBlank() }
            ?.let { queries.add(it) }

        fileName
            ?.nameWithoutJarSuffix()
            ?.takeIf { it.isNotBlank() }
            ?.let { queries.add(it) }

        return queries
    }

    private fun scoreHit(hit: JsonObject, modId: String?, displayName: String?, fileName: String?): Int {
        val slug = normalizeSearchText(hit.get("slug")?.asString)
        val title = normalizeSearchText(hit.get("title")?.asString)
        val normalizedModId = normalizeSearchText(modId)
        val normalizedDisplayName = normalizeSearchText(displayName)
        val fileBase = normalizeSearchText(fileName?.nameWithoutJarSuffix()?.stripVersionTokens())

        var score = 0

        if (slug.isNotBlank() && slug == normalizedModId) score = maxOf(score, 120)
        if (slug.isNotBlank() && slug == normalizedDisplayName) score = maxOf(score, 115)
        if (title.isNotBlank() && title == normalizedDisplayName) score = maxOf(score, 110)
        if (title.isNotBlank() && title == normalizedModId) score = maxOf(score, 105)

        if (slug.isNotBlank() && normalizedModId.isNotBlank() &&
            (slug.contains(normalizedModId) || normalizedModId.contains(slug))
        ) score = maxOf(score, 90)

        if (title.isNotBlank() && normalizedDisplayName.isNotBlank() &&
            (title.contains(normalizedDisplayName) || normalizedDisplayName.contains(title))
        ) score = maxOf(score, 88)

        if (slug.isNotBlank() && fileBase.isNotBlank() &&
            (slug.contains(fileBase) || fileBase.contains(slug))
        ) score = maxOf(score, 82)

        if (title.isNotBlank() && fileBase.isNotBlank() &&
            (title.contains(fileBase) || fileBase.contains(title))
        ) score = maxOf(score, 78)

        return score
    }

    /*private fun findLatestCompatibleVersion(
        projectId: String,
        installedLoaders: List<ModLoader>,
        minecraftVersion: String?
    ): JsonObject? {
        val response = runCatching {
            api.get("project/$projectId/version", JsonArray::class.java)
        }.onFailure {
            Logging.e("ModrinthUpdateChecker", "Failed to fetch versions for projectId=$projectId", it)
        }.getOrNull() ?: return null

        val preferredLoaders = normalizePreferredLoaders(installedLoaders)

        return response
            .mapNotNull { it?.asJsonObject }
            .filter { version ->
                matchesLoader(version, preferredLoaders) &&
                        matchesMinecraftVersion(version, minecraftVersion)
            }
            .maxByOrNull { version ->
                version.get("date_published")?.asString ?: ""
            }
    }*/
    private fun findLatestCompatibleVersion(
        projectId: String,
        installedLoaders: List<ModLoader>,
        minecraftVersion: String?
    ): JsonObject? {
        val response = runCatching {
            api.get("project/$projectId/version", JsonArray::class.java)
        }.onFailure {
            Logging.e("ModrinthUpdateChecker", "Failed to fetch versions for projectId=$projectId", it)
        }.getOrNull() ?: return null

        val preferredLoaders = normalizePreferredLoaders(installedLoaders)
        val versions = response.mapNotNull { it?.asJsonObject }

        val strictMatch = versions
            .filter { version ->
                matchesLoader(version, preferredLoaders) &&
                        matchesMinecraftVersionExact(version, minecraftVersion)
            }
            .maxByOrNull { version ->
                version.get("date_published")?.asString ?: ""
            }

        if (strictMatch != null) return strictMatch

        val relaxedMatch = versions
            .filter { version ->
                matchesLoader(version, preferredLoaders) &&
                        matchesMinecraftVersionFamily(version, minecraftVersion)
            }
            .maxByOrNull { version ->
                version.get("date_published")?.asString ?: ""
            }

        return relaxedMatch
    }

    private fun matchesMinecraftVersionExact(version: JsonObject, minecraftVersion: String?): Boolean {
        if (minecraftVersion.isNullOrBlank()) return true
        val versionsArray = version.getAsJsonArray("game_versions") ?: return true
        return versionsArray.any { it.asString == minecraftVersion }
    }

    private fun matchesMinecraftVersionFamily(version: JsonObject, minecraftVersion: String?): Boolean {
        if (minecraftVersion.isNullOrBlank()) return true

        val targetFamily = minecraftFamily(minecraftVersion)
        val versionsArray = version.getAsJsonArray("game_versions") ?: return true

        return versionsArray.any { entry ->
            minecraftFamily(entry.asString) == targetFamily
        }
    }

    private fun minecraftFamily(version: String): String {
        val parts = version.split(".")
        return if (parts.size >= 2) {
            parts[0] + "." + parts[1]
        } else {
            version
        }
    }

    private fun normalizePreferredLoaders(installedLoaders: List<ModLoader>): List<ModLoader> {
        val loaders = installedLoaders.filter { it != ModLoader.ALL }.toMutableList()
        if (loaders.contains(ModLoader.FORGE) && !loaders.contains(ModLoader.NEOFORGE)) {
            loaders.add(ModLoader.NEOFORGE)
        }
        if (loaders.contains(ModLoader.NEOFORGE) && !loaders.contains(ModLoader.FORGE)) {
            loaders.add(ModLoader.FORGE)
        }
        return loaders.distinct()
    }

    private fun matchesLoader(version: JsonObject, preferredLoaders: List<ModLoader>): Boolean {
        if (preferredLoaders.isEmpty()) return true

        val loadersArray = version.getAsJsonArray("loaders") ?: return true
        val loaders = loadersArray.mapNotNull { element ->
            val value = element.asString
            ModLoader.values().firstOrNull { it != ModLoader.ALL && it.modrinthName == value }
        }

        return loaders.any { it in preferredLoaders }
    }

    private fun matchesMinecraftVersion(version: JsonObject, minecraftVersion: String?): Boolean {
        if (minecraftVersion.isNullOrBlank()) return true

        val versionsArray = version.getAsJsonArray("game_versions") ?: return true
        return versionsArray.any { it.asString == minecraftVersion }
    }

    private fun normalizeSearchText(text: String?): String {
        return text.orEmpty()
            .trim()
            .lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
    }

    private fun normalizeVersion(version: String): String {
        return version.trim()
            .lowercase()
            .replace(" ", "")
    }

    private fun File.nameWithoutDisabledSuffix(): String {
        return name.removeDisabledSuffix()
    }

    private fun String.removeDisabledSuffix(): String {
        return removeSuffix(".disabled")
    }

    private fun String.nameWithoutJarSuffix(): String {
        return removeSuffix(".disabled").removeSuffix(".jar")
    }

    private fun String.stripVersionTokens(): String {
        return replace(Regex("""[-_]\d+(\.\d+)+.*$"""), "")
    }
}
