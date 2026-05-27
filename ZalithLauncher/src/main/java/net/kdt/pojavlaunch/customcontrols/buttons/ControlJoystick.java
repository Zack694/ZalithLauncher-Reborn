package net.kdt.pojavlaunch.customcontrols.buttons;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.View;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlPopup;

import org.lwjgl.glfw.CallbackBridge;

import io.github.controlwear.virtual.joystick.android.JoystickView;

@SuppressLint("ViewConstructor")
public class ControlJoystick extends JoystickView implements ControlInterface {
    public final static int DIRECTION_FORWARD_LOCK = 8;
    // Directions keycode
    private final int[] mDirectionForwardLock = new int[]{LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL};
    private final int[] mDirectionForward = new int[]{LwjglGlfwKeycode.GLFW_KEY_W};
    private final int[] mDirectionRight = new int[]{LwjglGlfwKeycode.GLFW_KEY_D};
    private final int[] mDirectionBackward = new int[]{LwjglGlfwKeycode.GLFW_KEY_S};
    private final int[] mDirectionLeft = new int[]{LwjglGlfwKeycode.GLFW_KEY_A};
    private ControlJoystickData mControlData;
    private boolean mForwardPressed = false;
    private boolean mBackwardPressed = false;
    private boolean mLeftPressed = false;
    private boolean mRightPressed = false;
    private static final int DEADZONE_STRENGTH = 35;

    /*
     * Zone-based directional input.
     *
     * The 360-degree joystick range is divided into 8 angular zones (45 deg each):
     *   0=E, 1=NE, 2=N, 3=NW, 4=W, 5=SW, 6=S, 7=SE
     * plus a -1 "no zone" state used for the deadzone.
     *
     * Each zone maps to a fixed combination of W/A/S/D. While the finger stays
     * inside a zone, no key events are emitted regardless of magnitude or
     * angular wobble - W/A/S/D are simply held. Crossing a zone boundary emits
     * exactly the diff between the two zones' key sets, never a flicker.
     *
     * To prevent flicker right at a boundary, we apply angular hysteresis:
     * once a zone is active, the user must push beyond ZONE_EXIT_HALFWIDTH_DEG
     * from that zone's center (instead of the implicit 22.5 deg enter
     * half-width) before we consider another zone.
     *
     * This replaces the previous per-axis hysteresis approach, which mapped
     * a continuous 2D input through two independent 1D thresholds and could
     * rapidly toggle W/A/S/D from normal thumb wobble - causing visible
     * movement stutter in Minecraft even at stable FPS, since each key edge
     * resets the player's movement acceleration / sprint state.
     */
    private static final int ZONE_NONE = -1;
    private static final int ZONE_E  = 0;
    private static final int ZONE_NE = 1;
    private static final int ZONE_N  = 2;
    private static final int ZONE_NW = 3;
    private static final int ZONE_W  = 4;
    private static final int ZONE_SW = 5;
    private static final int ZONE_S  = 6;
    private static final int ZONE_SE = 7;

    private static final double ZONE_WIDTH_DEG = 45.0;
    /** Angular hysteresis: stay in current zone while within +/-30 deg of its center. */
    private static final double ZONE_EXIT_HALFWIDTH_DEG = 30.0;

    private int mActiveZone = ZONE_NONE;

    public ControlJoystick(ControlLayout parent, ControlJoystickData data) {
        super(parent.getContext());
        init(data, parent);
    }

    private static void sendInput(int[] keys, boolean isDown) {
        for (int key : keys) {
            CallbackBridge.sendKeyPress(key, CallbackBridge.getCurrentMods(), isDown);
        }
    }

    private void init(ControlJoystickData data, ControlLayout layout) {
        mControlData = data;
        setProperties(preProcessProperties(data, layout));
        setDeadzone(DEADZONE_STRENGTH);
        setFixedCenter(data.absolute);
        setAutoReCenterButton(true);
        setButtonSizeRatio(0f);
        setButtonColor(Color.TRANSPARENT);

        injectBehaviors();

        setOnMoveListener(new OnMoveListener() {
            @Override
            public void onMove(int angle, int strength) {
                updateAxisStates(angle, strength);
            }

            @Override
            public void onForwardLock(boolean isLocked) {
                sendInput(mDirectionForwardLock, isLocked);
            }
        });
    }

    @Override
    public View getControlView() {
        return this;
    }

    @Override
    public ControlData getProperties() {
        return mControlData;
    }

