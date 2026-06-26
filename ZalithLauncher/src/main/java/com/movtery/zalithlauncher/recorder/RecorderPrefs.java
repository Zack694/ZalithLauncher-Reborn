package com.movtery.zalithlauncher.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaFormat;

/**
 * Lightweight SharedPreferences-backed config for the built-in recorder, kept
 * self-contained so it doesn't touch the launcher's settings framework.
 *
 * <p>Performance-first defaults for weak devices: 720p / 30 FPS / hardware HEVC.</p>
 */
public final class RecorderPrefs {

    private static final String PREFS = "recordzy_recorder";

    private static final String KEY_ENABLED = "enabled";          // tee/recording support
    private static final String KEY_HEIGHT = "height";            // target output height
    private static final String KEY_FPS = "fps";
    private static final String KEY_BITRATE_KBPS = "bitrate_kbps";
    private static final String KEY_CODEC = "codec";              // "hevc" | "avc"
    private static final String KEY_RECORD_AUDIO = "record_audio";
    private static final String KEY_RECORD_GAME_AUDIO = "record_game_audio";
    private static final String KEY_VOICECHAT_BUTTON = "voicechat_button"; // push-to-talk button
    private static final String KEY_PERFORMANCE_MODE = "performance_mode";

    private final SharedPreferences prefs;

    public RecorderPrefs(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the GPU capture tee is active. Default ON so recording can start
     *  instantly from the menu without relaunching the game. */
    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public boolean isPerformanceMode() {
        return prefs.getBoolean(KEY_PERFORMANCE_MODE, true);
    }

    public void setPerformanceMode(boolean v) {
        prefs.edit().putBoolean(KEY_PERFORMANCE_MODE, v).apply();
    }

    /** Target output height; performance mode caps it at 720p. */
    public int getHeight() {
        int h = prefs.getInt(KEY_HEIGHT, 720);
        if (isPerformanceMode()) {
            h = Math.min(h, 720);
        }
        return Math.max(240, h);
    }

    public void setHeight(int v) {
        prefs.edit().putInt(KEY_HEIGHT, v).apply();
    }

    /** Target FPS; performance mode caps it at 30. */
    public int getFps() {
        int fps = prefs.getInt(KEY_FPS, 30);
        if (isPerformanceMode()) {
            fps = Math.min(fps, 30);
        }
        return Math.max(15, Math.min(fps, 90));
    }

    public void setFps(int v) {
        prefs.edit().putInt(KEY_FPS, v).apply();
    }

    public int getBitrateKbps() {
        return Math.max(500, prefs.getInt(KEY_BITRATE_KBPS, 6000));
    }

    public void setBitrateKbps(int v) {
        prefs.edit().putInt(KEY_BITRATE_KBPS, v).apply();
    }

    private static final String KEY_AUDIO_DELAY_MS = "audio_delay_ms";

    /** How much to delay the audio track to line it up with video. OpenAL renders
     *  audio slightly ahead of when it's heard, so the captured audio tends to be
     *  a bit ahead of the video; this shifts it back. Default 90 ms, 0-500 ms. */
    public int getAudioDelayMs() {
        return Math.max(0, Math.min(500, prefs.getInt(KEY_AUDIO_DELAY_MS, 90)));
    }

    public void setAudioDelayMs(int v) {
        prefs.edit().putInt(KEY_AUDIO_DELAY_MS, Math.max(0, Math.min(500, v))).apply();
    }

    /** FFmpeg-free: returns the MediaCodec mime type. */
    public String getMimeType() {
        String codec = prefs.getString(KEY_CODEC, "hevc");
        return "avc".equalsIgnoreCase(codec)
                ? MediaFormat.MIMETYPE_VIDEO_AVC
                : MediaFormat.MIMETYPE_VIDEO_HEVC;
    }

    public void setCodec(String codec) {
        prefs.edit().putString(KEY_CODEC, codec).apply();
    }

    public boolean isRecordAudio() {
        return prefs.getBoolean(KEY_RECORD_AUDIO, true);
    }

    public void setRecordAudio(boolean v) {
        prefs.edit().putBoolean(KEY_RECORD_AUDIO, v).apply();
    }

    /** Capture the game's own audio output (via AudioPlaybackCapture). */
    public boolean isRecordGameAudio() {
        return prefs.getBoolean(KEY_RECORD_GAME_AUDIO, true);
    }

    public void setRecordGameAudio(boolean v) {
        prefs.edit().putBoolean(KEY_RECORD_GAME_AUDIO, v).apply();
    }

    /** Whether to show the Voicechat push-to-talk button. */
    public boolean isVoicechatButton() {
        return prefs.getBoolean(KEY_VOICECHAT_BUTTON, true);
    }

    public void setVoicechatButton(boolean v) {
        prefs.edit().putBoolean(KEY_VOICECHAT_BUTTON, v).apply();
    }
}
