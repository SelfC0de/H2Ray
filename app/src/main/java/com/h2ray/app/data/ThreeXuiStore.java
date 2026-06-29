package com.h2ray.app.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class ThreeXuiStore {
    private final SharedPreferences preferences;
    private final SecureStorage secureStorage = new SecureStorage();

    public ThreeXuiStore(Context context) {
        preferences = context.getSharedPreferences("h2ray_3xui", Context.MODE_PRIVATE);
        if (!preferences.contains("panel_server_host")
            && !preferences.getString("panel_url", "").isEmpty()) {
            preferences.edit()
                .putString("panel_server_host", preferences.getString("host", ""))
                .apply();
        }
    }

    public void saveServer(String host, int port, String username) {
        preferences.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("ssh_username", username)
            .apply();
    }

    public String host() {
        return preferences.getString("host", "");
    }

    public int port() {
        return preferences.getInt("port", 22);
    }

    public String sshUsername() {
        return preferences.getString("ssh_username", "root");
    }

    public void savePanel(String url, String username, String password) {
        preferences.edit()
            .putString("panel_url", url)
            .putString("panel_server_host", host())
            .putString("panel_username", secureStorage.encrypt(username))
            .putString("panel_password", secureStorage.encrypt(password))
            .apply();
    }

    public String panelUrl() {
        return preferences.getString("panel_url", "");
    }

    public String panelUsername() {
        return secureStorage.decrypt(preferences.getString("panel_username", ""));
    }

    public String panelPassword() {
        return secureStorage.decrypt(preferences.getString("panel_password", ""));
    }

    public String panelServerHost() {
        return preferences.getString("panel_server_host", "");
    }
}
