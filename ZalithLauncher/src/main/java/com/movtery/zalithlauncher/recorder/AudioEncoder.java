package com.movtery.zalithlauncher.recorder;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Microphone audio track, AAC-encoded, captured and encoded entirely on a
 * background thread (never the UI/render thread).
 *
 * <p><b>Push-to-talk:</b> the mic is always read to keep the AAC timeline
 * continuous and in sync with video, but when the Voicechat button is not held
 * the samples are zeroed (silence) - so your voice is only recorded while you
 * hold the button, matching push-to-talk behaviour.</p>
 */
public final class AudioEncoder {

    private static final String TAG = "RecorderAudioEncoder";
    private static final String MIME = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44_100;
    private static final int CHANNEL_COUNT = 1;
    private static final int BIT_RATE = 96_000;
    private static final int TIMEOUT_US = 10_000;

    private final Mp4Muxer mMuxer;
    private final MediaCodec mEncoder;
    private final AudioRecord mAudioRecord;
    private final int mBufferSize;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    private int mTrackIndex = -1;
    private volatile boolean mRunning = false;
    /** Push-to-talk gate: true only while the Voicechat button is held. */
    private volatile boolean mMicActive = false;
    private Thread mThread;

    @SuppressLint("MissingPermission") // caller must ensure RECORD_AUDIO is granted
    public AudioEncoder(Mp4Muxer muxer) throws IOException {
        this.mMuxer = muxer;

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            minBuffer = SAMPLE_RATE / 5 * 2; // ~200ms fallback
        }
        mBufferSize = minBuffer * 2;
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, mBufferSize);

        MediaFormat format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mBufferSize);

        mEncoder = MediaCodec.createEncoderByType(MIME);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    /** Called from the UI thread by the push-to-talk button (cheap, just a flag). */
    public void setMicActive(boolean active) {
        mMicActive = active;
    }

    public void start() {
        mEncoder.start();
        mAudioRecord.startRecording();
        mRunning = true;
        mThread = new Thread(this::loop, "RecordZy-Audio");
        mThread.setPriority(Thread.NORM_PRIORITY - 1);
        mThread.start();
    }

    private void loop() {
        byte[] buffer = new byte[mBufferSize];
        try {
            while (mRunning) {
                int read = mAudioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                if (!mMicActive) {
                    Arrays.fill(buffer, 0, read, (byte) 0); // push-to-talk: silence
                }
                long ptsUs = System.nanoTime() / 1000L;
                feed(buffer, read, ptsUs, false);
                drain();
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio loop failed", e);
        } finally {
            try {
                feed(null, 0, System.nanoTime() / 1000L, true); // EOS
                drain();
            } catch (Exception ignored) {
            }
        }
    }

    private void feed(byte[] data, int length, long ptsUs, boolean endOfStream) {
        int inputIndex = mEncoder.dequeueInputBuffer(TIMEOUT_US);
        if (inputIndex < 0) {
            return;
        }
        ByteBuffer input = mEncoder.getInputBuffer(inputIndex);
        if (input != null) {
            input.clear();
            if (data != null && length > 0) {
                input.put(data, 0, length);
            }
        }
        if (endOfStream) {
            mEncoder.queueInputBuffer(inputIndex, 0, 0, ptsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        } else {
            mEncoder.queueInputBuffer(inputIndex, 0, length, ptsUs, 0);
        }
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
                mThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            mAudioRecord.stop();
        } catch (Exception ignored) {
        }
        try {
            mAudioRecord.release();
        } catch (Exception ignored) {
        }
        try {
            mEncoder.stop();
        } catch (Exception ignored) {
        }
        try {
            mEncoder.release();
        } catch (Exception ignored) {
        }
    }
}
