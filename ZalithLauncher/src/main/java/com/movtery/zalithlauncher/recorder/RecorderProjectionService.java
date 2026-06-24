package com.movtery.zalithlauncher.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Minimal foreground service that owns the {@link MediaProjection} used for
 * game-audio capture. Android 14+ requires an active {@code mediaProjection}
 * foreground service before {@code getMediaProjection()} is used, so the
 * projection is created <em>here</em> (after going foreground) and handed to the
 * recorder. If anything fails, recording still proceeds without game audio.
 */
public final class RecorderProjectionService extends Service {

    private static final String TAG = "RecorderProjection";
    private static final String CHANNEL_ID = "recordzy_recorder";
    private static final int NOTIF_ID = 0x5EC0;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private MediaProjection mProjection;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();

        MediaProjection projection = null;
        try {
            if (intent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                if (data != null) {
                    MediaProjectionManager mpm =
                            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    projection = mpm.getMediaProjection(code, data);
                    if (projection != null) {
                        projection.registerCallback(new MediaProjection.Callback() {
                            @Override
                            public void onStop() {
                                stopSelf();
                            }
                        }, new Handler(Looper.getMainLooper()));
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not obtain MediaProjection; recording without game audio", t);
            projection = null;
        }

        mProjection = projection;
        // Start the actual recording now (projection may be null -> no game audio).
        try {
            GameRecorder.getInstance().startRecording(getApplicationContext(), projection);
        } catch (Throwable t) {
            Log.e(TAG, "startRecording failed", t);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        "Screen recording", NotificationManager.IMPORTANCE_LOW));
            }
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RecordZy")
                .setContentText("Recording gameplay")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0;
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type);
    }

    public static void start(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, RecorderProjectionService.class);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, RecorderProjectionService.class));
    }

    @Override
    public void onDestroy() {
        if (mProjection != null) {
            try {
                mProjection.stop();
            } catch (Throwable ignored) {
            }
            mProjection = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
