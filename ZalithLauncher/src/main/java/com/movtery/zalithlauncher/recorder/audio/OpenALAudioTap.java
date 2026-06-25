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

    private OpenALAudioTap() {
    }

    private static synchronized boolean ensureLib() {
        if (sLibTried) {
            return sLibLoaded;
        }
        sLibTried = true;
        try {
            System.loadLibrary("recordzytap");
            sLibLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "librecordzytap.so unavailable; audio capture disabled", t);
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

    private static native int nativeRead(short[] out, int maxSamples);
}
