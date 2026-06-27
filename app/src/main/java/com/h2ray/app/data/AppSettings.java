package com.h2ray.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    private static final String PREFERENCES = "h2ray_settings";
    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public boolean bypassRu() {
        return preferences.getBoolean("bypass_ru", true);
    }

    public void setBypassRu(boolean value) {
        preferences.edit().putBoolean("bypass_ru", value).apply();
    }

    public boolean bypassPrivate() {
        return preferences.getBoolean("bypass_private", true);
    }

    public void setBypassPrivate(boolean value) {
        preferences.edit().putBoolean("bypass_private", value).apply();
    }

    public boolean ipv6() {
        return preferences.getBoolean("ipv6", false);
    }

    public void setIpv6(boolean value) {
        preferences.edit().putBoolean("ipv6", value).apply();
    }

    public String dns() {
        return preferences.getString("dns", "1.1.1.1");
    }

    public void setDns(String value) {
        preferences.edit().putString("dns", value).apply();
    }
}
