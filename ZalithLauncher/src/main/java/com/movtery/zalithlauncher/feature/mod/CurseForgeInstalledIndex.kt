package com.movtery.zalithlauncher.feature.mod

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModVersionItem
import com.movtery.zalithlauncher.feature.log.Logging
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Stores lightweight CurseForge install metadata for jars that were installed through Zalith.
 *
 * This is intentionally a best-effort cache:
 * - If an entry exists, update checking can use the saved project identity directly.
 * - If no entry exists, callers should fall back to normal jar-metadata + search matching.
 */
object CurseForgeInstalledIndex {
    private const val FILE_NAME = "curseforge_installed_index.json"
    private val gson = Gson()

    data class Entry(
        val normalizedFileName: String,
        val sha1: String?,
        val projectId: String,
        val slug: String,
        val title: String,
        val installedVersionName: String?,
        val installedFileName: String,
        val loaders: List<String>
    )

    @Synchronized
    fun saveInstalled(context: Context, outputFile: File, infoItem: InfoItem, versionItem: ModVersionItem) {
        runCatching {
            val entries = loadEntries(context).toMutableList()
            val normalizedFileName = normalizeFileName(outputFile.name)
            val sha1 = versionItem.fileHash?.trim()?.lowercase(Locale.ROOT)

            entries.removeAll { existing ->
                existing.normalizedFileName == normalizedFileName ||
                        (!sha1.isNullOrBlank() && existing.sha1 == sha1)
            }

            entries.add(
                Entry(
                    normalizedFileName = normalizedFileName,
                    sha1 = sha1,
                    projectId = infoItem.projectId,
                    slug = infoItem.slug,
                    title = infoItem.title,
                    installedVersionName = versionItem.title,
                    installedFileName = versionItem.fileName,
                    loaders = versionItem.modloaders.map { it.name }
                )
            )

            writeEntries(context, entries)
        }.onFailure {
            Logging.e("CurseForgeInstalledIndex", "Failed to save installed CurseForge metadata", it)
        }
    }

    @Synchronized
    fun findByFile(context: Context, file: File): Entry? {
        return runCatching {
            val entries = loadEntries(context)
            val normalizedFileName = normalizeFileName(file.name)
            val sha1 = computeSha1OrNull(file)?.lowercase(Locale.ROOT)

            entries.firstOrNull { entry ->
                (!sha1.isNullOrBlank() && entry.sha1 == sha1) ||
                        entry.normalizedFileName == normalizedFileName
            }
        }.onFailure {
            Logging.e("CurseForgeInstalledIndex", "Failed to read installed CurseForge metadata", it)
        }.getOrNull()
    }

    private fun getIndexFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    private fun loadEntries(context: Context): List<Entry> {
        val indexFile = getIndexFile(context)
        if (!indexFile.exists()) return emptyList()

        return runCatching {
            indexFile.reader().use { reader ->
                val type = object : TypeToken<List<Entry>>() {}.type
                gson.fromJson<List<Entry>>(reader, type) ?: emptyList()
            }
        }.getOrElse {
            Logging.e("CurseForgeInstalledIndex", "Failed to parse installed CurseForge metadata index", it)
            emptyList()
        }
    }

    private fun writeEntries(context: Context, entries: List<Entry>) {
        val indexFile = getIndexFile(context)
        indexFile.parentFile?.mkdirs()
        indexFile.writer().use { writer ->
            gson.toJson(entries, writer)
        }
    }

    private fun normalizeFileName(fileName: String?): String {
        return fileName.orEmpty()
            .removeSuffix(".disabled")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun computeSha1OrNull(file: File): String? {
        if (!file.exists() || !file.isFile) return null

        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
