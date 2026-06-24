package com.movtery.zalithlauncher.recorder.egl;

import android.opengl.EGLSurface;
import android.view.Surface;

/**
 * An EGL window surface bound to an Android {@link Surface} (the display surface
 * or a MediaCodec encoder input surface).
 */
public final class WindowSurface {

    private final EglCore mEglCore;
    private Surface mSurface;
    private final boolean mReleaseSurface;
    private EGLSurface mEglSurface;

    public WindowSurface(EglCore eglCore, Surface surface, boolean releaseSurface) {
        mEglCore = eglCore;
        mSurface = surface;
        mReleaseSurface = releaseSurface;
        mEglSurface = eglCore.createWindowSurface(surface);
    }

    public void makeCurrent() {
        mEglCore.makeCurrent(mEglSurface);
    }

    public boolean swapBuffers() {
        return mEglCore.swapBuffers(mEglSurface);
    }

    public void setPresentationTime(long nsecs) {
        mEglCore.setPresentationTime(mEglSurface, nsecs);
    }

    public void release() {
        mEglCore.releaseSurface(mEglSurface);
        mEglSurface = null;
        if (mReleaseSurface && mSurface != null) {
            mSurface.release();
        }
        mSurface = null;
    }
}
