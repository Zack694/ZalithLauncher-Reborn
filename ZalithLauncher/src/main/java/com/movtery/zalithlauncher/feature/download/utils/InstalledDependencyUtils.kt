package com.movtery.zalithlauncher.feature.download.utils

import android.content.Context
import com.movtery.zalithlauncher.feature.mod.ModJarIconHelper
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

object InstalledDependencyUtils {
    private const val JAR_SUFFIX = ".jar"
    private const val DISABLED_JAR_SUFFIX = ".jar.disabled"

    data class InstalledIndex(
        val fileNames: Set<String>,
        val sha1Hashes: Set<String>,
        val modFilesById: Map<String, MutableList<File>>
    )

    fun buildInstalledIndex(
        context: Context,
        modsDir: File
    ): InstalledIndex {
        if (!modsDir.exists() || !modsDir.isDirectory) {
            return InstalledIndex(
                emptySet(),
                emptySet(),
                emptyMap()
            )
        }

        val fileNames = LinkedHashSet<String>()
        val sha1Hashes = LinkedHashSet<String>()
        val modFilesById = LinkedHashMap<String, MutableList<File>>()

        modsDir.listFiles()
            ?.filter { file ->
                file.isFile && (
                        file.name.endsWith(JAR_SUFFIX, ignoreCase = true) ||
                                file.name.endsWith(DISABLED_JAR_SUFFIX, ignoreCase = true)
                        )
            }
            ?.forEach { file ->
                fileNames.add(normalizeFileName(file.name))

                val sha1 = runCatching { computeSha1(file) }.getOrNull()
                if (!sha1.isNullOrBlank()) {
                    sha1Hashes.add(sha1.lowercase(Locale.ROOT))
                }

                val modInfo = runCatching { ModJarIconHelper.read(context, file) }.getOrNull()
                val modId = modInfo?.modId?.trim()?.lowercase(Locale.ROOT)
                if (!modId.isNullOrBlank()) {
                    modFilesById.getOrPut(modId) { mutableListOf() }.add(file)
                }
            }

        return InstalledIndex(fileNames, sha1Hashes, modFilesById)
    }

    fun isAlreadyInstalled(
        index: InstalledIndex,
        fileName: String?,
        fileHash: String?
    ): Boolean {
        val normalizedName = normalizeFileName(fileName)
        val normalizedHash = fileHash?.trim()?.lowercase(Locale.ROOT)

        if (!normalizedHash.isNullOrBlank() && normalizedHash in index.sha1Hashes) {
            return true
        }

        if (!normalizedName.isNullOrBlank() && normalizedName in index.fileNames) {
            return true
        }

        return false
    }

    fun findInstalledFilesByModId(
        index: InstalledIndex,
        modId: String?
    ): List<File> {
        val normalizedModId = modId?.trim()?.lowercase(Locale.ROOT)
        if (normalizedModId.isNullOrBlank()) return emptyList()
        return index.modFilesById[normalizedModId].orEmpty()
    }

    fun removeOldVersionsOfSameMod(
        context: Context,
        modsDir: File,
        downloadedFile: File
    ) {
        if (!downloadedFile.exists()) return

        val downloadedInfo = runCatching {
            ModJarIconHelper.read(context, downloadedFile)
        }.getOrNull() ?: return

        val downloadedModId = downloadedInfo.modId?.trim()?.lowercase(Locale.ROOT)
        if (downloadedModId.isNullOrBlank()) return

        modsDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                        file.absolutePath != downloadedFile.absolutePath &&
                        (
                                file.name.endsWith(JAR_SUFFIX, ignoreCase = true) ||
                                        file.name.endsWith(DISABLED_JAR_SUFFIX, ignoreCase = true)
                                )
            }
            ?.forEach { existingFile ->
                val existingInfo = runCatching {
                    ModJarIconHelper.read(context, existingFile)
                }.getOrNull()

                val existingModId = existingInfo?.modId?.trim()?.lowercase(Locale.ROOT)
                if (existingModId == downloadedModId) {
                    existingFile.delete()
                }
            }
    }

    private fun normalizeFileName(fileName: String?): String {
        return fileName.orEmpty()
            .removeSuffix(".disabled")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun computeSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}