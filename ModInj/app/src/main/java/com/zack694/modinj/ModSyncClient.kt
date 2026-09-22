package com.zack694.modinj

import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Access layer for the launcher's signature-guarded [ModSyncProvider].
 *
 * The launcher may be installed as release (`com.movtery.zalithlauncher`) or
 * as a debug build (`com.movtery.zalithlauncher.debug`), so the authority is
 * discovered at runtime by probing both candidates with a cheap `ping` call.
 */
object ModSyncClient {
    const val PERMISSION = "com.movtery.zalithlauncher.permission.MODSYNC"

    const val ACTION_GAME_STARTING = "com.zack694.modinj.ACTION_GAME_STARTING"
    const val ACTION_GAME_ENDED = "com.zack694.modinj.ACTION_GAME_ENDED"
    const val ACTION_FILE_CHANGED = "com.zack694.modinj.ACTION_FILE_CHANGED"

    const val EXTRA_GAME_DIR = "gameDir"
    const val EXTRA_HOME_DIR = "homeDir"
    const val EXTRA_INSTANCE = "instance"
    const val EXTRA_REL_PATH = "relPath"

    const val RESULT_INJECTED = 1
    const val RESULT_NO_SELECTION = 2

    private const val CALL_PING = "ping"
    private const val CALL_SET_SESSION = "setSession"
    private const val CALL_END_SESSION = "endSession"
    private const val CALL_SESSION_STATE = "sessionState"
    private const val CALL_GAME_STATE = "gameState"
    private const val KEY_GAME_KNOWN = "gameProcessKnown"
    private const val KEY_GAME_ALIVE = "gameProcessAlive"
    private const val KEY_GAME_IMPORTANCE = "gameProcessImportance"
    private const val PARAM_PATH = "path"

    private val CANDIDATE_PACKAGES = listOf(
        "com.movtery.zalithlauncher",
        "com.movtery.zalithlauncher.debug"
    )

    /** Discovered launcher package, or null when the bridge is unavailable. */
    @Volatile
    var launcherPackage: String? = null
        private set

    @Volatile
    var authority: String? = null
        private set

    fun fileUri(relPath: String): Uri? {
        val auth = authority ?: return null
        return Uri.parse("content://$auth/file").buildUpon()
            .appendQueryParameter(PARAM_PATH, relPath)
            .build()
    }

    fun dirUri(relPath: String): Uri? {
        val auth = authority ?: return null
        return Uri.parse("content://$auth/dir").buildUpon()
            .appendQueryParameter(PARAM_PATH, relPath)
            .build()
    }

    /**
     * Probes both candidate launcher packages. Returns true when the bridge
     * (and therefore a compatible, same-signature launcher) is available.
     */
    fun ping(context: Context): Boolean {
        val cr = context.contentResolver
        for (pkg in CANDIDATE_PACKAGES) {
            val auth = "$pkg.modsync"
            try {
                val bundle = cr.call(
                    Uri.parse("content://$auth"),
                    CALL_PING, null, null
                )
                if (bundle != null && bundle.getString("pong") != null) {
                    launcherPackage = pkg
                    authority = auth
                    return true
                }
            } catch (_: Exception) {
                // Not installed / not the right signature / process starting up
            }
        }
        launcherPackage = null
        authority = null
        return false
    }

    /** Lists one directory through the provider. Returns null on failure. */
    fun list(context: Context, relPath: String): List<BrowseEntry>? {
        val uri = dirUri(relPath) ?: return null
        return try {
            val out = ArrayList<BrowseEntry>()
            crQuery(context, uri) { name, rel, isDir, size, lastMod ->
                out.add(BrowseEntry(name, rel, isDir, size, lastMod))
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private inline fun crQuery(
        context: Context,
        uri: Uri,
        consume: (name: String, rel: String, isDir: Boolean, size: Long, lastMod: Long) -> Unit
    ) {
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, null, null, null, null)
            val iName = cursor?.getColumnIndexOrThrow("name") ?: return
            val iRel = cursor.getColumnIndexOrThrow("rel_path")
            val iDir = cursor.getColumnIndexOrThrow("is_dir")
            val iSize = cursor.getColumnIndexOrThrow("size")
            val iMod = cursor.getColumnIndexOrThrow("last_mod")
            while (cursor!!.moveToNext()) {
                consume(
                    cursor.getString(iName),
                    cursor.getString(iRel),
                    cursor.getInt(iDir) == 1,
                    cursor.getLong(iSize),
                    cursor.getLong(iMod)
                )
            }
        } finally {
            cursor?.close()
        }
    }

    /** Streams a launcher-side file into [out]. Returns bytes copied, or -1. */
    fun readInto(context: Context, relPath: String, out: OutputStream): Long {
        val uri = fileUri(relPath) ?: return -1
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.copyTo(out, 64 * 1024)
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /** Streams [input] into a launcher-side file (create/overwrite). */
    fun writeFrom(context: Context, relPath: String, input: InputStream): Boolean {
        val uri = fileUri(relPath) ?: return false
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                input.copyTo(output, 64 * 1024)
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun delete(context: Context, relPath: String): Boolean {
        val uri = fileUri(relPath) ?: return false
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    /** Registers a game session with the provider (arms the change watcher). */
    fun setSession(context: Context, gameDir: String, instance: String, files: List<String>): Boolean {
        val auth = authority ?: return false
        val json = org.json.JSONObject().apply {
            put("gameDir", gameDir)
            put("instance", instance)
            put("files", org.json.JSONArray(files))
        }.toString()
        return try {
            context.contentResolver.call(
                Uri.parse("content://$auth"), CALL_SET_SESSION, json, null
            )?.getBoolean("ok") == true
        } catch (_: Exception) {
            false
        }
    }

    /** Ends the session: the provider deletes exactly the tracked files. */
    fun endSession(context: Context): Int {
        val auth = authority ?: return -1
        return try {
            context.contentResolver.call(
                Uri.parse("content://$auth"), CALL_END_SESSION, null, null
            )?.getInt("deleted", -1) ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    /** Returns the raw session JSON (or null) — used for stale-session recovery. */
    fun sessionState(context: Context): String? {
        val auth = authority ?: return null
        return try {
            context.contentResolver.call(
                Uri.parse("content://$auth"), CALL_SESSION_STATE, null, null
            )?.getString("session")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Positive-evidence game liveness, straight from the launcher.
     *
     * The launcher reports whether its own `:game` process is running right
     * now, plus that process's oom importance. Returns null when the bridge is
     * unavailable, the launcher predates this call, or the launcher's own
     * process scan was unusable — callers MUST treat null as "unknown" and
     * never act destructively on it.
     */
    fun gameState(context: Context): GameState? {
        val auth = authority ?: return null
        return try {
            val bundle = context.contentResolver.call(
                Uri.parse("content://$auth"), CALL_GAME_STATE, null, null
            ) ?: return null
            if (!bundle.getBoolean(KEY_GAME_KNOWN, false)) return null
            GameState(
                alive = bundle.getBoolean(KEY_GAME_ALIVE, false),
                importance = bundle.getInt(KEY_GAME_IMPORTANCE, 0)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Launcher-reported game-process state.
     * @param alive true when the launcher's `:game` process is running.
     * @param importance its oom importance; while a session launches or plays,
     *   the game activity plus GameService (FGS) hold it at foreground-service
     *   level (125) or better. A finished session is either gone or cached.
     */
    data class GameState(val alive: Boolean, val importance: Int)

    data class BrowseEntry(
        val name: String,
        val relPath: String,
        val isDir: Boolean,
        val size: Long,
        val lastModified: Long
    )
}
