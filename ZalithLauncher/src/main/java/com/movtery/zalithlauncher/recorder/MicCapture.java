package com.movtery.zalithlauncher.recorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Captures the microphone (mono, 16-bit PCM) on a background thread into a small
 * ring buffer, for push-to-talk mixing into the game recording.
 *
 * <p>The recorder audio pump only pulls samples while the PTT button is held.
 * When it isn't held the reader keeps {@link AudioRecord} drained but discards
 * the data (and clears any backlog), so pressing the button always starts from
 * live audio with no stale lag.</p>
 *
 * <p>All heavy work runs on the dedicated reader thread — never the UI thread.</p>
 */
final class MicCapture {
    private static final String TAG = "RecordZyMic";

    private final Context appContext;
    private final int sampleRate;

    private AudioRecord record;
    private Thread readerThread;
    private volatile boolean running = false;
    private volatile boolean active = false;

    // Hardware DSP voice effects (Qualcomm Hexagon on the Snapdragon 7s Gen 2):
    // noise suppression + acoustic echo cancellation attached to the mic session.
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;

    // Mono ring buffer (~1s), guarded by `lock`.
    private final Object lock = new Object();
    private short[] ring;
    private int head, tail, count;

    MicCapture(Context context, int sampleRate) {
        this.appContext = context.getApplicationContext();
        this.sampleRate = sampleRate > 0 ? sampleRate : 48000;
    }

    boolean hasPermission() {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return true if the mic actually started (permission granted + AudioRecord OK). */
    @SuppressLint("MissingPermission")
    boolean start() {
        if (!hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted; push-to-talk mic disabled");
            return false;
        }
        int minBuf = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = sampleRate * 2;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, sampleRate) * 2);
        } catch (Throwable t) {
            Log.w(TAG, "AudioRecord create failed: " + t);
            record = null;
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized");
            try { record.release(); } catch (Throwable ignored) {}
            record = null;
            return false;
        }
        applyVoiceEffects(record.getAudioSessionId());
        ring = new short[sampleRate]; // ~1s of mono headroom
        head = tail = count = 0;
        running = true;
        try {
            record.startRecording();
        } catch (Throwable t) {
            Log.w(TAG, "startRecording failed: " + t);
            running = false;
            try { record.release(); } catch (Throwable ignored) {}
            record = null;
            return false;
        }
        readerThread = new Thread(this::readerLoop, "RecordZy-Mic");
        readerThread.start();
        Log.i(TAG, "Mic capture started @" + sampleRate + "Hz mono");
        return true;
    }

    void setActive(boolean a) {
        active = a;
    }

    /**
     * Attach the device's built-in DSP voice effects to the mic session. On the
     * Snapdragon 7s Gen 2 (Qualcomm Hexagon) these run on dedicated hardware, so
     * they clean up the voice with no extra CPU cost or latency. Guarded by
     * availability + try/catch since some devices don't expose them.
     * AGC (auto-gain) is intentionally left off to keep the voice natural
     * (it tends to pump/breathe).
     */
    private void applyVoiceEffects(int sessionId) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                    Log.i(TAG, "NoiseSuppressor enabled=" + noiseSuppressor.getEnabled());
                }
            } else {
                Log.i(TAG, "NoiseSuppressor not available on this device");
            }
        } catch (Throwable t) {
            Log.w(TAG, "NoiseSuppressor attach failed: " + t);
            noiseSuppressor = null;
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                    Log.i(TAG, "AcousticEchoCanceler enabled=" + echoCanceler.getEnabled());
                }
            } else {
                Log.i(TAG, "AcousticEchoCanceler not available on this device");
            }
        } catch (Throwable t) {
            Log.w(TAG, "AcousticEchoCanceler attach failed: " + t);
            echoCanceler = null;
        }
    }

    private void readerLoop() {
        short[] buf = new short[1024];
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Throwable t) {
                break;
            }
            if (n <= 0) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (!active) {
                // Keep the mic drained but discard, and clear the backlog so the
                // next press starts from fresh, live audio.
                synchronized (lock) {
                    head = tail = count = 0;
                }
                continue;
            }
            synchronized (lock) {
                for (int i = 0; i < n; i++) {
                    if (count == ring.length) {
                        head = (head + 1) % ring.length; // full: drop oldest
                        count--;
                    }
                    ring[tail] = buf[i];
                    tail = (tail + 1) % ring.length;
                    count++;
                }
            }
        }
    }

    /** Drain up to {@code max} mono samples into {@code out}; returns count read. */
    int read(short[] out, int max) {
        synchronized (lock) {
            int n = Math.min(max, Math.min(count, out.length));
            for (int i = 0; i < n; i++) {
                out[i] = ring[head];
                head = (head + 1) % ring.length;
            }
            count -= n;
            return n;
        }
    }

    void stop() {
        running = false;
        active = false;
        if (readerThread != null) {
            try {
                readerThread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Throwable ignored) {}
            noiseSuppressor = null;
        }
        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (Throwable ignored) {}
            echoCanceler = null;
        }
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) {}
            try { record.release(); } catch (Throwable ignored) {}
            record = null;
        }
        synchronized (lock) {
            head = tail = count = 0;
        }
    }
}
