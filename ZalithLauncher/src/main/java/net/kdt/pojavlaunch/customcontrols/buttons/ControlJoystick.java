package net.kdt.pojavlaunch.customcontrols.buttons;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlPopup;

import org.lwjgl.glfw.CallbackBridge;

/**
 * On-screen joystick implemented as a minimal custom View, intentionally
 * shaped to behave like a multi-state Button.
 *
 * <p>Replaces the previous {@code JoystickView}-based implementation. The
 * third-party widget fired its {@code OnMoveListener} (and called
 * {@code invalidate()}) from inside {@code onTouchEvent} on every
 * {@code ACTION_MOVE} touch sample - 120-240 Hz on modern phones, sometimes
 * higher on gaming panels. That per-sample work on the UI thread caused
 * in-game stutter while walking with the on-screen joystick (visible even at
 * stable FPS, never reproducible with on-screen Buttons because Buttons do
 * almost no work on {@code ACTION_MOVE}).
 *
 * <p>An earlier attempt coalesced the listener to vsync via
 * {@code Choreographer}; that did not resolve the stutter, which strongly
 * implied the underlying lib's own per-sample touch handling and per-sample
 * {@code invalidate()} were also significant contributors. So this version
 * drops the third-party widget entirely. The only work this view does on
 * {@code ACTION_MOVE} is a cheap zone hit-test; key dispatch and any redraw
 * happen on actual state changes only - exactly the cost profile of
 * {@link ControlButton}.
 *
 * <p>The view draws a static fill circle plus an optional stroke ring in its
 * {@code onDraw}. {@code invalidate()} is only ever called from
 * {@link #setBackground()} (in response to a property change), never per
 * touch, so the on-screen joystick no longer schedules redraws while the
 * finger is on it. The moving puck and forward-lock companion view from the
 * old lib are intentionally gone - the user explicitly does not want them.
 *
 * <p>Zone-based directional input (8 zones, 45 deg each, with angular
 * hysteresis) is preserved unchanged from the previous implementation: that
 * was the fix for an earlier W/A/S/D edge-flicker bug and is still needed.
 */
@SuppressLint("ViewConstructor")
public class ControlJoystick extends View implements ControlInterface {
    public final static int DIRECTION_FORWARD_LOCK = 8;

    // Direction keycodes
    private final int[] mDirectionForwardLock = new int[]{LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL};
    private final int[] mDirectionForward = new int[]{LwjglGlfwKeycode.GLFW_KEY_W};
    private final int[] mDirectionRight = new int[]{LwjglGlfwKeycode.GLFW_KEY_D};
    private final int[] mDirectionBackward = new int[]{LwjglGlfwKeycode.GLFW_KEY_S};
    private final int[] mDirectionLeft = new int[]{LwjglGlfwKeycode.GLFW_KEY_A};

    private ControlJoystickData mControlData;

    // Held-key bookkeeping; setDirectionalState only emits a key event when
    // one of these flips, mirroring how ControlButton handles its single
    // pressed/released edge.
    private boolean mForwardPressed = false;
    private boolean mBackwardPressed = false;
    private boolean mLeftPressed = false;
    private boolean mRightPressed = false;

    /** Strength below which the input is treated as deadzone (0-100). */
    private static final int DEADZONE_STRENGTH = 35;

    /*
     * Zone-based directional input (preserved from the previous
     * JoystickView-based implementation).
     *
     * The 360-degree joystick range is divided into 8 angular zones (45 deg
     * each):
     *   0=E, 1=NE, 2=N, 3=NW, 4=W, 5=SW, 6=S, 7=SE
     * plus a -1 "no zone" state used for the deadzone.
     *
     * Each zone maps to a fixed combination of W/A/S/D. While the finger
     * stays inside a zone, no key events are emitted regardless of magnitude
     * or angular wobble - W/A/S/D are simply held. Crossing a zone boundary
     * emits exactly the diff between the two zones' key sets, never a
     * flicker.
     *
     * Angular hysteresis: once a zone is active, the user must push beyond
     * ZONE_EXIT_HALFWIDTH_DEG from that zone's center (instead of the
     * implicit 22.5 deg enter half-width) before another zone can win.
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
    private static final double ZONE_EXIT_HALFWIDTH_DEG = 30.0;

    private int mActiveZone = ZONE_NONE;

    /*
     * Touch state.
     *
     * mActivePointerId tracks a single pointer at a time - additional fingers
     * are ignored, matching the old behavior. mDynamicCenterX/Y are the
     * coordinates the angle/strength are computed against; for relative
     * tracking they're the geometric center of the visible circle, for
     * absolute tracking they snap to the touch-down position.
     */
    private static final int INVALID_POINTER = -1;
    private int mActivePointerId = INVALID_POINTER;
    private float mDynamicCenterX = 0f;
    private float mDynamicCenterY = 0f;

