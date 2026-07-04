package com.movtery.zalithlauncher.recorder;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import com.movtery.zalithlauncher.recorder.egl.EglCore;
import com.movtery.zalithlauncher.recorder.egl.WindowSurface;
import com.movtery.zalithlauncher.recorder.gl.TextureBlit;
import com.movtery.zalithlauncher.recorder.gl.Texture2DBlit;
import com.movtery.zalithlauncher.recorder.audio.OpenALAudioTap;

import net.kdt.pojavlaunch.utils.JREUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Built-in game recorder (on-demand GPU "tee").
 *
 * <p>When idle, the native Minecraft renderer draws <b>straight to the display</b>
 * surface, so there is zero recording overhead during normal play. Recording is
 * started on demand from the Special Menu:</p>
 *
 * <ol>
 *   <li>A dedicated GL thread creates a capture {@link SurfaceTexture} and points
 *       the native renderer at it via {@link JREUtils#setupBridgeWindow}.</li>
 *   <li>Once native has released the display surface, the GL thread claims it and
 *       composites every game frame back onto it (so the player still sees the
 *       game). Paced to the target FPS, it also renders the frame into one of a
 *       few shared "relay" textures (a cheap offscreen FBO pass - no surface swap,
 *       so it never blocks).</li>
 *   <li>A separate encoder GL thread (sharing the EGL context) samples the most
 *       recent relay texture and swaps it into the hardware
 *       {@link android.media.MediaCodec} encoder input surface. Keeping that
 *       (potentially blocking) swap and any encoder back-pressure on its own
 *       thread means the live display stays smooth even when the encoder stalls.</li>
 *   <li>On stop, the encoder is flushed, the display surface is handed back to the
 *       native renderer, and all recorder GL resources are released.</li>
 * </ol>
 *
 * <p>The touch controls live in a separate Android view layer, so they are never
 * part of the recording. If the hand-off fails on a given device, recording simply
 * aborts and normal play is unaffected (native always owns the display when idle).</p>
 */
public final class GameRecorder {

    private static final String TAG = "GameRecorder";

    private static final int MSG_FRAME = 1;
    private static final int MSG_QUIT = 2;

    private static final GameRecorder INSTANCE = new GameRecorder();

    public static GameRecorder getInstance() {
        return INSTANCE;
    }

    /**
     * Push-to-talk mic state, driven by a control button flagged as "Voicechat
     * (mic push-to-talk)". While held/toggled on, the audio pump mixes the mic
     * into the recording. Static so the control button can set it without a
     * reference to the (single) recorder instance.
     */
    private static volatile boolean sMicHeld = false;

    public static void setMicHeld(boolean held) {
        sMicHeld = held;
    }

    // Display target, set by MinecraftGLSurface whenever the game window is (re)created.
    private Context appContext;
    private Surface displaySurface;
    private volatile int gameWidth = 16;
    private volatile int gameHeight = 16;

    private RenderThread mRenderThread;
    private volatile boolean mRecording = false;

    private GameRecorder() {
    }

    public boolean isRecording() {
        return mRecording;
    }

    /** True once the game window exists (a display target is set). */
    public boolean isActive() {
        return displaySurface != null;
    }

    /**
     * Register the live display surface. Native renders directly here when idle;
     * we only borrow it while recording. Called from the GL surface bridge.
     */
    public synchronized void setDisplayTarget(Context context, Surface displaySurface,
                                              int gameWidth, int gameHeight) {
        // If the display surface changes mid-recording (e.g. config change), stop
        // cleanly first - the old surface our compositor held is gone.
        if (mRecording && displaySurface != this.displaySurface) {
            stopRecording();
        }
        this.appContext = context.getApplicationContext();
        this.displaySurface = displaySurface;
        this.gameWidth = Math.max(16, gameWidth);
        this.gameHeight = Math.max(16, gameHeight);
    }

    public synchronized void updateGameSize(int width, int height) {
        this.gameWidth = Math.max(16, width);
        this.gameHeight = Math.max(16, height);
        if (mRenderThread != null) {
            mRenderThread.updateGameSize(this.gameWidth, this.gameHeight);
        }
    }

    public synchronized void toggleRecording(Context context) {
        if (mRecording) {
            stopRecording();
        } else {
            startRecording(context);
        }
    }

    public synchronized void startRecording(Context context) {
        if (mRecording || displaySurface == null) {
            return;
        }
        RecorderLog.logHeader(appContext, "start recording requested");
        RecorderLog.log(appContext, "displaySurface set, game size " + gameWidth + "x" + gameHeight);
        RenderThread thread = new RenderThread(appContext, displaySurface,
                gameWidth, gameHeight, new RecorderPrefs(context));
        thread.start();
        if (!thread.awaitInit()) {
            // Hand-off failed (device wouldn't release/claim the display surface).
            // The thread has already reverted native to the display and torn down.
            Log.w(TAG, "Recorder failed to start; staying in normal (non-recording) mode");
            RecorderLog.log(appContext, "RESULT: recording did NOT start (init/hand-off failed)");
            thread.quit();
            mRenderThread = null;
            mRecording = false;
            return;
        }
        mRenderThread = thread;
        mRecording = true;
    }

    public synchronized void stopRecording() {
        if (!mRecording || mRenderThread == null) {
            return;
        }
        mRecording = false;
        mRenderThread.quit();
        mRenderThread = null;
    }

    /** Called when the game is being torn down. */
    public synchronized void detach() {
        if (mRecording) {
            stopRecording();
        }
        displaySurface = null;
        appContext = null;
    }

    private void toast(final String msg) {
        final Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * Best-effort query of the Android audio output (HAL/AudioFlinger) latency in
     * milliseconds. OpenAL only reports its own buffer latency, which misses the
     * downstream output path; adding this gives a much closer auto A/V offset.
     * {@code getOutputLatency} is a hidden API and may be blocked on newer API
     * levels, in which case we return 0 and the caller falls back to a constant.
     */
    private int systemOutputLatencyMs() {
        try {
            final Context ctx = appContext;
            if (ctx == null) {
                return 0;
            }
            android.media.AudioManager am = (android.media.AudioManager)
                    ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return 0;
            }
            java.lang.reflect.Method m = android.media.AudioManager.class
                    .getMethod("getOutputLatency", int.class);
            Object r = m.invoke(am, 3 /* STREAM_MUSIC */);
            int v = (r instanceof Integer) ? (Integer) r : 0;
            return (v > 0 && v < 1000) ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int align16(int value) {
        int aligned = value & ~15;
        return Math.max(16, aligned);
    }

    private static File buildOutputFile(Context context) {
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RecordZy");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String name = "recordzy-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4";
        return new File(dir, name);
    }

    // ------------------------------------------------------------------
    // GL compositor + encoder management thread. Created per recording.
    // ------------------------------------------------------------------
    private final class RenderThread extends Thread {

        /** Max time to wait for native to release/claim a surface during hand-off. */
        private static final int HANDOFF_RETRY_COUNT = 60;   // ~1s @ 16ms (startRecording blocks the UI thread)
        private static final int HANDOFF_RETRY_SLEEP_MS = 16;
        /** Grace period at stop for native to leave the capture surface. */
        private static final int STOP_GRACE_MS = 150;

        private final Context appContext;
        private final Surface displaySurface;
        private final RecorderPrefs prefs;
        private volatile int gameWidth;
        private volatile int gameHeight;

        private final CountDownLatch initLatch = new CountDownLatch(1);
        private volatile boolean initSuccess = false;
        private Handler handler;

        private EglCore eglCore;
        private EGLSurface offscreen;
        private WindowSurface displayWindow;
        private TextureBlit blit;
        private SurfaceTexture captureTexture;
        private Surface captureSurface;
        private final float[] texMatrix = new float[16];

        // Encoder + relay state.
        private Mp4Muxer muxer;
        private VideoEncoder videoEncoder;
        private int encWidth;
        private int encHeight;
        private long frameIntervalNanos = 0;
        private long lastRelayNanos = 0;
        private long recordStartNanos = 0;

        // Relay: the display thread renders each (paced) frame into one of a few
        // shared GL_TEXTURE_2D buffers via a cheap FBO pass; a dedicated
        // EncoderThread (sharing the EGL context) samples the newest one and feeds
        // the hardware encoder. This keeps the encoder's potentially-blocking
        // surface swap - and any HW encoder back-pressure - entirely off the
        // display path, so the live game view never stutters because of encoding.
        private static final int RELAY_BUFFERS = 3;
        private EglCore encEglCore;
        private EncoderThread encoderThread;
        private int relayFbo = 0;
        private final int[] relayTex = new int[RELAY_BUFFERS];
        private final long[] relayFence = new long[RELAY_BUFFERS];
        private final long[] relayPts = new long[RELAY_BUFFERS];
        private final Object relayMon = new Object();
        private int relayReadyIdx = -1;   // newest fully-written buffer (-1 = none)
        private int relayInUseIdx = -1;   // buffer the encoder currently holds
        private boolean relayFenceSupported = false;
        private volatile boolean relayActive = false;

        // Audio state (game-audio tap -> AAC), present only when audio is enabled.
        private AudioEncoder audioEncoder;
        private Thread audioPump;
        private volatile boolean audioRunning = false;
        private short[] audioReadBuf;
        // Push-to-talk microphone (mixed into the game-audio track when held).
        private MicCapture micCapture;
        private short[] micReadBuf;
        // De-click state: length (in frames) of the mic fade in/out at block
        // boundaries (~2 ms @ 48 kHz), and whether the previous mixed block ran
        // short (so the next one fades the mic back in instead of stepping).
        private static final int MIC_FADE_FRAMES = 96;
        private boolean micWasShort = true;

        RenderThread(Context appContext, Surface displaySurface,
                     int gameWidth, int gameHeight, RecorderPrefs prefs) {
            super("RecordZy-GL");
            this.appContext = appContext;
            this.displaySurface = displaySurface;
            this.gameWidth = Math.max(16, gameWidth);
            this.gameHeight = Math.max(16, gameHeight);
            this.prefs = prefs;
        }

        @Override
        public void run() {
            // Keep the compositor responsive so the displayed game stays smooth.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch (Throwable ignored) {
            }

            Looper.prepare();
            handler = new Handler(Looper.myLooper(), msg -> {
                switch (msg.what) {
                    case MSG_FRAME:
                        drawFrame();
                        return true;
                    case MSG_QUIT:
                        Looper l = Looper.myLooper();
                        if (l != null) {
                            l.quitSafely();
                        }
                        return true;
                    default:
                        return false;
                }
            });

            try {
                initRecording();
                initSuccess = true;
            } catch (Throwable t) {
                Log.e(TAG, "Recorder init failed; reverting to direct display", t);
                RecorderLog.log(appContext, "Recorder init FAILED; reverting to direct display", t);
                initSuccess = false;
                // Make sure native is rendering to the display again, then clean up.
                safeSetupBridge(displaySurface);
                releaseEverything();
            } finally {
                initLatch.countDown();
            }

            if (!initSuccess) {
                return;
            }

            // Kick an initial draw in case a frame is already queued.
            handler.sendEmptyMessage(MSG_FRAME);
            Looper.loop();

            // Looper finished (quit() posted) -> tear down cleanly.
            shutdownRecording();
        }

        boolean awaitInit() {
            try {
                initLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return initSuccess;
        }

        void updateGameSize(int width, int height) {
            this.gameWidth = Math.max(16, width);
            this.gameHeight = Math.max(16, height);
            if (handler != null) {
                handler.post(() -> {
                    if (captureTexture != null) {
                        captureTexture.setDefaultBufferSize(gameWidth, gameHeight);
                    }
                });
            }
        }

        void quit() {
            if (handler != null) {
                handler.sendEmptyMessage(MSG_QUIT);
            }
            try {
                join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // -- initialization -------------------------------------------------

        private void initRecording() throws Exception {
            // 1) GL context + a 1x1 pbuffer so we can create GL objects before any
            //    window surface exists.
            eglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            offscreen = eglCore.createOffscreenSurface(1, 1);
            eglCore.makeCurrent(offscreen);

            blit = new TextureBlit();
            captureTexture = new SurfaceTexture(blit.getTextureId());
            captureTexture.setDefaultBufferSize(gameWidth, gameHeight);
            captureSurface = new Surface(captureTexture);

            // 2) Redirect the native renderer into our capture surface.
            JREUtils.setupBridgeWindow(captureSurface);

            // 3) Wait for native to release the display surface, then claim it for
            //    compositing. eglCreateWindowSurface fails while native still holds
            //    it, so retry briefly; this also synchronizes the hand-off.
            displayWindow = claimDisplayWithRetry();

            // 4) Start delivering frames now that we own the display surface.
            captureTexture.setOnFrameAvailableListener(st -> {
                if (handler != null) {
                    handler.sendEmptyMessage(MSG_FRAME);
                }
            }, handler);

            // 5) Spin up the hardware encoder.
            startEncoder();
        }

        private WindowSurface claimDisplayWithRetry() {
            RuntimeException last = null;
            for (int i = 0; i < HANDOFF_RETRY_COUNT; i++) {
                try {
                    return new WindowSurface(eglCore, displaySurface, false);
                } catch (RuntimeException e) {
                    last = e;
                    try {
                        Thread.sleep(HANDOFF_RETRY_SLEEP_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw (last != null) ? last
                    : new RuntimeException("Display surface never became available");
        }

        private void startEncoder() throws Exception {
            int targetH = align16(prefs.getHeight());
            int targetW = align16((int) Math.round((double) gameWidth / gameHeight * targetH));
            encWidth = targetW;
            encHeight = targetH;
            int bitrate = prefs.getBitrateKbps() * 1000;
            int fps = prefs.getFps();
            frameIntervalNanos = 1_000_000_000L / Math.max(1, fps);
            lastRelayNanos = 0;

            // Probe the game-audio tap first so we know whether to add an audio
            // track (the muxer can't start until every expected track is added).
            // Always attempt it (no stale-pref gate); falls back to video-only
            // if the tap can't capture.
            boolean audioOk = false;
            int aSampleRate = 0;
            int aChannels = 0;
            if (OpenALAudioTap.start()) {
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    aSampleRate = OpenALAudioTap.getSampleRate();
                    if (aSampleRate > 0) break;
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                aChannels = OpenALAudioTap.getChannels();
                if (aSampleRate > 0) {
                    if (aChannels <= 0) aChannels = 2;
                    audioOk = true;
                } else {
                    OpenALAudioTap.stop();
                    Log.w(TAG, "Audio tap produced no PCM; recording video only");
                }
            }
            RecorderLog.log(appContext, "audio tap: ok=" + audioOk
                    + ", status=\"" + OpenALAudioTap.getStatusMessage() + "\""
                    + ", sampleRate=" + aSampleRate + ", channels=" + aChannels
                    + ", devices=" + OpenALAudioTap.getDeviceCount()
                    + ", hookCalls=" + OpenALAudioTap.getHookCalls()
                    + ", diag=" + OpenALAudioTap.getDiag());

            File out = buildOutputFile(appContext);
            muxer = new Mp4Muxer(out.getAbsolutePath(), audioOk ? 2 : 1);

            videoEncoder = createVideoEncoder(prefs.getMimeType(), targetW, targetH, bitrate, fps);

            // Relay buffers (shared GL_TEXTURE_2D) + an FBO, created on THIS
            // (display) context. The encoder thread shares the context, so it can
            // sample these textures.
            relayFenceSupported = eglCore.isGles3();
            setupRelayBuffers();

            // Shared encoder context + its own GL thread. The display thread only
            // produces relay frames; this thread consumes them into the encoder.
            encEglCore = new EglCore(eglCore.getEglContext(), EglCore.FLAG_RECORDABLE);

            // Common A/V time anchor: video PTS = nanoTime - start; audio PTS is
            // derived from its sample count (also starting at 0).
            recordStartNanos = System.nanoTime();
            videoEncoder.startDraining();

            relayActive = true;
            encoderThread = new EncoderThread(videoEncoder.getInputSurface());
            encoderThread.start();

            if (audioOk) {
                drainTapBacklog(); // drop silence/lead-in captured during probing
                // A/V sync delay: 0 = AUTO (measured OpenAL buffer latency +
                // the Android output/HAL latency, which OpenAL can't see),
                // otherwise the user's fixed override.
                int manualMs = prefs.getAudioDelayMs();
                int openalMs = OpenALAudioTap.getLatencyMs();
                int sysMs = systemOutputLatencyMs();
                int delayMs;
                if (manualMs > 0) {
                    delayMs = manualMs;
                } else {
                    // OpenAL buffers `openalMs` before handing audio to the OS,
                    // then the OS adds its own output latency before it's heard.
                    delayMs = openalMs + (sysMs > 0 ? sysMs : 90);
                    if (delayMs > 500) delayMs = 500;
                }
                audioEncoder = new AudioEncoder(aSampleRate, aChannels, 128_000, muxer,
                        delayMs * 1000L);
                audioEncoder.startDraining();
                startAudioPump(aChannels, aSampleRate);
                RecorderLog.log(appContext, "audio delay: " + delayMs + "ms ("
                        + (manualMs > 0 ? "manual"
                        : "auto: openal=" + openalMs + "ms + sys=" + sysMs + "ms") + ")");
            }

            Log.i(TAG, "Recording -> " + out + " (" + targetW + "x" + targetH
                    + " @" + fps + "fps, audio=" + audioOk
                    + (audioOk ? " " + aSampleRate + "Hz/" + aChannels + "ch" : "") + ")");
            RecorderLog.log(appContext, "RESULT: recording started -> " + out.getName()
                    + " (" + targetW + "x" + targetH + " @" + fps + "fps, codec="
                    + prefs.getMimeType() + ", audio=" + audioOk + ")");

            toast(audioOk
                    ? "RecordZy: recording + audio (" + aSampleRate + "Hz x" + aChannels + ")"
                    : "RecordZy: recording, NO audio (" + OpenALAudioTap.getStatusMessage()
                            + ", hookCalls=" + OpenALAudioTap.getHookCalls() + ")");
        }

        private void drainTapBacklog() {
            short[] tmp = new short[4096];
            int guard = 0;
            while (OpenALAudioTap.read(tmp, tmp.length) > 0 && guard++ < 4096) {
                // discard
            }
        }

        private void startAudioPump(final int channels, final int sampleRate) {
            audioRunning = true;
            final int ch = Math.max(1, channels);
            final int outRate = sampleRate;
            audioReadBuf = new short[8192];
            audioPump = new Thread(() -> {
                // The tap over-delivers (≈2x real time). Dropping chunks makes the
                // audio choppy/"windy", so instead we RESAMPLE the incoming stream
                // down to real time with linear interpolation: output is paced to
                // the wall clock (outRate frames/sec) and the read cursor advances
                // through the input at the measured input/output rate ratio. This
                // yields a smooth, continuous, in-sync track regardless of how fast
                // the tap feeds.
                final long startNs = System.nanoTime();
                short[] pend = new short[outRate * ch * 2]; // ~2s of input headroom
                int pendFrames = 0;
                double pos = 0.0;       // fractional read index (in frames) into pend
                long inFrames = 0;       // total input frames received
                long outFrames = 0;      // total output frames produced
                short[] outBuf = new short[outRate * ch / 4 + 64]; // up to ~250ms per tick
                boolean micInitFailed = false; // set once if the mic can't be opened

                while (audioRunning) {
                    // Lazily open the mic the first time push-to-talk is pressed, so
                    // the mic is never touched (no privacy indicator) unless a
                    // voicechat button is actually used. It stays open for the rest
                    // of the session, discarding audio while not held.
                    if (sMicHeld && micCapture == null && !micInitFailed) {
                        MicCapture mc = new MicCapture(appContext, outRate);
                        if (mc.start()) {
                            micCapture = mc;
                        } else {
                            micInitFailed = true;
                        }
                    }
                    if (micCapture != null) {
                        micCapture.setActive(sMicHeld);
                    }

                    int n = OpenALAudioTap.read(audioReadBuf, audioReadBuf.length);
                    boolean producedAny = false;
                    if (n > 0) {
                        int frames = n / ch;
                        if ((pendFrames + frames) * ch > pend.length) {
                            short[] bigger = new short[Math.max((pendFrames + frames) * ch,
                                    pend.length * 2)];
                            System.arraycopy(pend, 0, bigger, 0, pendFrames * ch);
                            pend = bigger;
                        }
                        System.arraycopy(audioReadBuf, 0, pend, pendFrames * ch, frames * ch);
                        pendFrames += frames;
                        inFrames += frames;
                    }

                    double elapsed = (System.nanoTime() - startNs) / 1_000_000_000.0;
                    double ratio = 1.0;
                    if (elapsed > 0.1 && inFrames > 0) {
                        ratio = (inFrames / elapsed) / outRate; // ≈ input_rate / output_rate
                        if (ratio < 0.5) ratio = 0.5;
                        if (ratio > 4.0) ratio = 4.0;
                    }
                    // Cap backlog so latency can't build up (keep < ~0.3s of input).
                    double maxBacklog = outRate * ratio * 0.3;
                    if (pendFrames - pos > maxBacklog) {
                        pos = pendFrames - maxBacklog;
                    }

                    long desiredOut = (long) (elapsed * outRate);
                    int win = (int) Math.round(ratio);   // anti-alias window (~2 when 2x)
                    if (win < 1) win = 1;
                    int produced = 0;
                    while (outFrames < desiredOut && pos + win < pendFrames) {
                        int i0 = (int) pos;
                        for (int c = 0; c < ch; c++) {
                            // Average `win` input samples (a simple low-pass) before
                            // decimating, so downsampling doesn't alias ("deep fried").
                            int acc = 0;
                            for (int k = 0; k < win; k++) {
                                acc += pend[(i0 + k) * ch + c];
                            }
                            outBuf[produced * ch + c] = (short) (acc / win);
                        }
                        produced++;
                        pos += ratio;
                        outFrames++;
                        if ((produced + 1) * ch > outBuf.length) break; // flush this batch
                    }
                    if (produced > 0) {
                        mixMic(outBuf, produced, ch);
                        audioEncoder.feed(outBuf, produced * ch);
                        producedAny = true;
                    }

                    int drop = (int) Math.floor(pos);
                    if (drop > pendFrames) drop = pendFrames;
                    if (drop > 0) {
                        System.arraycopy(pend, drop * ch, pend, 0, (pendFrames - drop) * ch);
                        pendFrames -= drop;
                        pos -= drop;
                    }

                    if (n <= 0 && !producedAny) {
                        try {
                            Thread.sleep(3);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }, "RecordZy-AudioPump");
            audioPump.start();
        }

        /**
         * Mix the push-to-talk mic (mono) into the interleaved output frames.
         * Reads exactly as many mic samples as output frames (both real-time at
         * the same rate); any shortfall is left as game-audio only. Samples are
         * summed and clamped to 16-bit.
         */
        private void mixMic(short[] outBuf, int frames, int ch) {
            MicCapture mic = micCapture;
            if (mic == null || !sMicHeld) {
                // Next press should fade the mic back in from silence, not step.
                micWasShort = true;
                return;
            }
            if (micReadBuf == null || micReadBuf.length < frames) {
                micReadBuf = new short[frames];
            }
            int got = mic.read(micReadBuf, frames);
            if (got <= 0) {
                micWasShort = true;
                return;
            }
            // The mic ring can momentarily hand back fewer samples than the pump
            // asked for (got < frames). Summing raw samples and then stopping mid
            // -waveform leaves a step discontinuity -> an audible click/pop. So we
            // fade the mic IN over the first few samples when it resumes after a
            // short block, and fade it OUT over the last few samples when this
            // block under-runs. In steady state (got == frames) the gain is a flat
            // 1.0 and nothing changes.
            final int fade = Math.min(MIC_FADE_FRAMES, got);
            final boolean shortNow = got < frames;
            for (int f = 0; f < got; f++) {
                float g = 1f;
                if (micWasShort && f < fade) {
                    g = (f + 1) / (float) fade;                 // fade in
                }
                if (shortNow && f >= got - fade) {
                    float out = (got - f) / (float) fade;       // fade out
                    if (out < g) g = out;
                }
                int m = (int) (micReadBuf[f] * g);
                for (int c = 0; c < ch; c++) {
                    int idx = f * ch + c;
                    int mixed = outBuf[idx] + m;
                    if (mixed > 32767) mixed = 32767;
                    else if (mixed < -32768) mixed = -32768;
                    outBuf[idx] = (short) mixed;
                }
            }
            micWasShort = shortNow;
        }

        private VideoEncoder createVideoEncoder(String mime, int w, int h, int bitrate, int fps)
                throws Exception {
            try {
                return new VideoEncoder(w, h, bitrate, fps, mime, muxer);
            } catch (Exception primary) {
                Log.w(TAG, "Encoder " + mime + " failed, falling back to AVC", primary);
                return new VideoEncoder(w, h, bitrate, fps,
                        android.media.MediaFormat.MIMETYPE_VIDEO_AVC, muxer);
            }
        }

        // -- relay buffer setup -------------------------------------------

        private void setupRelayBuffers() {
            int[] fb = new int[1];
            GLES20.glGenFramebuffers(1, fb, 0);
            relayFbo = fb[0];

            GLES20.glGenTextures(RELAY_BUFFERS, relayTex, 0);
            for (int i = 0; i < RELAY_BUFFERS; i++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, relayTex[i]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, encWidth, encHeight,
                        0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                relayFence[i] = 0;
                relayPts[i] = 0;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        /** Pick a relay buffer that's neither the published nor the in-use one. */
        private int freeRelayIndex(int avoidA, int avoidB) {
            for (int i = 0; i < RELAY_BUFFERS; i++) {
                if (i != avoidA && i != avoidB) {
                    return i;
                }
            }
            return 0;
        }

        // -- per-frame compositing -----------------------------------------

        private void drawFrame() {
            if (captureTexture == null || displayWindow == null) {
                return;
            }
            try {
                captureTexture.updateTexImage();
                captureTexture.getTransformMatrix(texMatrix);
            } catch (Exception e) {
                return;
            }

            // 1) Present to the display first (full game resolution) so the player
            //    sees frames with minimal latency. This path does NOT touch the
            //    encoder, so it can never be stalled by encoder back-pressure.
            displayWindow.makeCurrent();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, gameWidth, gameHeight);
            blit.draw(texMatrix);
            displayWindow.swapBuffers();

            // 2) Paced to the target FPS, render the frame into a free relay buffer
            //    (offscreen FBO pass, target resolution, GPU-scaled - no surface
            //    swap, so this never blocks). The EncoderThread picks it up.
            if (!relayActive || relayFbo == 0) {
                return;
            }
            long now = System.nanoTime();
            boolean due;
            if (frameIntervalNanos <= 0 || lastRelayNanos == 0) {
                due = true;
            } else {
                long tolerance = frameIntervalNanos / 8;
                due = (now - lastRelayNanos) >= (frameIntervalNanos - tolerance);
            }
            if (!due) {
                return;
            }

            int ready;
            int inUse;
            synchronized (relayMon) {
                ready = relayReadyIdx;
                inUse = relayInUseIdx;
            }
            int w = freeRelayIndex(ready, inUse);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, relayFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, relayTex[w], 0);
            GLES20.glViewport(0, 0, encWidth, encHeight);
            blit.draw(texMatrix);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            if (relayFenceSupported) {
                if (relayFence[w] != 0) {
                    GLES30.glDeleteSync(relayFence[w]);
                    relayFence[w] = 0;
                }
                relayFence[w] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
            GLES20.glFlush(); // make the render visible to the encoder context

            long ptsNanos = now - recordStartNanos;
            synchronized (relayMon) {
                relayPts[w] = ptsNanos > 0 ? ptsNanos : 0;
                relayReadyIdx = w;
                relayMon.notifyAll();
            }

            if (frameIntervalNanos <= 0 || lastRelayNanos == 0) {
                lastRelayNanos = now;
            } else {
                lastRelayNanos += frameIntervalNanos;
                if (now - lastRelayNanos > frameIntervalNanos) {
                    lastRelayNanos = now;
                }
            }
        }

        // -- dedicated encoder GL thread -----------------------------------

        /**
         * Consumes relay frames produced by the display thread and swaps them into
         * the encoder input surface. Runs on its own thread with a shared EGL
         * context so its (potentially blocking) swap never stalls the display.
         */
        private final class EncoderThread extends Thread {

            private final Surface inputSurface;
            private WindowSurface encWindow;
            private Texture2DBlit blit2d;

            EncoderThread(Surface inputSurface) {
                super("RecordZy-Encoder");
                this.inputSurface = inputSurface;
            }

            @Override
            public void run() {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                } catch (Throwable ignored) {
                }
                try {
                    encWindow = new WindowSurface(encEglCore, inputSurface, false);
                    encWindow.makeCurrent();
                    blit2d = new Texture2DBlit();
                } catch (Throwable t) {
                    Log.e(TAG, "Encoder GL init failed", t);
                    relayActive = false;
                    releaseGl();
                    return;
                }

                while (relayActive) {
                    int idx;
                    long fence;
                    long pts;
                    synchronized (relayMon) {
                        while (relayActive && relayReadyIdx < 0) {
                            try {
                                relayMon.wait(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        if (!relayActive) {
                            break;
                        }
                        idx = relayReadyIdx;
                        relayReadyIdx = -1;
                        relayInUseIdx = idx;
                        fence = relayFence[idx];
                        pts = relayPts[idx];
                    }
                    try {
                        if (relayFenceSupported && fence != 0) {
                            // GPU-side wait: order our sampling after the display
                            // thread's render finished, without blocking the CPU.
                            GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED);
                        }
                        GLES20.glViewport(0, 0, encWidth, encHeight);
                        blit2d.draw(relayTex[idx]);
                        encWindow.setPresentationTime(pts > 0 ? pts : 0);
                        encWindow.swapBuffers(); // may block on encoder; that's fine here
                    } catch (Throwable t) {
                        Log.w(TAG, "encoder frame failed", t);
                    }
                }
                releaseGl();
            }

            private void releaseGl() {
                try {
                    if (blit2d != null) {
                        blit2d.release();
                        blit2d = null;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (encWindow != null) {
                        encWindow.release();
                        encWindow = null;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (encEglCore != null) {
                        encEglCore.release();
                        encEglCore = null;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // -- teardown -------------------------------------------------------

        /** Runs on the GL thread after the looper quits. */
        private void shutdownRecording() {
            // Stop drawing to / flush the encoder first.
            stopEncoder();

            // Stop using the display surface BEFORE handing it back to native,
            // otherwise native can't recreate its EGL surface on it.
            if (displayWindow != null) {
                try {
                    displayWindow.release();
                } catch (Exception ignored) {
                }
                displayWindow = null;
            }

            // Hand the display back to the native renderer.
            safeSetupBridge(displaySurface);

            // Give native a moment to switch off our capture surface before we
            // release it (it still holds it as its ANativeWindow for a frame or two).
            try {
                Thread.sleep(STOP_GRACE_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            releaseEverything();
        }

        private void stopEncoder() {
            // Stop audio first so its tail flushes before the muxer closes.
            long capturedSamples = OpenALAudioTap.getCapturedSamples();
            long hookCalls = OpenALAudioTap.getHookCalls();
            audioRunning = false;
            if (audioPump != null) {
                try {
                    audioPump.join(600);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                audioPump = null;
            }
            // Mic is only touched by the pump thread (lazy init); the join above
            // guarantees it's done before we release the AudioRecord here.
            if (micCapture != null) {
                try {
                    micCapture.stop();
                } catch (Exception ignored) {
                }
                micCapture = null;
            }
            sMicHeld = false; // clear so a held state can't leak into the next recording
            OpenALAudioTap.stop();
            if (audioEncoder != null) {
                try {
                    audioEncoder.stop();
                } catch (Exception ignored) {
                }
                audioEncoder = null;
            }

            // Stop the dedicated encoder thread before the codec: it owns the
            // encoder GL surface (and releases the shared encoder EGL context).
            relayActive = false;
            synchronized (relayMon) {
                relayMon.notifyAll();
            }
            if (encoderThread != null) {
                try {
                    encoderThread.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                encoderThread = null;
            }
            if (videoEncoder != null) {
                try {
                    videoEncoder.stop();
                } catch (Exception ignored) {
                }
                videoEncoder = null;
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
                muxer = null;
            }

            toast("RecordZy saved - audio samples: " + capturedSamples
                    + " (hookCalls=" + hookCalls + ")");
            RecorderLog.log(appContext, "STOP: audio samples captured=" + capturedSamples
                    + ", hookCalls=" + hookCalls
                    + ", devices=" + OpenALAudioTap.getDeviceCount()
                    + ", tapStatus=\"" + OpenALAudioTap.getStatusMessage() + "\"");
        }

        private void releaseEverything() {
            // Relay GL objects live on this (display) context.
            if (relayFbo != 0) {
                try {
                    GLES20.glDeleteFramebuffers(1, new int[]{relayFbo}, 0);
                } catch (Exception ignored) {
                }
                relayFbo = 0;
            }
            for (int i = 0; i < RELAY_BUFFERS; i++) {
                if (relayFenceSupported && relayFence[i] != 0) {
                    try {
                        GLES30.glDeleteSync(relayFence[i]);
                    } catch (Exception ignored) {
                    }
                    relayFence[i] = 0;
                }
            }
            try {
                GLES20.glDeleteTextures(RELAY_BUFFERS, relayTex, 0);
            } catch (Exception ignored) {
            }
            // If the encoder thread never started, its shared context is still ours
            // to release (normally the thread releases it itself).
            if (encoderThread == null && encEglCore != null) {
                try {
                    encEglCore.release();
                } catch (Exception ignored) {
                }
                encEglCore = null;
            }

            if (captureTexture != null) {
                try {
                    captureTexture.setOnFrameAvailableListener(null);
                } catch (Exception ignored) {
                }
            }
            if (blit != null) {
                try {
                    blit.release();
                } catch (Exception ignored) {
                }
                blit = null;
            }
            if (captureSurface != null) {
                captureSurface.release();
                captureSurface = null;
            }
            if (captureTexture != null) {
                captureTexture.release();
                captureTexture = null;
            }
            if (offscreen != null && eglCore != null) {
                eglCore.releaseSurface(offscreen);
                offscreen = null;
            }
            if (eglCore != null) {
                eglCore.release();
                eglCore = null;
            }
        }

        private void safeSetupBridge(Surface surface) {
            try {
                JREUtils.setupBridgeWindow(surface);
            } catch (Throwable t) {
                Log.e(TAG, "setupBridgeWindow failed", t);
            }
        }
    }
}
