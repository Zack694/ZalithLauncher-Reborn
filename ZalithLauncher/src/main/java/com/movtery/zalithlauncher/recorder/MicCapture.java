package com.movtery.zalithlauncher.recorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.movtery.zalithlauncher.recorder.audio.RnnoiseDenoiser;

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

    // Max mic latency we tolerate before dropping the oldest samples (~150ms).
    private final int maxBacklog;

    // Jitter-buffer cushion (~50ms). The audio pump drains the mic in bursts, so
    // we buffer this much before we start serving and refuse to serve until we
    // have it again after a dry-out. This keeps every mixed block full (got ==
    // frames) so the mixer never has to fade on an under-run - which is what made
    // the voice "cut"/warble when the ring ran chronically near-empty.
    private final int primeTarget;
    private boolean primed = false;

    private AudioRecord record;
    private Thread readerThread;
    private volatile boolean running = false;
    private volatile boolean active = false;

    // Mono ring buffer, guarded by `lock`.
    private final Object lock = new Object();
    private short[] ring;
    private int head, tail, count;

    // RNNoise neural denoiser (48 kHz only). Removes background noise while
    // keeping speech. The mic is framed into fixed rnFrame-size (480) blocks
    // before denoising. Null => denoising disabled (unsupported ABI / not 48k).
    private RnnoiseDenoiser denoiser;
    private short[] rnFrame;   // 480-sample accumulator (reader thread only)
    private int rnFill;

    // --- Makeup gain ---
    // Full-band sources (VOICE_RECOGNITION / UNPROCESSED) have no AGC, so the raw
    // voice is much quieter than the old MIC source. Boost it so it's audible in
    // the mix and the gate can track it. Clamped to avoid clipping.
    private static final float MAKEUP_GAIN = 3.0f;

    // --- Adaptive, FAIL-OPEN noise gate state (reader thread only) ---
    // The gate is RELATIVE to your own recent voice level, not a fixed threshold,
    // so it works no matter how quiet/loud the source is. It starts fully OPEN
    // and only attenuates once it has seen clear speech AND the current level has
    // dropped far below it - so it can never silence an active voice.
    private static final float ENV_DECAY = 0.9997f;     // fast envelope release/sample
    private static final float REF_DECAY = 0.999985f;   // voice-reference release (~secs)
    private static final float OPEN_RATIO = 0.10f;      // open when within ~20dB of ref
    private static final float MIN_REF = 900f;          // below this ref => fail-open
    private static final float ATTACK = 0.05f;          // gain rise (fast, ~ms)
    private static final float RELEASE = 0.0008f;       // gain fall (slow, ~25ms)
    private float gateEnv = 0f;    // fast amplitude envelope
    private float voiceRef = 0f;   // slow tracker of recent voice/peak level
    private float gateGain = 1f;   // smoothed 0..1 gain; starts OPEN (fail-open)

    MicCapture(Context context, int sampleRate) {
        this.appContext = context.getApplicationContext();
        this.sampleRate = sampleRate > 0 ? sampleRate : 48000;
        this.maxBacklog = Math.max(1024, this.sampleRate * 150 / 1000);  // ~150ms hard cap
        this.primeTarget = Math.max(512, this.sampleRate * 50 / 1000);    // ~50ms cushion
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

        // Use a full-band source so the voice isn't narrowband/robotic ("pilot's
        // mic") from the OEM telephony/comms mic path: VOICE_RECOGNITION first
        // (clean, full-band, with usable level), MIC as fallback.
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
        primed = false;
        gateEnv = 0f;
        voiceRef = 0f;
        gateGain = 1f; // start open

        // RNNoise operates at 48 kHz only. Enable it when the source is 48 kHz
        // and the native lib is present; otherwise fall back to gain+gate alone.
        denoiser = null;
        rnFill = 0;
        if (sampleRate == 48000 && RnnoiseDenoiser.isAvailable()) {
            RnnoiseDenoiser d = new RnnoiseDenoiser();
            int fs = RnnoiseDenoiser.frameSize();
            if (fs > 0 && d.create()) {
                denoiser = d;
                rnFrame = new short[fs];
                Log.i(TAG, "RNNoise denoiser ACTIVE (frame=" + fs + ")");
            } else {
                d.destroy();
            }
        }
        if (denoiser == null) {
            Log.i(TAG, "RNNoise denoiser inactive (available="
                    + RnnoiseDenoiser.isAvailable() + ", sampleRate=" + sampleRate + ")");
        }
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
     * Ordered list of mic sources to try. VOICE_RECOGNITION is full-band (avoids
     * the narrowband "pilot's mic" telephony path) AND keeps a usable input level,
     * unlike UNPROCESSED which on some devices returns a near-silent, un-gained
     * signal (that made the voice inaudible). MIC is the fallback.
     */
    private int[] buildSourceCandidates() {
        return new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC};
    }

    private static String sourceName(int src) {
        if (src == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
        if (src == MediaRecorder.AudioSource.MIC) return "MIC";
        return "SRC_" + src;
    }

    private void readerLoop() {
        // Run the mic capture + RNNoise denoise below the game's render/GL
        // threads so it yields CPU under contention - this is what removes the
        // ~5-10 fps dip while push-to-talk is held. We only lower the nice value
        // (stay on the normal/foreground cpuset), NOT full THREAD_PRIORITY_
        // BACKGROUND, so the thread is never throttled onto starved little cores;
        // the ~50 ms jitter buffer absorbs the small extra scheduling latency, so
        // audio stays glitch-free.
        try {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_DEFAULT + 6);
        } catch (Throwable ignored) {
        }
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
                    primed = false; // re-buffer the cushion on the next press
                }
                gateEnv = 0f;
                voiceRef = 0f;
                gateGain = 1f; // reset open so the next press never starts muted
                rnFill = 0;
                continue;
            }
            if (denoiser != null) {
                // Frame the mic into fixed 480-sample blocks: RNNoise denoise
                // (on the raw signal) -> makeup gain + adaptive gate -> enqueue.
                int fs = rnFrame.length;
                int off = 0;
                while (off < n) {
                    int take = Math.min(fs - rnFill, n - off);
                    System.arraycopy(buf, off, rnFrame, rnFill, take);
                    rnFill += take;
                    off += take;
                    if (rnFill == fs) {
                        denoiser.process(rnFrame);
                        applyGate(rnFrame, fs);
                        enqueue(rnFrame, fs);
                        rnFill = 0;
                    }
                }
            } else {
                applyGate(buf, n);
                enqueue(buf, n);
            }
        }
    }

    /** Append mono samples to the ring, dropping oldest to keep latency bounded. */
    private void enqueue(short[] src, int len) {
        synchronized (lock) {
            for (int i = 0; i < len; i++) {
                if (count == ring.length) {
                    head = (head + 1) % ring.length; // full: drop oldest
                    count--;
                }
                ring[tail] = src[i];
                tail = (tail + 1) % ring.length;
                count++;
            }
            // If the consumer fell behind, drop the oldest so the mic never lags.
            if (count > maxBacklog) {
                int drop = count - maxBacklog;
                head = (head + drop) % ring.length;
                count -= drop;
            }
        }
    }

    /**
     * Apply makeup gain + an adaptive, fail-open noise gate, in place.
     *
     * The gate is relative to your own recent voice level ({@code voiceRef}), so
     * it opens for speech regardless of the source's absolute gain. It fails OPEN:
     * until a clear voice reference is established it passes everything, and it
     * only attenuates when the level drops well below your recent speech - so an
     * active voice is never muted.
     */
    private void applyGate(short[] buf, int n) {
        for (int i = 0; i < n; i++) {
            // Makeup gain first (compensate for the un-AGC'd full-band source),
            // clamped to 16-bit.
            int s = (int) (buf[i] * MAKEUP_GAIN);
            if (s > 32767) s = 32767;
            else if (s < -32768) s = -32768;

            float a = Math.abs(s);
            gateEnv = a > gateEnv ? a : gateEnv * ENV_DECAY;          // fast envelope
            voiceRef = gateEnv > voiceRef ? gateEnv : voiceRef * REF_DECAY; // slow ref

            // Fail-open until we've seen real speech; then gate relative to it.
            boolean open = voiceRef < MIN_REF || gateEnv > voiceRef * OPEN_RATIO;
            float target = open ? 1f : 0f;
            float coef = target > gateGain ? ATTACK : RELEASE;
            gateGain += (target - gateGain) * coef;

            buf[i] = (short) (s * gateGain);
        }
    }

    /**
     * Drain up to {@code max} mono samples into {@code out}; returns count read.
     *
     * <p>Acts as a small jitter buffer: it stays silent (returns 0) until it has
     * built up {@link #primeTarget} samples of cushion, then serves normally. If
     * it ever runs completely dry it re-buffers the cushion before serving again.
     * This keeps the bursty audio pump supplied with full blocks so the mixer
     * doesn't have to fade on under-runs (which caused the voice to "cut").</p>
     */
    int read(short[] out, int max) {
        synchronized (lock) {
            if (!primed) {
                if (count < primeTarget) return 0; // still filling the cushion
                primed = true;
            }
            int n = Math.min(max, Math.min(count, out.length));
            for (int i = 0; i < n; i++) {
                out[i] = ring[head];
                head = (head + 1) % ring.length;
            }
            count -= n;
            if (count == 0) primed = false; // ran dry: rebuild cushion next time
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
        // Reader thread has exited (join above), so it's safe to free the denoiser.
        if (denoiser != null) {
            try { denoiser.destroy(); } catch (Throwable ignored) {}
            denoiser = null;
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
