package com.h2ray.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class AppSettings {
    private static final String PREFERENCES = "h2ray_settings";
    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        migrateSecureDefaults();
    }

    public boolean bypassRu() {
        return preferences.getBoolean("bypass_ru", false);
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

    public String xrayDns() {
        return preferences.getString("xray_dns", dns());
    }

    public void setDns(String androidDns, String xrayDns) {
        preferences.edit()
            .putString("dns", androidDns)
            .putString("xray_dns", xrayDns)
            .apply();
    }

    public boolean parallelDns() {
        return preferences.getBoolean("parallel_dns", true);
    }

    public void setParallelDns(boolean value) {
        preferences.edit().putBoolean("parallel_dns", value).apply();
    }

    public boolean fakeDns() {
        return preferences.getBoolean("fake_dns", false);
    }

    public void setFakeDns(boolean value) {
        preferences.edit().putBoolean("fake_dns", value).apply();
    }

    public boolean happyEyeballs() {
        return preferences.getBoolean("happy_eyeballs", true);
    }

    public void setHappyEyeballs(boolean value) {
        preferences.edit().putBoolean("happy_eyeballs", value).apply();
    }

    public String customDomains() {
        return preferences.getString("custom_domains", "");
    }

    public void setCustomDomains(String value) {
        preferences.edit().putString("custom_domains", value).apply();
    }

    public Set<String> bypassApps() {
        return new HashSet<>(
            preferences.getStringSet("bypass_apps", Collections.emptySet())
        );
    }

    public void setBypassApps(Set<String> values) {
        preferences.edit().putStringSet("bypass_apps", new HashSet<>(values)).apply();
    }

    public String appRoutingMode() {
        return preferences.getString("app_routing_mode", "bypass");
    }

    public void setAppRoutingMode(String value) {
        preferences.edit().putString("app_routing_mode", value).apply();
    }

    public int mtu() {
        return preferences.getInt("mtu", 1500);
    }

    public void setMtu(int value) {
        preferences.edit().putInt("mtu", value).apply();
    }

    public String routingMode() {
        return preferences.getString("routing_mode", "global");
    }

    public void setRoutingMode(String value) {
        preferences.edit().putString("routing_mode", value).apply();
    }

    public String routingPreset() {
        return preferences.getString("routing_preset", "standard");
    }

    public void setRoutingPreset(String value) {
        preferences.edit().putString("routing_preset", value).apply();
    }

    public boolean autoUpdateGeoData() {
        return preferences.getBoolean("auto_update_geo", true);
    }

    public void setAutoUpdateGeoData(boolean value) {
        preferences.edit().putBoolean("auto_update_geo", value).apply();
    }

    public boolean autoCheckUpdates() {
        return preferences.getBoolean("auto_check_updates", true);
    }

    public void setAutoCheckUpdates(boolean value) {
        preferences.edit().putBoolean("auto_check_updates", value).apply();
    }

    public boolean blockAds() {
        return preferences.getBoolean("block_ads", false);
    }

    public void setBlockAds(boolean value) {
        preferences.edit().putBoolean("block_ads", value).apply();
    }

    public boolean blockQuic() {
        return preferences.getBoolean("block_quic", false);
    }

    public void setBlockQuic(boolean value) {
        preferences.edit().putBoolean("block_quic", value).apply();
    }

    public boolean sniffing() {
        return preferences.getBoolean("sniffing", true);
    }

    public void setSniffing(boolean value) {
        preferences.edit().putBoolean("sniffing", value).apply();
    }

    public boolean autoReconnect() {
        return preferences.getBoolean("auto_reconnect", true);
    }

    public void setAutoReconnect(boolean value) {
        preferences.edit().putBoolean("auto_reconnect", value).apply();
    }

    public boolean restoreAfterBoot() {
        return preferences.getBoolean("restore_after_boot", true);
    }

    public void setRestoreAfterBoot(boolean value) {
        preferences.edit().putBoolean("restore_after_boot", value).apply();
    }

    public int retryCount() {
        return preferences.getInt("retry_count", 3);
    }

    public void setRetryCount(int value) {
        preferences.edit().putInt("retry_count", Math.max(1, Math.min(5, value))).apply();
    }

    public int connectionTimeoutSeconds() {
        return preferences.getInt("connection_timeout", 8);
    }

    public void setConnectionTimeoutSeconds(int value) {
        preferences.edit().putInt(
            "connection_timeout",
            Math.max(3, Math.min(30, value))
        ).apply();
    }

    public boolean desiredVpnRunning() {
        return preferences.getBoolean("desired_vpn_running", false);
    }

    public void setDesiredVpnRunning(boolean value) {
        preferences.edit().putBoolean("desired_vpn_running", value).commit();
    }

    public boolean appLock() {
        return preferences.getBoolean("app_lock", false);
    }

    public void setAppLock(boolean value) {
        preferences.edit().putBoolean("app_lock", value).apply();
    }

    private void migrateSecureDefaults() {
        if (preferences.getBoolean("secure_defaults_v2", false)) {
            return;
        }
        preferences.edit()
            .putBoolean("bypass_ru", false)
            .putString("routing_mode", "global")
            .putBoolean("secure_defaults_v2", true)
            .apply();
    }
}
