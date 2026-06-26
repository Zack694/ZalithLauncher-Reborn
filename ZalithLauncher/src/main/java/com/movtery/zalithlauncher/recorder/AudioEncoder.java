package com.movtery.zalithlauncher.recorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Hardware AAC-LC encoder for the tapped game audio. PCM (interleaved int16) is
 * fed in via {@link #feed}; presentation timestamps are derived from the running
 * sample count so audio stays in sync regardless of how bursty the tap is.
 * Output is drained on a background thread into the shared {@link Mp4Muxer}.
 */
public final class AudioEncoder {

    private static final String TAG = "RecorderAudioEncoder";
    private static final int TIMEOUT_US = 10_000;

    private final Mp4Muxer mMuxer;
    private final MediaCodec mEncoder;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private final int mSampleRate;
    private final int mChannels;
    private final long mPtsOffsetUs;

    private int mTrackIndex = -1;
    private volatile boolean mRunning = false;
    private Thread mDrainThread;
    private long mTotalFrames = 0; // frames = samples / channels, per channel

    public AudioEncoder(int sampleRate, int channels, int bitRate, Mp4Muxer muxer,
                        long ptsOffsetUs) throws IOException {
        this.mMuxer = muxer;
        this.mSampleRate = sampleRate;
        this.mChannels = Math.max(1, channels);
        this.mPtsOffsetUs = ptsOffsetUs;

        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, mChannels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);

        mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
    }

    public void startDraining() {
        mRunning = true;
        mDrainThread = new Thread(() -> {
            while (mRunning) {
                drain(false);
            }
        }, "RecordZy-AudioDrain");
        mDrainThread.setPriority(Thread.NORM_PRIORITY - 1);
        mDrainThread.start();
    }

    /**
     * Submit {@code sampleCount} interleaved int16 samples for encoding.
     * Safe to call from a single producer thread.
     */
    public void feed(short[] pcm, int sampleCount) {
        if (!mRunning || sampleCount <= 0) {
            return;
        }
        int offset = 0;
        while (offset < sampleCount) {
            int inIndex;
            try {
                inIndex = mEncoder.dequeueInputBuffer(TIMEOUT_US);
            } catch (IllegalStateException e) {
                return;
            }
            if (inIndex < 0) {
                return; // no input buffer free right now; drop to avoid stalling the pump
            }
            ByteBuffer in = mEncoder.getInputBuffer(inIndex);
            if (in == null) {
                return;
            }
            in.clear();
            int capSamples = in.remaining() / 2; // 2 bytes per int16
            int chunk = Math.min(capSamples, sampleCount - offset);
            in.asShortBuffer().put(pcm, offset, chunk);
            in.position(0);
            in.limit(chunk * 2);

            long ptsUs = mPtsOffsetUs + mTotalFrames * 1_000_000L / mSampleRate;
            mTotalFrames += chunk / mChannels;
            mEncoder.queueInputBuffer(inIndex, 0, chunk * 2, ptsUs, 0);
            offset += chunk;
        }
    }

    private void drain(boolean endOfStream) {
        if (endOfStream) {
            try {
                int inIndex = mEncoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    long ptsUs = mPtsOffsetUs + mTotalFrames * 1_000_000L / mSampleRate;
                    mEncoder.queueInputBuffer(inIndex, 0, 0, ptsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (IllegalStateException ignored) {
            }
        }
        while (true) {
            int status;
            try {
                status = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_US);
            } catch (IllegalStateException e) {
                return;
            }
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    return;
                }
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
        if (mDrainThread != null) {
            try {
                mDrainThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            drain(true);
        } catch (Exception e) {
            Log.e(TAG, "Final audio drain failed", e);
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
