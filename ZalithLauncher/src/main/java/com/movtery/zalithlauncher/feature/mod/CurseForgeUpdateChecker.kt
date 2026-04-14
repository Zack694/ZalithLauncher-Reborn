package com.movtery.zalithlauncher.feature.mod

import android.content.Context
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.platform.curseforge.CurseForgeCommonUtils
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils
import com.movtery.zalithlauncher.feature.log.Logging
import java.io.File
import java.util.LinkedHashSet

object CurseForgeUpdateChecker {
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

    private val api = PlatformUtils.createCurseForgeApi()

    @Throws(Throwable::class)
    fun checkForUpdate(
        context: Context,
        file: File,
        minecraftVersion: String? = null
    ): UpdateResult {
        val modInfo = ModJarIconHelper.read(context, file)
            ?: return unknownResult(reason = "Installed jar metadata could not be read.")

        val projectId = resolveProjectId(
            file = file,
            modId = modInfo.modId,
            displayName = modInfo.displayName
        ) ?: return unknownResult(
            installedVersion = modInfo.version,
            projectTitle = modInfo.displayName,
            reason = "Could not confidently match this installed mod to a CurseForge project."
        )

        val projectResponse = runCatching {
            CurseForgeCommonUtils.searchModFromID(api, projectId)
        }.onFailure {
            Logging.e("CurseForgeUpdateChecker", "Failed to fetch project info for modId=$projectId", it)
        }.getOrNull()

        val projectData = projectResponse?.getAsJsonObject("data")
            ?: return unknownResult(
                installedVersion = modInfo.version,
                projectId = projectId,
                projectTitle = modInfo.displayName,
                reason = "Failed to fetch CurseForge project details."
            )

        val latestCompatibleFile = findLatestCompatibleFile(
            modId = projectId,
            installedLoaders = modInfo.loaders,
            minecraftVersion = minecraftVersion,
            installedFileName = file.nameWithoutDisabledSuffix()
        ) ?: return unknownResult(
            installedVersion = modInfo.version,
            projectId = projectId,
            projectSlug = projectData.get("slug")?.asString,
            projectTitle = projectData.get("name")?.asString,
            reason = "No compatible CurseForge file matched the installed loader and Minecraft version."
        )

        val latestVersionName = latestCompatibleFile.get("displayName")?.asString
            ?: latestCompatibleFile.get("fileName")?.asString

        val latestFileName = latestCompatibleFile.get("fileName")?.asString
        val downloadUrl = latestCompatibleFile.get("downloadUrl")?.asString
        val fileHash = CurseForgeCommonUtils.getSha1FromData(latestCompatibleFile)
        val installedVersion = modInfo.version

        val status = determineStatus(
            installedVersion = installedVersion,
            latestVersion = latestVersionName,
            installedFileName = file.nameWithoutDisabledSuffix(),
            latestFileName = latestFileName?.removeDisabledSuffix()
        )

        Logging.i(
            "CurseForgeUpdateChecker",
            "file=${file.name}, projectId=$projectId, displayName=${modInfo.displayName}, " +
                    "mcVersion=$minecraftVersion, latestVersion=$latestVersionName, " +
                    "latestFileName=$latestFileName, status=$status, slug=${projectData.get("slug")?.asString}"
        )

        return UpdateResult(
            status = status,
            installedVersion = installedVersion,
            latestVersion = latestVersionName,
            projectId = projectId,
            projectSlug = projectData.get("slug")?.asString,
            projectTitle = projectData.get("name")?.asString,
            downloadUrl = downloadUrl,
            fileName = latestFileName,
            fileHash = fileHash,
            reason = null
        )
    }

