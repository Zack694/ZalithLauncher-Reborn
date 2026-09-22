package com.movtery.zalithlauncher.modsync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.path.PathManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Signature-guarded bridge between ZalithLauncher and the ModInj companion app.
 *
 * Runs in the launcher's default (`:launcher`) process, so it survives game
 * process crashes and keeps the change-watcher alive for the whole session.
 *
 * Security model:
 *  - The manifest declares this provider with [ModSyncContract.PERMISSION]
 *    (protectionLevel=signature); only apps signed with the same keystore
 *    (ModInj) can call it.
 *  - Every resolved path is canonicalized and required to stay inside the
 *    current game home, blocking path traversal and symlink escapes.
 *  - All mutating operations act on regular files only; directories are never
 *    deleted or overwritten.
 *
 * URI scheme:
 *  - `content://<pkg>.modsync/dir?path=<relative path>`  -> list children
 *  - `content://<pkg>.modsync/file?path=<relative path>` -> read/write one file
 *  - `content://<pkg>.modsync/file?path=<relative path>` -> delete one file
 *
 * call() methods:
 *  - [ModSyncContract.CALL_PING]          -> handshake ("pong")
 *  - [ModSyncContract.CALL_SET_SESSION]   -> arg = session JSON, starts watching
 *  - [ModSyncContract.CALL_END_SESSION]   -> wipes tracked files, stops watching
 *  - [ModSyncContract.CALL_SESSION_STATE] -> returns session JSON or null
 */
class ModSyncProvider : ContentProvider() {
    companion object {
        private const val TAG = "ModSyncProvider"
        private const val SESSION_FILE = "modsync_session.json"
        private const val SEG_DIR = "dir"
        private const val SEG_FILE = "file"
    }

    /** Files ModInj selected for this game session, all relative to the game home. */
    private data class Session(val gameDir: String, val instance: String, val files: MutableList<String>)

    private val gson = Gson()
    private val lock = Any()
    private var session: Session? = null
    private val watchers = HashMap<String, FileObserver>()
    private val lastNotified = HashMap<String, Long>()

    override fun onCreate(): Boolean {
        // Restore a session persisted before a crash / reboot so the files can
        // still be wiped even if this process was restarted in between.
        runCatching { loadPersistedSession() }
            .onFailure { Logging.e(TAG, "Failed to restore persisted session", it) }
        return true
    }

    // ------------------------------------------------------------------ query

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val rel = uri.getQueryParameter(ModSyncContract.PARAM_PATH) ?: ""
        val base = resolveForRead(rel)
            ?: throw IllegalArgumentException("Path escapes game home: $rel")
        val cursor = MatrixCursor(
            arrayOf(
                ModSyncContract.COL_NAME,
                ModSyncContract.COL_REL_PATH,
                ModSyncContract.COL_IS_DIR,
                ModSyncContract.COL_SIZE,
                ModSyncContract.COL_LAST_MOD
            )
        )

