package com.zack694.modinj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Normal autostart: boots the watch/backup foreground service after the
 * device starts so ModInj is always ready to receive the launcher's
 * injection broadcast with a live host — even if the user never opened the
 * app this session.
 *
 * The foreground-service start can be rejected on some OEM/Android-15
 * combinations (background-start rules); that is non-fatal — the service
 * still auto-starts when the app is opened, and the sticky service returns
 * after the next launch kicks it.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startForegroundService(Intent(context, ModWatchService::class.java))
            Log.i(TAG, "Watch service started on boot")
        } catch (t: Throwable) {
            Log.w(TAG, "Boot autostart rejected (will start on next app open): ${t.message}")
        }
    }
}
