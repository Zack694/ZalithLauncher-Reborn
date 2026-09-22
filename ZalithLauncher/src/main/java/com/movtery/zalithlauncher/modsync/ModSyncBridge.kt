package com.movtery.zalithlauncher.modsync

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager
import com.movtery.zalithlauncher.feature.log.Logging
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hooks called from [com.movtery.zalithlauncher.launch.LaunchGame] that open and
 * close ModInj's injection window.
 *
 * `onGameStarting` runs in the `:game` process at the very top of the launch
 * sequence — before plugins, renderer setup, mod checks and definitely before
 * the JVM starts. It sends an ordered broadcast to ModInj and blocks the launch
 * thread (bounded!) until ModInj finishes injecting its files into the instance
 * directory. If ModInj is absent, unresponsive or has no selection for this
 * instance, the wait ends quickly or times out and the launch proceeds
 * unaffected (fail-open).
 *
 * `onGameEnded` runs when the JVM call returns (normal exit path). The crash
 * path is covered separately: ModInj notices the [GameLivenessService] binding
 * dying when the `:game` process is killed.
 */
object ModSyncBridge {
    private const val TAG = "ModSyncBridge"

    /**
     * Hard cap on how long the launch waits for ModInj's injection.
     * When ModInj is not installed there are no receivers, so the ordered
     * broadcast completes almost immediately and this is never reached.
     */
    private const val INJECTION_TIMEOUT_SECONDS = 20L

    @JvmStatic
    fun onGameStarting(activity: Activity, gameDir: File, instanceName: String) {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Logging.i(TAG, "ModInj acknowledged game-starting (code=$resultCode)")
                latch.countDown()
            }
        }

        val homeDir = runCatching { ProfilePathManager.getCurrentPath() }
            .getOrDefault(gameDir.parentFile?.absolutePath ?: gameDir.absolutePath)

        val intent = Intent(ModSyncContract.ACTION_GAME_STARTING)
            .setPackage(ModSyncContract.MODINJ_PACKAGE)
            .putExtra(ModSyncContract.EXTRA_GAME_DIR, gameDir.absolutePath)
            .putExtra(ModSyncContract.EXTRA_HOME_DIR, homeDir)
            .putExtra(ModSyncContract.EXTRA_INSTANCE, instanceName)

        Logging.i(TAG, "Opening ModInj injection window for '$instanceName'")

        activity.sendOrderedBroadcast(
            intent,
            ModSyncContract.PERMISSION,
            receiver,
            null,
            Activity.RESULT_OK,
            null,
            null
        )

        val injected = latch.await(INJECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Logging.i(
            TAG,
            if (injected) "Injection window closed (ModInj responded)"
            else "Injection window closed (no response within $INJECTION_TIMEOUT_SECONDS s)"
        )
    }

    @JvmStatic
    fun onGameEnded(activity: Activity, instanceName: String) {
        val intent = Intent(ModSyncContract.ACTION_GAME_ENDED)
            .setPackage(ModSyncContract.MODINJ_PACKAGE)
            .putExtra(ModSyncContract.EXTRA_INSTANCE, instanceName)

        activity.sendBroadcast(intent, ModSyncContract.PERMISSION)
        Logging.i(TAG, "Notified ModInj: game ended ('$instanceName')")
    }
}
