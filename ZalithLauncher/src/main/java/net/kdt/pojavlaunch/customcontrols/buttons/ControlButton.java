package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_UNKNOWN;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.setting.AllSettings;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlPopup;

import org.lwjgl.glfw.CallbackBridge;

import java.util.concurrent.ThreadLocalRandom;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;

    protected boolean mIsToggled = false;
    protected boolean mIsPointerOutOfBounds = false;
    private final Handler mClickerHandler = new Handler(Looper.getMainLooper());
    private final Runnable mClickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isActivated() || !mProperties.isClicker) return;
            int mappedButton = mProperties.clickerButton == ControlData.SPECIALBTN_MOUSESEC ?
                    LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
            sendMouseButton(mappedButton, true);
            sendMouseButton(mappedButton, false);
            mClickerHandler.postDelayed(this, computeClickerDelayMs());
        }
    };

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        setAllCaps(AllSettings.getButtonAllCaps().getValue());
        setTextColor(Color.WHITE);
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);

        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(128);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(60);
        }

        setText(properties.name);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mIsToggled || (!mProperties.isToggle && isActivated()))
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }


    public void loadEditValues(EditControlPopup editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        getControlLayoutParent().getLayout().mControlDataList.remove(getProperties());
        getControlLayoutParent().removeView(this);
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()){
            case MotionEvent.ACTION_MOVE:
                //Send the event to be taken as a mouse action
                if(getProperties().passThruEnabled && CallbackBridge.isGrabbing()){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }

                //If out of bounds (event.getX/Y are button-local: 0..getWidth, 0..getHeight)
                if(event.getX() < 0 || event.getX() > getWidth() ||
                        event.getY() < 0 || event.getY() > getHeight()){
                    if(getProperties().isSwipeable && !mIsPointerOutOfBounds){
                        //Remove keys
                        if(!triggerToggle()) {
                            sendKeyPresses(false);
                        }
                    }
                    mIsPointerOutOfBounds = true;
                    getControlLayoutParent().onTouch(this, event);
                    break;
                }

                //Else if we now are in bounds
                if(mIsPointerOutOfBounds) {
                    getControlLayoutParent().onTouch(this, event);
                    //RE-press the button
                    if(getProperties().isSwipeable && !getProperties().isToggle){
                        sendKeyPresses(true);
                    }
                }
                mIsPointerOutOfBounds = false;
                break;

            case MotionEvent.ACTION_DOWN: // 0
                try {
                    requestUnbufferedDispatch(event);
                } catch (Throwable ignored) {
                }
                if(getProperties().isFlickButton){
                    // Flick buttons consume the press: the humanized flick plus the
                    // single snap-back attack IS the whole action, so no early click
                    // is fired and the 1.21 attack cooldown is never drained.
                    executeKbDisplacementFlick();
                    break;
                }
                if(!getProperties().isToggle){
                    sendKeyPresses(true);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                if(!getProperties().isFlickButton && !getProperties().isToggle){
                    sendKeyPresses(true);
                }
                break;

            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                if(getProperties().passThruEnabled){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                if(mIsPointerOutOfBounds) getControlLayoutParent().onTouch(this, event);
                mIsPointerOutOfBounds = false;

                if(!getProperties().isFlickButton && !triggerToggle()) {
                    sendKeyPresses(false);
                }
                break;

            default:
                return false;
        }

        return true;
    }



    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    // Dispatch native JNI key events FIRST (~1us @CriticalNative) before
    // triggering Android UI redraw (setActivated).
    public void sendKeyPresses(boolean isDown){
        if (!mProperties.isClicker) {
            // First pass: Process all regular keyboard keys immediately
            for(int keycode : mProperties.keycodes){
                if(keycode >= GLFW_KEY_UNKNOWN){
                    sendKeyPress(keycode, CallbackBridge.getCurrentMods(), isDown);
                    CallbackBridge.setModifiers(keycode, isDown);
                }
            }

            // Second pass: Process all special keys (mouse buttons, etc.)
            for(int keycode : mProperties.keycodes){
                if(keycode < GLFW_KEY_UNKNOWN){
                    sendSpecialKey(keycode, isDown);
                }
            }
        }

        setActivated(isDown);

        if (mProperties.isVoicechatMic) {
            com.movtery.zalithlauncher.recorder.GameRecorder.setMicHeld(isDown);
        }

        if (mProperties.isClicker) {
            if (isDown) {
                startClickerLoop();
            } else {
                stopClickerLoop();
            }
        }
    }

    private void startClickerLoop() {
        stopClickerLoop();
        mClickerHandler.post(mClickerRunnable);
    }

    private void stopClickerLoop() {
        mClickerHandler.removeCallbacks(mClickerRunnable);
    }

    private long computeClickerDelayMs() {
        int min = Math.max(1, Math.min(mProperties.minCps, mProperties.maxCps));
        int max = Math.max(min, Math.max(mProperties.minCps, mProperties.maxCps));
        int cps = ThreadLocalRandom.current().nextInt(min, max + 1);
        return Math.max(1L, 1000L / cps);
    }

    private void sendSpecialKey(int keycode, boolean isDown){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) MainActivity.switchKeyboardState();
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown) getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) MainActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1d);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1d);
                break;
            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private long mLastEditTapMs = 0L;
    private boolean mFlickInProgress = false;
    private float mAppliedFlickDx = 0f;
    private float mAppliedFlickDy = 0f;

    /**
     * Edit Controls mode: a quick double-tap on a button opens the
     * KB Displacement Camera Flick editor instead of the properties panel.
     * (A single clean tap still opens the regular properties panel.)
     */
    @Override
    public boolean onLongClick(View v) {
        if (getControlLayoutParent() != null && getControlLayoutParent().getModifiable()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - mLastEditTapMs < 400L) {
                mLastEditTapMs = 0L;
                showKbFlickEditorDialog();
                return true;
            }
            mLastEditTapMs = now;
        }
        return ControlInterface.super.onLongClick(v);
    }

    /** Per-button configuration popup for the Humanized KB Displacement Camera Flick. */
    private void showKbFlickEditorDialog() {
        android.content.Context ctx = getContext();
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (14 * ctx.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        scroll.addView(layout);

        final android.widget.CheckBox enableBox = new android.widget.CheckBox(ctx);
        enableBox.setText("Enable Humanized KB Displacement Camera Flick");
        enableBox.setChecked(mProperties.isFlickButton);
        layout.addView(enableBox);

        final android.widget.TextView dirLabel = new android.widget.TextView(ctx);
        dirLabel.setText("Flick Direction (8-Way):");
        layout.addView(dirLabel);

        final android.widget.Spinner dirSpinner = new android.widget.Spinner(ctx);
        String[] dirs = new String[] {
            "Right", "Left", "Up", "Down",
            "Up-Right", "Up-Left", "Down-Right", "Down-Left"
        };
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
            ctx, android.R.layout.simple_spinner_dropdown_item, dirs
        );
        dirSpinner.setAdapter(adapter);
        dirSpinner.setSelection(Math.max(0, Math.min(7, mProperties.flickDirection)));
        layout.addView(dirSpinner);

        final android.widget.TextView degLabel = new android.widget.TextView(ctx);
        degLabel.setText("Flick Angle in Degrees (auto-calibrated to options.txt sensitivity):");
        layout.addView(degLabel);

        final android.widget.EditText degInput = new android.widget.EditText(ctx);
        degInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        degInput.setText(String.valueOf(mProperties.flickDegrees));
        layout.addView(degInput);

        final android.widget.TextView stepsLabel = new android.widget.TextView(ctx);
        stepsLabel.setText("Humanize Smoothness Steps (1 = Instant, 4 = Fast Human Wrist, 6 = Smooth):");
        layout.addView(stepsLabel);

        final android.widget.EditText stepsInput = new android.widget.EditText(ctx);
        stepsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        stepsInput.setText(String.valueOf(mProperties.flickHumanizeSteps <= 0 ? 4 : mProperties.flickHumanizeSteps));
        layout.addView(stepsInput);

        final android.widget.TextView delayLabel = new android.widget.TextView(ctx);
        delayLabel.setText("Tick Hold Time in ms (52ms guarantees one full 50ms MC tick registers the displaced yaw):");
        layout.addView(delayLabel);

        final android.widget.EditText delayInput = new android.widget.EditText(ctx);
        delayInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        delayInput.setText(String.valueOf(mProperties.flickReturnDelayMs < 40 ? 52 : mProperties.flickReturnDelayMs));
        layout.addView(delayInput);

        final android.widget.TextView scaleLabel = new android.widget.TextView(ctx);
        scaleLabel.setText("Angle Calibration Multiplier (1.0 = Exact Vanilla Sensitivity):");
        layout.addView(scaleLabel);

        final android.widget.EditText scaleInput = new android.widget.EditText(ctx);
        scaleInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        scaleInput.setText(String.valueOf(mProperties.flickSensitivityScale <= 0f ? 1.0f : mProperties.flickSensitivityScale));
        layout.addView(scaleInput);

        final android.widget.CheckBox autoAttackBox = new android.widget.CheckBox(ctx);
        autoAttackBox.setText("Single Attack on Snap-Back (fires 1 clean hit after the crosshair returns)");
        autoAttackBox.setChecked(mProperties.flickAutoAttack);
        layout.addView(autoAttackBox);

        new android.app.AlertDialog.Builder(ctx)
            .setTitle("Humanized KB Displacement Flick")
            .setView(scroll)
            .setPositiveButton("Save", (d, w) -> {
                mProperties.isFlickButton = enableBox.isChecked();
                mProperties.flickDirection = dirSpinner.getSelectedItemPosition();
                try {
                    mProperties.flickDegrees = Float.parseFloat(degInput.getText().toString().trim());
                } catch (Throwable ignored) {}
                try {
                    mProperties.flickHumanizeSteps = Math.max(1, Math.min(12, Integer.parseInt(stepsInput.getText().toString().trim())));
                } catch (Throwable ignored) {}
                try {
                    mProperties.flickReturnDelayMs = Math.max(20, Math.min(250, Integer.parseInt(delayInput.getText().toString().trim())));
                } catch (Throwable ignored) {}
                try {
                    mProperties.flickSensitivityScale = Math.max(0.1f, Math.min(5.0f, Float.parseFloat(scaleInput.getText().toString().trim())));
                } catch (Throwable ignored) {}
                mProperties.flickAutoAttack = autoAttackBox.isChecked();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Single clean attack on snap-back: presses this button's own keycodes once. */
    private void triggerSingleFlickAttack(boolean pressed) {
        sendKeyPresses(pressed);
    }

    /**
     * Humanized KB displacement camera flick:
     *  Phase 1 - S-curve outbound whip (sine ease-out + tiny random jitter),
     *  Phase 2 - hold the displaced yaw for one full 50ms MC tick,
     *  Phase 3 - S-curve return to the exact starting pixel (zero net drift),
     *  Phase 4 - one render frame later, fire a SINGLE attack via the
     *            button's own keycodes so the 1.21 cooldown is never wasted.
     * Cursor units per degree are auto-calibrated from options.txt mouseSensitivity:
     * d = sens * 0.6 + 0.2; mult = d^3 * 8; degrees = delta * mult * 0.15.
     */
    private void executeKbDisplacementFlick() {
        if (mFlickInProgress || mProperties == null || !mProperties.isFlickButton || !CallbackBridge.isGrabbing()) return;
        mFlickInProgress = true;
        mAppliedFlickDx = 0f;
        mAppliedFlickDy = 0f;

        double sens = 0.5;
        try {
            String sVal = com.movtery.zalithlauncher.feature.MCOptions.INSTANCE.get("mouseSensitivity");
            if (sVal != null && !sVal.isEmpty()) {
                sens = Double.parseDouble(sVal.trim());
            }
        } catch (Throwable ignored) {}
        double d = sens * 0.6 + 0.2;
        double mcSensMult = (d * d * d) * 8.0;
        float scale = (mProperties.flickSensitivityScale > 0.05f) ? mProperties.flickSensitivityScale : 1.0f;
        float pixelsPerDegree = (float) (1.0 / (0.15 * Math.max(0.05, mcSensMult))) * scale;
        float mag = mProperties.flickDegrees * pixelsPerDegree;

        float dx = 0f;
        float dy = 0f;
        float diag = 0.70710678f;
        switch (mProperties.flickDirection) {
            case 0: dx =  mag; break; // Right
            case 1: dx = -mag; break; // Left
            case 2: dy = -mag; break; // Up
            case 3: dy =  mag; break; // Down
            case 4: dx =  mag * diag; dy = -mag * diag; break; // Up-Right
            case 5: dx = -mag * diag; dy = -mag * diag; break; // Up-Left
            case 6: dx =  mag * diag; dy =  mag * diag; break; // Down-Right
            case 7: dx = -mag * diag; dy =  mag * diag; break; // Down-Left
        }
        final float totalDx = dx;
        final float totalDy = dy;
        final int steps = Math.max(1, Math.min(12, mProperties.flickHumanizeSteps <= 0 ? 4 : mProperties.flickHumanizeSteps));
        final long stepIntervalMs = 4L; // 240Hz frame pacing

        // Phase 1: humanized S-curve outbound whip with zero-sum micro-jitter
        for (int i = 1; i <= steps; i++) {
            final int stepIdx = i;
            postDelayed(() -> {
                float targetX;
                float targetY;
                if (stepIdx == steps) {
                    targetX = totalDx;
                    targetY = totalDy;
                } else {
                    double t = stepIdx / (double) steps;
                    // Sine ease-out wrist acceleration curve + tiny natural human variance
                    float curve = (float) Math.sin(t * (Math.PI * 0.5));
                    float jitter = (java.util.concurrent.ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.036f;
                    float frac = Math.max(0.05f, Math.min(0.98f, curve + jitter));
                    targetX = totalDx * frac;
                    targetY = totalDy * frac;
                }
                float stepDx = targetX - mAppliedFlickDx;
                float stepDy = targetY - mAppliedFlickDy;
                mAppliedFlickDx += stepDx;
                mAppliedFlickDy += stepDy;
                CallbackBridge.mouseX += stepDx;
                CallbackBridge.mouseY += stepDy;
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
            }, (stepIdx - 1) * stepIntervalMs);
        }

        // Phase 2: hold the displaced angle across one full 50ms MC client tick
        long outboundDurationMs = (steps - 1) * stepIntervalMs;
        int holdMs = Math.max(25, Math.min(250, mProperties.flickReturnDelayMs <= 0 ? 52 : mProperties.flickReturnDelayMs));
        long returnStartMs = outboundDurationMs + holdMs;

        // Phase 3: humanized S-curve return to the exact starting pixel (0.0 net drift)
        for (int j = 1; j <= steps; j++) {
            final int retIdx = j;
            postDelayed(() -> {
                float remX;
                float remY;
                if (retIdx == steps) {
                    remX = 0f;
                    remY = 0f;
                } else {
                    double t = retIdx / (double) steps;
                    float curve = (float) (1.0 - Math.sin(t * (Math.PI * 0.5)));
                    float jitter = (java.util.concurrent.ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.036f;
                    float frac = Math.max(0.02f, Math.min(0.95f, curve + jitter));
                    remX = totalDx * frac;
                    remY = totalDy * frac;
                }
                float stepDx = remX - mAppliedFlickDx;
                float stepDy = remY - mAppliedFlickDy;
                mAppliedFlickDx += stepDx;
                mAppliedFlickDy += stepDy;
                CallbackBridge.mouseX += stepDx;
                CallbackBridge.mouseY += stepDy;
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
            }, returnStartMs + (retIdx * stepIntervalMs));
        }

        // Phase 4: one render frame after the return so Mouse.updateMouse() locks the
        // crosshair back on target, then fire ONE single attack (no double click).
        long returnDoneMs = returnStartMs + (steps * stepIntervalMs);
        postDelayed(() -> {
            if (mProperties.flickAutoAttack) {
                triggerSingleFlickAttack(true);
                postDelayed(() -> {
                    triggerSingleFlickAttack(false);
                    mFlickInProgress = false;
                }, 22L);
            } else {
                mFlickInProgress = false;
            }
        }, returnDoneMs + 10L);
    }
}
