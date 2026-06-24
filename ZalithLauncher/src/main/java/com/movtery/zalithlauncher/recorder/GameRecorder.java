package com.movtery.zalithlauncher.recorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import androidx.core.content.ContextCompat;

import com.movtery.zalithlauncher.recorder.egl.EglCore;
import com.movtery.zalithlauncher.recorder.egl.WindowSurface;
import com.movtery.zalithlauncher.recorder.gl.TextureBlit;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Built-in game recorder.
 *
 * <p>When recording support is enabled, the native Minecraft renderer is pointed
 * at a capture {@link SurfaceTexture} we own (the "tee"). A dedicated GL thread
 * composites each game frame onto the real display surface, and - while recording
 * - also onto a hardware {@link android.media.MediaCodec} encoder's input surface
 * (GPU-side scale, no CPU readback). The touch controls live in a separate Android
 * view layer, so they are never part of the recording.</p>
 */
public final class GameRecorder {

    private static final String TAG = "GameRecorder";

    private static final int MSG_FRAME = 1;
    private static final int MSG_START = 2;
    private static final int MSG_STOP = 3;
    private static final int MSG_QUIT = 4;

    private static final GameRecorder INSTANCE = new GameRecorder();

    public static GameRecorder getInstance() {
        return INSTANCE;
    }

    private RenderThread mRenderThread;
    private volatile boolean mRecording = false;

    private GameRecorder() {
    }

    public boolean isRecording() {
        return mRecording;
    }

    public boolean isActive() {
        return mRenderThread != null;
    }

    /**
     * Begin compositing. Returns the capture Surface that should be handed to the
     * native renderer (via {@code setupBridgeWindow}) instead of the display one.
     */
    public synchronized Surface attachDisplay(Context context, Surface displaySurface,
                                              int gameWidth, int gameHeight) {
        if (mRenderThread != null) {
            detach();
        }
        mRenderThread = new RenderThread(context.getApplicationContext(),
                displaySurface, gameWidth, gameHeight);
        mRenderThread.start();
        mRenderThread.awaitReady();
        return mRenderThread.getCaptureSurface();
    }

