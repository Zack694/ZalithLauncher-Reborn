package com.movtery.zalithlauncher.recorder;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
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
 *       game) and - paced to the target FPS - onto a hardware
 *       {@link android.media.MediaCodec} encoder input surface (GPU-side scale,
 *       no CPU pixel readback).</li>
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

        // Encoder state (render-thread only).
        private Mp4Muxer muxer;
        private VideoEncoder videoEncoder;
        private WindowSurface encoderWindow;
        private int encWidth;
        private int encHeight;
        private long frameIntervalNanos = 0;
        private long lastEncodeNanos = 0;
        private long recordStartNanos = 0;

        // Audio state (game-audio tap -> AAC), present only when audio is enabled.
        private AudioEncoder audioEncoder;
        private Thread audioPump;
        private volatile boolean audioRunning = false;
        private short[] audioReadBuf;

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
            lastEncodeNanos = 0;

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
            encoderWindow = new WindowSurface(eglCore, videoEncoder.getInputSurface(), false);

            // Common A/V time anchor: video PTS = nanoTime - start; audio PTS is
            // derived from its sample count (also starting at 0).
            recordStartNanos = System.nanoTime();
            videoEncoder.startDraining();

            if (audioOk) {
                drainTapBacklog(); // drop silence/lead-in captured during probing
                // A/V sync delay: 0 = AUTO (use the measured audio output latency),
                // otherwise the user's fixed override.
                int manualMs = prefs.getAudioDelayMs();
                int delayMs = (manualMs > 0) ? manualMs : OpenALAudioTap.getLatencyMs();
                audioEncoder = new AudioEncoder(aSampleRate, aChannels, 128_000, muxer,
                        delayMs * 1000L);
                audioEncoder.startDraining();
                startAudioPump(aChannels, aSampleRate);
                RecorderLog.log(appContext, "audio delay: " + delayMs + "ms ("
                        + (manualMs > 0 ? "manual" : "auto, latency=" + OpenALAudioTap.getLatencyMs()
                        + "ms") + ")");
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

                while (audioRunning) {
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
            //    sees frames with minimal latency.
            displayWindow.makeCurrent();
            GLES20.glViewport(0, 0, gameWidth, gameHeight);
            blit.draw(texMatrix);
            displayWindow.swapBuffers();

            // 2) Feed the encoder (target resolution, GPU-scaled), paced to the
            //    target FPS so a high-FPS game doesn't overload the encoder and
            //    back-pressure the capture queue (which would lag the game).
            if (encoderWindow != null && videoEncoder != null) {
                long now = System.nanoTime();
                if (frameIntervalNanos <= 0 || (now - lastEncodeNanos) >= frameIntervalNanos) {
                    lastEncodeNanos = now;
                    encoderWindow.makeCurrent();
                    GLES20.glViewport(0, 0, encWidth, encHeight);
                    blit.draw(texMatrix);
                    long ptsNanos = now - recordStartNanos;
                    encoderWindow.setPresentationTime(ptsNanos > 0 ? ptsNanos : 0);
                    encoderWindow.swapBuffers();
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
            OpenALAudioTap.stop();
            if (audioEncoder != null) {
                try {
                    audioEncoder.stop();
                } catch (Exception ignored) {
                }
                audioEncoder = null;
            }

            if (encoderWindow != null) {
                try {
                    encoderWindow.release();
                } catch (Exception ignored) {
                }
                encoderWindow = null;
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
