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
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Foreground service that keeps ModInj alive for the whole game session so it
 * is never killed mid-game. It:
 *  - binds to the launcher's GameLivenessService (inside the `:game` process —
 *    the process that hosts the game UI + JVM). When that binding dies the
 *    game process is gone: clean exit, hard crash, or force close — ALL paths;
 *  - receives mid-game FILE_CHANGED broadcasts and copies updated files into
 *    the backup store;
 *  - ends the session on GAME_ENDED / binding death — the provider then wipes
 *    exactly the injected files.
 *
 * LIFENESS BINDING — why BIND_AUTO_CREATE:
 * the binding used to be flags=0 ("never auto-create"). But a non-AUTO_CREATE
 * binding only ever attaches to an ALREADY-CREATED service, and nothing in the
 * launcher creates GameLivenessService on its own — so with the current
 * launcher build the binding would sit dormant forever and the crash/force-
 * close detection would be dead code. AUTO_CREATE makes the system create the
 * service (and the :game process, which the launcher needs moments later for
 * the game anyway) and guarantees onBindingDied when that process dies.
 * endSession() unbinds, releasing the create-ref, so ModInj never keeps an
 * empty :game process alive after the game is done.
 *
 * STALE SESSIONS — adopt or wipe, never guess wrong in the destructive way:
 * if the service starts and the provider still holds a session, the game is
 * either still running (ModInj was killed mid-game) or long dead (reboot,
 * force-stop). A passive (flags=0) probe binding connects only if the liveness
 * service still exists (= game alive) — connect => adopt the session; no
 * connect within the probe window => wipe. One override: if ModInj itself died
 * while a session was live and the device did NOT reboot meanwhile, adopt
 * directly (the probe cannot reconnect because the create-ref died with the
 * old process). Reboots are detected via SystemClock.elapsedRealtime().
 */
class ModWatchService : Service() {
    companion object {
        private const val TAG = "ModWatchService"
        private const val CHANNEL_ID = "modinj_watch"
        private const val NOTIFICATION_ID = 42

        const val ACTION_STOP_WATCH = "com.zack694.modinj.ACTION_STOP_WATCH"
        const val ACTION_BIND_LIVENESS = "com.zack694.modinj.ACTION_BIND_LIVENESS"

        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val LOST_GRACE_MS = 15_000L        // connected once, then lost
        private const val BIND_FAIL_GRACE_MS = 60_000L   // never connected, bind keeps failing
        private const val STALE_PROBE_MS = 10_000L       // passive probe window for stale sessions

        var isRunning = false
            private set
    }

    /** ModInj-side session bookkeeping that survives process death. */
    internal object WatchState {
        private const val PREFS = "watch_state"
        private const val KEY_WATCHING = "watching"
        private const val KEY_INSTANCE = "instance"
        private const val KEY_SINCE = "since"
        private const val KEY_ELAPSED = "elapsed"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun mark(context: Context, instance: String) {
            prefs(context).edit()
                .putBoolean(KEY_WATCHING, true)
                .putString(KEY_INSTANCE, instance)
                .putLong(KEY_SINCE, System.currentTimeMillis())
                .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime())
                .apply()
        }

