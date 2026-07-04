package com.movtery.zalithlauncher.recorder.audio;

import android.util.Log;

/**
 * Thin wrapper around the native RNNoise (Xiph, BSD) recurrent-neural-network
 * speech denoiser (librecordzydsp.so).
 *
 * <p>RNNoise removes background noise (fans, keyboard, hiss, ambient, and much
 * non-speech sound) while preserving the voice, and returns a voice-activity
 * probability. It processes fixed mono frames of {@link #frameSize()} samples
 * (480 = 10 ms) at 48 kHz, 16-bit PCM.</p>
 *
 * <p>If the native library is missing (e.g. an ABI where it wasn't built) the
 * wrapper degrades gracefully: {@link #isAvailable()} is false and the recorder
 * simply skips denoising.</p>
 */
public final class RnnoiseDenoiser {
    private static final String TAG = "RecordZyDsp";
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("recordzydsp");
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "librecordzydsp not available: " + t);
            ok = false;
        }
        LOADED = ok;
    }

    private long handle;

    /** True if the native denoiser library loaded on this ABI. */
    public static boolean isAvailable() {
        return LOADED;
    }

    /** RNNoise frame size in mono samples (480 @ 48 kHz), or 0 if unavailable. */
    public static int frameSize() {
        if (!LOADED) return 0;
        try {
            return nativeFrameSize();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Create the denoiser; @return true on success. */
    public boolean create() {
        if (!LOADED) return false;
        try {
            handle = nativeCreate();
        } catch (Throwable t) {
            Log.w(TAG, "RNNoise create failed: " + t);
            handle = 0;
        }
        return handle != 0;
    }

    /**
     * Denoise one frame in place ({@code frame} must be {@link #frameSize()}
     * samples). @return voice-activity probability [0..1].
     */
    public float process(short[] frame) {
        if (handle == 0) return 0f;
        try {
            return nativeProcess(handle, frame);
        } catch (Throwable t) {
            return 0f;
        }
    }

    public void destroy() {
        if (handle != 0) {
            try {
                nativeDestroy(handle);
            } catch (Throwable ignored) {
            }
            handle = 0;
        }
    }

    private static native int nativeFrameSize();
    private static native long nativeCreate();
    private static native float nativeProcess(long handle, short[] frame);
    private static native void nativeDestroy(long handle);
}