    @Override
    public void setProperties(ControlData properties, boolean changePos) {
        mControlData = (ControlJoystickData) properties;
        mControlData.isHideable = true;
        ControlInterface.super.setProperties(properties, changePos);
        postDelayed(() -> {
            setForwardLockDistance(mControlData.forwardLock ? (int) Tools.dpToPx(60) : 0);
            setFixedCenter(mControlData.absolute);
        }, 10);
    }

    @Override
    public void removeButton() {
        getControlLayoutParent().getLayout().mJoystickDataList.remove(getProperties());
        getControlLayoutParent().removeView(this);
    }

    @Override
    public void cloneButton() {
        ControlJoystickData data = new ControlJoystickData(mControlData);
        getControlLayoutParent().addJoystickButton(data);
    }


    @Override
    public void setBackground() {
        setBorderWidth((int) Tools.dpToPx(getProperties().strokeWidth * (getControlLayoutParent().getLayoutScale()/100f)));
        setBorderColor(getProperties().strokeColor);
        setBackgroundColor(getProperties().bgColor);
    }

    @Override
    public void sendKeyPresses(boolean isDown) {/*STUB since non swipeable*/ }

    @Override
    public void loadEditValues(EditControlPopup editControlPopup) {
        editControlPopup.loadJoystickValues(mControlData);
    }

    /**
     * Resolve the current (angle, strength) pair into a single discrete zone
     * and fire any key transitions needed to reach that zone's key combo.
     *
     * Driven by the underlying JoystickView's polling thread (~20 Hz while
     * the finger is held). When the resolved zone is unchanged from last
     * tick this method emits zero key events, even though it runs every tick.
     */
    private void updateAxisStates(int angle, int strength) {
        int targetZone = resolveZone(angle, strength, mActiveZone);
        if (targetZone == mActiveZone) {
            return;
        }
        mActiveZone = targetZone;
        applyZone(targetZone);
    }

    /**
     * Pick the zone the user is currently in, with angular hysteresis around
     * each zone boundary so that thumb wobble inside a zone (or right at an
     * edge) does not cause key events to flicker.
     */
    private static int resolveZone(int angle, int strength, int currentZone) {
        if (strength <= DEADZONE_STRENGTH) {
            return ZONE_NONE;
        }

        int normalizedAngle = ((angle % 360) + 360) % 360;

        // Hysteresis: keep the current zone as long as the angle is still
        // within the (wider) exit half-width of that zone's center.
        if (currentZone != ZONE_NONE) {
            double currentZoneCenter = currentZone * ZONE_WIDTH_DEG;
            if (angularDistance(normalizedAngle, currentZoneCenter) <= ZONE_EXIT_HALFWIDTH_DEG) {
                return currentZone;
            }
        }

        // Otherwise, snap to the nearest zone center (each zone is 45 deg wide,
        // so this is equivalent to a 22.5 deg enter half-width).
        return ((int) Math.round(normalizedAngle / ZONE_WIDTH_DEG)) & 7;
    }

    /**
     * Smallest unsigned angular distance between two angles in degrees.
     */
    private static double angularDistance(double a, double b) {
        double diff = Math.abs(a - b) % 360.0;
        return Math.min(diff, 360.0 - diff);
    }

    /**
     * Map the resolved zone to its W/A/S/D combo and dispatch only the
     * differences vs. the currently-held keys.
     */
    private void applyZone(int zone) {
        boolean forward, backward, left, right;
        switch (zone) {
            case ZONE_E:  forward=false; backward=false; left=false; right=true;  break;
            case ZONE_NE: forward=true;  backward=false; left=false; right=true;  break;
            case ZONE_N:  forward=true;  backward=false; left=false; right=false; break;
            case ZONE_NW: forward=true;  backward=false; left=true;  right=false; break;
            case ZONE_W:  forward=false; backward=false; left=true;  right=false; break;
            case ZONE_SW: forward=false; backward=true;  left=true;  right=false; break;
            case ZONE_S:  forward=false; backward=true;  left=false; right=false; break;
            case ZONE_SE: forward=false; backward=true;  left=false; right=true;  break;
            default:      forward=false; backward=false; left=false; right=false; break;
        }
        setDirectionalState(forward, backward, left, right);
    }

    private void setDirectionalState(boolean forward, boolean backward, boolean left, boolean right) {
        if (mForwardPressed != forward) {
            mForwardPressed = forward;
            sendInput(mDirectionForward, forward);
        }
        if (mBackwardPressed != backward) {
            mBackwardPressed = backward;
            sendInput(mDirectionBackward, backward);
        }
        if (mLeftPressed != left) {
            mLeftPressed = left;
            sendInput(mDirectionLeft, left);
        }
        if (mRightPressed != right) {
            mRightPressed = right;
            sendInput(mDirectionRight, right);
        }
    }
}
