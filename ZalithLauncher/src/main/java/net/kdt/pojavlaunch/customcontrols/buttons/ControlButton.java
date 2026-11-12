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
                executeKbDisplacementFlick();
                if(!getProperties().isToggle){
                    sendKeyPresses(true);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                if(!getProperties().isToggle){
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

                if(!triggerToggle()) {
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

    /** Per-button configuration popup for the KB Displacement Camera Flick. */
    private void showKbFlickEditorDialog() {
        android.content.Context ctx = getContext();
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final android.widget.CheckBox enableBox = new android.widget.CheckBox(ctx);
        enableBox.setText("Enable KB Displacement Camera Flick");
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
        degLabel.setText("Flick Angle in Degrees (e.g. 45, 65, 80, 90, 180):");
        layout.addView(degLabel);

        final android.widget.EditText degInput = new android.widget.EditText(ctx);
        degInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        degInput.setText(String.valueOf(mProperties.flickDegrees));
        layout.addView(degInput);

        final android.widget.TextView delayLabel = new android.widget.TextView(ctx);
        delayLabel.setText("Snap-Back Delay in ms (35ms = 1 tick for KB Displacement):");
        layout.addView(delayLabel);

        final android.widget.EditText delayInput = new android.widget.EditText(ctx);
        delayInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        delayInput.setText(String.valueOf(mProperties.flickReturnDelayMs));
        layout.addView(delayInput);

        final android.widget.CheckBox autoAttackBox = new android.widget.CheckBox(ctx);
        autoAttackBox.setText("Auto Left-Click (Attack) on Snap-Back");
        autoAttackBox.setChecked(mProperties.flickAutoAttack);
        layout.addView(autoAttackBox);

        new android.app.AlertDialog.Builder(ctx)
            .setTitle("KB Displacement / Camera Flick Settings")
            .setView(layout)
            .setPositiveButton("Save", (d, w) -> {
                mProperties.isFlickButton = enableBox.isChecked();
                mProperties.flickDirection = dirSpinner.getSelectedItemPosition();
                try {
                    mProperties.flickDegrees = Float.parseFloat(degInput.getText().toString().trim());
                } catch (Throwable ignored) {}
                try {
                    mProperties.flickReturnDelayMs = Integer.parseInt(delayInput.getText().toString().trim());
                } catch (Throwable ignored) {}
                mProperties.flickAutoAttack = autoAttackBox.isChecked();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Visibly flicks the real camera via CallbackBridge cursor displacement and
     * snaps back to the exact original pixel after flickReturnDelayMs.
     * ~4.4444 cursor units per degree of yaw at default MC sensitivity.
     */
    private void executeKbDisplacementFlick() {
        if (mProperties == null || !mProperties.isFlickButton || !CallbackBridge.isGrabbing()) return;
        float pixelsPerDegree = 4.444444f;
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
        final float finalDx = dx;
        final float finalDy = dy;

        // Step 1: visibly flick the real camera to the displaced angle
        CallbackBridge.mouseX += finalDx;
        CallbackBridge.mouseY += finalDy;
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);

        // Step 2: snap back to the exact original angle after the delay
        int delayMs = Math.max(1, Math.min(250, mProperties.flickReturnDelayMs));
        postDelayed(() -> {
            CallbackBridge.mouseX -= finalDx;
            CallbackBridge.mouseY -= finalDy;
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
            if (mProperties.flickAutoAttack) {
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
                postDelayed(() -> sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false), 20);
            }
        }, delayMs);
    }
}
