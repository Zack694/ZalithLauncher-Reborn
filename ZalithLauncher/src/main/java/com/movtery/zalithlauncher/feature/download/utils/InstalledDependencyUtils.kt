package com.movtery.zalithlauncher.feature.download.utils

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

object InstalledDependencyUtils {
    private const val JAR_SUFFIX = ".jar"
    private const val DISABLED_JAR_SUFFIX = ".jar.disabled"

    data class InstalledIndex(
        val fileNames: Set<String>,
        val sha1Hashes: Set<String>
    )

    fun buildInstalledIndex(modsDir: File): InstalledIndex {
        if (!modsDir.exists() || !modsDir.isDirectory) {
            return InstalledIndex(emptySet(), emptySet())
        }

        val fileNames = LinkedHashSet<String>()
        val sha1Hashes = LinkedHashSet<String>()

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
            }

        return InstalledIndex(fileNames, sha1Hashes)
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