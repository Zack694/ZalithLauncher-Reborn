package net.kdt.pojavlaunch.recording;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.movtery.zalithlauncher.R;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GameRecorder {
    private static final int FPS = 30;
    private static final int I_FRAME_INTERVAL = 2;
    private static final int BITRATE_PER_PIXEL = 4;
    private static GameRecorder sInstance;

    private final Activity activity;
    private final View gameSurface;
    private final HandlerThread workerThread;
    private final Handler workerHandler;
    private final MediaCodec videoEncoder;
    private final Surface encoderSurface;
    private final MediaMuxer muxer;
    private final Bitmap frameBitmap;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private final AtomicBoolean recording = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean voicechatMicHeld = new AtomicBoolean(false);
    private final File outputFile;

    private int videoTrack = -1;
    private boolean muxerStarted;

    private GameRecorder(Activity activity, View gameSurface, File outputFile) throws IOException {
        this.activity = activity;
        this.gameSurface = gameSurface;
        this.outputFile = outputFile;
        int width = Math.max(2, gameSurface.getWidth() & ~1);
        int height = Math.max(2, gameSurface.getHeight() & ~1);
        frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2_000_000, width * height * BITRATE_PER_PIXEL));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = videoEncoder.createInputSurface();
        muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        workerThread = new HandlerThread("ZalithGameRecorder");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    public static void toggleRecording(Activity activity, View gameView) {
        if (sInstance != null && sInstance.recording.get()) { sInstance.stop(); return; }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { Toast.makeText(activity, R.string.recorder_requires_oreo, Toast.LENGTH_SHORT).show(); return; }
        if (!isSupportedGameSurface(gameView) || gameView.getWidth() <= 0 || gameView.getHeight() <= 0) { Toast.makeText(activity, R.string.recorder_surface_unavailable, Toast.LENGTH_SHORT).show(); return; }
        try {
            File dir = new File(activity.getExternalFilesDir(null), "recordings");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Unable to create recordings directory");
            File out = new File(dir, "zalith-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4");
            sInstance = new GameRecorder(activity, gameView, out);
            sInstance.start();
            Toast.makeText(activity, R.string.recorder_started, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(activity, activity.getString(R.string.recorder_start_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    public static void togglePause(Activity activity) {
        GameRecorder recorder = sInstance;
        if (recorder == null || !recorder.recording.get()) { Toast.makeText(activity, R.string.recorder_not_running, Toast.LENGTH_SHORT).show(); return; }
        boolean isPaused = !recorder.paused.get();
        recorder.paused.set(isPaused);
        Toast.makeText(activity, isPaused ? R.string.recorder_paused : R.string.recorder_resumed, Toast.LENGTH_SHORT).show();
    }

    public static void setVoicechatMicHeld(boolean held) {
        GameRecorder recorder = sInstance;
        if (recorder != null) recorder.voicechatMicHeld.set(held);
    }

    private void start() { videoEncoder.start(); workerHandler.post(frameRunnable); }

    private final Runnable frameRunnable = new Runnable() {
        @Override public void run() {
            if (!recording.get()) return;
            drainEncoder(false);
            if (!paused.get()) copyFrame();
            workerHandler.postDelayed(this, 1000L / FPS);
        }
    };

    private static boolean isSupportedGameSurface(View gameView) {
        return gameView instanceof SurfaceView || gameView instanceof TextureView;
    }

    private void copyFrame() {
        if (gameSurface instanceof TextureView) {
            ((TextureView) gameSurface).getBitmap(frameBitmap);
            if (recording.get() && !paused.get()) drawFrame();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && gameSurface instanceof SurfaceView) {
            PixelCopy.request((SurfaceView) gameSurface, frameBitmap, result -> { if (result == PixelCopy.SUCCESS && recording.get() && !paused.get()) drawFrame(); }, workerHandler);
        }
    }

    private void drawFrame() {
        Canvas canvas = null;
        try { canvas = encoderSurface.lockCanvas(null); canvas.drawBitmap(frameBitmap, 0, 0, null); }
        finally { if (canvas != null) encoderSurface.unlockCanvasAndPost(canvas); }
    }

    private void stop() {
        recording.set(false);
        workerHandler.post(() -> {
            drainEncoder(true);
            release();
            activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.recorder_saved, outputFile.getAbsolutePath()), Toast.LENGTH_LONG).show());
            sInstance = null;
        });
    }

    private void drainEncoder(boolean endOfStream) {
        if (endOfStream) videoEncoder.signalEndOfInputStream();
        while (true) {
            int encoderStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, 0);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) break; }
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { videoTrack = muxer.addTrack(videoEncoder.getOutputFormat()); muxer.start(); muxerStarted = true; }
            else if (encoderStatus >= 0) {
                ByteBuffer encodedData = videoEncoder.getOutputBuffer(encoderStatus);
                if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset); encodedData.limit(bufferInfo.offset + bufferInfo.size); muxer.writeSampleData(videoTrack, encodedData, bufferInfo);
                }
                videoEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void release() {
        try { videoEncoder.stop(); } catch (Exception ignored) {}
        try { videoEncoder.release(); } catch (Exception ignored) {}
        try { encoderSurface.release(); } catch (Exception ignored) {}
        try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
        try { muxer.release(); } catch (Exception ignored) {}
        frameBitmap.recycle(); workerThread.quitSafely();
    }

    @Nullable public static Boolean isVoicechatMicHeldForTests() { return sInstance == null ? null : sInstance.voicechatMicHeld.get(); }
}
