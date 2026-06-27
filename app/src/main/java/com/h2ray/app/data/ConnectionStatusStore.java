package com.h2ray.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class ConnectionStatusStore {
    public static final String STOPPED = "stopped";
    public static final String CONNECTING = "connecting";
    public static final String RUNNING = "running";
    public static final String ERROR = "error";

    private static final String PREFERENCES = "h2ray_connection";
    private static final String KEY_STATE = "state";
    private static final String KEY_ERROR = "error";
    private static final String KEY_RX_BASE = "rx_base";
    private static final String KEY_TX_BASE = "tx_base";
    private static final String KEY_PUBLIC_IP = "public_ip";
    private static final String KEY_DIRECT_IP = "direct_ip";

    private final SharedPreferences preferences;

    public ConnectionStatusStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public String getState() {
        return preferences.getString(KEY_STATE, STOPPED);
    }

    public String getError() {
        return preferences.getString(KEY_ERROR, "");
    }

    public void setConnecting() {
        save(CONNECTING, "");
    }

    public void setRunning(long rxBase, long txBase) {
        preferences.edit()
            .putString(KEY_STATE, RUNNING)
            .putString(KEY_ERROR, "")
            .putLong(KEY_RX_BASE, rxBase)
            .putLong(KEY_TX_BASE, txBase)
            .putString(KEY_PUBLIC_IP, "")
            .apply();
    }

    public void setStopped() {
        save(STOPPED, "");
    }

    public void setError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        save(ERROR, message);
    }

    public long getRxBase() {
        return preferences.getLong(KEY_RX_BASE, 0);
    }

    public long getTxBase() {
        return preferences.getLong(KEY_TX_BASE, 0);
    }

    public String getPublicIp() {
        return preferences.getString(KEY_PUBLIC_IP, "");
    }

    public void setPublicIp(String value) {
        preferences.edit().putString(KEY_PUBLIC_IP, value).apply();
    }

    public String getDirectIp() {
        return preferences.getString(KEY_DIRECT_IP, "");
    }

    public void setDirectIp(String value) {
        preferences.edit().putString(KEY_DIRECT_IP, value).apply();
    }

    private void save(String state, String error) {
        preferences.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_ERROR, error)
            .putString(KEY_PUBLIC_IP, "")
            .apply();
    }
}
