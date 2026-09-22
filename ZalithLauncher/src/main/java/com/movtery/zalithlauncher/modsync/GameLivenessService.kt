package com.movtery.zalithlauncher.modsync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.movtery.zalithlauncher.feature.log.Logging

/**
 * No-op service pinned to the `:game` process.
 *
 * ModInj binds to it while a game session is running. When the game process
 * goes away — whether through a clean exit or a hard native crash — the binding
 * dies ([android.content.ServiceConnection.onBindingDied]), which is ModInj's
 * signal to end the session (wipe the selected files through the provider).
 *
 * It intentionally has no logic of its own: its only job is to exist inside the
 * game process so that ModInj can observe that process's lifetime.
 *
 * Guarded by [ModSyncContract.PERMISSION] (signature level) in the manifest.
 */
class GameLivenessService : Service() {
    companion object {
        private const val TAG = "GameLiveness"
    }

    override fun onCreate() {
        super.onCreate()
        Logging.i(TAG, "Game liveness service created (game process alive)")
    }

    override fun onDestroy() {
        Logging.i(TAG, "Game liveness service destroyed (game process going away)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