        /** Keeps the reboot marker fresh while a session is live. */
        fun touch(context: Context) {
            prefs(context).edit()
                .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime())
                .apply()
        }

        fun clear(context: Context) {
            prefs(context).edit().putBoolean(KEY_WATCHING, false).apply()
        }

        data class State(val instance: String, val since: Long, val elapsedAtMark: Long)

        fun snapshot(context: Context): State? {
            val p = prefs(context)
            if (!p.getBoolean(KEY_WATCHING, false)) return null
            val instance = p.getString(KEY_INSTANCE, "").orEmpty()
            if (instance.isEmpty()) return null
            return State(instance, p.getLong(KEY_SINCE, 0L), p.getLong(KEY_ELAPSED, 0L))
        }

        /** True when the device rebooted after [state] was recorded. */
        fun rebootedSince(context: Context, state: State): Boolean =
            SystemClock.elapsedRealtime() < state.elapsedAtMark
    }

    private enum class Mode { IDLE, WATCHING, RESOLVING }

    private var mode = Mode.IDLE

    // Liveness binding state
    private var bindRegistered = false     // bindService() succeeded; unbind required
    private var connected = false          // onServiceConnected has fired
    private var everConnected = false      // connected at least once for this session
    private var firstBindAt = 0L           // first bind attempt of the current session
    private var disconnectedAt = 0L        // when the binding was last observed lost

    // Stale-session probe state (Mode.RESOLVING)
    private var staleInstance: String? = null
    private var staleSince = 0L

    private var receiversRegistered = false
    private var watchInstance: String? = null

    private val watchdog = Handler(Looper.getMainLooper())
    private val watchdogTick = object : Runnable {
        override fun run() {
            try {
                stepWatchdog()
            } catch (t: Throwable) {
                Log.w(TAG, "Watchdog step failed: ${t.message}")
            }
            watchdog.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

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
            connected = true
            everConnected = true
            Log.i(TAG, "Bound to game liveness service (game process alive)")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected = false
            disconnectedAt = SystemClock.elapsedRealtime()
            Log.w(TAG, "Liveness binding disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            // The :game process is GONE — clean exit, crash or force close all
            // end here. This is the definitive game-death signal.
            Log.e(TAG, "Game process died — wiping injected files (crash-safe path)")
            connected = false
            unbindLiveness()
            endSession(applicationContext, watchInstance ?: "")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        watchdog.post(watchdogTick)
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
            // Game is starting right now: InjectionReceiver just registered a
            // fresh session. Bind AUTO_CREATE and watch it live or die.
            val instance = currentSessionInstance() ?: ""
            if (instance.isNotEmpty()) {
                watchInstance = instance
                WatchState.mark(this, instance)
                mode = Mode.WATCHING
                firstBindAt = SystemClock.elapsedRealtime()
                everConnected = false
                connected = false
                bindLiveness(autoCreate = true)
                notifyGameState(instance, ended = false)
                Log.i(TAG, "Session adopted at game start: '$instance'")
            } else {
                Log.w(TAG, "BIND_LIVENESS without a provider session — resolving as stale")
                startStaleResolve()
            }
        } else {
            // Boot autostart, app-open autostart or a STICKY restart: figure
            // out whether a game session exists and what to do with it.
            resolveSessionOnStart()
        }

        return START_STICKY
    }

    /**
     * Entry-point reconciliation: adopt a live session, probe an ambiguous
     * one, or go idle. Never wipes blindly — see the class doc.
     */
    private fun resolveSessionOnStart() {
        val providerSession = currentSessionInstance()
        if (providerSession == null) {
            // No session at the provider: nothing to watch, nothing to wipe.
            WatchState.clear(this)
            mode = Mode.IDLE
            watchInstance = null
            Log.i(TAG, "No active session — service idle (watching for the next launch)")
            return
        }

        val marker = WatchState.snapshot(this)
        if (marker != null && marker.instance == providerSession && !WatchState.rebootedSince(this, marker)) {
            // ModInj died while this exact session was live and the device did
            // not reboot since: the game is very likely still running. Adopt.
            Log.w(TAG, "Recovered mid-game (session='$providerSession') — resuming watch")
            watchInstance = providerSession
            mode = Mode.WATCHING
            firstBindAt = SystemClock.elapsedRealtime()
            everConnected = false
            connected = false
            bindLiveness(autoCreate = true)
            notifyGameState(providerSession, ended = false)
        } else {
            // Ambiguous: probe passively, adopt on connect, wipe on timeout.
            startStaleResolve(providerSession)
        }
    }

    private fun startStaleResolve(instance: String? = null) {
        val target = instance ?: currentSessionInstance()
        if (target == null) {
            WatchState.clear(this)
            mode = Mode.IDLE
            return
        }
        staleInstance = target
        staleSince = SystemClock.elapsedRealtime()
        mode = Mode.RESOLVING
        everConnected = false
        connected = false
        bindLiveness(autoCreate = false) // passive probe: only connects if the service exists
        Log.i(TAG, "Probing stale session '$target' for ${STALE_PROBE_MS / 1000}s")
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

    private fun bindLiveness(autoCreate: Boolean) {
        unbindLiveness()
        val pkg = ModSyncClient.launcherPackage ?: run {
            // Discover the bridge lazily (fresh process may never have pinged).
            if (!ModSyncClient.ping(this)) {
                Log.w(TAG, "Liveness bind skipped: launcher bridge not found")
                return
            }
            ModSyncClient.launcherPackage!!
        }
        val intent = Intent().setClassName(pkg, "com.movtery.zalithlauncher.modsync.GameLivenessService")
        bindRegistered = try {
            bindService(intent, livenessConnection, if (autoCreate) Context.BIND_AUTO_CREATE else 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Liveness bind failed: ${t.message}")
            false
        }
        if (bindRegistered) {
            Log.i(TAG, "Liveness bind requested (autoCreate=$autoCreate)")
        }
    }

    private fun unbindLiveness() {
        if (bindRegistered) {
            runCatching { unbindService(livenessConnection) }
            bindRegistered = false
        }
        connected = false
    }

    private fun stepWatchdog() {
        if (mode == Mode.WATCHING) {
            WatchState.touch(this) // keeps the reboot marker fresh
            if (connected) return
            val now = SystemClock.elapsedRealtime()
            if (everConnected) {
                // Lost the binding. onBindingDied normally lands within a
                // second; passive rebinds act as probes while we wait, and
                // the grace covers missed signals. A reconnected probe means
                // the game process is alive again — go back to healthy.
                if (!bindRegistered) bindLiveness(autoCreate = false)
                if (now - disconnectedAt > LOST_GRACE_MS) {
                    Log.e(TAG, "Liveness lost for >${LOST_GRACE_MS / 1000}s — treating game as ended")
                    endSession(this, watchInstance ?: "")
                }
            } else {
                // Never connected (game process still booting, or the bridge
                // is gone). Keep retrying the create-ref bind; wipe only if
                // it stays down for the full grace.
                if (!bindRegistered) bindLiveness(autoCreate = true)
                if (now - firstBindAt > BIND_FAIL_GRACE_MS) {
                    Log.e(TAG, "Liveness never connected for >${BIND_FAIL_GRACE_MS / 1000}s — wiping orphaned session")
                    endSession(this, watchInstance ?: "")
                }
            }
        } else if (mode == Mode.RESOLVING) {
            val target = staleInstance ?: return
            if (connected) {
                // The liveness service exists => the game process is alive.
                Log.i(TAG, "Stale probe connected — session '$target' is LIVE, adopting")
                watchInstance = target
                WatchState.mark(this, target)
                staleInstance = null
                mode = Mode.WATCHING
                firstBindAt = SystemClock.elapsedRealtime()
                bindLiveness(autoCreate = true) // upgrade to a create-ref binding
                notifyGameState(target, ended = false)
            } else if (SystemClock.elapsedRealtime() - staleSince > STALE_PROBE_MS) {
                Log.e(TAG, "Stale probe found no live game — wiping session '$target'")
                unbindLiveness()
                staleInstance = null
                mode = Mode.IDLE
                endSession(this, target)
            }
        }
        // Mode.IDLE: nothing to do; new sessions arrive via onStartCommand.
    }

    /**
     * Makes sure the launcher bridge authority is discovered before ANY
     * provider interaction. A freshly started process (manifest GAME_ENDED
     * receiver, sticky restart, boot autostart) has authority == null — every
     * ModSyncClient call silently no-ops until ping() runs once.
     */
    private fun ensureBridge(): Boolean =
        ModSyncClient.authority != null || ModSyncClient.ping(this)

    private fun currentSessionInstance(): String? {
        if (!ensureBridge()) return null
        val json = ModSyncClient.sessionState(this) ?: return null
        return try {
            org.json.JSONObject(json).optString("instance").ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun endSession(context: Context, instance: String) {
        val deleted = if (ensureBridge()) ModSyncClient.endSession(context) else -1
        Log.i(TAG, "Session ended for '$instance', files wiped: $deleted")
        WatchState.clear(context)
        unbindLiveness()
        everConnected = false
        mode = Mode.IDLE
        watchInstance = null
        if (instance.isNotEmpty()) notifyGameState(instance, ended = true)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep-alive: the user swiped ModInj from recents while a game session
        // is live. Best-effort re-kick; START_STICKY covers the rest.
        super.onTaskRemoved(rootIntent)
        if (WatchState.snapshot(this) != null) {
            try {
                startForegroundService(Intent(this, ModWatchService::class.java))
                Log.i(TAG, "Task removed with a live session — service re-kicked")
            } catch (t: Throwable) {
                Log.w(TAG, "Re-kick after task removal failed: ${t.message}")
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        watchdog.removeCallbacks(watchdogTick)
        if (receiversRegistered) {
            runCatching { unregisterReceiver(endedReceiver) }
            runCatching { unregisterReceiver(fileChangedReceiver) }
            receiversRegistered = false
        }
        unbindLiveness()
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
