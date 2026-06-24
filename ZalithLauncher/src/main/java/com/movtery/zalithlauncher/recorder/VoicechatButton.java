package com.movtery.zalithlauncher.recorder;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

/**
 * A single small on-screen push-to-talk button. Push-to-talk inherently needs an
 * on-screen control you hold <em>while playing</em>, so it can't live inside the
 * (modal) Special Menu - but it's only shown while recording with mic push-to-talk
 * enabled, and it's toggled from the menu's "Microphone (push-to-talk)" switch.
 *
 * <p>While held it records the microphone; on release the mic is muted again.</p>
 */
public final class VoicechatButton {

    private static final int TAG = 0x5EC0_DE02;

    private VoicechatButton() {
    }

    public static void show(Activity activity) {
        if (activity == null) {
            return;
        }
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null || root.findViewWithTag(TAG) != null) {
            return;
        }
        Button talk = new Button(activity);
        talk.setTag(TAG);
        talk.setAllCaps(false);
        talk.setText("\uD83C\uDF99 Talk");
        talk.setTextColor(Color.WHITE);
        talk.setBackgroundColor(0x66000000);
        talk.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    GameRecorder.getInstance().setMicActive(true);
                    talk.setTextColor(0xFF55FF55);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    GameRecorder.getInstance().setMicActive(false);
                    talk.setTextColor(Color.WHITE);
                    v.performClick();
                    return true;
                default:
                    return false;
            }
        });

        float density = activity.getResources().getDisplayMetrics().density;
        int margin = Math.round(16 * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = margin;
        lp.bottomMargin = margin;
        root.addView(talk, lp);
    }

    public static void hide(Activity activity) {
        ViewGroup root = activity == null ? null : activity.findViewById(android.R.id.content);
        if (root != null) {
            View talk = root.findViewWithTag(TAG);
            if (talk != null) {
                root.removeView(talk);
            }
        }
    }
}
