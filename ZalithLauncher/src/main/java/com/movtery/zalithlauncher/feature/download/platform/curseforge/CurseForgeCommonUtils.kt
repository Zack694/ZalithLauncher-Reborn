package com.movtery.zalithlauncher.feature.download.platform.curseforge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.Filters
import com.movtery.zalithlauncher.feature.download.InfoCache
import com.movtery.zalithlauncher.feature.download.enums.Category
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.enums.Platform
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ScreenshotItem
import com.movtery.zalithlauncher.feature.download.item.SearchResult
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.platform.PlatformNotSupportedException
import com.movtery.zalithlauncher.feature.download.utils.CategoryUtils
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils.Companion.safeRun
import com.movtery.zalithlauncher.feature.download.utils.VersionTypeUtils
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.MCVersionRegex.Companion.RELEASE_REGEX
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.stringutils.StringUtilsKt
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import net.kdt.pojavlaunch.utils.GsonJsonUtils
import java.io.IOException
import java.util.TreeSet

class CurseForgeCommonUtils {
    companion object {
        private const val ALGO_SHA_1 = 1
        private const val CURSEFORGE_MINECRAFT_GAME_ID = 432
        private const val CURSEFORGE_SEARCH_COUNT = 20
        private const val CURSEFORGE_PAGINATION_SIZE = 500
        internal const val CURSEFORGE_MODPACK_CLASS_ID = 4471
        internal const val CURSEFORGE_MOD_CLASS_ID = 6

        internal fun putDefaultParams(params: HashMap<String, Any>, filters: Filters, index: Int) {
            params["gameId"] = CURSEFORGE_MINECRAFT_GAME_ID
            params["searchFilter"] = filters.name
            params["sortField"] = filters.sort.curseforge
            params["sortOrder"] = "desc"
            params["pageSize"] = CURSEFORGE_SEARCH_COUNT
            filters.category.curseforgeID?.let {
                if (filters.category != Category.ALL) params["categoryId"] = it
            }
            filters.mcVersion?.takeIf { it.isNotEmpty() }?.let {
                params["gameVersion"] = it
            }
            params["index"] = index
        }

        internal fun getAllCategories(hit: JsonObject): Set<Category> {
            val list: MutableSet<Category> = TreeSet()
            val categories = GsonJsonUtils.getJsonArraySafe(hit, "categories") ?: return list

            for (categoryElement in categories) {
                val id = GsonJsonUtils.getStringSafe(categoryElement.asJsonObject, "id") ?: continue
                CategoryUtils.getCategoryByCurseForge(id)?.let(list::add)
            }
            return list
        }

        internal fun getIconUrl(hit: JsonObject): String? {
            return runCatching {
                hit.getAsJsonObject("logo")?.get("thumbnailUrl")?.asString
            }.getOrNull()
        }

        internal fun getScreenshots(api: ApiHandler, projectId: String): List<ScreenshotItem> {
            val hit = GsonJsonUtils.getJsonObjectSafe(searchModFromID(api, projectId), "data") ?: return emptyList()
            val screenshots = GsonJsonUtils.getJsonArraySafe(hit, "screenshots") ?: return emptyList()

            val screenshotItems = ArrayList<ScreenshotItem>(screenshots.size())
            for (element in screenshots) {
                val screenshotObject = GsonJsonUtils.getJsonObjectSafe(element) ?: continue
                val url = GsonJsonUtils.getStringSafe(screenshotObject, "url") ?: continue
                screenshotItems.add(
                    ScreenshotItem(
                        url,
                        StringUtilsKt.getNonEmptyOrBlank(GsonJsonUtils.getStringSafe(screenshotObject, "title")),
                        StringUtilsKt.getNonEmptyOrBlank(GsonJsonUtils.getStringSafe(screenshotObject, "description")),
                    )
                )
            }
            return screenshotItems
        }

        internal fun getResults(
            api: ApiHandler,
            lastResult: SearchResult,
            filters: Filters,
            classId: Int,
            classify: Classify
        ): SearchResult? {
            if (filters.category != Category.ALL && filters.category.curseforgeID == null) {
                throw PlatformNotSupportedException("The platform does not support the ${filters.category} category!")
            }

            val params = HashMap<String, Any>()
            putDefaultParams(params, filters, lastResult.previousCount)
            params["classId"] = classId

            val response = api.get("mods/search", params, JsonObject::class.java) ?: return null
            val dataArray = GsonJsonUtils.getJsonArraySafe(response, "data") ?: return null

            val infoItems: MutableList<InfoItem> = ArrayList(dataArray.size())
            for (data in dataArray) {
                val dataElement = GsonJsonUtils.getJsonObjectSafe(data) ?: continue
                getInfoItem(dataElement, classify)?.let(infoItems::add)
            }

            return returnResults(lastResult, infoItems, dataArray, response)
        }

        internal fun getInfoItem(dataObject: JsonObject, classify: Classify): InfoItem? {
            val allowModDistribution = dataObject.get("allowModDistribution")
            if (allowModDistribution != null && !allowModDistribution.isJsonNull && !allowModDistribution.asBoolean) {
                Logging.i(
                    "CurseForgeCommonUtils",
                    "Skipping project ${GsonJsonUtils.getStringSafe(dataObject, "name")} because distribution is disabled"
                )
                return null
            }

            val id = GsonJsonUtils.getStringSafe(dataObject, "id") ?: return null
            val slug = GsonJsonUtils.getStringSafe(dataObject, "slug") ?: return null
            val authors = GsonJsonUtils.getJsonArraySafe(dataObject, "authors") ?: return null
            val name = GsonJsonUtils.getStringSafe(dataObject, "name") ?: return null
            val summary = GsonJsonUtils.getStringSafe(dataObject, "summary") ?: ""
            val downloadCount = dataObject.get("downloadCount")?.asLong ?: 0L
            val dateCreated = GsonJsonUtils.getStringSafe(dataObject, "dateCreated") ?: return null

            return InfoItem(
                classify,
                Platform.CURSEFORGE,
                id,
                slug,
                getAuthors(authors).toTypedArray(),
                name,
                summary,
                downloadCount,
                ZHTools.getDate(dateCreated),
                getIconUrl(dataObject),
                getAllCategories(dataObject).toList(),
            )
        }

        @Throws(Throwable::class)
        internal fun getVersions(api: ApiHandler, infoItem: InfoItem, force: Boolean): List<VersionItem>? {
            if (!force && InfoCache.VersionCache.containsKey(infoItem.projectId)) {
                return InfoCache.VersionCache.get(infoItem.projectId)
            }

            val allData = getPaginatedData(api, infoItem.projectId)
            val versionsItem: MutableList<VersionItem> = ArrayList(allData.size)

            for (data in allData) {
                try {
                    val fileName = GsonJsonUtils.getStringSafe(data, "fileName") ?: continue
                    val displayName = GsonJsonUtils.getStringSafe(data, "displayName") ?: fileName
                    val fileDate = GsonJsonUtils.getStringSafe(data, "fileDate") ?: continue
                    val downloadUrl = resolveDownloadUrl(api, data) ?: continue

                    versionsItem.add(
                        VersionItem(
                            infoItem.projectId,
                            displayName,
                            data.get("downloadCount")?.asLong ?: 0L,
                            ZHTools.getDate(fileDate),
                            extractMinecraftVersions(data),
                            VersionTypeUtils.getVersionType(GsonJsonUtils.getStringSafe(data, "releaseType") ?: "1"),
                            fileName,
                            getSha1FromData(data),
                            downloadUrl
                        )
                    )
                } catch (e: Exception) {
                    Logging.e("CurseForgeHelper", Tools.printToString(e))
                }
            }

            versionsItem.sortByDescending { it.uploadDate.time }
            InfoCache.VersionCache.put(infoItem.projectId, versionsItem)
            return versionsItem
        }

        internal fun getAuthors(array: JsonArray): List<String> {
            val authors: MutableList<String> = ArrayList(array.size())
            for (authorElement in array) {
                val authorObject = GsonJsonUtils.getJsonObjectSafe(authorElement) ?: continue
                GsonJsonUtils.getStringSafe(authorObject, "name")?.let(authors::add)
            }
            return authors
        }

        internal fun getSha1FromData(jsonObject: JsonObject): String? {
            val hashes = GsonJsonUtils.getJsonArraySafe(jsonObject, "hashes") ?: return null
            for (jsonElement in hashes) {
                val hashObject = GsonJsonUtils.getJsonObjectSafe(jsonElement) ?: continue
                if (GsonJsonUtils.getIntSafe(hashObject, "algo", -1) == ALGO_SHA_1) {
                    return GsonJsonUtils.getStringSafe(hashObject, "value")
                }
            }
            return null
        }

        internal fun getDownloadUrl(api: ApiHandler, projectID: Long, fileID: Long): String? {
            val response = api.safeRun { get("mods/$projectID/files/$fileID/download-url", JsonObject::class.java) }
            val direct = GsonJsonUtils.getStringSafe(response, "data")
            if (!direct.isNullOrBlank()) return direct

            val fallbackResponse = api.safeRun { get("mods/$projectID/files/$fileID", JsonObject::class.java) }
            val modData = GsonJsonUtils.getJsonObjectSafe(fallbackResponse, "data") ?: return null
            val id = GsonJsonUtils.getIntSafe(modData, "id", -1)
            val fileName = GsonJsonUtils.getStringSafe(modData, "fileName") ?: return null
            if (id <= 0) return null

            return "https://edge.forgecdn.net/files/${id / 1000}/${id % 1000}/$fileName"
        }

        internal fun getDownloadSha1(api: ApiHandler, projectID: Long, fileID: Long): String? {
            val response = api.safeRun { get("mods/$projectID/files/$fileID", JsonObject::class.java) }
            val data = GsonJsonUtils.getJsonObjectSafe(response, "data") ?: return null
            return getSha1FromData(data)
        }

        internal fun searchModFromID(api: ApiHandler, id: String): JsonObject? {
            return api.safeRun { get("mods/$id", JsonObject::class.java) }
        }

        internal fun resolveDownloadUrl(api: ApiHandler, fileData: JsonObject): String? {
            val directUrl = GsonJsonUtils.getStringSafe(fileData, "downloadUrl")
            if (!directUrl.isNullOrBlank()) return directUrl

            val projectId = GsonJsonUtils.getIntSafe(fileData, "modId", -1).toLong()
            val fileId = GsonJsonUtils.getIntSafe(fileData, "id", -1).toLong()
            if (projectId <= 0L || fileId <= 0L) return null

            return getDownloadUrl(api, projectId, fileId)
        }

        internal fun extractMinecraftVersions(fileData: JsonObject): List<String> {
            val versions: MutableSet<String> = TreeSet()
            val rawVersions = GsonJsonUtils.getJsonArraySafe(fileData, "gameVersions") ?: return emptyList()

            for (gameVersionElement in rawVersions) {
                val gameVersion = gameVersionElement.asString
                if (RELEASE_REGEX.matcher(gameVersion).find()) {
                    versions.add(gameVersion)
                }
            }

            return versions.toList()
        }

        @Throws(IOException::class)
        internal fun getPaginatedData(api: ApiHandler, projectId: String): List<JsonObject> {
            val dataList: MutableList<JsonObject> = ArrayList()
            var index = 0

            while (index != -1) {
                val params = HashMap<String, Any>()
                params["index"] = index
                params["pageSize"] = CURSEFORGE_PAGINATION_SIZE

                val response = api.get("mods/$projectId/files", params, JsonObject::class.java)
                val data = GsonJsonUtils.getJsonArraySafe(response, "data") ?: throw IOException("Invalid data!")

                for (i in 0 until data.size()) {
                    val fileInfo = data[i].asJsonObject
                    val isServerPack = fileInfo.get("isServerPack")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
                    if (isServerPack) continue
                    dataList.add(fileInfo)
                }

                index = if (data.size() < CURSEFORGE_PAGINATION_SIZE) -1 else index + CURSEFORGE_PAGINATION_SIZE
            }

            return dataList
        }

        internal fun returnResults(
            lastResult: SearchResult,
            infoItems: List<InfoItem>,
            dataArray: JsonArray,
            response: JsonObject
        ): SearchResult = lastResult.apply {
            this.infoItems.addAll(infoItems)
            this.previousCount += dataArray.size()
            this.totalResultCount = response.getAsJsonObject("pagination").get("totalCount").asInt
            this.isLastPage = dataArray.size() < CURSEFORGE_SEARCH_COUNT
        }
    }
}
