package com.h2ray.app.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import com.h2ray.app.MainActivity;
import com.h2ray.app.R;
import com.h2ray.app.data.AppSettings;
import com.h2ray.app.data.ConnectionStatusStore;
import com.h2ray.app.data.LogStore;
import com.h2ray.app.data.ProfileStore;
import com.h2ray.app.network.PublicIpResolver;
import com.h2ray.app.xray.ServerEndpoint;
import com.h2ray.app.xray.XrayBridge;
import com.h2ray.app.xray.XrayConfigFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class H2RayVpnService extends VpnService {
    public static final String ACTION_START = "com.h2ray.app.action.START";
    public static final String ACTION_STOP = "com.h2ray.app.action.STOP";
    public static final String ACTION_RESTART = "com.h2ray.app.action.RESTART";
    private static final String TAG = "H2RayVpnService";
    private static final String CHANNEL_ID = "h2ray_vpn";
    private static final int NOTIFICATION_ID = 1001;

    private enum CoreState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }

    private static final AtomicReference<CoreState> CORE_STATE =
        new AtomicReference<>(CoreState.STOPPED);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService ipExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelStart = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private ParcelFileDescriptor vpnInterface;
    private ConnectionStatusStore connectionStatus;
    private LogStore logStore;
    private volatile boolean preserveFailure;
    private volatile boolean destroyed;
    private volatile Network underlyingNetwork;
    private volatile int startAttempt;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    public static Intent startIntent(Context context) {
        return new Intent(context, H2RayVpnService.class).setAction(ACTION_START);
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, H2RayVpnService.class).setAction(ACTION_STOP);
    }

    public static Intent restartIntent(Context context) {
        return new Intent(context, H2RayVpnService.class).setAction(ACTION_RESTART);
    }

    public static boolean isRunning() {
        return CORE_STATE.get() == CoreState.RUNNING && XrayBridge.isRunning();
    }

    public static boolean isBusy() {
        CoreState state = CORE_STATE.get();
        return state == CoreState.STARTING || state == CoreState.STOPPING;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        connectionStatus = new ConnectionStatusStore(this);
        logStore = new LogStore(this);
        createNotificationChannel();
        registerNetworkObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            new AppSettings(this).setDesiredVpnRunning(false);
            requestStop(false);
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            requestRestart();
            return START_STICKY;
        }
        new AppSettings(this).setDesiredVpnRunning(true);

        CoreState current = CORE_STATE.get();
        if (current == CoreState.STARTING || current == CoreState.RUNNING) {
            return START_STICKY;
        }
        if (!CORE_STATE.compareAndSet(current, CoreState.STARTING)) {
            return START_STICKY;
        }

        cancelStart.set(false);
        startAttempt = 0;
        preserveFailure = false;
        connectionStatus.setConnecting();
        logStore.add("INFO", "Запуск VPN");
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.connecting)));
        executor.execute(this::startTunnel);
        return START_STICKY;
    }

    private void requestRestart() {
        AppSettings settings = new AppSettings(this);
        settings.setDesiredVpnRunning(true);
        CoreState state = CORE_STATE.get();
        if (state != CoreState.RUNNING) {
            return;
        }
        if (!CORE_STATE.compareAndSet(CoreState.RUNNING, CoreState.STOPPING)) {
            return;
        }
        cancelStart.set(true);
        connectionStatus.setConnecting();
        updateNotification(getString(R.string.connecting));
        logStore.add("INFO", "Перезапуск VPN для применения правил приложений");
        executor.execute(() -> {
            synchronized (lifecycleLock) {
                cleanupResources();
                cancelStart.set(false);
                startAttempt = 0;
                preserveFailure = false;
                CORE_STATE.set(CoreState.STARTING);
                startTunnel();
            }
        });
    }

    private void startTunnel() {
        startAttempt++;
        try {
            synchronized (lifecycleLock) {
                cleanupResources();
                if (cancelStart.get()) {
                    finishStopped();
                    return;
                }

                ProfileStore store = new ProfileStore(this);
                if (!store.hasActiveProfile()) {
                    throw new IllegalStateException(getString(R.string.no_profile));
                }

                copyCoreAsset("geoip.dat");
                copyCoreAsset("geosite.dat");
                AppSettings settings = new AppSettings(this);
                int mtu = settings.mtu();
                verifyServerReachable(store, settings.connectionTimeoutSeconds());

                Builder builder = new Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(mtu)
                    .addAddress("10.10.0.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(settings.dns());
                configureApplicationRouting(builder, settings);
                builder.addAddress("fd00:10:10::2", 126)
                    .addRoute("::", 0);

                vpnInterface = builder.establish();
                if (vpnInterface == null) {
                    throw new IOException("Android не создал TUN-интерфейс");
                }

                XrayBridge.registerSocketProtection(this::protect);
                XrayBridge.setTunFd(vpnInterface.getFd());
                String runtimeConfig = XrayConfigFactory.createRuntimeConfig(
                    store.getConfig(), settings
                );
                XrayBridge.start(getCoreDirectory().getAbsolutePath(), runtimeConfig);

                if (cancelStart.get()) {
                    cleanupResources();
                    finishStopped();
                    return;
                }

                CORE_STATE.set(CoreState.RUNNING);
                startAttempt = 0;
                long rx = safeTrafficValue(TrafficStats.getUidRxBytes(Process.myUid()));
                long tx = safeTrafficValue(TrafficStats.getUidTxBytes(Process.myUid()));
                connectionStatus.setRunning(rx, tx);
                updateNotification(getString(R.string.connected));
                logStore.add("INFO", "Xray запущен: " + XrayBridge.version());
                Log.i(TAG, "Xray tunnel started, core=" + XrayBridge.version());
                ipExecutor.execute(this::resolvePublicIp);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to start tunnel", error);
            AppSettings settings = new AppSettings(this);
            if (!cancelStart.get() && settings.desiredVpnRunning()
                && settings.autoReconnect() && startAttempt < settings.retryCount()) {
                synchronized (lifecycleLock) {
                    cleanupResources();
                }
                logStore.add(
                    "WARN",
                    "Попытка " + startAttempt + " не удалась, повтор подключения"
                );
                selectReachableFallback(settings.connectionTimeoutSeconds());
                try {
                    Thread.sleep(Math.min(5000L, 1000L * startAttempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                connectionStatus.setConnecting();
                CORE_STATE.set(CoreState.STARTING);
                startTunnel();
                return;
            }
            preserveFailure = true;
            CORE_STATE.set(CoreState.ERROR);
            connectionStatus.setError(error);
            logStore.add("ERROR", error.toString());
            synchronized (lifecycleLock) {
                cleanupResources();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void requestStop(boolean revoked) {
        CoreState state = CORE_STATE.getAndSet(CoreState.STOPPING);
        if (state == CoreState.STOPPED || state == CoreState.STOPPING) {
            return;
        }
        cancelStart.set(true);
        preserveFailure = false;
        logStore.add("INFO", revoked ? "VPN отозван системой" : "Остановка VPN");
        executor.execute(() -> {
            synchronized (lifecycleLock) {
                cleanupResources();
                finishStopped();
            }
        });
    }

    private void cleanupResources() {
        try {
            if (XrayBridge.isRunning()) {
                XrayBridge.stop();
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to stop Xray cleanly", error);
            logStore.add("WARN", "Ошибка остановки Xray: " + error);
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException error) {
                Log.w(TAG, "Unable to close TUN", error);
            }
            vpnInterface = null;
        }
        XrayBridge.setTunFd(-1);
        XrayBridge.clearSocketProtection();
    }

    private void finishStopped() {
        CORE_STATE.set(CoreState.STOPPED);
        if (!preserveFailure) {
            connectionStatus.setStopped();
        }
        logStore.add("INFO", "VPN остановлен, Xray освобождён");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        new AppSettings(this).setDesiredVpnRunning(false);
        requestStop(true);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        cancelStart.set(true);
        synchronized (lifecycleLock) {
            cleanupResources();
            if (!preserveFailure) {
                CORE_STATE.set(CoreState.STOPPED);
                connectionStatus.setStopped();
            }
        }
        executor.shutdownNow();
        ipExecutor.shutdownNow();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    private long safeTrafficValue(long value) {
        return value == TrafficStats.UNSUPPORTED ? 0 : Math.max(0, value);
    }

    private void verifyServerReachable(ProfileStore store, int timeoutSeconds)
        throws IOException {
        ServerEndpoint endpoint = ServerEndpoint.fromConfig(store.getConfig());
        if (endpoint == null) {
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(endpoint.address, endpoint.port),
                timeoutSeconds * 1000
            );
        }
    }

    private void configureApplicationRouting(Builder builder, AppSettings settings) {
        boolean onlySelected = "only".equals(settings.appRoutingMode());
        if (onlySelected) {
            try {
                builder.addAllowedApplication(getPackageName());
            } catch (android.content.pm.PackageManager.NameNotFoundException error) {
                logStore.add("WARN", "Не удалось добавить H2Ray в VPN: " + error.getMessage());
            }
        }
        for (String packageName : settings.bypassApps()) {
            try {
                if (onlySelected) {
                    builder.addAllowedApplication(packageName);
                } else {
                    builder.addDisallowedApplication(packageName);
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException error) {
                logStore.add("WARN", "Приложение правила не найдено: " + packageName);
            }
        }
    }

    private void selectReachableFallback(int timeoutSeconds) {
        ProfileStore store = new ProfileStore(this);
        ProfileStore.Profile active = store.getActiveProfile();
        List<ProfileStore.Profile> profiles = new ArrayList<>(store.getProfiles());
        profiles.sort(Comparator.comparingLong(profile ->
            profile.ping < 0 || profile.ping == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : profile.ping));
        for (ProfileStore.Profile profile : profiles) {
            if (active != null && active.id.equals(profile.id)) {
                continue;
            }
            ServerEndpoint endpoint = ServerEndpoint.fromConfig(profile.config);
            if (endpoint == null) {
                continue;
            }
            try (Socket socket = new Socket()) {
                socket.connect(
                    new InetSocketAddress(endpoint.address, endpoint.port),
                    Math.min(timeoutSeconds * 1000, 4000)
                );
                store.select(profile.id);
                logStore.add("INFO", "Выбран резервный профиль: " + profile.name);
                return;
            } catch (IOException ignored) {
            }
        }
    }

    private void registerNetworkObserver() {
        connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Network previous = underlyingNetwork;
                underlyingNetwork = network;
                if (previous == null || previous.equals(network)
                    || CORE_STATE.get() != CoreState.RUNNING
                    || !new AppSettings(H2RayVpnService.this).autoReconnect()) {
                    return;
                }
                logStore.add("INFO", "Сеть изменилась, переподключение VPN");
                if (!CORE_STATE.compareAndSet(CoreState.RUNNING, CoreState.STARTING)) {
                    return;
                }
                connectionStatus.setConnecting();
                updateNotification(getString(R.string.connecting));
                startAttempt = 0;
                executor.execute(H2RayVpnService.this::startTunnel);
            }
        };
        connectivityManager.registerNetworkCallback(
            new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            networkCallback
        );
    }

    private void resolvePublicIp() {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Thread.sleep(attempt == 0 ? 1500 : 2500);
                if (CORE_STATE.get() != CoreState.RUNNING) {
                    return;
                }
                if (!connectionStatus.getPublicIp().trim().isEmpty()) {
                    return;
                }
                String ip = PublicIpResolver.resolve();
                if (!ip.isEmpty() && CORE_STATE.get() == CoreState.RUNNING) {
                    connectionStatus.setPublicIp(ip);
                    logStore.add("INFO", "Внешний IP определён");
                    return;
                }
            }
            logStore.add("WARN", "Сервисы внешнего IP недоступны после повторных попыток");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
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
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent disconnectIntent = PendingIntent.getService(
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
                disconnectIntent
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
