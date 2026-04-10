
package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.movtery.zalithlauncher.InfoCenter;
import com.movtery.zalithlauncher.R;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.NotificationUtils;

public class GameService extends Service {

    private static final String EXTRA_KILL = "kill";

    private static boolean sActive;

    private final Messenger messenger = new Messenger(new IncomingHandler());

    public static boolean isActive() {
        return sActive;
    }

    public static void setActive(boolean active) {
        sActive = active;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Tools.buildNotificationChannel(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isKillRequest(intent)) {
            shutdown();
            return START_NOT_STICKY;
        }

        startGameNotification();
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private boolean isKillRequest(@Nullable Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_KILL, false);
    }

    private void startGameNotification() {
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NotificationUtils.NOTIFICATION_ID_GAME_SERVICE,
                    notification,
                    resolveForegroundServiceType()
            );
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, Tools.NOTIFICATION_CHANNEL_DEFAULT)
                .setContentTitle(InfoCenter.replaceName(this, R.string.lazy_service_default_title))
                .setContentText(getString(R.string.notification_game_runs))
                .setContentIntent(createContentIntent())
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.notification_terminate),
                        createKillIntent()
                )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setSilent(true)
                .build();
    }

    private PendingIntent createKillIntent() {
        Intent intent = new Intent(this, GameService.class);
        intent.putExtra(EXTRA_KILL, true);

        return PendingIntent.getService(
                this,
                NotificationUtils.PENDINGINTENT_CODE_KILL_GAME_SERVICE,
                intent,
                PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent createContentIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private int resolveForegroundServiceType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
    }

    private void shutdown() {
        sActive = false;
        stopForeground(true);
        stopSelf();
    }

    private static final class IncomingHandler extends Handler {
        IncomingHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
        }
    }
}