package com.zack694.modinj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manifest-declared fallback for the launcher's "game ended" broadcast.
 *
 * The watch service handles GAME_ENDED through a RUNTIME receiver, which only
 * exists while ModInj's process is alive. If the service was dead at the
 * moment the game exited (battery, OOM, boot…), the runtime receiver missed
 * the broadcast and injected files would linger until the next launch — the
 * "it deletes, but not when you think" symptom. A manifest receiver WAKES
 * ModInj for the broadcast, so the wipe happens right when the game ends.
 *
 * The provider's endSession is idempotent (no session => no-op), so a double
 * delivery (this receiver + the running service's runtime receiver) is safe.
 *
 * SESSION IDENTITY GUARD (v1.5.0): a GAME_ENDED can be delivered LATE — e.g.
 * the process was dead when the game exited and this manifest receiver wakes
 * it minutes later, after the user already relaunched and ModInj registered a
 * NEW session. Wiping whatever the provider holds would destroy the new
 * session's files mid-launch ("Game ended" while still launching). So the
 * wipe only fires when the provider's current session still matches the
 * instance this broadcast says ended (or holds none — then it is a no-op).
 */
class GameEndedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GameEndedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModSyncClient.ACTION_GAME_ENDED) return
        val pending = goAsync()
        Thread {
            try {
                val instance = intent.getStringExtra(ModSyncClient.EXTRA_INSTANCE).orEmpty()
                // Cold-process guard: a freshly woken process must discover
                // the bridge authority first or the wipe would silently no-op.
                val deleted = if (
                    ModSyncClient.authority != null || ModSyncClient.ping(context)
                ) {
                    endSessionIfCurrent(context, instance)
                } else -1
                ModWatchService.WatchState.clear(context)
                Log.i(TAG, "GAME_ENDED received: instance='$instance', files wiped=$deleted")
            } catch (t: Throwable) {
                Log.e(TAG, "GAME_ENDED handling failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * Wipes through the provider only when its current session is still the
     * one this broadcast reports as ended. Returns the deleted-file count,
     * or -2 when the wipe was blocked as a stale signal (nothing deleted).
     */
    private fun endSessionIfCurrent(context: Context, instance: String): Int {
        val currentJson = ModSyncClient.sessionState(context) ?: return ModSyncClient.endSession(context)
        val currentInstance = try {
            org.json.JSONObject(currentJson).optString("instance").ifEmpty { null }
        } catch (_: Exception) {
            null
        }
        return if (instance.isEmpty() || currentInstance == instance) {
            ModSyncClient.endSession(context)
        } else {
            Log.w(
                TAG,
                "Stale GAME_ENDED for '$instance' ignored — provider session is '$currentInstance'"
            )
            -2
        }
    }
}
