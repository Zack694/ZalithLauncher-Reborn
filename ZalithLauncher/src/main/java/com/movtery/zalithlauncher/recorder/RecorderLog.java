package com.movtery.zalithlauncher.recorder;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Appends recorder diagnostics to a plain-text file next to the recordings, so
 * problems can be inspected on-device without logcat:
 *   Android/data/&lt;pkg&gt;/files/Movies/RecordZy/recordzy-log.txt
 */
public final class RecorderLog {

    public static final String TAG = "RecordZy";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private RecorderLog() {
    }

    private static File logFile(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RecordZy");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, "recordzy-log.txt");
    }

    public static synchronized void log(Context ctx, String msg) {
        Log.i(TAG, msg);
        if (ctx == null) {
            return;
        }
        try (FileWriter w = new FileWriter(logFile(ctx), true)) {
            w.write(TS.format(new Date()) + "  " + msg + "\n");
        } catch (Exception ignored) {
        }
    }

    public static synchronized void log(Context ctx, String msg, Throwable t) {
        Log.w(TAG, msg, t);
        if (ctx == null) {
            return;
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(logFile(ctx), true))) {
            w.println(TS.format(new Date()) + "  " + msg);
            t.printStackTrace(w);
        } catch (Exception ignored) {
        }
    }

    /** One-line environment header to start a recording attempt's log block. */
    public static void logHeader(Context ctx, String what) {
        log(ctx, "==== " + what + " | " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " | abis=" + Arrays.toString(Build.SUPPORTED_ABIS) + " ====");
    }
}
