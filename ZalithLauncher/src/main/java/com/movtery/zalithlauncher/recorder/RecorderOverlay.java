package com.movtery.zalithlauncher.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Small in-game overlay with a REC toggle and a "Voicechat" push-to-talk button.
 * Added on top of everything (above the touch-control layer). Only shown when the
 * recorder is enabled in settings. Lightweight: button callbacks just flip flags.
 */
public final class RecorderOverlay {

    private static final int OVERLAY_TAG = 0x5EC0_DE01;
    private static final int REQ_RECORD_AUDIO = 0x5EC1;

    private RecorderOverlay() {
    }

    public static void attach(Activity activity) {
        RecorderPrefs prefs = new RecorderPrefs(activity);
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null || root.findViewWithTag(OVERLAY_TAG) != null) {
            return;
        }

        LinearLayout bar = new LinearLayout(activity);
        bar.setTag(OVERLAY_TAG);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0x66000000);
        int pad = dp(activity, 4);
        bar.setPadding(pad, pad, pad, pad);

        if (!prefs.isEnabled()) {
            // Recorder support is a pre-game decision (it routes the render surface),
            // so enabling only takes effect on the next game launch. Default OFF so
            // normal play is never affected unless the user opts in.
            Button enable = new Button(activity);
            enable.setAllCaps(false);
            enable.setText("\u25CF Recorder: OFF");
            enable.setTextColor(Color.WHITE);
            enable.setOnClickListener(v -> {
                prefs.setEnabled(true);
                Toast.makeText(activity,
                        "Built-in recorder enabled - relaunch the game to use it",
                        Toast.LENGTH_LONG).show();
                v.setEnabled(false);
                ((Button) v).setText("\u25CF Recorder: ON (relaunch)");
            });
            bar.addView(enable);
            addBar(activity, root, bar);
            return;
        }

        final Button recButton = new Button(activity);
        recButton.setAllCaps(false);
        recButton.setText("\u25CF REC");
        recButton.setTextColor(Color.WHITE);
        recButton.setOnClickListener(v -> {
            GameRecorder recorder = GameRecorder.getInstance();
            if (!recorder.isRecording()) {
                if (prefs.isRecordAudio() && ContextCompat.checkSelfPermission(activity,
                        Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activity,
                            new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                    Toast.makeText(activity, "Grant microphone, then tap REC again",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                recorder.startRecording(activity);
                recButton.setText("\u25A0 STOP");
                recButton.setTextColor(0xFFFF5555);
                Toast.makeText(activity, "Recording started", Toast.LENGTH_SHORT).show();
            } else {
                recorder.stopRecording();
                recButton.setText("\u25CF REC");
                recButton.setTextColor(Color.WHITE);
                Toast.makeText(activity, "Recording saved", Toast.LENGTH_SHORT).show();
            }
        });
        bar.addView(recButton);

        if (prefs.isVoicechatButton()) {
            final Button talkButton = new Button(activity);
            talkButton.setAllCaps(false);
            talkButton.setText("\uD83C\uDF99 Voicechat");
            talkButton.setTextColor(Color.WHITE);
            talkButton.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        GameRecorder.getInstance().setMicActive(true);
                        talkButton.setTextColor(0xFF55FF55);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        GameRecorder.getInstance().setMicActive(false);
                        talkButton.setTextColor(Color.WHITE);
                        v.performClick();
                        return true;
                    default:
                        return false;
                }
            });
            bar.addView(talkButton);
        }

        // Disable path so the user is never stuck if the tee misbehaves.
        Button gear = new Button(activity);
        gear.setAllCaps(false);
        gear.setText("\u2699");
        gear.setTextColor(Color.WHITE);
        gear.setOnClickListener(v -> {
            GameRecorder.getInstance().stopRecording();
            prefs.setEnabled(false);
            Toast.makeText(activity, "Recorder disabled - relaunch the game",
                    Toast.LENGTH_LONG).show();
        });
        bar.addView(gear);

        addBar(activity, root, bar);
    }

    private static void addBar(Activity activity, ViewGroup root, LinearLayout bar) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(activity, 4);
        root.addView(bar, lp);
    }

    public static void detach(Activity activity) {
        ViewGroup root = activity == null ? null : activity.findViewById(android.R.id.content);
        if (root != null) {
            View bar = root.findViewWithTag(OVERLAY_TAG);
            if (bar != null) {
                root.removeView(bar);
            }
        }
    }

    private static int dp(Activity activity, int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