    /*
     * Forward-lock (sprint) state.
     *
     * mForwardLockEnabled mirrors mControlData.forwardLock.
     * mForwardLockDistancePx is how far above the dynamic center the touch
     * must travel (in pixels) to engage the lock; 0 disables the feature.
     * Hysteresis on disengage (FORWARD_LOCK_HYSTERESIS_RATIO) prevents LCTRL
     * down/up flicker right at the threshold.
     */
    private static final float FORWARD_LOCK_DEFAULT_DP = 60f;
    private static final float FORWARD_LOCK_HYSTERESIS_RATIO = 0.7f;
    private boolean mForwardLockEnabled = false;
    private float mForwardLockDistancePx = 0f;
    private boolean mForwardLockActive = false;

    /*
     * Visual state.
     *
     * The joystick draws a filled circle + optional stroke ring. The radius
     * is sized to match the old library's default 75% background-size ratio
     * so that existing user layouts (which were authored against that
     * smaller visible circle) still look right and don't suddenly overlap
     * neighboring buttons.
     *
     * onDraw runs only when the framework redraws the View, which here only
     * happens on size changes and explicit invalidate() calls from
     * setBackground(). Never per touch sample.
     */
    private static final float CIRCLE_RADIUS_RATIO = 0.375f; // = 0.75 / 2
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mVisualCenterX = 0f;
    private float mVisualCenterY = 0f;
    private float mVisualRadius = 0f;

    public ControlJoystick(ControlLayout parent, ControlJoystickData data) {
        super(parent.getContext());
        mFillPaint.setStyle(Paint.Style.FILL);
        mStrokePaint.setStyle(Paint.Style.STROKE);
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
        injectBehaviors();
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
        applyJoystickBehaviorProperties();
    }

