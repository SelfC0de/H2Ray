package com.h2ray.app.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.h2ray.app.MainActivity;
import com.h2ray.app.R;
import com.h2ray.app.data.ProfileStore;
import com.h2ray.app.xray.XrayBridge;
import com.h2ray.app.xray.XrayConfigFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class H2RayVpnService extends VpnService {
    public static final String ACTION_START = "com.h2ray.app.action.START";
    public static final String ACTION_STOP = "com.h2ray.app.action.STOP";
    private static final String TAG = "H2RayVpnService";
    private static final String CHANNEL_ID = "h2ray_vpn";
    private static final int NOTIFICATION_ID = 1001;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ParcelFileDescriptor vpnInterface;

    public static Intent startIntent(Context context) {
        return new Intent(context, H2RayVpnService.class).setAction(ACTION_START);
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, H2RayVpnService.class).setAction(ACTION_STOP);
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            executor.execute(this::stopTunnel);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.connecting)));
        executor.execute(this::startTunnel);
        return START_STICKY;
    }

    private synchronized void startTunnel() {
        if (RUNNING.get()) {
            updateNotification(getString(R.string.connected));
            return;
        }

        try {
            ProfileStore store = new ProfileStore(this);
            if (!store.hasActiveProfile()) {
                throw new IllegalStateException(getString(R.string.no_profile));
            }

            copyCoreAsset("geoip.dat");
            copyCoreAsset("geosite.dat");

            Builder builder = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress("10.10.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8");

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                throw new IOException("Android не создал TUN-интерфейс");
            }

            XrayBridge.registerSocketProtection(this::protect);
            XrayBridge.setTunFd(vpnInterface.getFd());
            String runtimeConfig = XrayConfigFactory.createRuntimeConfig(store.getConfig());
            XrayBridge.start(getCoreDirectory().getAbsolutePath(), runtimeConfig);

            RUNNING.set(true);
            updateNotification(getString(R.string.connected));
            Log.i(TAG, "Xray tunnel started, core=" + XrayBridge.version());
        } catch (Exception error) {
            Log.e(TAG, "Unable to start tunnel", error);
            updateNotification(getString(R.string.connection_failed));
            stopTunnel();
        }
    }

    private synchronized void stopTunnel() {
        RUNNING.set(false);
        try {
            XrayBridge.stop();
        } catch (Exception error) {
            Log.w(TAG, "Unable to stop Xray cleanly", error);
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException error) {
                Log.w(TAG, "Unable to close TUN", error);
            }
            vpnInterface = null;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        executor.execute(this::stopTunnel);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    private void copyCoreAsset(String name) throws IOException {
        File destination = new File(getCoreDirectory(), name);
        if (destination.exists() && destination.length() > 0) {
            return;
        }

        try (InputStream input = getAssets().open(name);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private File getCoreDirectory() throws IOException {
        File directory = new File(getFilesDir(), "xray");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Не удалось создать каталог Xray");
        }
        return directory;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.vpn_notification_description));
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String state) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(state)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(
                null,
                getString(R.string.disconnect),
                stopIntent
            ).build())
            .build();
    }

    private void updateNotification(String state) {
        getSystemService(NotificationManager.class).notify(
            NOTIFICATION_ID,
            createNotification(state)
        );
    }
}
