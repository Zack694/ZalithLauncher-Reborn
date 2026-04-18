package com.movtery.zalithlauncher.feature.mod

import android.content.Context
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.platform.curseforge.CurseForgeCommonUtils
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils
import com.movtery.zalithlauncher.feature.log.Logging
import net.kdt.pojavlaunch.utils.GsonJsonUtils
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet

//*TODO REMOVE THIS AFTER CLOWNS READ IT*//
// This class was partially VIBE CODED for your enjoyment however I also wrote 70% of it which then I told ChatGPT to help me
// fix my dirty garbage which in 2026 a lot of tech companies utilize.  So maybe learn how to utilize your sources in 2026
// and possibly you'll get more done FASTER so you can enjoy touching grass instead of staring at a screen!
// Buhbyee!

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

    private data class CandidateProject(
        val project: JsonObject,
        var searchScore: Int,
        var query: String
    )

    private data class CandidateFileMatch(
        val file: JsonObject,
        val score: Int
    )

    private data class VerifiedProjectMatch(
        val project: JsonObject,
        val query: String,
        val searchScore: Int,
        val fileScore: Int,
        val matchedFile: JsonObject?
    )

    private val api = PlatformUtils.createCurseForgeApi()

    private const val SEARCH_PAGE_SIZE = 50
    private const val SEARCH_PAGE_LIMIT = 4
    private const val CURSEFORGE_GAME_ID = 432
    private const val CURSEFORGE_MOD_CLASS_ID = 6

    @Throws(Throwable::class)
    fun checkForUpdate(
        context: Context,
        file: File,
        minecraftVersion: String? = null
    ): UpdateResult {
        val modInfo = ModJarIconHelper.read(context, file)
            ?: return unknownResult(reason = "Installed jar metadata could not be read.")

        val savedEntry = CurseForgeInstalledIndex.findByFile(context, file)
        val projectId = savedEntry?.projectId ?: resolveProjectId(
            file = file,
            modId = modInfo.modId,
            displayName = modInfo.displayName,
            installedVersion = modInfo.version,
            installedLoaders = modInfo.loaders,
            minecraftVersion = minecraftVersion
        )

        if (projectId == null) {
            return unknownResult(
                installedVersion = modInfo.version,
                projectTitle = modInfo.displayName,
                reason = "Could not confidently match this installed mod to a CurseForge project."
            )
        }

        if (savedEntry != null) {
            Logging.i(
                "CurseForgeUpdateChecker",
                "Using saved CurseForge install metadata for ${file.name}, projectId=${savedEntry.projectId}, slug=${savedEntry.slug}"
            )
        }

        val projectResponse = runCatching {
            CurseForgeCommonUtils.searchModFromID(api, projectId)
        }.onFailure {
            Logging.e("CurseForgeUpdateChecker", "Failed to fetch project info for modId=$projectId", it)
        }.getOrNull()

        val projectData = GsonJsonUtils.getJsonObjectSafe(projectResponse, "data")
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
            projectSlug = GsonJsonUtils.getStringSafe(projectData, "slug"),
            projectTitle = GsonJsonUtils.getStringSafe(projectData, "name"),
            reason = "No compatible CurseForge file matched the installed loader and Minecraft version."
        )

        val latestVersionName = GsonJsonUtils.getStringSafe(latestCompatibleFile, "displayName")
            ?: GsonJsonUtils.getStringSafe(latestCompatibleFile, "fileName")

        val latestFileName = GsonJsonUtils.getStringSafe(latestCompatibleFile, "fileName")
        val downloadUrl = CurseForgeCommonUtils.resolveDownloadUrl(api, latestCompatibleFile)
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
                    "latestFileName=$latestFileName, status=$status, slug=${GsonJsonUtils.getStringSafe(projectData, "slug")}, " +
                    "loaders=${modInfo.loaders}"
        )

        return UpdateResult(
            status = status,
            installedVersion = installedVersion,
            latestVersion = latestVersionName,
            projectId = projectId,
            projectSlug = GsonJsonUtils.getStringSafe(projectData, "slug"),
            projectTitle = GsonJsonUtils.getStringSafe(projectData, "name"),
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
        displayName: String?,
        installedVersion: String?,
        installedLoaders: List<ModLoader>,
        minecraftVersion: String?
    ): String? {
        val verifiedMatch = findProjectByVerification(
            modId = modId,
            displayName = displayName,
            fileName = file.name,
            installedVersion = installedVersion,
            installedLoaders = installedLoaders,
            minecraftVersion = minecraftVersion
        )

        if (verifiedMatch != null) {
            Logging.i(
                "CurseForgeUpdateChecker",
                "Using verified search match for ${file.name}, query=${verifiedMatch.query}, searchScore=${verifiedMatch.searchScore}, fileScore=${verifiedMatch.fileScore}, slug=${GsonJsonUtils.getStringSafe(verifiedMatch.project, "slug")}"
            )
            return GsonJsonUtils.getStringSafe(verifiedMatch.project, "id")
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
        val installedVersionToken = extractComparableVersion(installedVersion, installedFileName)
        val latestVersionToken = extractComparableVersion(latestVersion, latestFileName)

        return when {
            latestVersion.isNullOrBlank() && latestFileName.isNullOrBlank() -> UpdateStatus.UNKNOWN
            installedVersionToken.isNotBlank() && latestVersionToken.isNotBlank() &&
                    installedVersionToken == latestVersionToken -> UpdateStatus.UP_TO_DATE
            latestFileName != null && normalizeVersion(installedFileName) == normalizeVersion(latestFileName) ->
                UpdateStatus.UP_TO_DATE
            !latestVersion.isNullOrBlank() || !latestFileName.isNullOrBlank() -> UpdateStatus.UPDATE_AVAILABLE
            else -> UpdateStatus.UNKNOWN
        }
    }

    private fun extractComparableVersion(versionName: String?, fileName: String?): String {
        val source = listOfNotNull(versionName, fileName).joinToString(" ")
        return Regex("""\d+\.\d+\.\d+(?:[-+._][a-z0-9.]+)*""", RegexOption.IGNORE_CASE)
            .findAll(source)
            .map { it.value.lowercase() }
            .maxByOrNull { it.length }
            ?.replace(Regex("""^[^\d]+|[^\da-z+._-]+$""", RegexOption.IGNORE_CASE), "")
            .orEmpty()
    }

    @Throws(Throwable::class)
    private fun findProjectByVerification(
        modId: String?,
        displayName: String?,
        fileName: String?,
        installedVersion: String?,
        installedLoaders: List<ModLoader>,
        minecraftVersion: String?
    ): VerifiedProjectMatch? {
        val candidates = collectProjectCandidates(modId, displayName, fileName, installedLoaders)
        if (candidates.isEmpty()) return null

        var best: VerifiedProjectMatch? = null
        for (candidate in candidates.values) {
            val projectId = GsonJsonUtils.getStringSafe(candidate.project, "id") ?: continue
            val fileMatch = findBestInstalledFileMatch(
                modId = projectId,
                installedVersion = installedVersion,
                installedFileName = fileName,
                installedLoaders = installedLoaders,
                minecraftVersion = minecraftVersion
            )

            val verified = VerifiedProjectMatch(
                project = candidate.project,
                query = candidate.query,
                searchScore = candidate.searchScore,
                fileScore = fileMatch?.score ?: 0,
                matchedFile = fileMatch?.file
            )

            if (best == null || compareVerifiedMatches(verified, best) > 0) {
                best = verified
            }
        }

        best?.let {
            Logging.i(
                "CurseForgeUpdateChecker",
                "Best verified project match for modId=$modId displayName=$displayName fileName=$fileName " +
                        "query=${it.query} searchScore=${it.searchScore} fileScore=${it.fileScore} slug=${GsonJsonUtils.getStringSafe(it.project, "slug")} " +
                        "title=${GsonJsonUtils.getStringSafe(it.project, "name")} matchedFile=${GsonJsonUtils.getStringSafe(it.matchedFile, "fileName")}"
            )
        }

        return best?.takeIf {
            it.fileScore >= 140 ||
                    (it.fileScore >= 100 && it.searchScore >= 1) ||
                    (it.searchScore >= 120)
        }
    }

    private fun compareVerifiedMatches(first: VerifiedProjectMatch, second: VerifiedProjectMatch): Int {
        val firstTotal = first.fileScore * 10 + first.searchScore
        val secondTotal = second.fileScore * 10 + second.searchScore
        return when {
            firstTotal != secondTotal -> firstTotal.compareTo(secondTotal)
            first.fileScore != second.fileScore -> first.fileScore.compareTo(second.fileScore)
            else -> first.searchScore.compareTo(second.searchScore)
        }
    }

    @Throws(Throwable::class)
    private fun collectProjectCandidates(
        modId: String?,
        displayName: String?,
        fileName: String?,
        installedLoaders: List<ModLoader>
    ): LinkedHashMap<String, CandidateProject> {
        val queries = buildQueries(modId, displayName, fileName)
        val candidates = LinkedHashMap<String, CandidateProject>()

        for (query in queries) {
            for (page in 0 until SEARCH_PAGE_LIMIT) {
                val index = page * SEARCH_PAGE_SIZE
                val response = runCatching {
                    api.get(
                        "mods/search",
                        hashMapOf(
                            "gameId" to CURSEFORGE_GAME_ID,
                            "classId" to CURSEFORGE_MOD_CLASS_ID,
                            "searchFilter" to query,
                            "pageSize" to SEARCH_PAGE_SIZE,
                            "index" to index
                        ),
                        JsonObject::class.java
                    )
                }.onFailure {
                    Logging.e(
                        "CurseForgeUpdateChecker",
                        "Failed to search CurseForge project for query=$query index=$index",
                        it
                    )
                }.getOrNull() ?: continue

                val hits = GsonJsonUtils.getJsonArraySafe(response, "data") ?: continue
                if (hits.size() == 0) break

                for (element in hits) {
                    val hit = element.asJsonObject
                    if (!projectSupportsInstalledLoader(hit, installedLoaders)) continue

                    val projectId = GsonJsonUtils.getStringSafe(hit, "id") ?: continue
                    val searchScore = scoreHit(hit, modId, displayName, fileName, query)
                    val discoveryScore = if (searchScore > 0) searchScore else candidateDiscoveryScore(hit, query, modId, displayName)
                    if (discoveryScore <= 0) continue

                    val existing = candidates[projectId]
                    if (existing == null || discoveryScore > existing.searchScore) {
                        candidates[projectId] = CandidateProject(hit, discoveryScore, query)
                    }
                }

                if (hits.size() < SEARCH_PAGE_SIZE) break
            }
        }

        return candidates
    }

    private fun candidateDiscoveryScore(
        hit: JsonObject,
        query: String,
        modId: String?,
        displayName: String?
    ): Int {
        val slugPhrase = normalizePhrase(GsonJsonUtils.getStringSafe(hit, "slug"))
        val titlePhrase = normalizePhrase(GsonJsonUtils.getStringSafe(hit, "name"))
        val normalizedQuery = normalizeSearchText(query)
        val normalizedModId = normalizeSearchText(modId)
        val normalizedDisplayName = normalizeSearchText(displayName)

        return when {
            containsWholeToken(slugPhrase, normalizedQuery) -> 12
            containsWholeToken(titlePhrase, normalizedQuery) -> 11
            containsWholeToken(slugPhrase, normalizedModId) -> 10
            containsWholeToken(titlePhrase, normalizedDisplayName) -> 9
            else -> 0
        }
    }

    @Throws(Throwable::class)
    private fun findBestInstalledFileMatch(
        modId: String,
        installedVersion: String?,
        installedFileName: String?,
        installedLoaders: List<ModLoader>,
        minecraftVersion: String?
    ): CandidateFileMatch? {
        val files = runCatching {
            CurseForgeCommonUtils.getPaginatedData(api, modId)
        }.onFailure {
            Logging.e("CurseForgeUpdateChecker", "Failed to fetch candidate files for modId=$modId", it)
        }.getOrNull() ?: return null

        val strictLoaders = normalizeStrictLoaders(installedLoaders)
        var best: CandidateFileMatch? = null
        for (candidate in files) {
            val score = scoreInstalledFileAgainstCandidate(
                file = candidate,
                installedVersion = installedVersion,
                installedFileName = installedFileName,
                strictLoaders = strictLoaders,
                minecraftVersion = minecraftVersion
            )
            if (score > 0 && (best == null || score > best.score)) {
                best = CandidateFileMatch(candidate, score)
            }
        }
        return best
    }

    private fun scoreInstalledFileAgainstCandidate(
        file: JsonObject,
        installedVersion: String?,
        installedFileName: String?,
        strictLoaders: List<ModLoader>,
        minecraftVersion: String?
    ): Int {
        var score = 0

        val detectedLoaders = detectFileLoaders(file)
        if (strictLoaders.isNotEmpty() && detectedLoaders.isNotEmpty()) {
            if (detectedLoaders.any { it in strictLoaders }) {
                score += 25
            } else {
                return 0
            }
        }

        if (minecraftVersion != null) {
            when {
                matchesMinecraftVersionExact(file, minecraftVersion) -> score += 30
                matchesMinecraftVersionFamily(file, minecraftVersion) -> score += 15
                else -> return 0
            }
        }

        val candidateFileName = GsonJsonUtils.getStringSafe(file, "fileName")
        val candidateVersion = GsonJsonUtils.getStringSafe(file, "displayName")
        val installedBase = normalizeSearchText(installedFileName?.nameWithoutJarSuffix())
        val candidateBase = normalizeSearchText(candidateFileName?.nameWithoutJarSuffix())
        val installedToken = extractComparableVersion(installedVersion, installedFileName)
        val candidateToken = extractComparableVersion(candidateVersion, candidateFileName)

        if (installedBase.isNotBlank() && candidateBase.isNotBlank()) {
            score += when {
                installedBase == candidateBase -> 150
                candidateBase.contains(installedBase) || installedBase.contains(candidateBase) -> 90
                sharedPrefixScore(installedBase, candidateBase) >= 12 -> 60
                else -> 0
            }
        }

        if (installedToken.isNotBlank() && candidateToken.isNotBlank()) {
            score += when {
                installedToken == candidateToken -> 120
                candidateToken.startsWith(installedToken) || installedToken.startsWith(candidateToken) -> 70
                else -> 0
            }
        }

        score += releaseTypeScore(file)
        return score
    }

    private fun sharedPrefixScore(first: String, second: String): Int {
        val max = minOf(first.length, second.length)
        var index = 0
        while (index < max && first[index] == second[index]) {
            index++
        }
        return index
    }

    private fun buildQueries(modId: String?, displayName: String?, fileName: String?): LinkedHashSet<String> {
        val queries = linkedSetOf<String>()

        fun addQuery(value: String?) {
            val cleaned = value?.normalizeEncodedName()?.trim().orEmpty()
            if (cleaned.isBlank()) return
            if (!isUsefulQuery(cleaned)) return
            queries.add(cleaned)
        }

        addQuery(modId)
        addQuery(displayName)

        modId?.let { commonAliases(it).forEach(::addQuery) }
        displayName?.let { commonAliases(it).forEach(::addQuery) }

        fileName
            ?.nameWithoutJarSuffix()
            ?.stripVersionTokens()
            ?.removeLoaderSuffix()
            ?.takeIf { it.isNotBlank() }
            ?.let { cleaned ->
                addQuery(cleaned)
                commonAliases(cleaned).forEach(::addQuery)
            }

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
            .removeSuffix("-quilt")
            .removeSuffix(" fabric")
            .removeSuffix(" forge")
            .removeSuffix(" neoforge")
            .removeSuffix(" quilt")
    }

    private fun scoreHit(
        hit: JsonObject,
        modId: String?,
        displayName: String?,
        fileName: String?,
        query: String
    ): Int {
        val slugCompact = normalizeSearchText(GsonJsonUtils.getStringSafe(hit, "slug"))
        val titleCompact = normalizeSearchText(GsonJsonUtils.getStringSafe(hit, "name"))
        val slugPhrase = normalizePhrase(GsonJsonUtils.getStringSafe(hit, "slug"))
        val titlePhrase = normalizePhrase(GsonJsonUtils.getStringSafe(hit, "name"))
        val normalizedModId = normalizeSearchText(modId)
        val normalizedDisplayName = normalizeSearchText(displayName)
        val fileBase = normalizeSearchText(fileName?.nameWithoutJarSuffix()?.stripVersionTokens())
        val normalizedQuery = normalizeSearchText(query)
        val shortExact = shortIdRequiresExactMatch(modId)

        var score = 0

        if (slugCompact.isNotBlank() && slugCompact == normalizedModId) score = maxOf(score, 120)
        if (slugCompact.isNotBlank() && slugCompact == normalizedDisplayName) score = maxOf(score, 115)
        if (titleCompact.isNotBlank() && titleCompact == normalizedDisplayName) score = maxOf(score, 110)
        if (titleCompact.isNotBlank() && titleCompact == normalizedModId) score = maxOf(score, 105)
        if (slugCompact.isNotBlank() && slugCompact == normalizedQuery) score = maxOf(score, 118)
        if (titleCompact.isNotBlank() && titleCompact == normalizedQuery) score = maxOf(score, 112)

        if (!shortExact) {
            if (slugCompact.isNotBlank() && normalizedModId.isNotBlank() &&
                containsWholeToken(slugPhrase, normalizedModId)
            ) score = maxOf(score, 90)

            if (titleCompact.isNotBlank() && normalizedDisplayName.isNotBlank() &&
                containsWholeToken(titlePhrase, normalizedDisplayName)
            ) score = maxOf(score, 88)

            if (slugCompact.isNotBlank() && normalizedQuery.isNotBlank() &&
                containsWholeToken(slugPhrase, normalizedQuery)
            ) score = maxOf(score, 92)

            if (titleCompact.isNotBlank() && normalizedQuery.isNotBlank() &&
                containsWholeToken(titlePhrase, normalizedQuery)
            ) score = maxOf(score, 89)
        }

        if (!shortExact && slugCompact.isNotBlank() && fileBase.isNotBlank() &&
            (slugCompact.contains(fileBase) || fileBase.contains(slugCompact))
        ) score = maxOf(score, 82)

        if (!shortExact && titleCompact.isNotBlank() && fileBase.isNotBlank() &&
            (titleCompact.contains(fileBase) || fileBase.contains(titleCompact))
        ) score = maxOf(score, 78)

        if (shortExact) {
            if (titleCompact.startsWith(normalizedModId) && titleCompact != normalizedModId) score -= 40
            if (slugCompact.startsWith(normalizedModId) && slugCompact != normalizedModId) score -= 40
        }

        return score.coerceAtLeast(0)
    }

    @Throws(Throwable::class)
    private fun findLatestCompatibleFile(
        modId: String,
        installedLoaders: List<ModLoader>,
        minecraftVersion: String?,
        installedFileName: String? = null
    ): JsonObject? {
        val files = runCatching {
            CurseForgeCommonUtils.getPaginatedData(api, modId)
        }.onFailure {
            Logging.e("CurseForgeUpdateChecker", "Failed to fetch files for modId=$modId", it)
        }.getOrNull() ?: return null

        val strictLoaders = normalizeStrictLoaders(installedLoaders)
        val installedBase = normalizeSearchText(installedFileName?.removeSuffix(".disabled")?.removeSuffix(".jar"))

        val exactMatches = files
            .filter { file ->
                matchesMinecraftVersionExact(file, minecraftVersion) && matchesLoaderStrict(file, strictLoaders)
            }
            .sortedWith(newestCompatibleComparator(installedBase))

        if (exactMatches.isNotEmpty()) {
            Logging.i(
                "CurseForgeUpdateChecker",
                "Found newest exact compatible file for modId=$modId, file=${GsonJsonUtils.getStringSafe(exactMatches.first(), "fileName")}"
            )
            return exactMatches.first()
        }

        val familyMatches = files
            .filter { file ->
                matchesMinecraftVersionFamily(file, minecraftVersion) && matchesLoaderStrict(file, strictLoaders)
            }
            .sortedWith(newestCompatibleComparator(installedBase))

        if (familyMatches.isNotEmpty()) {
            Logging.i(
                "CurseForgeUpdateChecker",
                "Using Minecraft family strict-loader fallback for modId=$modId, file=${GsonJsonUtils.getStringSafe(familyMatches.first(), "fileName")}"
            )
            return familyMatches.first()
        }

        val permissiveLoaders = normalizePreferredLoaders(installedLoaders)

        val exactMcSameFamily = files
            .filter { file ->
                matchesMinecraftVersionExact(file, minecraftVersion) && matchesLoaderFamily(file, permissiveLoaders)
            }
            .sortedWith(newestCompatibleComparator(installedBase))

        if (exactMcSameFamily.isNotEmpty()) {
            Logging.i(
                "CurseForgeUpdateChecker",
                "Using exact Minecraft version same-family fallback for modId=$modId, installedLoaders=$installedLoaders, file=${GsonJsonUtils.getStringSafe(exactMcSameFamily.first(), "fileName")}"
            )
            return exactMcSameFamily.first()
        }

        val familySameLoader = files
            .filter { file ->
                matchesMinecraftVersionFamily(file, minecraftVersion) && matchesLoaderFamily(file, permissiveLoaders)
            }
            .sortedWith(newestCompatibleComparator(installedBase))

        if (familySameLoader.isNotEmpty()) {
            Logging.i(
                "CurseForgeUpdateChecker",
                "Using Minecraft family same-family fallback for modId=$modId, file=${GsonJsonUtils.getStringSafe(familySameLoader.first(), "fileName")}"
            )
            return familySameLoader.first()
        }

        return null
    }

    private fun newestCompatibleComparator(installedBase: String): Comparator<JsonObject> {
        return compareByDescending<JsonObject> { releaseTypeScore(it) }
            .thenByDescending { GsonJsonUtils.getStringSafe(it, "fileDate") ?: "" }
            .thenByDescending { versionRecencyScore(it) }
            .thenByDescending { fileNameSimilarityScore(it, installedBase) }
    }

    private fun versionRecencyScore(file: JsonObject): Int {
        val display = GsonJsonUtils.getStringSafe(file, "displayName")
        val fileName = GsonJsonUtils.getStringSafe(file, "fileName")
        val token = extractComparableVersion(display, fileName)
        return token.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .take(4)
            .fold(0) { acc, part -> (acc * 1000) + (part.toIntOrNull() ?: 0) }
    }

    private fun fileNameSimilarityScore(file: JsonObject, installedBase: String): Int {
        if (installedBase.isBlank()) return 0
        val fileName = normalizeSearchText(GsonJsonUtils.getStringSafe(file, "fileName"))
        return when {
            fileName.isBlank() -> 0
            fileName == installedBase -> 5
            fileName.contains(installedBase) || installedBase.contains(fileName) -> 4
            installedBase.substringBefore("fabric").isNotBlank() &&
                    fileName.contains(installedBase.substringBefore("fabric")) -> 3
            else -> 1
        }
    }

    private fun releaseTypeScore(file: JsonObject): Int {
        return when (GsonJsonUtils.getIntSafe(file, "releaseType", 0)) {
            1 -> 3
            2 -> 2
            3 -> 1
            else -> 0
        }
    }

    private fun matchesMinecraftVersionExact(file: JsonObject, minecraftVersion: String?): Boolean {
        if (minecraftVersion.isNullOrBlank()) return true
        val versionsArray = GsonJsonUtils.getJsonArraySafe(file, "gameVersions") ?: return true
        return versionsArray.any { it.asString == minecraftVersion }
    }

    private fun matchesMinecraftVersionFamily(file: JsonObject, minecraftVersion: String?): Boolean {
        if (minecraftVersion.isNullOrBlank()) return true
        val targetFamily = minecraftFamily(minecraftVersion)
        val versionsArray = GsonJsonUtils.getJsonArraySafe(file, "gameVersions") ?: return true
        return versionsArray.any { entry -> minecraftFamily(entry.asString) == targetFamily }
    }

    private fun minecraftFamily(version: String): String {
        val parts = version.split(".")
        return if (parts.size >= 2) parts[0] + "." + parts[1] else version
    }

    private fun normalizeStrictLoaders(installedLoaders: List<ModLoader>): List<ModLoader> {
        return installedLoaders.filter { it != ModLoader.ALL }.distinct()
    }

    private fun normalizePreferredLoaders(installedLoaders: List<ModLoader>): List<ModLoader> {
        val loaders = installedLoaders.filter { it != ModLoader.ALL }.toMutableList()
        if (loaders.contains(ModLoader.FORGE) && !loaders.contains(ModLoader.NEOFORGE)) loaders.add(ModLoader.NEOFORGE)
        if (loaders.contains(ModLoader.NEOFORGE) && !loaders.contains(ModLoader.FORGE)) loaders.add(ModLoader.FORGE)
        if (loaders.contains(ModLoader.QUILT) && !loaders.contains(ModLoader.FABRIC)) loaders.add(ModLoader.FABRIC)
        if (loaders.contains(ModLoader.FABRIC) && !loaders.contains(ModLoader.QUILT)) loaders.add(ModLoader.QUILT)
        return loaders.distinct()
    }

    private fun matchesLoaderStrict(file: JsonObject, strictLoaders: List<ModLoader>): Boolean {
        if (strictLoaders.isEmpty()) return true
        val detectedLoaders = detectFileLoaders(file)
        if (detectedLoaders.isEmpty()) return true
        return detectedLoaders.any { it in strictLoaders }
    }

    private fun matchesLoaderFamily(file: JsonObject, preferredLoaders: List<ModLoader>): Boolean {
        if (preferredLoaders.isEmpty()) return true
        val detectedLoaders = detectFileLoaders(file)
        if (detectedLoaders.isEmpty()) return true

        val preferredFabricFamily = preferredLoaders.any { it == ModLoader.FABRIC || it == ModLoader.QUILT }
        val preferredForgeFamily = preferredLoaders.any { it == ModLoader.FORGE || it == ModLoader.NEOFORGE }
        val detectedFabricFamily = detectedLoaders.any { it == ModLoader.FABRIC || it == ModLoader.QUILT }
        val detectedForgeFamily = detectedLoaders.any { it == ModLoader.FORGE || it == ModLoader.NEOFORGE }

        return when {
            preferredFabricFamily -> detectedFabricFamily
            preferredForgeFamily -> detectedForgeFamily
            else -> detectedLoaders.any { it in preferredLoaders }
        }
    }

    private fun projectSupportsInstalledLoader(hit: JsonObject, installedLoaders: List<ModLoader>): Boolean {
        val strictLoaders = normalizeStrictLoaders(installedLoaders)
        if (strictLoaders.isEmpty()) return true

        val latestFilesIndexes = GsonJsonUtils.getJsonArraySafe(hit, "latestFilesIndexes") ?: return true
        if (latestFilesIndexes.size() == 0) return true

        val projectLoaders = mutableSetOf<ModLoader>()
        for (indexElement in latestFilesIndexes) {
            val indexObject = indexElement.asJsonObject
            val rawLoader = GsonJsonUtils.getStringSafe(indexObject, "modLoader") ?: continue
            ModLoader.entries.firstOrNull { loader -> loader != ModLoader.ALL && rawLoader == loader.curseforgeId }
                ?.let { projectLoaders.add(it) }
        }

        if (projectLoaders.isEmpty()) return true
        return projectLoaders.any { it in strictLoaders }
    }

    private fun detectFileLoaders(file: JsonObject): Set<ModLoader> {
        val detectedLoaders = mutableSetOf<ModLoader>()
        val gameVersions = GsonJsonUtils.getJsonArraySafe(file, "gameVersions") ?: return emptySet()
        gameVersions.forEach { element ->
            val value = element.asString
            ModLoader.entries.firstOrNull { loader ->
                loader != ModLoader.ALL && value.equals(loader.loaderName, ignoreCase = true)
            }?.let { detectedLoaders.add(it) }
        }
        return detectedLoaders
    }

    private fun containsWholeToken(text: String, token: String): Boolean {
        if (text.isBlank() || token.isBlank()) return false
        val escaped = Regex.escape(token)
        return Regex("(^|[^a-z0-9])$escaped([^a-z0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    private fun normalizePhrase(text: String?): String {
        return text.orEmpty().normalizeEncodedName().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun normalizeSearchText(text: String?): String {
        return text.orEmpty().normalizeEncodedName().trim().lowercase().replace(" ", "").replace("-", "").replace("_", "")
    }

    private fun normalizeVersion(version: String): String {
        return version.normalizeEncodedName().lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private fun shortIdRequiresExactMatch(modId: String?): Boolean {
        val normalized = normalizeSearchText(modId)
        return normalized.length in 3..5
    }

    private fun File.nameWithoutDisabledSuffix(): String = name.removeDisabledSuffix()

    private fun String.removeDisabledSuffix(): String = normalizeEncodedName().removeSuffix(".disabled")

    private fun String.nameWithoutJarSuffix(): String {
        return normalizeEncodedName().removeSuffix(".disabled").removeSuffix(".jar")
    }

    private fun String.stripVersionTokens(): String {
        return this
            .replace(Regex("(?i)\\[[^]]*]"), " ")
            .replace(Regex("(?i)\\+mc\\d+(\\.\\d+)+"), " ")
            .replace(Regex("(?i)\\bmc\\d+(\\.\\d+)+\\b"), " ")
            .replace(Regex("(?i)[-_ ]v?\\d+(\\.\\d+)+([+._-][a-z0-9]+)*"), " ")
            .replace(Regex("[-_+. ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.normalizeEncodedName(): String {
        return this.replace("%2b", "+", ignoreCase = true).replace("%20", " ", ignoreCase = true)
    }

    private fun isUsefulQuery(value: String): Boolean {
        val cleaned = value.trim()
        if (cleaned.length < 3) return false
        val normalized = normalizeSearchText(cleaned)
        if (normalized.length < 3) return false
        return normalized !in setOf("api", "mod", "lib", "core", "forge", "fabric", "quilt", "neoforge")
    }
}
