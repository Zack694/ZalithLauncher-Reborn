package com.zack694.modinj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Local, app-private storage ("vault") for files the user selected for
 * injection, plus the mid-game backup store.
 *
 * vault.json layout:
 *   [ { "instance": "...", "relPath": "versions/Foo/mods/AppleSkin.jar",
 *       "stored": "ab12cd34.jar", "addedAt": 1234567890 } ]
 *
 * Backups live under backups/<encoded instance>/<md5>.<ext> and are indexed by
 * backups/index.json:
 *   [ { "instance": "...", "relPath": "...", "stored": "cd34ab12.cfg" } ]
 * (An index — NOT path-derived filenames — keeps names with underscores safe.)
 */
object Vault {
    private const val INDEX = "vault.json"
    private const val BACKUP_INDEX = "backups/index.json"

    data class Entry(
        val instance: String,
        val relPath: String,
        val stored: String,
        val addedAt: Long
    )

    data class BackupEntry(
        val instance: String,
        val relPath: String,
        val stored: String
    )

    private fun indexFile(context: Context) = File(context.filesDir, INDEX)

    private fun backupIndexFile(context: Context): File {
        val f = File(context.filesDir, BACKUP_INDEX)
        f.parentFile?.mkdirs()
        return f
    }

    // ------------------------------------------------------------- vault index

    @Synchronized
    fun load(context: Context): MutableList<Entry> {
        val f = indexFile(context)
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val instance = o.optString("instance")
                val relPath = o.optString("relPath")
                val stored = o.optString("stored")
                if (instance.isEmpty() || relPath.isEmpty() || stored.isEmpty()) null
                else Entry(instance, relPath, stored, o.optLong("addedAt"))
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun save(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("instance", e.instance)
                put("relPath", e.relPath)
                put("stored", e.stored)
                put("addedAt", e.addedAt)
            })
        }
        indexFile(context).writeText(arr.toString())
    }

    fun vaultDir(context: Context): File = File(context.filesDir, "vault").apply { mkdirs() }

    fun backupRoot(context: Context): File = File(context.filesDir, "backups").apply { mkdirs() }

    private fun digestName(instance: String, relPath: String): String {
        val md = MessageDigest.getInstance("MD5")
        val hex = md.digest("$instance|$relPath".toByteArray()).joinToString("") { "%02x".format(it) }
        val ext = relPath.substringAfterLast('.', "")
        return if (ext.isEmpty()) "$hex.bin" else "$hex.$ext"
    }

    private fun storedName(instance: String, relPath: String): String = digestName(instance, relPath)

    /** Copies a file from the launcher into the vault. Returns the new entry, or null. */
    fun importFromLauncher(context: Context, instance: String, relPath: String): Entry? {
        val name = storedName(instance, relPath)
        val target = File(vaultDir(context), name)
        // Ensure a clean target, then stream from the provider.
        target.delete()
        var copied = false
        target.outputStream().use { out ->
            copied = ModSyncClient.readInto(context, relPath, out) >= 0
        }
        if (!copied) {
            target.delete()
            return null
        }
        val entry = Entry(instance, relPath, name, System.currentTimeMillis())
        val entries = load(context).filterNot {
            it.instance == instance && it.relPath == relPath
        }.toMutableList()
        entries.add(entry)
        save(context, entries)
        return entry
    }

    fun entryFor(context: Context, instance: String, relPath: String): Entry? =
        load(context).firstOrNull { it.instance == instance && it.relPath == relPath }

    fun remove(context: Context, entry: Entry) {
        val entries = load(context).filterNot { it.stored == entry.stored }
        save(context, entries)
        File(vaultDir(context), entry.stored).delete()
    }

    fun entriesForInstance(context: Context, instance: String): List<Entry> =
        load(context).filter { it.instance == instance }

    // ------------------------------------------------------------ backup index

    @Synchronized
    private fun loadBackupIndex(context: Context): MutableList<BackupEntry> {
        val f = backupIndexFile(context)
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val instance = o.optString("instance")
                val relPath = o.optString("relPath")
                val stored = o.optString("stored")
                if (instance.isEmpty() || relPath.isEmpty() || stored.isEmpty()) null
                else BackupEntry(instance, relPath, stored)
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun saveBackupIndex(context: Context, entries: List<BackupEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("instance", e.instance)
                put("relPath", e.relPath)
                put("stored", e.stored)
            })
        }
        backupIndexFile(context).writeText(arr.toString())
    }

    /**
     * Copies a launcher-side file into the backup store. Returns the stored
     * backup file, or null when the source could not be read.
     */
    fun backupFromLauncher(context: Context, instance: String, relPath: String): File? {
        val stored = digestName(instance, relPath)
        val target = File(backupRoot(context), stored)
        target.delete()
        var copied = false
        target.outputStream().use { out ->
            copied = ModSyncClient.readInto(context, relPath, out) > 0
        }
        if (!copied) {
            target.delete()
            return null
        }
        val entries = loadBackupIndex(context).filterNot {
            it.instance == instance && it.relPath == relPath
        }.toMutableList()
        entries.add(BackupEntry(instance, relPath, stored))
        saveBackupIndex(context, entries)
        return target
    }

    fun backupEntryFor(context: Context, instance: String, relPath: String): BackupEntry? =
        loadBackupIndex(context).firstOrNull { it.instance == instance && it.relPath == relPath }

    fun backupEntries(context: Context): List<BackupEntry> = loadBackupIndex(context)

    fun backupFileFor(context: Context, entry: BackupEntry): File =
        File(backupRoot(context), entry.stored)

    /** Copies a backup back into the vault so the next launch re-injects it. */
    fun restoreBackupToVault(context: Context, instance: String, relPath: String): Boolean {
        val backupEntry = backupEntryFor(context, instance, relPath) ?: return false
        val backup = File(backupRoot(context), backupEntry.stored)
        if (!backup.isFile) return false
        val entry = entryFor(context, instance, relPath) ?: run {
            // Not in vault yet — import the current backup content.
            val name = storedName(instance, relPath)
            val target = File(vaultDir(context), name)
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
            val e = Entry(instance, relPath, name, System.currentTimeMillis())
            val entries = load(context)
            entries.add(e)
            save(context, entries)
            return true
        }
        val stored = File(vaultDir(context), entry.stored)
        stored.parentFile?.mkdirs()
        return try {
            backup.copyTo(stored, overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }
}
