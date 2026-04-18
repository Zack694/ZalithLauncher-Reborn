package com.movtery.zalithlauncher.feature.download.platform.curseforge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.Filters
import com.movtery.zalithlauncher.feature.download.InfoCache
import com.movtery.zalithlauncher.feature.download.enums.Category
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.enums.Platform
import com.movtery.zalithlauncher.feature.download.item.DependenciesInfoItem
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModInfoItem
import com.movtery.zalithlauncher.feature.download.item.ModLikeVersionItem
import com.movtery.zalithlauncher.feature.download.item.ModVersionItem
import com.movtery.zalithlauncher.feature.download.item.SearchResult
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.platform.PlatformNotSupportedException
import com.movtery.zalithlauncher.feature.download.utils.DependencyUtils
import com.movtery.zalithlauncher.feature.download.utils.ModLoaderUtils
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils
import com.movtery.zalithlauncher.feature.download.utils.VersionTypeUtils
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.ZHTools
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import net.kdt.pojavlaunch.utils.GsonJsonUtils

class CurseForgeModHelper {
    companion object {
        @Throws(Throwable::class)
        internal fun modLikeSearch(
            api: ApiHandler,
            lastResult: SearchResult,
            filters: Filters,
            type: Int,
            classify: Classify
        ): SearchResult? {
            if (filters.category != Category.ALL && filters.category.curseforgeID == null) {
                throw PlatformNotSupportedException("The platform does not support the ${filters.category} category!")
            }

            PlatformUtils.searchModLikeWithChinese(filters, type == CurseForgeCommonUtils.CURSEFORGE_MOD_CLASS_ID)?.let {
                filters.name = it
            }

            val params = HashMap<String, Any>()
            CurseForgeCommonUtils.putDefaultParams(params, filters, lastResult.previousCount)
            params["classId"] = type
            filters.modloader?.let { params["modLoaderTypes"] = "[${it.curseforgeId}]" }

            val response = api.get("mods/search", params, JsonObject::class.java) ?: return null
            val dataArray = response.getAsJsonArray("data") ?: return null

            val infoItems: MutableList<InfoItem> = ArrayList()
            for (data in dataArray) {
                val dataElement = data.asJsonObject
                CurseForgeCommonUtils.getInfoItem(dataElement, classify)?.let { item ->
                    infoItems.add(
                        ModInfoItem(
                            item.classify,
                            Platform.CURSEFORGE,
                            item.projectId,
                            item.slug,
                            item.author,
                            item.title,
                            item.description,
                            item.downloadCount,
                            item.uploadDate,
                            item.iconUrl,
                            item.category,
                            getModLoaders(dataElement.getAsJsonArray("latestFilesIndexes"))
                        )
                    )
                }
            }

            return CurseForgeCommonUtils.returnResults(lastResult, infoItems, dataArray, response)
        }

        @Throws(Throwable::class)
        internal fun <T : VersionItem> getModOrModPackVersions(
            api: ApiHandler,
            infoItem: InfoItem,
            force: Boolean,
            cache: InfoCache.CacheBase<MutableList<T>>,
            createVersionItem: (
                projectId: String,
                title: String,
                downloadCount: Long,
                fileDate: String,
                mcVersions: List<String>,
                releaseType: String,
                fileHash: String?,
                downloadUrl: String,
                modloaders: List<ModLoader>,
                fileName: String,
                dependencies: List<DependenciesInfoItem>?
            ) -> T
        ): List<T>? {
            if (!force && cache.containsKey(infoItem.projectId)) {
                return cache.get(infoItem.projectId)
            }

            val allModData = CurseForgeCommonUtils.getPaginatedData(api, infoItem.projectId)
            val versionItems: MutableList<T> = ArrayList()
            val invalidDependencies: MutableSet<String> = HashSet()

            for (modData in allModData) {
                try {
                    val fileName = GsonJsonUtils.getStringSafe(modData, "fileName") ?: continue
                    val downloadUrl = CurseForgeCommonUtils.resolveDownloadUrl(api, modData) ?: continue
                    val mcVersions = CurseForgeCommonUtils.extractMinecraftVersions(modData)
                    val modloaders = getModLoadersFromFileData(modData)
                    val dependencies = readDependencies(api, infoItem, modData, invalidDependencies)

                    versionItems.add(
                        createVersionItem(
                            infoItem.projectId,
                            modData.get("displayName").asString,
                            modData.get("downloadCount").asLong,
                            modData.get("fileDate").asString,
                            mcVersions,
                            modData.get("releaseType").asString,
                            CurseForgeCommonUtils.getSha1FromData(modData),
                            downloadUrl,
                            modloaders,
                            fileName,
                            dependencies.ifEmpty { null }
                        )
                    )
                } catch (e: Exception) {
                    Logging.e("CurseForgeHelper", Tools.printToString(e))
                }
            }

            cache.put(infoItem.projectId, versionItems)
            return versionItems
        }

        private fun readDependencies(
            api: ApiHandler,
            infoItem: InfoItem,
            modData: JsonObject,
            invalidDependencies: MutableSet<String>
        ): List<DependenciesInfoItem> {
            val dependencies = modData.get("dependencies")?.asJsonArray ?: return emptyList()
            val dependencyInfoList: MutableList<DependenciesInfoItem> = ArrayList()

            for (dependency in dependencies) {
                val dependencyObject = dependency.asJsonObject
                val modId = dependencyObject.get("modId").asString
                if (invalidDependencies.contains(modId)) continue

                val relationType = DependencyUtils.getDependencyTypeFromCurseForge(
                    dependencyObject.get("relationType").asString
                )

                if (!InfoCache.DependencyInfoCache.containsKey(modId)) {
                    val response = CurseForgeCommonUtils.searchModFromID(api, modId)
                    val hit = GsonJsonUtils.getJsonObjectSafe(response, "data")

                    if (hit != null) {
                        val dependencyItem = DependenciesInfoItem(
                            infoItem.classify,
                            Platform.CURSEFORGE,
                            modId,
                            hit.get("slug").asString,
                            CurseForgeCommonUtils.getAuthors(hit.get("authors").asJsonArray).toTypedArray(),
                            hit.get("name").asString,
                            hit.get("summary").asString,
                            hit.get("downloadCount").asLong,
                            ZHTools.getDate(hit.get("dateCreated").asString),
                            CurseForgeCommonUtils.getIconUrl(hit),
                            CurseForgeCommonUtils.getAllCategories(hit).toList(),
                            getModLoaders(hit.getAsJsonArray("latestFilesIndexes")),
                            relationType
                        )
                        InfoCache.DependencyInfoCache.put(modId, dependencyItem)
                    } else {
                        invalidDependencies.add(modId)
                        continue
                    }
                }

                val cacheItem = InfoCache.DependencyInfoCache.get(modId)
                if (cacheItem != null) {
                    if (cacheItem.dependencyType != relationType) {
                        dependencyInfoList.add(
                            DependenciesInfoItem(
                                cacheItem.classify,
                                cacheItem.platform,
                                cacheItem.projectId,
                                cacheItem.slug,
                                cacheItem.author,
                                cacheItem.title,
                                cacheItem.description,
                                cacheItem.downloadCount,
                                cacheItem.uploadDate,
                                cacheItem.iconUrl,
                                cacheItem.category,
                                cacheItem.modloaders,
                                relationType
                            )
                        )
                    } else {
                        dependencyInfoList.add(cacheItem)
                    }
                }
            }

            return dependencyInfoList
        }

        @Throws(Throwable::class)
        internal fun getModVersions(api: ApiHandler, infoItem: InfoItem, force: Boolean): List<ModVersionItem>? {
            return getModOrModPackVersions(
                api,
                infoItem,
                force,
                InfoCache.ModVersionCache
            ) { projectId, title, downloadCount, fileDate, mcVersions, releaseType, fileHash, downloadUrl, modloaders, fileName, dependencies ->
                ModVersionItem(
                    projectId,
                    title,
                    downloadCount,
                    ZHTools.getDate(fileDate),
                    mcVersions,
                    VersionTypeUtils.getVersionType(releaseType),
                    fileName,
                    fileHash,
                    downloadUrl,
                    modloaders,
                    dependencies ?: emptyList()
                )
            }
        }

        @Throws(Throwable::class)
        internal fun getModPackVersions(api: ApiHandler, infoItem: InfoItem, force: Boolean): List<ModLikeVersionItem>? {
            return getModOrModPackVersions(
                api,
                infoItem,
                force,
                InfoCache.ModPackVersionCache
            ) { projectId, title, downloadCount, fileDate, mcVersions, releaseType, fileHash, downloadUrl, modloaders, fileName, _ ->
                ModLikeVersionItem(
                    projectId,
                    title,
                    downloadCount,
                    ZHTools.getDate(fileDate),
                    mcVersions,
                    VersionTypeUtils.getVersionType(releaseType),
                    fileName,
                    fileHash,
                    downloadUrl,
                    modloaders
                )
            }
        }

        private fun getModLoadersFromFileData(fileData: JsonObject): List<ModLoader> {
            val modLoaders: MutableSet<ModLoader> = LinkedHashSet()
            CurseForgeCommonUtils.extractMinecraftVersions(fileData).forEach {
                ModLoaderUtils.addModLoaderToList(modLoaders, it)
            }

            val sortableIndexes = GsonJsonUtils.getJsonArraySafe(fileData, "sortableGameVersions")
            sortableIndexes?.forEach { element ->
                val jsonObject = element.asJsonObject
                val loaderName = GsonJsonUtils.getStringSafe(jsonObject, "gameVersionName") ?: return@forEach
                ModLoaderUtils.addModLoaderToList(modLoaders, loaderName)
            }

            return modLoaders.toList()
        }

        private fun getModLoaders(data: JsonArray?): List<ModLoader> {
            if (data == null) return emptyList()

            val modLoaders: MutableSet<ModLoader> = LinkedHashSet()
            for (element in data) {
                val jsonObject = element.asJsonObject
                val modloader = jsonObject.get("modLoader")?.asString ?: continue
                ModLoaderUtils.getModLoaderByCurseForge(modloader)?.let { modLoaders.add(it) }
            }
            return modLoaders.toList()
        }
    }
}