    public synchronized void updateGameSize(int width, int height) {
        if (mRenderThread != null) {
            mRenderThread.updateGameSize(width, height);
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
        if (mRenderThread == null || mRecording) {
            return;
        }
        boolean audio = new RecorderPrefs(context).isRecordAudio()
                && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        mRenderThread.postStart(new RecorderPrefs(context), audio);
        mRecording = true;
    }

    public synchronized void stopRecording() {
        if (mRenderThread == null || !mRecording) {
            return;
        }
        mRenderThread.postStop();
        mRecording = false;
    }

    /** Push-to-talk: cheap flag flip from the UI thread. */
    public void setMicActive(boolean active) {
        RenderThread t = mRenderThread;
        if (t != null) {
            t.setMicActive(active);
        }
    }

    public synchronized void detach() {
        if (mRecording) {
            stopRecording();
        }
        if (mRenderThread != null) {
            mRenderThread.quit();
            mRenderThread = null;
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
    // GL compositor + encoder management thread.
    // ------------------------------------------------------------------
    private final class RenderThread extends Thread {

        private final Context appContext;
        private final Surface displaySurface;
        private volatile int gameWidth;
        private volatile int gameHeight;

        private final CountDownLatch readyLatch = new CountDownLatch(1);
        private Handler handler;

        private EglCore eglCore;
        private WindowSurface displayWindow;
        private TextureBlit blit;
        private SurfaceTexture captureTexture;
        private Surface captureSurface;
        private final float[] texMatrix = new float[16];

        // Recording state (render-thread only).
        private Mp4Muxer muxer;
        private VideoEncoder videoEncoder;
        private AudioEncoder audioEncoder;
        private WindowSurface encoderWindow;
        private int encWidth;
        private int encHeight;
        private long frameIntervalNanos = 0;
        private long lastEncodeNanos = 0;
        private volatile boolean micActive = false;

        RenderThread(Context appContext, Surface displaySurface, int gameWidth, int gameHeight) {
            super("RecordZy-GL");
            this.appContext = appContext;
            this.displaySurface = displaySurface;
            this.gameWidth = Math.max(16, gameWidth);
            this.gameHeight = Math.max(16, gameHeight);
        }

        @Override
        public void run() {
            Looper.prepare();
            handler = new Handler(Looper.myLooper(), msg -> {
                switch (msg.what) {
                    case MSG_FRAME:
                        drawFrame();
                        return true;
                    case MSG_START:
                        Object[] args = (Object[]) msg.obj;
                        doStartRecording((RecorderPrefs) args[0], (Boolean) args[1]);
                        return true;
                    case MSG_STOP:
                        doStopRecording();
                        return true;
                    case MSG_QUIT:
                        doStopRecording();
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
                initGl();
            } catch (Throwable t) {
                Log.e(TAG, "GL init failed", t);
            } finally {
                readyLatch.countDown();
            }

            Looper.loop();
            releaseGl();
        }

        private void initGl() {
            eglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            displayWindow = new WindowSurface(eglCore, displaySurface, false);
            displayWindow.makeCurrent();
            blit = new TextureBlit();
            captureTexture = new SurfaceTexture(blit.getTextureId());
            captureTexture.setDefaultBufferSize(gameWidth, gameHeight);
            captureTexture.setOnFrameAvailableListener(
                    surfaceTexture -> {
                        if (handler != null) {
                            handler.sendEmptyMessage(MSG_FRAME);
                        }
                    }, handler);
            captureSurface = new Surface(captureTexture);
        }

        void awaitReady() {
            try {
                readyLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Surface getCaptureSurface() {
            return captureSurface;
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

        void setMicActive(boolean active) {
            micActive = active;
            if (audioEncoder != null) {
                audioEncoder.setMicActive(active);
            }
        }

        void postStart(RecorderPrefs prefs, boolean audio) {
            if (handler != null) {
                handler.obtainMessage(MSG_START, new Object[]{prefs, audio}).sendToTarget();
            }
        }

        void postStop() {
            if (handler != null) {
                handler.sendEmptyMessage(MSG_STOP);
            }
        }

        void quit() {
            if (handler != null) {
                handler.sendEmptyMessage(MSG_QUIT);
            }
            try {
                join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        private void drawFrame() {
            if (captureTexture == null) {
                return;
            }
            try {
                captureTexture.updateTexImage();
                captureTexture.getTransformMatrix(texMatrix);
            } catch (Exception e) {
                return;
            }

            // 1) Display (full game resolution).
            displayWindow.makeCurrent();
            GLES20.glViewport(0, 0, gameWidth, gameHeight);
            blit.draw(texMatrix);
            displayWindow.swapBuffers();

            // 2) Encoder (target resolution, GPU-scaled) while recording, paced to
            // the target FPS so a high-FPS game doesn't overload the encoder (which
            // would back-pressure the capture queue and lag the game).
            if (encoderWindow != null && videoEncoder != null) {
                long now = System.nanoTime();
                if (frameIntervalNanos <= 0 || (now - lastEncodeNanos) >= frameIntervalNanos) {
                    lastEncodeNanos = now;
                    encoderWindow.makeCurrent();
                    GLES20.glViewport(0, 0, encWidth, encHeight);
                    blit.draw(texMatrix);
                    long ts = captureTexture.getTimestamp();
                    encoderWindow.setPresentationTime(ts > 0 ? ts : now);
                    encoderWindow.swapBuffers();
                }
            }
        }

        private void doStartRecording(RecorderPrefs prefs, boolean audio) {
            try {
                int targetH = align16(prefs.getHeight());
                int targetW = align16((int) Math.round(
                        (double) gameWidth / gameHeight * targetH));
                encWidth = targetW;
                encHeight = targetH;
                int bitrate = prefs.getBitrateKbps() * 1000;
                int fps = prefs.getFps();
                frameIntervalNanos = 1_000_000_000L / Math.max(1, fps);
                lastEncodeNanos = 0;

                File out = buildOutputFile(appContext);
                muxer = new Mp4Muxer(out.getAbsolutePath(), audio ? 2 : 1);

                videoEncoder = createVideoEncoder(prefs.getMimeType(),
                        targetW, targetH, bitrate, fps);
                encoderWindow = new WindowSurface(eglCore, videoEncoder.getInputSurface(), false);
                videoEncoder.startDraining();

                if (audio) {
                    audioEncoder = new AudioEncoder(muxer);
                    audioEncoder.setMicActive(micActive);
                    audioEncoder.start();
                }
                Log.i(TAG, "Recording -> " + out + " (" + targetW + "x" + targetH
                        + " @" + fps + "fps, audio=" + audio + ")");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to start recording", t);
                doStopRecording();
            }
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

        private void doStopRecording() {
            // Stop drawing to the encoder first.
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
            if (audioEncoder != null) {
                try {
                    audioEncoder.stop();
                } catch (Exception ignored) {
                }
                audioEncoder = null;
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
                muxer = null;
            }
        }

        private void releaseGl() {
            if (blit != null) {
                blit.release();
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
            if (displayWindow != null) {
                displayWindow.release();
                displayWindow = null;
            }
            if (eglCore != null) {
                eglCore.release();
                eglCore = null;
            }
        }
    }
}
