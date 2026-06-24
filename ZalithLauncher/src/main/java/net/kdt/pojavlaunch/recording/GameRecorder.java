package net.kdt.pojavlaunch.recording;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioFormat;
import android.media.AudioRecord;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.movtery.zalithlauncher.R;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GameRecorder {
    private static final int FPS = 30;
    private static final int I_FRAME_INTERVAL = 2;
    private static final int BITRATE_PER_PIXEL = 4;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_BITRATE = 64000;
    private static final int REQUEST_RECORD_AUDIO = 9024;
    private static GameRecorder sInstance;

    private final Activity activity;
    private final View gameSurface;
    private final HandlerThread videoThread;
    private final Handler videoHandler;
    private final MediaCodec videoEncoder;
    private final Surface encoderSurface;
    private final MediaMuxer muxer;
    private final Bitmap frameBitmap;
    private final MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();
    private final AtomicBoolean recording = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean voicechatMicHeld = new AtomicBoolean(false);
    private final Object muxerLock = new Object();
    private final File outputFile;

    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxerStarted;
    private boolean audioEnabled;
    private long audioSamplesSubmitted;

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
        videoThread = new HandlerThread("ZalithGameRecorderVideo");
        videoThread.start();
        videoHandler = new Handler(videoThread.getLooper());
        configureAudioIfPermitted();
    }

    public static void toggleRecording(Activity activity, View gameView) {
        if (sInstance != null && sInstance.recording.get()) { sInstance.stop(); return; }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { Toast.makeText(activity, R.string.recorder_requires_oreo, Toast.LENGTH_SHORT).show(); return; }
        if (!isSupportedGameSurface(gameView) || gameView.getWidth() <= 0 || gameView.getHeight() <= 0) { Toast.makeText(activity, R.string.recorder_surface_unavailable, Toast.LENGTH_SHORT).show(); return; }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            Toast.makeText(activity, R.string.recorder_audio_permission_required, Toast.LENGTH_SHORT).show();
            return;
        }
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

    private void configureAudioIfPermitted() throws IOException {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        int minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) return;
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            return;
        }
        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEnabled = true;
    }

    private void start() {
        videoEncoder.start();
        if (audioEnabled) startAudioCapture();
        videoHandler.post(frameRunnable);
    }

    private final Runnable frameRunnable = new Runnable() {
        @Override public void run() {
            if (!recording.get()) return;
            drainVideoEncoder(false);
            if (!paused.get()) copyFrame();
            videoHandler.postDelayed(this, 1000L / FPS);
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
            PixelCopy.request((SurfaceView) gameSurface, frameBitmap, result -> { if (result == PixelCopy.SUCCESS && recording.get() && !paused.get()) drawFrame(); }, videoHandler);
        }
    }

    private void drawFrame() {
        Canvas canvas = null;
        try { canvas = encoderSurface.lockCanvas(null); canvas.drawBitmap(frameBitmap, 0, 0, null); }
        finally { if (canvas != null) encoderSurface.unlockCanvasAndPost(canvas); }
    }

    private void startAudioCapture() {
        audioEncoder.start();
        audioRecord.startRecording();
        audioThread = new Thread(this::audioLoop, "ZalithGameRecorderAudio");
        audioThread.start();
    }

    private void audioLoop() {
        byte[] pcm = new byte[Math.max(2048, AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))];
        while (recording.get()) {
            drainAudioEncoder(false);
            int inputIndex = audioEncoder.dequeueInputBuffer(10_000);
            if (inputIndex < 0) continue;
            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputIndex);
            if (inputBuffer == null) continue;
            inputBuffer.clear();
            int bytesRead = 0;
            if (!paused.get()) bytesRead = audioRecord.read(pcm, 0, Math.min(pcm.length, inputBuffer.remaining()));
            if (bytesRead <= 0 || paused.get()) bytesRead = Math.min(pcm.length, inputBuffer.remaining());
            if (!voicechatMicHeld.get() || paused.get()) Arrays.fill(pcm, 0, bytesRead, (byte) 0);
            inputBuffer.put(pcm, 0, bytesRead);
            long presentationTimeUs = (audioSamplesSubmitted * 1_000_000L) / AUDIO_SAMPLE_RATE;
            audioSamplesSubmitted += bytesRead / 2L;
            audioEncoder.queueInputBuffer(inputIndex, 0, bytesRead, presentationTimeUs, 0);
        }
        int inputIndex = audioEncoder.dequeueInputBuffer(10_000);
        if (inputIndex >= 0) audioEncoder.queueInputBuffer(inputIndex, 0, 0, (audioSamplesSubmitted * 1_000_000L) / AUDIO_SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        drainAudioEncoder(true);
    }

    private void stop() {
        recording.set(false);
        videoHandler.post(() -> {
            waitForAudioThread();
            drainVideoEncoder(true);
            release();
            activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.recorder_saved, outputFile.getAbsolutePath()), Toast.LENGTH_LONG).show());
            sInstance = null;
        });
    }

    private void waitForAudioThread() {
        if (audioThread == null) return;
        try { audioThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void drainVideoEncoder(boolean endOfStream) {
        if (endOfStream) videoEncoder.signalEndOfInputStream();
        while (true) {
            int encoderStatus = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 0);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) break; }
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { addTrackAndStartMuxerIfReady(true, videoEncoder.getOutputFormat()); }
            else if (encoderStatus >= 0) {
                ByteBuffer encodedData = videoEncoder.getOutputBuffer(encoderStatus);
                writeSampleData(videoTrack, encodedData, videoBufferInfo);
                videoEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void drainAudioEncoder(boolean endOfStream) {
        while (true) {
            int encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, endOfStream ? 10_000 : 0);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) break; }
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { addTrackAndStartMuxerIfReady(false, audioEncoder.getOutputFormat()); }
            else if (encoderStatus >= 0) {
                ByteBuffer encodedData = audioEncoder.getOutputBuffer(encoderStatus);
                writeSampleData(audioTrack, encodedData, audioBufferInfo);
                audioEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void addTrackAndStartMuxerIfReady(boolean video, MediaFormat format) {
        synchronized (muxerLock) {
            if (video) videoTrack = muxer.addTrack(format);
            else audioTrack = muxer.addTrack(format);
            if (!muxerStarted && videoTrack >= 0 && (!audioEnabled || audioTrack >= 0)) {
                muxer.start();
                muxerStarted = true;
            }
        }
    }

    private void writeSampleData(int track, ByteBuffer encodedData, MediaCodec.BufferInfo info) {
        if (encodedData == null || info.size <= 0 || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
        synchronized (muxerLock) {
            if (!muxerStarted || track < 0) return;
            encodedData.position(info.offset);
            encodedData.limit(info.offset + info.size);
            muxer.writeSampleData(track, encodedData, info);
        }
    }

    private void release() {
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
        }
        if (audioEncoder != null) {
            try { audioEncoder.stop(); } catch (Exception ignored) {}
            try { audioEncoder.release(); } catch (Exception ignored) {}
        }
        try { videoEncoder.stop(); } catch (Exception ignored) {}
        try { videoEncoder.release(); } catch (Exception ignored) {}
        try { encoderSurface.release(); } catch (Exception ignored) {}
        synchronized (muxerLock) {
            try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
            try { muxer.release(); } catch (Exception ignored) {}
        }
        frameBitmap.recycle(); videoThread.quitSafely();
    }

    @Nullable public static Boolean isVoicechatMicHeldForTests() { return sInstance == null ? null : sInstance.voicechatMicHeld.get(); }
}
