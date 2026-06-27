package com.h2ray.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class ProfileStore {
    private static final String PREFERENCES = "h2ray_profiles";
    private static final String KEY_NAME = "active_name";
    private static final String KEY_PROTOCOL = "active_protocol";
    private static final String KEY_CONFIG = "active_config";
    private static final String KEY_SOURCE = "active_source";

    private final SharedPreferences preferences;

    public ProfileStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public void saveActiveProfile(String name, String protocol, String config, String source) {
        preferences.edit()
            .putString(KEY_NAME, name)
            .putString(KEY_PROTOCOL, protocol)
            .putString(KEY_CONFIG, config)
            .putString(KEY_SOURCE, source)
            .apply();
    }

    public boolean hasActiveProfile() {
        return !getConfig().trim().isEmpty();
    }

    public String getName() {
        return preferences.getString(KEY_NAME, "Imported profile");
    }

    public String getProtocol() {
        return preferences.getString(KEY_PROTOCOL, "Xray");
    }

    public String getConfig() {
        return preferences.getString(KEY_CONFIG, "");
    }

    public String getSource() {
        return preferences.getString(KEY_SOURCE, "");
    }

    public void clear() {
        preferences.edit().clear().apply();
    }
}
