package com.movtery.zalithlauncher.recorder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Thread-safe wrapper around {@link MediaMuxer} shared by the video and audio
 * encoders. The muxer only starts once every expected track has reported its
 * format, so samples produced before that are dropped (acceptable: a few ms of
 * lead-in until the first sync frame).
 */
public final class Mp4Muxer {

    private static final String TAG = "RecorderMuxer";

    private final MediaMuxer mMuxer;
    private final int mExpectedTracks;
    private int mTrackCount = 0;
    private boolean mStarted = false;
    private boolean mReleased = false;

    public Mp4Muxer(String outputPath, int expectedTracks) throws IOException {
        mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mExpectedTracks = Math.max(1, expectedTracks);
    }

    /** Register a track; starts the muxer once all expected tracks are present. */
    public synchronized int addTrack(MediaFormat format) {
        if (mStarted) {
            throw new IllegalStateException("addTrack called after muxer start");
        }
        int index = mMuxer.addTrack(format);
        mTrackCount++;
        if (mTrackCount == mExpectedTracks) {
            mMuxer.start();
            mStarted = true;
            Log.i(TAG, "Muxer started with " + mTrackCount + " track(s)");
        }
        return index;
    }

    public synchronized boolean isStarted() {
        return mStarted;
    }

    public synchronized void writeSampleData(int trackIndex, ByteBuffer buffer,
                                             MediaCodec.BufferInfo info) {
        if (!mStarted || mReleased || trackIndex < 0) {
            return; // drop pre-start / post-stop samples
        }
        if (info.size <= 0) {
            return;
        }
        try {
            mMuxer.writeSampleData(trackIndex, buffer, info);
        } catch (IllegalStateException e) {
            Log.e(TAG, "writeSampleData failed", e);
        }
    }

    public synchronized void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        try {
            if (mStarted) {
                mMuxer.stop();
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Muxer stop failed", e);
        } finally {
            try {
                mMuxer.release();
            } catch (Exception ignored) {
            }
            mStarted = false;
        }
    }
}
