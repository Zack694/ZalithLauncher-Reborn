package com.movtery.zalithlauncher.recorder.gl;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws a full-screen quad sampling a regular {@code GL_TEXTURE_2D}.
 *
 * <p>Used by the dedicated encoder thread to blit a relay texture (already
 * composited from the capture {@link android.graphics.SurfaceTexture} by the
 * display thread) into the encoder's input surface. Because the relay texture is
 * stored upright by the producing FBO pass, no transform matrix is needed - the
 * quad's UVs map 1:1.</p>
 */
public final class Texture2DBlit {

    private static final int FLOAT_SIZE = 4;

    // Full-screen quad: (x, y, u, v) with v origin at bottom-left (GL convention),
    // matching how the producing FBO pass stored the frame.
    private static final float[] FULL_QUAD = {
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f,
    };

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTextureCoord = aTextureCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private final FloatBuffer mQuad;
    private int mProgram;
    private int aPositionLoc;
    private int aTextureCoordLoc;
    private int sTextureLoc;

    public Texture2DBlit() {
        mQuad = ByteBuffer.allocateDirect(FULL_QUAD.length * FLOAT_SIZE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mQuad.put(FULL_QUAD).position(0);

        mProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionLoc = GLES20.glGetAttribLocation(mProgram, "aPosition");
        aTextureCoordLoc = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        sTextureLoc = GLES20.glGetUniformLocation(mProgram, "sTexture");
    }

    /** Draw {@code textureId} (a GL_TEXTURE_2D) filling the current viewport. */
    public void draw(int textureId) {
        GLES20.glUseProgram(mProgram);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(sTextureLoc, 0);

        mQuad.position(0);
        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false,
                4 * FLOAT_SIZE, mQuad);

        mQuad.position(2);
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc);
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false,
                4 * FLOAT_SIZE, mQuad);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
    }

    public void release() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }

    private static int buildProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Could not link program: " + log);
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Could not compile shader " + type + ": " + log);
        }
        return shader;
    }
}
