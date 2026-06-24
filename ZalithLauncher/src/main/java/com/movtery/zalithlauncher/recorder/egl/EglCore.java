package com.movtery.zalithlauncher.recorder.egl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

/**
 * Minimal EGL 1.4 helper for the recorder's GL compositor thread.
 *
 * <p>Creates a GLES2 context (optionally sharing with another context and/or
 * flagged as "recordable" so its window surfaces can be a MediaCodec input
 * surface). Modeled on the well-known Grafika EglCore pattern.</p>
 */
public final class EglCore {

    private static final String TAG = "RecorderEglCore";

    /** Surfaces created with this flag may be used as a MediaCodec input surface. */
    public static final int FLAG_RECORDABLE = 0x01;

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mEglConfig = null;

    public EglCore(EGLContext sharedContext, int flags) {
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("EGL already set up");
        }
        if (sharedContext == null) {
            sharedContext = EGL14.EGL_NO_CONTEXT;
        }

        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            mEglDisplay = EGL14.EGL_NO_DISPLAY;
            throw new RuntimeException("Unable to initialize EGL14");
        }

        EGLConfig config = getConfig(flags);
        if (config == null) {
            throw new RuntimeException("Unable to find a suitable EGLConfig");
        }
        int[] attribList = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        EGLContext context = EGL14.eglCreateContext(mEglDisplay, config, sharedContext,
                attribList, 0);
        checkEglError("eglCreateContext");
        mEglConfig = config;
        mEglContext = context;
    }

    private EGLConfig getConfig(int flags) {
        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                EGL14.EGL_NONE, 0, // placeholder for recordable [@ index 10]
                EGL14.EGL_NONE
        };
        if ((flags & FLAG_RECORDABLE) != 0) {
            attribList[attribList.length - 3] = EGL_RECORDABLE_ANDROID;
            attribList[attribList.length - 2] = 1;
        }
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEglDisplay, attribList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            Log.w(TAG, "unable to find RGBA8888 EGL config");
            return null;
        }
        return configs[0];
    }

    public EGLSurface createWindowSurface(Surface surface) {
        if (surface == null) {
            throw new RuntimeException("surface is null");
        }
        int[] surfaceAttribs = {EGL14.EGL_NONE};
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, mEglConfig, surface,
                surfaceAttribs, 0);
        checkEglError("eglCreateWindowSurface");
        if (eglSurface == null) {
            throw new RuntimeException("surface was null");
        }
        return eglSurface;
    }

    /** Small offscreen surface used to make the context current before any window exists. */
    public EGLSurface createOffscreenSurface(int width, int height) {
        int[] surfaceAttribs = {EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE};
        EGLSurface eglSurface = EGL14.eglCreatePbufferSurface(mEglDisplay, mEglConfig,
                surfaceAttribs, 0);
        checkEglError("eglCreatePbufferSurface");
        if (eglSurface == null) {
            throw new RuntimeException("offscreen surface was null");
        }
        return eglSurface;
    }

    public void makeCurrent(EGLSurface eglSurface) {
        if (!EGL14.eglMakeCurrent(mEglDisplay, eglSurface, eglSurface, mEglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    public boolean swapBuffers(EGLSurface eglSurface) {
        return EGL14.eglSwapBuffers(mEglDisplay, eglSurface);
    }

    public void setPresentationTime(EGLSurface eglSurface, long nsecs) {
        EGLExt.eglPresentationTimeANDROID(mEglDisplay, eglSurface, nsecs);
    }

    public void releaseSurface(EGLSurface eglSurface) {
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEglDisplay, eglSurface);
        }
    }

    public void makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)) {
            throw new RuntimeException("eglMakeCurrent(null) failed");
        }
    }

    public void release() {
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(mEglDisplay, mEglContext);
            // NOTE: deliberately do NOT call eglTerminate() here. This EglCore is
            // created and destroyed per recording session, but the EGL display
            // (EGL_DEFAULT_DISPLAY) is shared process-wide with the native
            // Minecraft renderer. Terminating it would tear down the game's EGL
            // and black-screen/crash it. We only release our own thread + context;
            // the shared display stays initialized for native.
            EGL14.eglReleaseThread();
        }
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        mEglContext = EGL14.EGL_NO_CONTEXT;
        mEglConfig = null;
    }

    private void checkEglError(String msg) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error 0x" + Integer.toHexString(error));
        }
    }
}