    private void applyJoystickBehaviorProperties() {
        mForwardLockEnabled = mControlData.forwardLock;
        mForwardLockDistancePx = mForwardLockEnabled
                ? Tools.dpToPx(FORWARD_LOCK_DEFAULT_DP)
                : 0f;
        // mControlData.absolute is read directly in onTouchEvent on each
        // ACTION_DOWN, so no separate cached flag is needed.
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

    /**
     * Update the fill / stroke colors and stroke width from the current
     * properties, and request a redraw. This intentionally does NOT call
     * View.setBackground(Drawable) - the joystick draws itself in onDraw,
     * keeping the backing drawable null so the framework doesn't introduce
     * an extra GradientDrawable invalidation channel.
     */
    @Override
    public void setBackground() {
        ControlData props = getProperties();
        ControlLayout parent = getControlLayoutParent();
        float scaleFactor = parent != null ? parent.getLayoutScale() / 100f : 1f;
        mFillPaint.setColor(props.bgColor);
        mStrokePaint.setColor(props.strokeColor);
        mStrokePaint.setStrokeWidth(Tools.dpToPx(props.strokeWidth * scaleFactor));
        invalidate();
    }

    @Override
    public void sendKeyPresses(boolean isDown) {/*STUB since non swipeable*/}

    @Override
    public void loadEditValues(EditControlPopup editControlPopup) {
        editControlPopup.loadJoystickValues(mControlData);
    }

    /**
     * Force a square measured size so the rendered circle stays a true
     * circle. EditControlPopup already keeps width and height in sync for
     * joysticks, but old or hand-edited save data could in theory be
     * non-square, so be defensive here.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int d = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(d, d);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        int d = Math.min(w, h);
        mVisualCenterX = w * 0.5f;
        mVisualCenterY = h * 0.5f;
        mVisualRadius = d * CIRCLE_RADIUS_RATIO;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mVisualRadius <= 0f) return;

        // Background fill.
        canvas.drawCircle(mVisualCenterX, mVisualCenterY, mVisualRadius, mFillPaint);

        // Stroke ring, drawn inset by half the stroke width so the ring sits
        // entirely inside the visible circle (and never clips at the View's
        // bounding square).
        float strokeW = mStrokePaint.getStrokeWidth();
        if (strokeW > 0f) {
            canvas.drawCircle(mVisualCenterX, mVisualCenterY,
                    mVisualRadius - strokeW * 0.5f, mStrokePaint);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int actionMasked = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mActivePointerId != INVALID_POINTER) break;
                float x = event.getX(actionIndex);
                float y = event.getY(actionIndex);
                if (mVisualRadius <= 0f) return false;
                // Reject touches that landed in the bounding square but
                // outside the actual circle - matches the old lib's gating.
                if ((float) Math.hypot(x - mVisualCenterX, y - mVisualCenterY) > mVisualRadius) {
                    return false;
                }
                mActivePointerId = event.getPointerId(actionIndex);
                if (mControlData.absolute) {
                    mDynamicCenterX = x;
                    mDynamicCenterY = y;
                } else {
                    mDynamicCenterX = mVisualCenterX;
                    mDynamicCenterY = mVisualCenterY;
                }
                handlePointer(x, y);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INVALID_POINTER) break;
                int idx = event.findPointerIndex(mActivePointerId);
                if (idx < 0) break;
                handlePointer(event.getX(idx), event.getY(idx));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP: {
                int upPid = event.getPointerId(actionIndex);
                if (upPid != mActivePointerId) break;
                mActivePointerId = INVALID_POINTER;
                releaseAll();
                return true;
            }
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Convert a touch position into (angle, strength), evaluate the
     * forward-lock edge if enabled, and route through the existing zone-
     * resolution path. Critically this method does NOT call invalidate() -
     * the visible circle never moves, so there's no per-touch redraw work.
     */
    private void handlePointer(float x, float y) {
        float dx = x - mDynamicCenterX;
        float dy = y - mDynamicCenterY;
        float radius = mVisualRadius;
        if (radius <= 0f) return;

        // Strength as 0..100 percent of the visible-circle radius, clamped.
        int strength = (int) Math.min(100.0,
                Math.round(Math.hypot(dx, dy) / radius * 100.0));
        // Angle in degrees, counter-clockwise, with east = 0 (matches the
        // old lib's convention so the existing zone math is unchanged).
        int angleDeg = (int) Math.round(Math.toDegrees(Math.atan2(-dy, dx)));
        int normalizedAngle = ((angleDeg % 360) + 360) % 360;

        // Forward-lock edge with hysteresis: engage at full distance,
        // disengage only after pulling well below it. Prevents LCTRL flicker
        // from small thumb wobble at the threshold.
        boolean wantForwardLock = false;
        if (mForwardLockEnabled && mForwardLockDistancePx > 0f) {
            float distanceUp = mDynamicCenterY - y;
            float threshold = mForwardLockActive
                    ? mForwardLockDistancePx * FORWARD_LOCK_HYSTERESIS_RATIO
                    : mForwardLockDistancePx;
            wantForwardLock = distanceUp >= threshold;
        }
        if (wantForwardLock != mForwardLockActive) {
            mForwardLockActive = wantForwardLock;
            sendInput(mDirectionForwardLock, mForwardLockActive);
        }

        // While forward-locked, force the resolved zone to north regardless
        // of small angle drift, so the user can wiggle their thumb after
        // locking in without losing W. Mirrors the old lib's getAngle() /
        // getStrength() override during forward-lock.
        int effectiveAngle = mForwardLockActive ? 90 : normalizedAngle;
        int effectiveStrength = mForwardLockActive ? 100 : strength;
        updateAxisStates(effectiveAngle, effectiveStrength);
    }

    private void releaseAll() {
        if (mForwardLockActive) {
            mForwardLockActive = false;
            sendInput(mDirectionForwardLock, false);
        }
        // strength = 0 resolves to ZONE_NONE which clears all directional
        // state through the existing edge-triggered setDirectionalState.
        updateAxisStates(0, 0);
    }

    /**
     * Resolve (angle, strength) into a discrete zone and emit any key edges
     * needed to reach it. Idempotent when the zone hasn't changed; the heavy
     * lifting only runs on actual zone transitions.
     */
    private void updateAxisStates(int angle, int strength) {
        int targetZone = resolveZone(angle, strength, mActiveZone);
        if (targetZone == mActiveZone) return;
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

        // Otherwise, snap to the nearest zone center (each zone is 45 deg
        // wide, so this is equivalent to a 22.5 deg enter half-width).
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

    @Override
    protected void onDetachedFromWindow() {
        // Release any keys we still hold so a removed-mid-press joystick
        // doesn't leave the player walking forever.
        if (mActivePointerId != INVALID_POINTER) {
            mActivePointerId = INVALID_POINTER;
            releaseAll();
        }
        super.onDetachedFromWindow();
    }
}
