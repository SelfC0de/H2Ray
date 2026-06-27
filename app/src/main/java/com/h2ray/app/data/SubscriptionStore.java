package com.h2ray.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.h2ray.app.xray.ProfileImporter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SubscriptionStore {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final SharedPreferences preferences;

    public SubscriptionStore(Context context) {
        preferences = context.getSharedPreferences("h2ray_subscriptions", Context.MODE_PRIVATE);
    }

    public void add(String url, int hours) throws Exception {
        URI uri = URI.create(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme())
            && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Подписка должна использовать HTTP или HTTPS");
        }
        JSONArray items = items();
        for (int index = 0; index < items.length(); index++) {
            if (url.equals(items.getJSONObject(index).optString("url"))) {
                return;
            }
        }
        items.put(new JSONObject()
            .put("url", url.trim())
            .put("hours", Math.max(1, hours))
            .put("lastUpdate", 0));
        save(items);
    }

    public List<String> urls() {
        List<String> result = new ArrayList<>();
        JSONArray items = items();
        for (int index = 0; index < items.length(); index++) {
            result.add(items.optJSONObject(index).optString("url"));
        }
        return result;
    }

    public int updateDue(ProfileStore profiles, boolean force) throws Exception {
        JSONArray items = items();
        int imported = 0;
        long now = System.currentTimeMillis();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            long interval = item.optInt("hours", 24) * 3_600_000L;
            if (!force && now - item.optLong("lastUpdate") < interval) {
                continue;
            }
            String url = item.getString("url");
            String content = download(url);
            List<ProfileImporter.ProfileData> data;
            try {
                data = ProfileImporter.importContent(content, "Subscription");
            } catch (Exception firstError) {
                byte[] decoded = Base64.decode(content.trim(), Base64.DEFAULT);
                data = ProfileImporter.importContent(
                    new String(decoded, StandardCharsets.UTF_8),
                    "Subscription"
                );
            }
            String group = "Подписка: " + URI.create(url).getHost();
            profiles.deleteGroup(group);
            for (ProfileImporter.ProfileData profile : data) {
                profiles.saveActiveProfile(
                    profile.name,
                    profile.protocol,
                    profile.config,
                    profile.source
                );
                ProfileStore.Profile active = profiles.getActiveProfile();
                if (active != null) {
                    profiles.update(
                        active.id,
                        active.name,
                        active.config,
                        active.source,
                        group
                    );
                }
                imported++;
            }
            item.put("lastUpdate", now);
        }
        save(items);
        return imported;
    }

    private String download(String source) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent", "H2Ray/Android");
        connection.setInstanceFollowRedirects(true);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > MAX_BYTES) {
                        throw new IllegalStateException("Подписка превышает 2 МБ");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private JSONArray items() {
        try {
            return new JSONArray(preferences.getString("items", "[]"));
        } catch (Exception error) {
            return new JSONArray();
        }
    }

    private void save(JSONArray items) {
        preferences.edit().putString("items", items.toString()).apply();
    }
}
