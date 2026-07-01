package com.movtery.zalithlauncher.recorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Captures the microphone (mono, 16-bit PCM) on a background thread into a small
 * ring buffer, for push-to-talk mixing into the game recording.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li><b>No hardware NoiseSuppressor / AcousticEchoCanceler.</b> On this device
 *       those forced a "voice-comms" path that made the mic sound hollow/robotic
 *       ("pilot's mic") and added latency, while still failing to cancel game
 *       audio (Android's AEC references the phone-call downlink, not the game's
 *       media output). We use the raw mic instead.</li>
 *   <li><b>Low latency.</b> A small AudioRecord buffer plus a capped ring backlog
 *       keep the captured voice close to real time so it stays aligned with the
 *       game audio it's mixed into (no drift/delay).</li>
 *   <li><b>Gentle noise gate.</b> An envelope-follower gate (fast attack, slow
 *       release, short hold) mutes the mic below a threshold, so idle hiss and
 *       quiet game-speaker bleed between words are removed - without any spectral
 *       processing artifacts. Loud bleed during speech still passes (use
 *       headphones to eliminate speaker->mic bleed entirely).</li>
 * </ul>
 *
 * <p>The recorder audio pump only pulls samples while the PTT button is held;
 * when it isn't, the reader keeps AudioRecord drained but discards the data (and
 * clears any backlog), so each press starts from fresh, live audio. All heavy
 * work runs on the dedicated reader thread - never the UI thread.</p>
 */
final class MicCapture {
    private static final String TAG = "RecordZyMic";

    private final Context appContext;
    private final int sampleRate;

    // Max mic latency we tolerate before dropping the oldest samples (~120ms).
    private final int maxBacklog;

    private AudioRecord record;
    private Thread readerThread;
    private volatile boolean running = false;
    private volatile boolean active = false;

    // Mono ring buffer, guarded by `lock`.
    private final Object lock = new Object();
    private short[] ring;
    private int head, tail, count;

    // --- Noise gate state (reader thread only) ---
    // Threshold in 16-bit amplitude. ~500/32768 ~= -36 dBFS: normal speech opens
    // the gate easily; idle hiss / quiet bleed stays below it and is muted.
    private static final float GATE_OPEN_AMP = 500f;
    private static final float ENV_DECAY = 0.9997f; // envelope release per sample
    private static final float ATTACK = 0.05f;      // gain rise (fast, ~ms)
    private static final float RELEASE = 0.0006f;    // gain fall (slow, ~35ms)
    private float gateEnv = 0f;   // running amplitude envelope
    private float gateGain = 0f;  // smoothed 0..1 gain applied to samples
    private int gateHold = 0;     // samples to keep the gate open after last peak
    private final int gateHoldSamples;

    MicCapture(Context context, int sampleRate) {
        this.appContext = context.getApplicationContext();
        this.sampleRate = sampleRate > 0 ? sampleRate : 48000;
        this.maxBacklog = Math.max(512, this.sampleRate / 8);   // ~125ms
        this.gateHoldSamples = this.sampleRate / 6;             // ~160ms hold
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
        if (minBuf <= 0) minBuf = 8192;
        int bufSize = Math.max(minBuf * 2, 8192);

        // Try the rawest / most full-band source first so the voice doesn't sound
        // narrowband/robotic ("pilot's mic") from the OEM telephony/comms mic path.
        // UNPROCESSED (when the device supports it) = no OEM DSP at all;
        // VOICE_RECOGNITION = clean, full-band, no AGC/NS; MIC = last resort.
        for (int src : buildSourceCandidates()) {
            try {
                AudioRecord r = new AudioRecord(src, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    record = r;
                    Log.i(TAG, "Mic source = " + sourceName(src));
                    break;
                }
                r.release();
            } catch (Throwable t) {
                Log.w(TAG, "AudioRecord source " + sourceName(src) + " failed: " + t);
            }
        }
        if (record == null) {
            Log.w(TAG, "No usable mic AudioRecord source");
            return false;
        }
        ring = new short[Math.max(maxBacklog * 4, sampleRate / 2)];
        head = tail = count = 0;
        gateEnv = 0f;
        gateGain = 0f;
        gateHold = 0;
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
        Log.i(TAG, "Mic capture started @" + sampleRate + "Hz mono (raw, gated, low-latency)");
        return true;
    }

    void setActive(boolean a) {
        active = a;
    }

    /**
     * Ordered list of mic sources to try, rawest/full-band first, to avoid the
     * OEM "communication" mic path that produces the narrowband "pilot's mic"
     * sound. UNPROCESSED is only offered when the device advertises support.
     */
    private int[] buildSourceCandidates() {
        boolean unprocessed = false;
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            unprocessed = am != null && "true".equals(
                    am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        } catch (Throwable ignored) {}
        if (unprocessed) {
            return new int[]{
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC};
        }
        return new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC};
    }

    private static String sourceName(int src) {
        if (src == MediaRecorder.AudioSource.UNPROCESSED) return "UNPROCESSED";
        if (src == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
        return "MIC";
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
                // Keep the mic drained but discard, clear the backlog and reset the
                // gate so the next press starts from fresh, live, closed-gate audio.
                synchronized (lock) {
                    head = tail = count = 0;
                }
                gateEnv = 0f;
                gateGain = 0f;
                gateHold = 0;
                continue;
            }
            applyGate(buf, n);
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
                // Keep latency bounded: if the consumer fell behind, drop the
                // oldest samples so the mic never lags the game audio.
                if (count > maxBacklog) {
                    int drop = count - maxBacklog;
                    head = (head + drop) % ring.length;
                    count -= drop;
                }
            }
        }
    }

    /** In-place noise gate: mute samples while the envelope is below threshold. */
    private void applyGate(short[] buf, int n) {
        for (int i = 0; i < n; i++) {
            float a = Math.abs(buf[i]);
            // Peak-hold envelope with slow decay.
            gateEnv = a > gateEnv ? a : gateEnv * ENV_DECAY;
            if (gateEnv > GATE_OPEN_AMP) {
                gateHold = gateHoldSamples;
            } else if (gateHold > 0) {
                gateHold--;
            }
            float target = gateHold > 0 ? 1f : 0f;
            float coef = target > gateGain ? ATTACK : RELEASE;
            gateGain += (target - gateGain) * coef;
            buf[i] = (short) (buf[i] * gateGain);
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
