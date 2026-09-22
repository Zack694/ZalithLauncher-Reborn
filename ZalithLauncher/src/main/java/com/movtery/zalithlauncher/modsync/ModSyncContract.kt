package com.movtery.zalithlauncher.modsync

/**
 * Shared constants for the ModInj bridge.
 *
 * The bridge lets the ModInj companion app (signed with the same keystore as the
 * launcher) manage mod/config files inside the launcher's private game home:
 *  - [PERMISSION] guards every entry point (provider, liveness service, broadcasts).
 *  - ModInj talks to [ModSyncProvider] to browse/read/write/delete files.
 *  - [ACTION_GAME_STARTING] opens ModInj's injection window before the JVM starts.
 *  - [ACTION_GAME_ENDED] / [ACTION_FILE_CHANGED] keep ModInj's backups in sync.
 */
object ModSyncContract {
    /** Signature-level permission guarding the whole bridge. Both APKs share one keystore. */
    const val PERMISSION = "com.movtery.zalithlauncher.permission.MODSYNC"

    /** applicationId of the ModInj companion app. Broadcasts are targeted at this package. */
    const val MODINJ_PACKAGE = "com.zack694.modinj"

    /** Authority suffix appended to the launcher's package name for [ModSyncProvider]. */
    const val AUTHORITY_SUFFIX = ".modsync"

    const val ACTION_GAME_STARTING = "com.zack694.modinj.ACTION_GAME_STARTING"
    const val ACTION_GAME_ENDED = "com.zack694.modinj.ACTION_GAME_ENDED"
    const val ACTION_FILE_CHANGED = "com.zack694.modinj.ACTION_FILE_CHANGED"

    const val EXTRA_GAME_DIR = "gameDir"
    const val EXTRA_HOME_DIR = "homeDir"
    const val EXTRA_INSTANCE = "instance"
    const val EXTRA_REL_PATH = "relPath"

    /** Ordered-broadcast result codes ModInj sends back for ACTION_GAME_STARTING. */
    const val RESULT_INJECTED = 1
    const val RESULT_NO_SELECTION = 2

    /** Provider call() method names. */
    const val CALL_SET_SESSION = "setSession"
    const val CALL_END_SESSION = "endSession"
    const val CALL_SESSION_STATE = "sessionState"
    const val CALL_PING = "ping"

    /** Query/open/delete parameter: path relative to the current game home. */
    const val PARAM_PATH = "path"

    /** Columns of the directory-listing cursor. */
    const val COL_NAME = "name"
    const val COL_REL_PATH = "rel_path"
    const val COL_IS_DIR = "is_dir"
    const val COL_SIZE = "size"
    const val COL_LAST_MOD = "last_mod"
}
