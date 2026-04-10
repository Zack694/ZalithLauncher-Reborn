package net.kdt.pojavlaunch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;

import net.kdt.pojavlaunch.utils.JREUtils;

import java.util.LinkedList;

public class AWTCanvasView extends TextureView implements TextureView.SurfaceTextureListener, Runnable {
    public static final int AWT_CANVAS_WIDTH = (int) (Tools.currentDisplayMetrics.widthPixels * 0.8);
    public static final int AWT_CANVAS_HEIGHT = (int) (Tools.currentDisplayMetrics.heightPixels * 0.8);
    private static final int MAX_SIZE = 100;
    private static final double NANOS = 1000000000.0;

    private volatile boolean mIsDestroyed = true;
    private Thread mRenderThread;
    private Surface mSurface;
    private Bitmap mRgbArrayBitmap;
    private final TextPaint mFpsPaint;

    private final LinkedList<Long> mTimes = new LinkedList<Long>() {{
        add(System.nanoTime());
    }};

    public AWTCanvasView(Context ctx) {
        this(ctx, null);
    }

    public AWTCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);

        mFpsPaint = new TextPaint();
        mFpsPaint.setColor(Color.WHITE);
        mFpsPaint.setTextSize(24);

        setSurfaceTextureListener(this);
        post(this::refreshSize);
    }

    @Override
    public synchronized void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        texture.setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
        shutdownRendererLocked();

        mIsDestroyed = false;
        mSurface = new Surface(texture);
        mRenderThread = new Thread(this, "AndroidAWTRenderer");
        mRenderThread.start();
    }

    @Override
    public synchronized boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        mIsDestroyed = true;
        shutdownRendererLocked();
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
        texture.setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    public synchronized void releaseNow() {
        mIsDestroyed = true;
        shutdownRendererLocked();
    }

    private void shutdownRendererLocked() {
        Thread thread = mRenderThread;
        mRenderThread = null;

        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (mSurface != null) {
            try {
                mSurface.release();
            } catch (Throwable ignored) {
            }
            mSurface = null;
        }

        if (mRgbArrayBitmap != null && !mRgbArrayBitmap.isRecycled()) {
            mRgbArrayBitmap.recycle();
            mRgbArrayBitmap = null;
        }
    }

    @Override
    public void run() {
        final Paint paint = new Paint();

        try {
            mRgbArrayBitmap = Bitmap.createBitmap(
                    AWT_CANVAS_WIDTH,
                    AWT_CANVAS_HEIGHT,
                    Bitmap.Config.ARGB_8888
            );

            while (!mIsDestroyed) {
                Surface surface = mSurface;
                if (surface == null || !surface.isValid()) break;

                Canvas canvas = null;
                try {
                    canvas = surface.lockCanvas(null);
                    canvas.drawRGB(0, 0, 0);

                    int[] rgbArray = JREUtils.renderAWTScreenFrame();
                    boolean drawing = rgbArray != null;

                    if (rgbArray != null) {
                        mRgbArrayBitmap.setPixels(
                                rgbArray, 0, AWT_CANVAS_WIDTH,
                                0, 0, AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT
                        );
                        canvas.drawBitmap(mRgbArrayBitmap, 0, 0, paint);
                    }

                    canvas.drawText(
                            "FPS: " + (Math.round(fps() * 10) / 10) + ", drawing=" + drawing,
                            20,
                            20,
                            mFpsPaint
                    );
                } finally {
                    if (canvas != null && surface.isValid()) {
                        surface.unlockCanvasAndPost(canvas);
                    }
                }
            }
        } catch (Throwable throwable) {
            if (!mIsDestroyed) {
                Tools.showError(getContext(), throwable);
            }
        } finally {
            synchronized (this) {
                if (mRgbArrayBitmap != null && !mRgbArrayBitmap.isRecycled()) {
                    mRgbArrayBitmap.recycle();
                    mRgbArrayBitmap = null;
                }

                if (mSurface != null) {
                    try {
                        mSurface.release();
                    } catch (Throwable ignored) {
                    }
                    mSurface = null;
                }
            }
        }
    }

    private double fps() {
        long lastTime = System.nanoTime();
        double difference = (lastTime - mTimes.getFirst()) / NANOS;
        mTimes.addLast(lastTime);
        if (mTimes.size() > MAX_SIZE) {
            mTimes.removeFirst();
        }
        return difference > 0 ? mTimes.size() / difference : 0.0;
    }

    private void refreshSize() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if (layoutParams == null) return;

        if (getHeight() < getWidth()) {
            layoutParams.width = AWT_CANVAS_WIDTH * getHeight() / AWT_CANVAS_HEIGHT;
        } else {
            layoutParams.height = AWT_CANVAS_HEIGHT * getWidth() / AWT_CANVAS_WIDTH;
        }
        setLayoutParams(layoutParams);
    }
}