    @Throws(Throwable::class)
    private fun resolveProjectId(
        file: File,
        modId: String?,
        displayName: String?
    ): String? {
        val fallbackMatch = findProject(modId, displayName, file.name)
        if (fallbackMatch != null) {
            Logging.i(
                "CurseForgeUpdateChecker",
                "Using search match for ${file.name}, query=${fallbackMatch.query}, score=${fallbackMatch.score}"
            )
            return fallbackMatch.project.get("id")?.asString
        }
        return null
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
            !installedVersion.isNullOrBlank() &&
                    !latestVersion.isNullOrBlank() &&
                    normalizeVersion(installedVersion) == normalizeVersion(latestVersion) -> UpdateStatus.UP_TO_DATE
            latestFileName != null &&
                    normalizeVersion(installedFileName) == normalizeVersion(latestFileName) -> UpdateStatus.UP_TO_DATE
            !latestVersion.isNullOrBlank() || !latestFileName.isNullOrBlank() -> UpdateStatus.UPDATE_AVAILABLE
            else -> UpdateStatus.UNKNOWN
        }
    }

    @Throws(Throwable::class)
    private fun findProject(
        modId: String?,
        displayName: String?,
        fileName: String?
    ): ProjectMatch? {
        val queries = buildQueries(modId, displayName, fileName)
        var bestMatch: ProjectMatch? = null

        for (query in queries) {
            val response = runCatching {
                api.get(
                    "mods/search",
                    hashMapOf(
                        "gameId" to 432,
                        "classId" to 6,
                        "searchFilter" to query,
                        "pageSize" to 10
                    ),
                    JsonObject::class.java
                )
            }.onFailure {
                Logging.e("CurseForgeUpdateChecker", "Failed to search CurseForge project for query=$query", it)
            }.getOrNull() ?: continue

            val hits = response.getAsJsonArray("data") ?: continue
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

    private fun buildQueries(
        modId: String?,
        displayName: String?,
        fileName: String?
    ): LinkedHashSet<String> {
        val queries = linkedSetOf<String>()

        if (!modId.isNullOrBlank()) queries.add(modId.normalizeEncodedName())
        if (!displayName.isNullOrBlank()) queries.add(displayName.normalizeEncodedName())

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

    private fun commonAliases(value: String): List<String> {
        return when (normalizeSearchText(value)) {
            "fabricapi" -> listOf("fabric api", "fabric-api", "fabricapi")
            "placeholderapi" -> listOf("placeholder api", "placeholder-api", "placeholderapi")
            "sodiumextra" -> listOf("sodium extra", "sodium-extra", "sodiumextra")
            "yetanotherconfiglib", "yetanotherconfiglibv3" ->
                listOf("yet another config lib", "yet-another-config-lib", "yacl", "yetanotherconfiglib")
            "reesessodiumoptions" ->
                listOf("reese's sodium options", "reeses sodium options", "reeses-sodium-options")
            "moreculling" -> listOf("more culling", "more-culling")
            else -> emptyList()
        }
    }

    private fun String.removeLoaderSuffix(): String {
        return this
            .removeSuffix("-fabric")
            .removeSuffix("-forge")
            .removeSuffix("-neoforge")
            .removeSuffix(" fabric")
            .removeSuffix(" forge")
            .removeSuffix(" neoforge")
    }

    private fun scoreHit(
        hit: JsonObject,
        modId: String?,
        displayName: String?,
        fileName: String?
    ): Int {
        val slug = normalizeSearchText(hit.get("slug")?.asString)
        val title = normalizeSearchText(hit.get("name")?.asString)
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

    private fun findLatestCompatibleFile(
        modId: String,
        installedLoaders: List<ModLoader>,
        minecraftVersion: String?,
        installedFileName: String? = null
    ): JsonObject? {
        val response = runCatching {
            api.get("mods/$modId/files", JsonObject::class.java)
        }.onFailure {
            Logging.e("CurseForgeUpdateChecker", "Failed to fetch files for modId=$modId", it)
        }.getOrNull() ?: return null

        val files = response.getAsJsonArray("data")
            ?.mapNotNull { it?.asJsonObject }
            ?: return null

        val preferredLoaders = normalizePreferredLoaders(installedLoaders)

        val exactMatches = files
            .filter { file ->
                matchesLoader(file, preferredLoaders) &&
                        matchesMinecraftVersionExact(file, minecraftVersion)
            }
            .sortedByDescending { file ->
                file.get("fileDate")?.asString ?: ""
            }

        if (exactMatches.isNotEmpty()) return exactMatches.first()

        val familyMatches = files
            .filter { file ->
                matchesLoader(file, preferredLoaders) &&
                        matchesMinecraftVersionFamily(file, minecraftVersion)
            }

        if (familyMatches.isEmpty()) return null

        val installedBase = normalizeSearchText(installedFileName?.removeSuffix(".disabled")?.removeSuffix(".jar"))

        return familyMatches.maxWithOrNull(
            compareBy<JsonObject>(
                { file ->
                    val name = normalizeSearchText(file.get("fileName")?.asString)
                    when {
                        installedBase.isBlank() -> 0
                        name == installedBase -> 4
                        name.contains(installedBase) || installedBase.contains(name) -> 3
                        else -> 1
                    }
                },
                { file -> file.get("fileDate")?.asString ?: "" }
            )
        )
    }

    private fun matchesMinecraftVersionExact(file: JsonObject, minecraftVersion: String?): Boolean {
        if (minecraftVersion.isNullOrBlank()) return true
        val versionsArray = file.getAsJsonArray("gameVersions") ?: return true
        return versionsArray.any { it.asString == minecraftVersion }
    }

    private fun matchesMinecraftVersionFamily(file: JsonObject, minecraftVersion: String?): Boolean {
        if (minecraftVersion.isNullOrBlank()) return true
        val targetFamily = minecraftFamily(minecraftVersion)
        val versionsArray = file.getAsJsonArray("gameVersions") ?: return true
        return versionsArray.any { entry ->
            minecraftFamily(entry.asString) == targetFamily
        }
    }

    private fun minecraftFamily(version: String): String {
        val parts = version.split(".")
        return if (parts.size >= 2) parts[0] + "." + parts[1] else version
    }

    private fun normalizePreferredLoaders(installedLoaders: List<ModLoader>): List<ModLoader> {
        val loaders = installedLoaders.filter { it != ModLoader.ALL }.toMutableList()
        if (loaders.contains(ModLoader.FORGE) && !loaders.contains(ModLoader.NEOFORGE)) loaders.add(ModLoader.NEOFORGE)
        if (loaders.contains(ModLoader.NEOFORGE) && !loaders.contains(ModLoader.FORGE)) loaders.add(ModLoader.FORGE)
        return loaders.distinct()
    }

    private fun matchesLoader(file: JsonObject, preferredLoaders: List<ModLoader>): Boolean {
        if (preferredLoaders.isEmpty()) return true
        val gameVersions = file.getAsJsonArray("gameVersions") ?: return true
        val detectedLoaders = mutableSetOf<ModLoader>()
        gameVersions.forEach { element ->
            val value = element.asString
            ModLoader.values().firstOrNull { loader ->
                loader != ModLoader.ALL && value.equals(loader.loaderName, ignoreCase = true)
            }?.let { detectedLoaders.add(it) }
        }
        if (detectedLoaders.isEmpty()) return true
        return detectedLoaders.any { it in preferredLoaders }
    }

    private fun normalizeSearchText(text: String?): String {
        return text.orEmpty()
            .normalizeEncodedName()
            .trim()
            .lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
    }

    private fun normalizeVersion(version: String): String {
        return version
            .normalizeEncodedName()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
    }

    private fun File.nameWithoutDisabledSuffix(): String = name.removeDisabledSuffix()

    private fun String.removeDisabledSuffix(): String {
        return normalizeEncodedName().removeSuffix(".disabled")
    }

    private fun String.nameWithoutJarSuffix(): String {
        return normalizeEncodedName()
            .removeSuffix(".disabled")
            .removeSuffix(".jar")
    }

    private fun String.stripVersionTokens(): String {
        return replace(Regex("""[-_]\d+(\.\d+)+.*$"""), "")
    }

    private fun String.normalizeEncodedName(): String {
        return this
            .replace("%2b", "+", ignoreCase = true)
            .replace("%20", " ", ignoreCase = true)
    }
}
