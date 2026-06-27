package com.h2ray.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ProfileStore {
    private static final String PREFERENCES = "h2ray_profiles";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE_ID = "active_id";
    private static final String LEGACY_CONFIG = "active_config";

    private final SharedPreferences preferences;

    public ProfileStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        migrateLegacyProfile();
    }

    public void saveActiveProfile(String name, String protocol, String config, String source) {
        List<Profile> profiles = getProfiles();
        Profile profile = new Profile(
            UUID.randomUUID().toString(), name, protocol, config, source, -1
        );
        profiles.add(profile);
        saveProfiles(profiles, profile.id);
    }

    public List<Profile> getProfiles() {
        List<Profile> profiles = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_PROFILES, "[]"));
            for (int index = 0; index < array.length(); index++) {
                profiles.add(Profile.fromJson(array.getJSONObject(index)));
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_PROFILES).apply();
        }
        return profiles;
    }

    public boolean hasActiveProfile() {
        return getActiveProfile() != null;
    }

    public Profile getActiveProfile() {
        String activeId = preferences.getString(KEY_ACTIVE_ID, "");
        List<Profile> profiles = getProfiles();
        for (Profile profile : profiles) {
            if (profile.id.equals(activeId)) {
                return profile;
            }
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public void select(String id) {
        preferences.edit().putString(KEY_ACTIVE_ID, id).apply();
    }

    public void updatePing(String id, long ping) {
        List<Profile> profiles = getProfiles();
        for (Profile profile : profiles) {
            if (profile.id.equals(id)) {
                profile.ping = ping;
                break;
            }
        }
        saveProfiles(profiles, preferences.getString(KEY_ACTIVE_ID, ""));
    }

    public void delete(String id) {
        List<Profile> profiles = getProfiles();
        profiles.removeIf(profile -> profile.id.equals(id));
        String activeId = preferences.getString(KEY_ACTIVE_ID, "");
        if (id.equals(activeId)) {
            activeId = profiles.isEmpty() ? "" : profiles.get(0).id;
        }
        saveProfiles(profiles, activeId);
    }

    public String getName() {
        Profile profile = getActiveProfile();
        return profile == null ? "Профиль" : profile.name;
    }

    public String getProtocol() {
        Profile profile = getActiveProfile();
        return profile == null ? "Xray" : profile.protocol;
    }

    public String getConfig() {
        Profile profile = getActiveProfile();
        return profile == null ? "" : profile.config;
    }

    public String getSource() {
        Profile profile = getActiveProfile();
        return profile == null ? "" : profile.source;
    }

    public void clear() {
        Profile profile = getActiveProfile();
        if (profile != null) {
            delete(profile.id);
        }
    }

    private void saveProfiles(List<Profile> profiles, String activeId) {
        JSONArray array = new JSONArray();
        for (Profile profile : profiles) {
            array.put(profile.toJson());
        }
        preferences.edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_ACTIVE_ID, activeId)
            .apply();
    }

    private void migrateLegacyProfile() {
        String legacyConfig = preferences.getString(LEGACY_CONFIG, "");
        if (legacyConfig == null || legacyConfig.trim().isEmpty()
            || preferences.contains(KEY_PROFILES)) {
            return;
        }
        Profile profile = new Profile(
            UUID.randomUUID().toString(),
            preferences.getString("active_name", "Imported profile"),
            preferences.getString("active_protocol", "Xray"),
            legacyConfig,
            preferences.getString("active_source", ""),
            -1
        );
        List<Profile> migrated = new ArrayList<>();
        migrated.add(profile);
        saveProfiles(migrated, profile.id);
        preferences.edit()
            .remove("active_name")
            .remove("active_protocol")
            .remove("active_config")
            .remove("active_source")
            .apply();
    }

    public static final class Profile {
        public final String id;
        public final String name;
        public final String protocol;
        public final String config;
        public final String source;
        public long ping;

        Profile(String id, String name, String protocol, String config, String source, long ping) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.config = config;
            this.source = source;
            this.ping = ping;
        }

        JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("protocol", protocol)
                    .put("config", config)
                    .put("source", source)
                    .put("ping", ping);
            } catch (JSONException error) {
                throw new IllegalStateException(error);
            }
        }

        static Profile fromJson(JSONObject json) {
            return new Profile(
                json.optString("id", UUID.randomUUID().toString()),
                json.optString("name", "Imported profile"),
                json.optString("protocol", "Xray"),
                json.optString("config", ""),
                json.optString("source", ""),
                json.optLong("ping", -1)
            );
        }
    }
}
