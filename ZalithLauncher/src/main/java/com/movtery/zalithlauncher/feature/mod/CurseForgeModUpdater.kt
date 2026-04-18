package com.movtery.zalithlauncher.feature.mod

import android.content.Context
import com.movtery.zalithlauncher.feature.log.Logging
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

object CurseForgeModUpdater {
    enum class UpdateActionStatus {
        UPDATED,
        NO_UPDATE,
        UNKNOWN,
        FAILED
    }

    data class UpdateActionResult(
        val status: UpdateActionStatus,
        val oldFile: File,
        val newFile: File? = null,
        val message: String
    )

    @Throws(Throwable::class)
    fun updateSingleMod(
        context: Context,
        installedFile: File,
        minecraftVersion: String? = null
    ): UpdateActionResult {
        val updateInfo = CurseForgeUpdateChecker.checkForUpdate(
            context = context,
            file = installedFile,
            minecraftVersion = minecraftVersion
        )

        when (updateInfo.status) {
            CurseForgeUpdateChecker.UpdateStatus.UP_TO_DATE -> {
                return UpdateActionResult(
                    status = UpdateActionStatus.NO_UPDATE,
                    oldFile = installedFile,
                    newFile = installedFile,
                    message = "This mod is already up to date."
                )
            }

            CurseForgeUpdateChecker.UpdateStatus.UNKNOWN -> {
                return UpdateActionResult(
                    status = UpdateActionStatus.UNKNOWN,
                    oldFile = installedFile,
                    newFile = null,
                    message = updateInfo.reason ?: "Could not determine update information for this mod."
                )
            }

            CurseForgeUpdateChecker.UpdateStatus.UPDATE_AVAILABLE -> Unit
        }

        val downloadUrl = updateInfo.downloadUrl
        val newFileName = updateInfo.fileName

        if (downloadUrl.isNullOrBlank() || newFileName.isNullOrBlank()) {
            return UpdateActionResult(
                status = UpdateActionStatus.UNKNOWN,
                oldFile = installedFile,
                newFile = null,
                message = "The update exists, but the download file information is missing."
            )
        }

        val parentDir = installedFile.parentFile
            ?: return UpdateActionResult(
                status = UpdateActionStatus.FAILED,
                oldFile = installedFile,
                newFile = null,
                message = "The installed mod file has no parent directory."
            )

        val wasDisabled = installedFile.name.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)
        val outputName = if (wasDisabled && !newFileName.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)) {
            newFileName + ModUtils.DISABLE_JAR_FILE_SUFFIX.removePrefix(ModUtils.JAR_FILE_SUFFIX)
        } else {
            newFileName
        }

        val targetFile = File(parentDir, outputName)
        val backupFile = File(parentDir, installedFile.name + ".bak")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        if (targetFile.exists() && targetFile.absolutePath != installedFile.absolutePath) {
            targetFile.delete()
        }

        if (!installedFile.renameTo(backupFile)) {
            return UpdateActionResult(
                status = UpdateActionStatus.FAILED,
                oldFile = installedFile,
                newFile = null,
                message = "Could not create a backup before updating."
            )
        }

        return try {
            downloadFile(downloadUrl, targetFile)

            if (!updateInfo.fileHash.isNullOrBlank()) {
                val downloadedSha1 = calculateSha1(targetFile)
                if (!downloadedSha1.equals(updateInfo.fileHash, ignoreCase = true)) {
                    throw IllegalStateException(
                        "Downloaded file hash did not match the expected SHA-1.\nExpected=${updateInfo.fileHash}\nActual=$downloadedSha1"
                    )
                }
            }

            if (backupFile.exists()) {
                backupFile.delete()
            }

            UpdateActionResult(
                status = UpdateActionStatus.UPDATED,
                oldFile = installedFile,
                newFile = targetFile,
                message = "Updated to ${updateInfo.latestVersion ?: targetFile.name}"
            )
        } catch (t: Throwable) {
            Logging.e("CurseForgeModUpdater", "Failed to update ${installedFile.absolutePath}", t)

            runCatching {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
            }

            runCatching {
                if (backupFile.exists()) {
                    backupFile.renameTo(installedFile)
                }
            }

            UpdateActionResult(
                status = UpdateActionStatus.FAILED,
                oldFile = installedFile,
                newFile = null,
                message = t.message ?: "Update failed."
            )
        }
    }

    @Throws(Throwable::class)
    private fun downloadFile(url: String, destination: File) {
        destination.parentFile?.mkdirs()

        URL(url).openStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }

    @Throws(Throwable::class)
    private fun calculateSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
