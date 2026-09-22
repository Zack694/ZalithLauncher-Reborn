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
                    ModSyncClient.endSession(context)
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
}
