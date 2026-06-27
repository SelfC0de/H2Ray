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

    public void setRunning() {
        save(RUNNING, "");
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

    private void save(String state, String error) {
        preferences.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_ERROR, error)
            .apply();
    }
}
