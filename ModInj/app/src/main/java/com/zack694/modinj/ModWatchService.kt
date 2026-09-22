package com.zack694.modinj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that keeps ModInj alive for the whole game session so it
 * is never killed mid-game. It:
 *  - binds to the launcher's GameLivenessService (inside the `:game` process);
 *    when that binding dies the game process is gone (exit OR hard crash);
 *  - receives mid-game FILE_CHANGED broadcasts and copies updated files into
 *    the backup store;
 *  - ends the session on GAME_ENDED / binding death — the provider then wipes
 *    exactly the injected files.
 *
 * Designed to be cheap: one runtime receiver set, one binding, no polling.
 */
class ModWatchService : Service() {
    companion object {
        private const val TAG = "ModWatchService"
        private const val CHANNEL_ID = "modinj_watch"
        private const val NOTIFICATION_ID = 42

        const val ACTION_STOP_WATCH = "com.zack694.modinj.ACTION_STOP_WATCH"
        const val ACTION_BIND_LIVENESS = "com.zack694.modinj.ACTION_BIND_LIVENESS"

        var isRunning = false
            private set
    }

    private var bound = false
    private var receiversRegistered = false
    private var watchInstance: String? = null

    private val endedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val instance = intent.getStringExtra(ModSyncClient.EXTRA_INSTANCE) ?: watchInstance ?: ""
            Log.i(TAG, "GAME_ENDED for '$instance' — wiping injected files")
            endSession(context, instance)
        }
    }

    private val fileChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val relPath = intent.getStringExtra(ModSyncClient.EXTRA_REL_PATH) ?: return
            val instance = currentSessionInstance() ?: watchInstance ?: return
            val backup = Vault.backupFromLauncher(context, instance, relPath)
            Log.i(TAG, "Mid-game change backed up: $relPath -> ${backup != null}")
        }
    }

    private val livenessConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Bound to game liveness service")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Transient; process may still come back.
            Log.w(TAG, "Liveness binding disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            // The :game process is GONE — this is the crash/exit signal.
            Log.e(TAG, "Game process died — wiping injected files (crash-safe path)")
            endSession(applicationContext, watchInstance ?: "")
            watchInstance?.let { notifyGameState(it, ended = true) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every entry point promotes the service to a real foreground service
        // first — including the ACTION_BIND_LIVENESS path arriving straight
        // from the injection receiver — so ModInj is never killed mid-session.
        createChannel(this)
        val notification = buildNotification(getString(R.string.notif_watching))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_STOP_WATCH) {
            Log.i(TAG, "Stop requested — ending session if active")
            endSession(this, watchInstance ?: "")
            stopSelf()
            return START_NOT_STICKY
        }

        registerReceivers()

        if (intent?.action == ACTION_BIND_LIVENESS) {
            // Game is actually starting now — the :game process exists.
            Log.i(TAG, "Rebinding liveness at game start")
        } else {
            // Recover from a previous crash where BOTH processes died: a stale
            // session means injected files may still be lying around.
            cleanupStaleSession()
        }

        bindLiveness()
        return START_STICKY
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filterEnded = IntentFilter(ModSyncClient.ACTION_GAME_ENDED)
        val filterChanged = IntentFilter(ModSyncClient.ACTION_FILE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            // These actions are NOT system broadcasts, so Android 13+/14+
            // requires an explicit export flag. The broadcasts come from the
            // LAUNCHER (a different app), so the receivers must be EXPORTED —
            // the permission argument still restricts delivery to senders
            // holding the launcher's signature-level MODSYNC permission.
            registerReceiver(
                endedReceiver, filterEnded, ModSyncClient.PERMISSION, null,
                Context.RECEIVER_EXPORTED
            )
            registerReceiver(
                fileChangedReceiver, filterChanged, ModSyncClient.PERMISSION, null,
                Context.RECEIVER_EXPORTED
            )
        } else {
            // Pre-13: permission guard only, no export flag concept.
            registerReceiver(endedReceiver, filterEnded, ModSyncClient.PERMISSION, null)
            registerReceiver(fileChangedReceiver, filterChanged, ModSyncClient.PERMISSION, null)
        }
        receiversRegistered = true
    }

    private fun bindLiveness() {
        val pkg = ModSyncClient.launcherPackage ?: return
        val intent = Intent().setClassName(pkg, "com.movtery.zalithlauncher.modsync.GameLivenessService")
        bound = try {
            // Flags = 0: never auto-create the :game process; only observe it
            // while a game session is actually running.
            bindService(intent, livenessConnection, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Liveness bind failed: ${t.message}")
            false
        }
    }

    private fun currentSessionInstance(): String? {
        val json = ModSyncClient.sessionState(this) ?: return null
        return try {
            org.json.JSONObject(json).optString("instance").ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanupStaleSession() {
        val stale = ModSyncClient.sessionState(this) ?: return
        val instance = try {
            org.json.JSONObject(stale).optString("instance")
        } catch (_: Exception) {
            ""
        }
        Log.w(TAG, "Stale session found (instance='$instance') — cleaning up")
        endSession(this, instance)
    }

    private fun endSession(context: Context, instance: String) {
        val deleted = ModSyncClient.endSession(context)
        Log.i(TAG, "Session ended for '$instance', files wiped: $deleted")
        watchInstance = null
        if (instance.isNotEmpty()) notifyGameState(instance, ended = true)
    }

    override fun onDestroy() {
        isRunning = false
        if (receiversRegistered) {
            runCatching { unregisterReceiver(endedReceiver) }
            runCatching { unregisterReceiver(fileChangedReceiver) }
            receiversRegistered = false
        }
        if (bound) runCatching { unbindService(livenessConnection) }
        bound = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        // Tap = open ModInj; the "Stop" action explicitly ends watching.
        val openIntent = PendingIntentCompat.getActivity(
            this, 1, Intent(this, MainActivity::class.java)
        )
        val stopIntent = PendingIntentCompat.getService(
            this, 2, Intent(this, ModWatchService::class.java).setAction(ACTION_STOP_WATCH)
        )
        return NotificationCompatCompat.build(
            this, CHANNEL_ID, text, openIntent, stopIntent
        )
    }

    private fun notifyGameState(instance: String, ended: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val text = if (ended) getString(R.string.notif_game_ended, instance)
        else getString(R.string.notif_watching_instance, instance)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Game file watching", NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Keeps ModInj alive while Minecraft runs"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
}

/** Tiny indirection so the service file stays free of verbose builder boilerplate. */
private object NotificationCompatCompat {
    fun build(
        context: Context,
        channelId: String,
        text: String,
        openIntent: android.app.PendingIntent?,
        stopIntent: android.app.PendingIntent?
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stop),
            context.getString(R.string.notif_stop), stopIntent
        ).build()
        return builder
            .setSmallIcon(R.drawable.ic_stat_modinj)
            .setContentTitle("ModInj")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }
}

private object PendingIntentCompat {
    fun getActivity(context: Context, requestCode: Int, intent: Intent): android.app.PendingIntent? =
        android.app.PendingIntent.getActivity(
            context, requestCode, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

    fun getService(context: Context, requestCode: Int, intent: Intent): android.app.PendingIntent? =
        android.app.PendingIntent.getService(
            context, requestCode, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
}
