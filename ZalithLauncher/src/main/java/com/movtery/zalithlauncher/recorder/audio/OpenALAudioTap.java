package com.movtery.zalithlauncher.recorder.audio;

import android.util.Log;

/**
 * Java front-end for the native OpenAL-soft audio tap (see
 * {@code jni/recordzytap/recordzy_audiotap.cpp}).
 *
 * <p>Captures the exact PCM Minecraft renders through OpenAL (game sounds +
 * simple-voicechat playback) by inline-hooking the mixer output. No
 * MediaProjection, no permission prompt. If the native library or the hook
 * isn't available, {@link #start()} returns {@code false} and the recorder
 * falls back to video-only.</p>
 */
public final class OpenALAudioTap {

    private static final String TAG = "OpenALAudioTap";

    private static boolean sLibLoaded = false;
    private static boolean sLibTried = false;
    private static volatile String sLoadError = null;

    private OpenALAudioTap() {
    }

    private static synchronized boolean ensureLib() {
        if (sLibTried) {
            return sLibLoaded;
        }
        sLibTried = true;
        // Load the ShadowHook dependency explicitly first so a packaging problem
        // is reported against the right library, then our tap.
        try {
            System.loadLibrary("shadowhook");
        } catch (Throwable t) {
            sLoadError = "shadowhook: " + t.getMessage();
            Log.w(TAG, "libshadowhook.so load failed", t);
        }
        try {
            System.loadLibrary("recordzytap");
            sLibLoaded = true;
        } catch (Throwable t) {
            sLoadError = (sLoadError != null ? sLoadError + " | " : "")
                    + "recordzytap: " + t.getMessage();
            Log.w(TAG, "librecordzytap.so load failed", t);
            sLibLoaded = false;
        }
        return sLibLoaded;
    }

    /** Installs the hook (idempotent) and begins filling the capture buffer. */
    public static boolean start() {
        if (!ensureLib()) {
            return false;
        }
        try {
            return nativeStart();
        } catch (Throwable t) {
            Log.e(TAG, "nativeStart failed", t);
            return false;
        }
    }

    /** Stops filling the buffer (leaves the hook installed for next time). */
    public static void stop() {
        if (!sLibLoaded) {
            return;
        }
        try {
            nativeStop();
        } catch (Throwable ignored) {
        }
    }

    /** Device sample rate, or 0 until the first mixer frame has been seen. */
    public static int getSampleRate() {
        return sLibLoaded ? safeGetSampleRate() : 0;
    }

    /** Channel count (typically 2), or 0 until the first mixer frame. */
    public static int getChannels() {
        return sLibLoaded ? safeGetChannels() : 0;
    }

    /** Human-readable status of the last {@link #start()} attempt. */
    public static String getStatusMessage() {
        if (!sLibTried) {
            return "not started";
        }
        if (!sLibLoaded) {
            return "lib load failed: " + (sLoadError != null ? sLoadError : "unknown");
        }
        int code;
        try {
            code = nativeGetStatus();
        } catch (Throwable t) {
            return "unknown";
        }
        switch (code) {
            case 0: return "hook installed";
            case 2: return "ShadowHook init failed";
            case 3: return "OpenAL symbol not found";
            case 4: return "inline hook failed";
            case 5: return "out of memory";
            default: return "code " + code;
        }
    }

    /** How many times the hooked mixer function has run (0 = hook not firing). */
    public static long getHookCalls() {
        if (!sLibLoaded) return 0;
        try {
            return nativeGetHookCalls();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Total int16 samples captured since the last start. */
    public static long getCapturedSamples() {
        if (!sLibLoaded) return 0;
        try {
            return nativeGetCapturedSamples();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Drains up to {@code out.length} interleaved int16 samples into {@code out}.
     * @return the number of samples copied (0 if none are buffered yet).
     */
    public static int read(short[] out, int maxSamples) {
        if (!sLibLoaded || out == null) {
            return 0;
        }
        try {
            return nativeRead(out, Math.min(maxSamples, out.length));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safeGetSampleRate() {
        try {
            return nativeGetSampleRate();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safeGetChannels() {
        try {
            return nativeGetChannels();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static native boolean nativeStart();

    private static native void nativeStop();

    private static native int nativeGetSampleRate();

    private static native int nativeGetChannels();

    private static native int nativeGetStatus();

    private static native long nativeGetHookCalls();

    private static native long nativeGetCapturedSamples();

    private static native int nativeRead(short[] out, int maxSamples);
}
