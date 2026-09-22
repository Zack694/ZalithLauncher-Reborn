package com.zack694.modinj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Fired by the launcher the instant the user presses "Launch Game" — BEFORE
 * the launcher scans mods or starts the JVM. This receiver synchronously
 * injects every vaulted file for that instance through the signature-guarded
 * provider, registers the wipe session, and ACKs the ordered broadcast so the
 * launcher continues booting the game.
 *
 * Manifest-declared + ordered broadcast: this wakes ModInj even if its process
 * was dead. Copying happens on a worker thread (goAsync) so the ordered
 * broadcast stays serialized until injection is complete.
 *
 * RESULT-CODE RULE (this rule fixes a real crash — do not "simplify" it away):
 * once [goAsync] is called, the receiver's own pending result is RELEASED.
 * Calling `setResultCode()` on the RECEIVER after that throws
 * `IllegalStateException: Call while result is not pending` and kills the
 * whole ModInj process mid-launch (which then cascades: the sticky service
 * restarts, treats the live session as stale and wipes files while the game
 * is still running). The ONLY valid target is the [PendingResult] returned by
 * [goAsync] — so [inject] returns the code and the worker thread sets it on
 * the pending result, right before finishing it.
 */
class InjectionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "InjectionReceiver"
        private const val MAX_INJECT_FILES = 256
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModSyncClient.ACTION_GAME_STARTING) return

        val instance = intent.getStringExtra(ModSyncClient.EXTRA_INSTANCE) ?: ""
        val gameDir = intent.getStringExtra(ModSyncClient.EXTRA_GAME_DIR) ?: ""

        Log.i(TAG, "Game starting: instance='$instance' — injection window open")

        val pending = goAsync()
        Thread {
            var result = ModSyncClient.RESULT_NO_SELECTION
            try {
                result = inject(context, instance, gameDir)
            } catch (t: Throwable) {
                Log.e(TAG, "Injection failed (launch continues without mods)", t)
            } finally {
                try {
                    pending.setResultCode(result)
                    pending.finish()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to close the injection window", t)
                }
            }
            Log.i(TAG, "Injection window closed (result=$result)")
        }.start()
    }

    /**
     * Performs the injection and returns the ordered-broadcast result code.
     * NEVER calls setResultCode() itself — the receiver's pending result is
     * already released once goAsync() ran.
     */
    private fun inject(context: Context, instance: String, gameDir: String): Int {
        // Cold-process guard: this broadcast can wake a dead ModInj process in
        // which the bridge authority was never discovered — without a ping
        // every provider call below would silently no-op.
        if (ModSyncClient.authority == null && !ModSyncClient.ping(context)) {
            Log.w(TAG, "Launcher bridge not found — skipping injection")
            return ModSyncClient.RESULT_NO_SELECTION
        }

        val entries = Vault.entriesForInstance(context, instance)
        if (entries.isEmpty()) {
            Log.i(TAG, "No vault selection for '$instance' — nothing to inject")
            return ModSyncClient.RESULT_NO_SELECTION
        }

        var injected = 0
        val injectedRelPaths = ArrayList<String>()
        for (entry in entries.take(MAX_INJECT_FILES)) {
            val vaultFile = File(Vault.vaultDir(context), entry.stored)
            if (!vaultFile.isFile) continue
            val ok = vaultFile.inputStream().use { input ->
                ModSyncClient.writeFrom(context, entry.relPath, input)
            }
            if (ok) {
                injected++
                injectedRelPaths.add(entry.relPath)
                Log.i(TAG, "Injected: ${entry.relPath}")
            } else {
                Log.w(TAG, "Injection failed for: ${entry.relPath}")
            }
        }

        // Track ONLY what was actually injected: arms the mid-game change
        // watcher and defines exactly what gets wiped on exit/crash.
        if (injectedRelPaths.isNotEmpty()) {
            val sessionSet = ModSyncClient.setSession(context, gameDir, instance, injectedRelPaths)
            Log.i(TAG, "Injected $injected/${entries.size} files (sessionSet=$sessionSet)")

            // If the watch service is running, have it bind to the game process
            // now that it actually exists (crash detector). Best-effort only.
            if (sessionSet) runCatching {
                context.startService(
                    Intent(context, ModWatchService::class.java)
                        .setAction(ModWatchService.ACTION_BIND_LIVENESS)
                )
            }
        } else {
            Log.w(TAG, "Nothing injected (vault blobs missing for ${entries.size} entries)")
        }

        return ModSyncClient.RESULT_INJECTED
    }
}
