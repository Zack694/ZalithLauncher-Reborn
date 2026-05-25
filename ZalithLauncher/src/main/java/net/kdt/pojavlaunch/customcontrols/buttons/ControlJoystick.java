package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NONE;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_WEST;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.View;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick;
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
    private int mLastDirectionInt = GamepadJoystick.DIRECTION_NONE;
    private int mCurrentDirectionInt = GamepadJoystick.DIRECTION_NONE;
    private boolean mForwardPressed = false;
    private boolean mBackwardPressed = false;
    private boolean mLeftPressed = false;
    private boolean mRightPressed = false;
    private static final int DEADZONE_STRENGTH = 35;
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

    private void updateAxisStates(int angle, int intensity) {
        if (intensity <= DEADZONE_STRENGTH) {
            setDirectionalState(false, false, false, false);
            return;
        }

        double radians = Math.toRadians(angle);
        double magnitude = intensity / 100d;
        double x = Math.cos(radians) * magnitude;
        double y = -Math.sin(radians) * magnitude;

        boolean nextForward = computeAxisPressed(-y, mForwardPressed);
        boolean nextBackward = computeAxisPressed(y, mBackwardPressed);
        boolean nextLeft = computeAxisPressed(-x, mLeftPressed);
        boolean nextRight = computeAxisPressed(x, mRightPressed);

        setDirectionalState(nextForward, nextBackward, nextLeft, nextRight);
    }

    private static boolean computeAxisPressed(double axisValue, boolean wasPressed) {
        final double pressThreshold = 0.55d;
        final double releaseThreshold = 0.35d;
        if (wasPressed) {
            return axisValue >= releaseThreshold;
        }
        return axisValue >= pressThreshold;
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

        int nextDirection = getDirectionFromState(forward, backward, left, right);
        mLastDirectionInt = mCurrentDirectionInt;
        mCurrentDirectionInt = nextDirection;
    }

    private static int getDirectionFromState(boolean forward, boolean backward, boolean left, boolean right) {
        if (forward && right) return DIRECTION_NORTH_EAST;
        if (forward && left) return DIRECTION_NORTH_WEST;
        if (backward && right) return DIRECTION_SOUTH_EAST;
        if (backward && left) return DIRECTION_SOUTH_WEST;
        if (forward) return DIRECTION_NORTH;
        if (right) return DIRECTION_EAST;
        if (backward) return DIRECTION_SOUTH;
        if (left) return DIRECTION_WEST;
        return DIRECTION_NONE;
    }

    private void sendDirectionalKeycode(int direction, boolean isDown) {
        switch (direction) {
            case DIRECTION_NORTH:
                sendInput(mDirectionForward, isDown);
                break;
            case DIRECTION_NORTH_EAST:
                sendInput(mDirectionForward, isDown);
                sendInput(mDirectionRight, isDown);
                break;
            case DIRECTION_EAST:
                sendInput(mDirectionRight, isDown);
                break;
            case DIRECTION_SOUTH_EAST:
                sendInput(mDirectionRight, isDown);
                sendInput(mDirectionBackward, isDown);
                break;
            case DIRECTION_SOUTH:
                sendInput(mDirectionBackward, isDown);
                break;
            case DIRECTION_SOUTH_WEST:
                sendInput(mDirectionBackward, isDown);
                sendInput(mDirectionLeft, isDown);
                break;
            case DIRECTION_WEST:
                sendInput(mDirectionLeft, isDown);
                break;
            case DIRECTION_NORTH_WEST:
                sendInput(mDirectionForward, isDown);
                sendInput(mDirectionLeft, isDown);
                break;
            case DIRECTION_FORWARD_LOCK:
                sendInput(mDirectionForwardLock, isDown);
                break;
        }
    }

}
