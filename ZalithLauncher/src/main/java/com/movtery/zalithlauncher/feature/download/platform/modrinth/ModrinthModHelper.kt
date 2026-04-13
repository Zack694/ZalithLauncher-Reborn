package com.movtery.zalithlauncher.feature.download.platform.modrinth

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
import com.movtery.zalithlauncher.feature.download.item.ModrinthDependencyRef
import com.movtery.zalithlauncher.feature.download.item.SearchResult
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.platform.PlatformNotSupportedException
import com.movtery.zalithlauncher.feature.download.utils.DependencyUtils
import com.movtery.zalithlauncher.feature.download.utils.ModLoaderUtils
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils
import com.movtery.zalithlauncher.feature.download.utils.VersionTypeUtils
import com.movtery.zalithlauncher.utils.ZHTools
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler

class ModrinthModHelper {
    companion object {
        @Throws(Throwable::class)
        internal fun modLikeSearch(
            api: ApiHandler,
            lastResult: SearchResult,
            filters: Filters,
            type: String,
            classify: Classify
        ): SearchResult? {
            if (filters.category != Category.ALL && filters.category.modrinthName == null) {
                throw PlatformNotSupportedException("The platform does not support the ${filters.category} category!")
            }

            PlatformUtils.searchModLikeWithChinese(filters, type == "mod")?.let {
                filters.name = it
            }

            val response = api.get(
                "search",
                ModrinthCommonUtils.getParams(lastResult, filters, type),
                JsonObject::class.java
            ) ?: return null
            val responseHits = response.getAsJsonArray("hits") ?: return null

            val infoItems: MutableList<InfoItem> = ArrayList()
            responseHit@ for (responseHit in responseHits) {
                val hit = responseHit.asJsonObject

                val categories = hit.get("categories").asJsonArray
                val modloaders: MutableList<ModLoader> = ArrayList()
                for (category in categories) {
                    val string = category.asString
                    if (string == "datapack") continue@responseHit // Datapacks often appear here unexpectedly, so skip them.
                    ModLoaderUtils.getModLoaderByModrinth(string)?.let { modloaders.add(it) }
                }

                infoItems.add(
                    ModInfoItem(
                        classify,
                        Platform.MODRINTH,
                        hit.get("project_id").asString,
                        hit.get("slug").asString,
                        arrayOf(hit.get("author").asString),
                        hit.get("title").asString,
                        hit.get("description").asString,
                        hit.get("downloads").asLong,
                        ZHTools.getDate(hit.get("date_created").asString),
                        ModrinthCommonUtils.getIconUrl(hit),
                        ModrinthCommonUtils.getAllCategories(hit).toList(),
                        modloaders
                    )
                )
            }

            return ModrinthCommonUtils.returnResults(lastResult, infoItems, response, responseHits)
        }

        @Throws(Throwable::class)
        internal fun getModVersions(api: ApiHandler, infoItem: InfoItem, force: Boolean): List<VersionItem>? {
            return ModrinthCommonUtils.getCommonVersions(
                api,
                infoItem,
                force,
                InfoCache.ModVersionCache
            ) { versionObject, filesJsonObject, invalidDependencies ->
                val dependencies = versionObject.getAsJsonArray("dependencies")
                val dependencyInfoItems: MutableList<DependenciesInfoItem> = ArrayList()
                val dependencyRefs: MutableList<ModrinthDependencyRef> = ArrayList()

                if (dependencies != null && dependencies.size() != 0) {
                    for (dependency in dependencies) {
                        val dObject = dependency.asJsonObject

                        val projectIdElement = dObject.get("project_id")
                        if (projectIdElement == null || projectIdElement.isJsonNull) continue

                        val dProjectId = projectIdElement.asString
                        val dependencyTypeString = dObject.get("dependency_type").asString
                        val dependencyType = DependencyUtils.getDependencyTypeFromModrinth(dependencyTypeString)

                        val versionIdElement = dObject.get("version_id")
                        val dVersionId =
                            if (versionIdElement == null || versionIdElement.isJsonNull) null
                            else versionIdElement.asString

                        dependencyRefs.add(
                            ModrinthDependencyRef(
                                projectId = dProjectId,
                                versionId = dVersionId,
                                dependencyType = dependencyType
                            )
                        )

                        if (invalidDependencies.contains(dProjectId)) continue

                        if (!InfoCache.DependencyInfoCache.containsKey(dProjectId)) {
                            val hit = ModrinthCommonUtils.searchModFromID(api, dProjectId)
                            if (hit != null) {
                                InfoCache.DependencyInfoCache.put(
                                    dProjectId,
                                    DependenciesInfoItem(
                                        infoItem.classify,
                                        Platform.MODRINTH,
                                        dProjectId,
                                        hit.get("slug").asString,
                                        null,
                                        hit.get("title").asString,
                                        hit.get("description").asString,
                                        hit.get("downloads").asLong,
                                        ZHTools.getDate(hit.get("published").asString),
                                        ModrinthCommonUtils.getIconUrl(hit),
                                        ModrinthCommonUtils.getAllCategories(hit).toList(),
                                        getModLoaders(hit.getAsJsonArray("loaders")),
                                        dependencyType
                                    )
                                )
                            } else {
                                invalidDependencies.add(dProjectId)
                            }
                        }

                        InfoCache.DependencyInfoCache.get(dProjectId)?.let {
                            dependencyInfoItems.add(it)
                        }
                    }
                }

                ModVersionItem(
                    infoItem.projectId,
                    versionObject.get("name").asString,
                    versionObject.get("downloads").asLong,
                    ZHTools.getDate(versionObject.get("date_published").asString),
                    ModrinthCommonUtils.getMcVersions(versionObject.getAsJsonArray("game_versions")),
                    VersionTypeUtils.getVersionType(versionObject.get("version_type").asString),
                    filesJsonObject.get("filename").asString,
                    ModrinthCommonUtils.getSha1Hash(filesJsonObject),
                    filesJsonObject.get("url").asString,
                    getModLoaders(versionObject.getAsJsonArray("loaders")),
                    dependencyInfoItems,
                    dependencyRefs
                )
            }
        }

        @Throws(Throwable::class)
        internal fun getModPackVersions(api: ApiHandler, infoItem: InfoItem, force: Boolean): List<ModLikeVersionItem>? {
            return ModrinthCommonUtils.getCommonVersions(
                api,
                infoItem,
                force,
                InfoCache.ModPackVersionCache
            ) { versionObject, filesJsonObject, _ ->
                ModLikeVersionItem(
                    infoItem.projectId,
                    versionObject.get("name").asString,
                    versionObject.get("downloads").asLong,
                    ZHTools.getDate(versionObject.get("date_published").asString),
                    ModrinthCommonUtils.getMcVersions(versionObject.getAsJsonArray("game_versions")),
                    VersionTypeUtils.getVersionType(versionObject.get("version_type").asString),
                    filesJsonObject.get("filename").asString,
                    ModrinthCommonUtils.getSha1Hash(filesJsonObject),
                    filesJsonObject.get("url").asString,
                    getModLoaders(versionObject.getAsJsonArray("loaders"))
                )
            }
        }

        private fun getModLoaders(jsonArray: JsonArray): List<ModLoader> {
            val modLoaders: MutableList<ModLoader> = ArrayList()
            jsonArray.forEach {
                ModLoaderUtils.getModLoader(it.asString)?.let { ml ->
                    modLoaders.add(ml)
                }
            }
            return modLoaders
        }
    }
}