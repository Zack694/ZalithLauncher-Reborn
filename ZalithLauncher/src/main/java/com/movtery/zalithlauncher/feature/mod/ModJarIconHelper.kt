package com.movtery.zalithlauncher.feature.mod

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.log.Logging
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Manifest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object ModJarIconHelper {
    data class ModVisualInfo(
        val displayName: String?,
        val iconDrawable: Drawable?,
        val modId: String?,
        val version: String?,
        val loaders: List<ModLoader>
    )

    private data class CacheEntry(
        val lastModified: Long,
        val length: Long,
        val info: ModVisualInfo?
    )

    private data class ManifestInfo(
        val implementationTitle: String?,
        val specificationTitle: String?,
        val implementationVersion: String?,
        val specificationVersion: String?
    )

    private data class ForgeTomlInfo(
        val displayName: String?,
        val logoFile: String?,
        val modId: String?,
        val version: String?
    )

    private class InMemoryZip(private val entries: Map<String, ByteArray>) {
        fun hasEntry(path: String): Boolean = entries.containsKey(path.removePrefix("/"))

        fun readText(path: String): String? {
            val data = readBytes(path) ?: return null
            return data.toString(Charsets.UTF_8)
        }

        fun readBytes(path: String): ByteArray? = entries[path.removePrefix("/")]

        fun entryNames(): Sequence<String> = entries.keys.asSequence()
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun read(context: Context, file: File): ModVisualInfo? {
        val key = file.absolutePath
        val cached = cache[key]
        if (cached != null &&
            cached.lastModified == file.lastModified() &&
            cached.length == file.length()
        ) {
            return cached.info
        }

        val info = runCatching {
            ZipFile(file).use { zip ->
                readTopLevel(context, zip, file)
                    ?: readNestedJarJars(context, zip, file)
                    ?: fallbackFromFileName(file)
            }
        }.onFailure {
            Logging.e("ModJarIconHelper", "Failed to read mod metadata from ${file.absolutePath}", it)
        }.getOrNull() ?: fallbackFromFileName(file)

        Logging.i(
            "ModJarIconHelper",
            "file=${file.name}, displayName=${info?.displayName}, modId=${info?.modId}, version=${info?.version}, loaders=${info?.loaders}, hasIcon=${info?.iconDrawable != null}"
        )

        cache[key] = CacheEntry(file.lastModified(), file.length(), info)
        return info
    }

    private fun readTopLevel(context: Context, zip: ZipFile, file: File): ModVisualInfo? {
        return readFabric(context, zip, file)
            ?: readQuilt(context, zip, file)
            ?: readForgeToml(context, zip, file, "META-INF/mods.toml", ModLoader.FORGE)
            ?: readForgeToml(context, zip, file, "META-INF/neoforge.mods.toml", ModLoader.NEOFORGE)
    }

    private fun readNestedJarJars(context: Context, outerZip: ZipFile, outerFile: File): ModVisualInfo? {
        val nestedEntries = outerZip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("META-INF/jarjar/") && it.name.endsWith(".jar") }

        for (entry in nestedEntries) {
            val nestedBytes = outerZip.getInputStream(entry).use { it.readBytes() }
            val nestedZip = readInMemoryZip(nestedBytes)

            val info = readFabric(context, nestedZip, outerFile)
                ?: readQuilt(context, nestedZip, outerFile)
                ?: readForgeToml(context, nestedZip, outerFile, "META-INF/mods.toml", ModLoader.FORGE)
                ?: readForgeToml(context, nestedZip, outerFile, "META-INF/neoforge.mods.toml", ModLoader.NEOFORGE)

            if (info != null) {
                Logging.i("ModJarIconHelper", "Resolved metadata from nested jar ${entry.name}")
                return info
            }
        }

        return null
    }

    private fun readInMemoryZip(bytes: ByteArray): InMemoryZip {
        val map = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    map[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return InMemoryZip(map)
    }

    private fun readFabric(context: Context, zip: ZipFile, file: File): ModVisualInfo? {
        val entry = zip.getEntry("fabric.mod.json") ?: return null
        InputStreamReader(zip.getInputStream(entry)).use { reader ->
            val root = JsonParser.parseReader(reader).asJsonObject
            val modId = root.getAsStringOrNull("id")
            val version = normalizeVersionString(root.getAsStringOrNull("version"))
            val iconPath = parseFabricIconPath(root.get("icon"))
            val iconDrawable = iconPath?.let { loadDrawableFromZip(context, zip, it) }
            val displayName = root.getAsStringOrNull("name")
                ?: deriveFriendlyName(modId, file.name)

            return ModVisualInfo(
                displayName = displayName,
                iconDrawable = iconDrawable,
                modId = modId,
                version = version,
                loaders = listOf(ModLoader.FABRIC)
            )
        }
    }

    private fun readFabric(context: Context, zip: InMemoryZip, file: File): ModVisualInfo? {
        val text = zip.readText("fabric.mod.json") ?: return null
        val root = JsonParser.parseString(text).asJsonObject
        val modId = root.getAsStringOrNull("id")
        val version = normalizeVersionString(root.getAsStringOrNull("version"))
        val iconPath = parseFabricIconPath(root.get("icon"))
        val iconDrawable = iconPath?.let { loadDrawableFromZip(context, zip, it) }
        val displayName = root.getAsStringOrNull("name")
            ?: deriveFriendlyName(modId, file.name)

        return ModVisualInfo(
            displayName = displayName,
            iconDrawable = iconDrawable,
            modId = modId,
            version = version,
            loaders = listOf(ModLoader.FABRIC)
        )
    }

    private fun readQuilt(context: Context, zip: ZipFile, file: File): ModVisualInfo? {
        val entry = zip.getEntry("quilt.mod.json") ?: return null
        InputStreamReader(zip.getInputStream(entry)).use { reader ->
            val root = JsonParser.parseReader(reader).asJsonObject
            val quiltLoader = root.getAsJsonObject("quilt_loader") ?: return null
            val metadata = quiltLoader.getAsJsonObject("metadata")

            val modId = quiltLoader.getAsStringOrNull("id")
            val version = normalizeVersionString(quiltLoader.getAsStringOrNull("version"))
            val iconPath = metadata?.get("icon")?.let { parseFabricIconPath(it) }
            val iconDrawable = iconPath?.let { loadDrawableFromZip(context, zip, it) }
            val displayName = metadata?.getAsStringOrNull("name")
                ?: deriveFriendlyName(modId, file.name)

            return ModVisualInfo(
                displayName = displayName,
                iconDrawable = iconDrawable,
                modId = modId,
                version = version,
                loaders = listOf(ModLoader.QUILT)
            )
        }
    }

    private fun readQuilt(context: Context, zip: InMemoryZip, file: File): ModVisualInfo? {
        val text = zip.readText("quilt.mod.json") ?: return null
        val root = JsonParser.parseString(text).asJsonObject
        val quiltLoader = root.getAsJsonObject("quilt_loader") ?: return null
        val metadata = quiltLoader.getAsJsonObject("metadata")

        val modId = quiltLoader.getAsStringOrNull("id")
        val version = normalizeVersionString(quiltLoader.getAsStringOrNull("version"))
        val iconPath = metadata?.get("icon")?.let { parseFabricIconPath(it) }
        val iconDrawable = iconPath?.let { loadDrawableFromZip(context, zip, it) }
        val displayName = metadata?.getAsStringOrNull("name")
            ?: deriveFriendlyName(modId, file.name)

        return ModVisualInfo(
            displayName = displayName,
            iconDrawable = iconDrawable,
            modId = modId,
            version = version,
            loaders = listOf(ModLoader.QUILT)
        )
    }

    private fun readForgeToml(
        context: Context,
        zip: ZipFile,
        file: File,
        path: String,
        defaultLoader: ModLoader
    ): ModVisualInfo? {
        val entry = zip.getEntry(path) ?: return null
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        val manifestInfo = readManifestInfo(zip)
        return buildForgeTomlInfo(context, file, defaultLoader, text, manifestInfo) { iconPath ->
            loadDrawableFromZip(context, zip, iconPath)
        }
    }

    private fun readForgeToml(
        context: Context,
        zip: InMemoryZip,
        file: File,
        path: String,
        defaultLoader: ModLoader
    ): ModVisualInfo? {
        val text = zip.readText(path) ?: return null
        val manifestInfo = readManifestInfo(zip)
        return buildForgeTomlInfo(context, file, defaultLoader, text, manifestInfo) { iconPath ->
            loadDrawableFromZip(context, zip, iconPath)
        }
    }

    private fun buildForgeTomlInfo(
        context: Context,
        file: File,
        defaultLoader: ModLoader,
        text: String,
        manifestInfo: ManifestInfo,
        iconLoader: (String) -> Drawable?
    ): ModVisualInfo {
        val tomlInfo = parseForgeToml(text)
        val modId = tomlInfo.modId
        val version = resolveForgeVersion(tomlInfo.version, manifestInfo)
        val displayName = tomlInfo.displayName
            ?: manifestInfo.implementationTitle
            ?: manifestInfo.specificationTitle
            ?: deriveFriendlyName(modId, file.name)
        val iconDrawable = tomlInfo.logoFile?.let { iconLoader(it) }
        val loaders = inferForgeFamilyLoaders(file.name, defaultLoader, modId, displayName)

        return ModVisualInfo(
            displayName = displayName,
            iconDrawable = iconDrawable,
            modId = modId,
            version = version,
            loaders = loaders
        )
    }

    private fun inferForgeFamilyLoaders(
        fileName: String,
        defaultLoader: ModLoader,
        modId: String?,
        displayName: String?
    ): List<ModLoader> {
        val source = (fileName + " " + (modId ?: "") + " " + (displayName ?: "")).lowercase()
        return when {
            source.contains("neoforge") -> listOf(ModLoader.NEOFORGE, ModLoader.FORGE)
            source.contains("forge") -> listOf(ModLoader.FORGE, ModLoader.NEOFORGE)
            else -> listOf(defaultLoader)
        }
    }

    private fun parseForgeToml(text: String): ForgeTomlInfo {
        val modsBlock = Regex("""(?s)\[\[mods\]\](.*?)(?=\n\s*\[\[|\n\s*\[|$)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: text

        return ForgeTomlInfo(
            displayName = findTomlValue(modsBlock, "displayName") ?: findTomlValue(text, "displayName"),
            logoFile = findTomlValue(modsBlock, "logoFile") ?: findTomlValue(text, "logoFile"),
            modId = findTomlValue(modsBlock, "modId") ?: findTomlValue(text, "modId"),
            version = findTomlValue(modsBlock, "version") ?: findTomlValue(text, "version")
        )
    }

    private fun resolveForgeVersion(version: String?, manifestInfo: ManifestInfo): String? {
        val normalized = normalizeVersionString(version)
        if (!normalized.isNullOrBlank()) return normalized
        return normalizeVersionString(
            manifestInfo.implementationVersion ?: manifestInfo.specificationVersion
        )
    }

    private fun normalizeVersionString(version: String?): String? {
        val trimmed = version?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.startsWith("\${") && trimmed.endsWith("}")) return null
        return trimmed
    }

    private fun readManifestInfo(zip: ZipFile): ManifestInfo {
        val entry = zip.getEntry("META-INF/MANIFEST.MF") ?: return ManifestInfo(null, null, null, null)
        return runCatching {
            zip.getInputStream(entry).use { input ->
                val manifest = Manifest(input)
                val attrs = manifest.mainAttributes
                ManifestInfo(
                    implementationTitle = attrs.getValue("Implementation-Title"),
                    specificationTitle = attrs.getValue("Specification-Title"),
                    implementationVersion = attrs.getValue("Implementation-Version"),
                    specificationVersion = attrs.getValue("Specification-Version")
                )
            }
        }.getOrElse {
            ManifestInfo(null, null, null, null)
        }
    }

    private fun readManifestInfo(zip: InMemoryZip): ManifestInfo {
        val bytes = zip.readBytes("META-INF/MANIFEST.MF") ?: return ManifestInfo(null, null, null, null)
        return runCatching {
            ByteArrayInputStream(bytes).use { input ->
                val manifest = Manifest(input)
                val attrs = manifest.mainAttributes
                ManifestInfo(
                    implementationTitle = attrs.getValue("Implementation-Title"),
                    specificationTitle = attrs.getValue("Specification-Title"),
                    implementationVersion = attrs.getValue("Implementation-Version"),
                    specificationVersion = attrs.getValue("Specification-Version")
                )
            }
        }.getOrElse {
            ManifestInfo(null, null, null, null)
        }
    }

    private fun parseFabricIconPath(iconElement: JsonElement?): String? {
        if (iconElement == null || iconElement.isJsonNull) return null

        return when {
            iconElement.isJsonPrimitive -> iconElement.asString
            iconElement.isJsonObject -> {
                val obj = iconElement.asJsonObject
                obj.entrySet().maxByOrNull { it.key.toIntOrNull() ?: -1 }
                    ?.value
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            }
            else -> null
        }
    }

    private fun loadDrawableFromZip(context: Context, zip: ZipFile, path: String): Drawable? {
        val normalizedPath = path.removePrefix("/")
        val entry = zip.getEntry(normalizedPath) ?: return null
        zip.getInputStream(entry).use { input ->
            val bitmap = BitmapFactory.decodeStream(input) ?: return null
            return bitmap.toDrawable(context.resources)
        }
    }

    private fun loadDrawableFromZip(context: Context, zip: InMemoryZip, path: String): Drawable? {
        val normalizedPath = path.removePrefix("/")
        val bytes = zip.readBytes(normalizedPath) ?: return null
        ByteArrayInputStream(bytes).use { input ->
            val bitmap = BitmapFactory.decodeStream(input) ?: return null
            return bitmap.toDrawable(context.resources)
        }
    }

    private fun findTomlValue(text: String, key: String): String? {
        val regex = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*["']([^"']+)["']""")
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun deriveFriendlyName(modId: String?, fileName: String): String {
        val source = modId?.takeIf { it.isNotBlank() }
            ?: fileName.substringBefore(".jar").substringBefore(".disabled")
        val normalized = source.lowercase()

        return when {
            normalized.contains("kotlinforforge") -> "Kotlin for Forge"
            normalized.contains("geckolib") -> "GeckoLib"
            normalized.contains("fzzyconfig") || normalized.contains("fzzy_config") -> "Fzzy Config"
            else -> humanizeIdentifier(source)
        }
    }

    private fun humanizeIdentifier(value: String): String {
        return value
            .replace(".jar", "")
            .replace(".disabled", "")
            .replace(Regex("""[-_]\d+(\.\d+)+.*$"""), "")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                }
            }
    }

    private fun JsonObject.getAsStringOrNull(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return value.asString
    }

    private fun fallbackFromFileName(file: File): ModVisualInfo? {
        val fileName = file.name
        val lower = fileName.lowercase()
        val version = Regex("""\d+(\.\d+)+""").find(fileName)?.value

        val loaders = when {
            lower.contains("fabric") -> listOf(ModLoader.FABRIC)
            lower.contains("quilt") -> listOf(ModLoader.QUILT)
            lower.contains("neoforge") -> listOf(ModLoader.NEOFORGE, ModLoader.FORGE)
            lower.contains("forge") || lower.contains("kotlinforforge") -> listOf(ModLoader.FORGE, ModLoader.NEOFORGE)
            else -> emptyList()
        }

        val normalizedBase = fileName
            .substringBefore(".jar")
            .substringBefore(".disabled")
            .replace(Regex("""[-_]\d+(\.\d+)+.*$"""), "")

        if (normalizedBase.isBlank()) return null

        return ModVisualInfo(
            displayName = deriveFriendlyName(null, fileName),
            iconDrawable = null,
            modId = normalizedBase
                .lowercase()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", ""),
            version = version,
            loaders = loaders
        )
    }
}
