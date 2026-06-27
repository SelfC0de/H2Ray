package com.h2ray.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LogStore {
    private static final String PREFERENCES = "h2ray_logs";
    private static final String KEY_LOG = "log";
    private static final int MAX_LENGTH = 30000;
    private final SharedPreferences preferences;

    public LogStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public synchronized void add(String level, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        String current = preferences.getString(KEY_LOG, "");
        String updated = current + time + " [" + level + "] " + message + "\n";
        if (updated.length() > MAX_LENGTH) {
            updated = updated.substring(updated.length() - MAX_LENGTH);
        }
        preferences.edit().putString(KEY_LOG, updated).apply();
    }

    public String get() {
        return preferences.getString(KEY_LOG, "");
    }

    public void clear() {
        preferences.edit().remove(KEY_LOG).apply();
    }
}