        val home = gameHome()
        if (base.isFile) {
            cursor.addRow(rowFor(base, home))
        } else {
            base.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                cursor.addRow(rowFor(child, home))
            }
        }
        return cursor
    }

    private fun rowFor(file: File, home: File): Array<Any> {
        val rel = runCatching { file.canonicalFile.toRelativeString(home.canonicalFile) }
            .getOrDefault(file.name)
        return arrayOf(
            file.name,
            rel,
            if (file.isDirectory) 1 else 0,
            if (file.isFile) file.length() else 0L,
            file.lastModified()
        )
    }

    // --------------------------------------------------------------- openFile

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val rel = uri.getQueryParameter(ModSyncContract.PARAM_PATH) ?: ""
        val file = resolveForWrite(rel)
            ?: throw IllegalArgumentException("Path escapes game home: $rel")

        val writeMode = mode.contains("w") || mode.contains("t")
        if (writeMode) {
            if (file.isDirectory) throw IOException("Refusing to write: $rel is a directory")
            file.parentFile?.let { parent -> if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Cannot create parent directory for $rel")
            } }
        } else {
            if (!file.isFile) throw FileNotFoundException("Not a file: $rel")
        }

        val flags = if (writeMode) {
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        Logging.i(TAG, "openFile: $rel (mode=$mode)")
        return ParcelFileDescriptor.open(file, flags)
    }

    // ----------------------------------------------------------------- delete

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val rel = uri.getQueryParameter(ModSyncContract.PARAM_PATH) ?: ""
        val file = resolveForWrite(rel)
            ?: throw IllegalArgumentException("Path escapes game home: $rel")
        if (!file.isFile) throw IOException("Only regular files can be deleted: $rel")
        val deleted = file.delete()
        Logging.i(TAG, "delete: $rel -> $deleted")
        return if (deleted) 1 else 0
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Use openFile() with write mode instead")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Use openFile() with write mode instead")

    override fun getType(uri: Uri): String = "application/octet-stream"

    // ------------------------------------------------------------------ call()

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        return when (method) {
            ModSyncContract.CALL_PING -> Bundle().apply { putString("pong", "modsync-1") }

            ModSyncContract.CALL_SET_SESSION -> {
                val parsed = parseSession(arg)
                    ?: throw IllegalArgumentException("setSession requires a valid session JSON")
                synchronized(lock) {
                    session = parsed
                    persistSession(parsed)
                    startWatchers(parsed)
                }
                Logging.i(
                    TAG,
                    "Session set: instance=${parsed.instance}, files=${parsed.files.size}"
                )
                Bundle().apply { putBoolean("ok", true) }
            }

            ModSyncContract.CALL_END_SESSION -> {
                val count = endSessionAndWipe()
                Logging.i(TAG, "Session ended, files wiped: $count")
                Bundle().apply {
                    putBoolean("ok", true)
                    putInt("deleted", count)
                }
            }

            ModSyncContract.CALL_SESSION_STATE -> {
                val current = synchronized(lock) { session }
                if (current == null) null else Bundle().apply { putString("session", gson.toJson(current)) }
            }

            else -> throw IllegalArgumentException("Unknown method: $method")
        }
    }

    // ---------------------------------------------------------------- session

    private fun parseSession(json: String?): Session? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val obj = gson.fromJson(json, SessionJson::class.java)
            if (obj.gameDir.isNullOrBlank() || obj.files.isNullOrEmpty()) null
            else Session(obj.gameDir, obj.instance ?: "", obj.files.toMutableList())
        }.onFailure { Logging.e(TAG, "Bad session JSON", it) }.getOrNull()
    }

    /** Gson DTO (mutable lists, default ctor). */
    private class SessionJson {
        var gameDir: String = ""
        var instance: String = ""
        var files: MutableList<String> = mutableListOf()
    }

    private fun sessionStore(): File? =
        context?.let { File(it.filesDir, SESSION_FILE) }

    private fun persistSession(s: Session) {
        val store = sessionStore() ?: return
        runCatching { store.writeText(gson.toJson(s)) }
            .onFailure { Logging.e(TAG, "Failed to persist session", it) }
    }

    private fun clearPersistedSession() {
        sessionStore()?.delete()
    }

    private fun loadPersistedSession() {
        val store = sessionStore() ?: return
        if (!store.isFile) return
        val loaded = parseSession(store.readText())
        session = loaded
        Logging.i(TAG, "Restored persisted session: ${loaded != null}")
        // Watchers are only armed once the launcher process is fully initialized
        // (PathManager ready); ModInj re-arms them via setSession on next launch.
        if (loaded != null && PathManager.DIR_GAME_HOME.isNotBlank()) {
            startWatchers(loaded)
        }
    }

    /** Deletes exactly the tracked files (never directories) and stops watching. */
    private fun endSessionAndWipe(): Int {
        val s = synchronized(lock) { session }
        val home = gameHome()
        var deleted = 0
        s?.files?.forEach { rel ->
            val f = resolveForWriteIn(home, rel)
            if (f != null && f.isFile && f.delete()) {
                deleted++
                Logging.i(TAG, "Wiped: $rel")
            }
        }
        synchronized(lock) {
            stopWatchers()
            session = null
        }
        clearPersistedSession()
        return deleted
    }

    // ---------------------------------------------------------------- watcher

    private fun startWatchers(s: Session) {
        synchronized(lock) { stopWatchers() }
        val home = gameHome()
        val parents = HashMap<String, MutableSet<String>>()
        s.files.forEach { rel ->
            val f = resolveForWriteIn(home, rel) ?: return@forEach
            val parent = f.parentFile?.path ?: return@forEach
            parents.getOrPut(parent) { mutableSetOf() }.add(f.name)
        }
        parents.forEach { (parentPath, names) ->
            val observer = object : FileObserver(parentPath, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (event != CLOSE_WRITE && event != MOVED_TO) return
                    if (names.none { it == path }) return
                    val rel = findTrackedRelPath(parentPath, path) ?: return
                    notifyChanged(rel)
                }
            }
            observer.startWatching()
            synchronized(lock) { watchers[parentPath] = observer }
            Logging.i(TAG, "Watching: $parentPath (${names.size} tracked)")
        }
    }

    private fun stopWatchers() {
        watchers.values.forEach { runCatching { it.stopWatching() } }
        watchers.clear()
        lastNotified.clear()
    }

    private fun findTrackedRelPath(parentPath: String, fileName: String): String? {
        val s = synchronized(lock) { session } ?: return null
        val home = gameHome()
        val absolute = File(parentPath, fileName).path
        return s.files.firstOrNull { rel ->
            resolveForWriteIn(home, rel)?.path == absolute
        }
    }

    private fun notifyChanged(relPath: String) {
        val now = System.currentTimeMillis()
        val last = lastNotified[relPath] ?: 0L
        if (now - last < 500L) return // debounce rapid successive writes
        lastNotified[relPath] = now
        val context = context ?: return
        val intent = Intent(ModSyncContract.ACTION_FILE_CHANGED)
            .setPackage(ModSyncContract.MODINJ_PACKAGE)
            .putExtra(ModSyncContract.EXTRA_REL_PATH, relPath)
        context.sendBroadcast(intent, ModSyncContract.PERMISSION)
        Logging.i(TAG, "File changed mid-game: $relPath")
    }

    // ----------------------------------------------------------- path resolve

    /**
     * Game home of the currently selected profile; falls back to the default.
     *
     * MUST be the launcher's REAL game home — `<profile path>/.minecraft` (see
     * [ProfilePathHome.getGameHome]) — because that is where `versions/<name>/`,
     * `mods`, `saves` etc. actually live. Anchoring at the bare profile path
     * (`Android/data/<pkg>/files/`) made every instance invisible to ModInj
     * (empty Instances Drawer) and would have pointed injection at
     * `files/versions/...` instead of `files/.minecraft/versions/...`.
     */
    private fun gameHome(): File = runCatching {
        File(ProfilePathHome.getGameHome())
    }.getOrElse { e ->
        Logging.e(TAG, "ProfilePathManager unavailable, using default game home", e)
        File(PathManager.DIR_GAME_HOME, ".minecraft")
    }

    /** Canonicalizes and ensures the target stays inside [base]. Read operations. */
    private fun resolveForRead(rel: String): File? = resolveIn(gameHome(), rel)

    /** Same as [resolveForRead] but also refuses directories for write/delete ops. */
    private fun resolveForWrite(rel: String): File? = resolveIn(gameHome(), rel)

    private fun resolveForWriteIn(base: File, rel: String): File? = resolveIn(base, rel)

    private fun resolveIn(base: File, rel: String): File? {
        if (rel.isBlank()) return null
        // Reject obvious traversal early (canonicalization below is the real gate).
        if (rel.split('/').any { it == ".." }) return null
        return try {
            val canBase = base.canonicalFile
            val candidate = File(canBase, rel).canonicalFile
            if (candidate.path == canBase.path ||
                candidate.path.startsWith(canBase.path + File.separator)
            ) candidate else null
        } catch (e: IOException) {
            Logging.e(TAG, "Path resolution failed for $rel", e)
            null
        }
    }
}
