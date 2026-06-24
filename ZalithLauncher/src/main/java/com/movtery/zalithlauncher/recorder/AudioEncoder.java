package com.movtery.zalithlauncher.recorder;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Audio track for the recording, encoded to AAC on a background thread.
 *
 * <ul>
 *   <li><b>Game audio</b> via {@link AudioPlaybackCapture} (needs a MediaProjection,
 *       API 29+). This is the game's own sound output.</li>
 *   <li><b>Microphone</b> mixed in, push-to-talk gated (only while the Voicechat
 *       button is held).</li>
 * </ul>
 *
 * <p>Everything degrades gracefully: if game-audio capture can't be set up, it
 * falls back to mic-only; if neither is available the recording simply has no
 * audio track - video is never affected.</p>
 */
public final class AudioEncoder {

    private static final String TAG = "RecorderAudioEncoder";
    private static final String MIME = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44_100;
    private static final int BIT_RATE = 128_000;
    private static final int TIMEOUT_US = 10_000;
    private static final int FRAMES_PER_CHUNK = 1024;

    private Mp4Muxer mMuxer;
    private final MediaCodec mEncoder;
    private final int mChannels;

    private final AudioRecord mGameRecord; // game audio (nullable)
    private final AudioRecord mMicRecord;  // microphone (nullable)

    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private int mTrackIndex = -1;
    private volatile boolean mRunning = false;
    private volatile boolean mMicActive = false;
    private Thread mThread;

    @SuppressLint("MissingPermission") // mic gated by caller permission check
    public AudioEncoder(MediaProjection projection, boolean micEnabled)
            throws IOException {
        AudioRecord game = null;
        if (projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                AudioPlaybackCaptureConfiguration config =
                        new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build();
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                game = new AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(min, FRAMES_PER_CHUNK * 4) * 2)
                        .build();
                if (game.getState() != AudioRecord.STATE_INITIALIZED) {
                    game.release();
                    game = null;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Game audio capture unavailable, falling back", t);
                game = null;
            }
        }
        mGameRecord = game;

        AudioRecord mic = null;
        if (micEnabled) {
            try {
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                mic = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, FRAMES_PER_CHUNK * 2) * 2);
                if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                    mic.release();
                    mic = null;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Microphone unavailable", t);
                mic = null;
            }
        }
        mMicRecord = mic;

        // Stereo when we have game audio, otherwise mono mic.
        mChannels = (mGameRecord != null) ? 2 : 1;

        MediaFormat format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, mChannels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES_PER_CHUNK * mChannels * 2);
        mEncoder = MediaCodec.createEncoderByType(MIME);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    /** True if any audio source was successfully created. */
    public boolean hasSource() {
        return mGameRecord != null || mMicRecord != null;
    }

    public void setMicActive(boolean active) {
        mMicActive = active;
    }

    public void start(Mp4Muxer muxer) {
        this.mMuxer = muxer;
        mEncoder.start();
        if (mGameRecord != null) {
            mGameRecord.startRecording();
        }
        if (mMicRecord != null) {
            mMicRecord.startRecording();
        }
        mRunning = true;
        mThread = new Thread(this::loop, "RecordZy-Audio");
        mThread.setPriority(Thread.NORM_PRIORITY - 1);
        mThread.start();
    }

    private void loop() {
        short[] gameBuf = new short[FRAMES_PER_CHUNK * 2];
        short[] micBuf = new short[FRAMES_PER_CHUNK];
        short[] out = new short[FRAMES_PER_CHUNK * mChannels];
        try {
            while (mRunning) {
                int frames;
                if (mGameRecord != null) {
                    int read = mGameRecord.read(gameBuf, 0, FRAMES_PER_CHUNK * 2);
                    if (read <= 0) {
                        continue;
                    }
                    frames = read / 2;
                    int micRead = readMic(micBuf, frames);
                    for (int i = 0; i < frames; i++) {
                        int l = gameBuf[i * 2];
                        int r = gameBuf[i * 2 + 1];
                        if (mMicActive && i < micRead) {
                            l += micBuf[i];
                            r += micBuf[i];
                        }
                        out[i * 2] = clamp(l);
                        out[i * 2 + 1] = clamp(r);
                    }
                    feed(out, frames * 2);
                } else if (mMicRecord != null) {
                    int read = mMicRecord.read(micBuf, 0, FRAMES_PER_CHUNK);
                    if (read <= 0) {
                        continue;
                    }
                    frames = read;
                    for (int i = 0; i < frames; i++) {
                        out[i] = mMicActive ? micBuf[i] : 0; // push-to-talk silence
                    }
                    feed(out, frames);
                } else {
                    break; // no source
                }
                drain();
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio loop failed", e);
        } finally {
            try {
                feedEos();
                drain();
            } catch (Exception ignored) {
            }
        }
    }

    private int readMic(short[] buf, int frames) {
        if (mMicRecord == null) {
            return 0;
        }
        int n = Math.min(frames, buf.length);
        int read = mMicRecord.read(buf, 0, n, AudioRecord.READ_NON_BLOCKING);
        return Math.max(read, 0);
    }

    private static short clamp(int v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }

    private void feed(short[] data, int sampleCount) {
        int inputIndex = mEncoder.dequeueInputBuffer(TIMEOUT_US);
        if (inputIndex < 0) {
            return;
        }
        ByteBuffer input = mEncoder.getInputBuffer(inputIndex);
        if (input != null) {
            input.clear();
            input.order(ByteOrder.nativeOrder());
            ShortBuffer sb = input.asShortBuffer();
            sb.put(data, 0, sampleCount);
        }
        long ptsUs = System.nanoTime() / 1000L;
        mEncoder.queueInputBuffer(inputIndex, 0, sampleCount * 2, ptsUs, 0);
    }

    private void feedEos() {
        int inputIndex = mEncoder.dequeueInputBuffer(TIMEOUT_US);
        if (inputIndex < 0) {
            return;
        }
        mEncoder.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    }

    private void drain() {
        while (true) {
            int status = mEncoder.dequeueOutputBuffer(mBufferInfo, 0);
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mTrackIndex < 0) {
                    mTrackIndex = mMuxer.addTrack(mEncoder.getOutputFormat());
                }
            } else if (status >= 0) {
                ByteBuffer encoded = mEncoder.getOutputBuffer(status);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mBufferInfo.size = 0;
                }
                if (mBufferInfo.size > 0 && encoded != null && mMuxer.isStarted()) {
                    encoded.position(mBufferInfo.offset);
                    encoded.limit(mBufferInfo.offset + mBufferInfo.size);
                    mMuxer.writeSampleData(mTrackIndex, encoded, mBufferInfo);
                }
                mEncoder.releaseOutputBuffer(status, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            }
        }
    }

    public void stop() {
        mRunning = false;
        if (mThread != null) {
            try {
                mThread.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        releaseRecord(mGameRecord);
        releaseRecord(mMicRecord);
        try {
            mEncoder.stop();
        } catch (Exception ignored) {
        }
        try {
            mEncoder.release();
        } catch (Exception ignored) {
        }
    }

    private static void releaseRecord(AudioRecord record) {
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            try {
                record.release();
            } catch (Exception ignored) {
            }
        }
    }
}
