package com.movtery.zalithlauncher.recorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Hardware video encoder: MediaCodec configured for a Surface input, so the GL
 * compositor renders frames straight into it (no CPU pixel readback). Output is
 * drained on a dedicated background thread and written to the shared muxer.
 */
public final class VideoEncoder {

    private static final String TAG = "RecorderVideoEncoder";
    private static final int TIMEOUT_US = 10_000;

    private final Mp4Muxer mMuxer;
    private final MediaCodec mEncoder;
    private final Surface mInputSurface;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    private int mTrackIndex = -1;
    private volatile boolean mRunning = false;
    private Thread mDrainThread;

    public VideoEncoder(int width, int height, int bitRate, int frameRate,
                        String mimeType, Mp4Muxer muxer) throws IOException {
        this.mMuxer = muxer;

        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        // Constant-ish bitrate keeps file size predictable on low-end devices.
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        // Best-effort priority (1) so the encoder yields to the game's GPU work
        // instead of competing with it in realtime - keeps gameplay smooth.
        format.setInteger(MediaFormat.KEY_PRIORITY, 1);
        // Hint the hardware encoder to target the recording frame rate; helps the
        // scheduler size its workload and reduces stalls that back-pressure the
        // capture queue (which would lag the game).
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate);
        // Low-latency encoding: minimise internal buffering/B-frames so the
        // encoder returns frames quickly and doesn't back-pressure the GL
        // compositor (which would stutter the live game view).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                format.setInteger(MediaFormat.KEY_LATENCY, 1);
            } catch (Throwable ignored) {
            }
        }
        // No B-frames: they add reorder latency and encode cost for no benefit in
        // a real-time screen capture. Guarded - not every encoder accepts it.
        try {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        } catch (Throwable ignored) {
        }

        mEncoder = MediaCodec.createEncoderByType(mimeType);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();
    }

    /** The Surface the GL compositor should render the recorded frames into. */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    public void startDraining() {
        mRunning = true;
        mDrainThread = new Thread(() -> {
            while (mRunning) {
                drain(false);
            }
        }, "RecordZy-VideoDrain");
        mDrainThread.setPriority(Thread.NORM_PRIORITY - 1);
        mDrainThread.start();
    }

    private void drain(boolean endOfStream) {
        if (endOfStream) {
            try {
                mEncoder.signalEndOfInputStream();
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
                // Keep looping until EOS arrives.
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mTrackIndex < 0) {
                    mTrackIndex = mMuxer.addTrack(mEncoder.getOutputFormat());
                }
            } else if (status >= 0) {
                ByteBuffer encoded = mEncoder.getOutputBuffer(status);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mBufferInfo.size = 0; // codec config is carried by the track format
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

    /** Stop input, flush remaining output, and release the encoder. */
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
            drain(true); // signal EOS and flush the tail
        } catch (Exception e) {
            Log.e(TAG, "Final video drain failed", e);
        }
        try {
            mEncoder.stop();
        } catch (Exception ignored) {
        }
        try {
            mEncoder.release();
        } catch (Exception ignored) {
        }
        if (mInputSurface != null) {
            mInputSurface.release();
        }
    }
}
