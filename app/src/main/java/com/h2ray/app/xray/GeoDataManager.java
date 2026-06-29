package com.h2ray.app.xray;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class GeoDataManager {
    public static final String STANDARD = "standard";
    public static final String DEFAULT_RU = "default_ru";
    public static final String WHITELIST_RU = "whitelist_ru";

    private static final String GEOIP_API =
        "https://api.github.com/repos/hydraponique/roscomvpn-geoip/releases/latest";
    private static final String GEOSITE_API =
        "https://api.github.com/repos/hydraponique/roscomvpn-geosite/releases/latest";
    private static final String PREFERENCES = "h2ray_geo";
    private static final long UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_DATA_BYTES = 32 * 1024 * 1024;

    private GeoDataManager() {
    }

    public static File standardDirectory(Context context) {
        return new File(context.getFilesDir(), "xray");
    }

    public static File customDirectory(Context context) {
        return new File(context.getFilesDir(), "xray-roscom");
    }

    public static File directory(Context context, String preset) {
        return STANDARD.equals(preset)
            ? standardDirectory(context)
            : customDirectory(context);
    }

    public static boolean isCustomReady(Context context) {
        File directory = customDirectory(context);
        return validFile(new File(directory, "geoip.dat"))
            && validFile(new File(directory, "geosite.dat"));
    }

    public static boolean updateDue(Context context) {
        long checked = preferences(context).getLong("checked_at", 0);
        return System.currentTimeMillis() - checked >= UPDATE_INTERVAL_MS;
    }

    public static String status(Context context) {
        SharedPreferences values = preferences(context);
        if (!isCustomReady(context)) {
            return "Geo-базы RU не загружены";
        }
        String geoIpTag = values.getString("geoip_tag", "?");
        String geoSiteTag = values.getString("geosite_tag", "?");
        String date = values.getString("installed_at", "");
        return "Источник: hydraponique · GeoIP " + geoIpTag
            + " · GeoSite " + geoSiteTag
            + (date.isEmpty() ? "" : "\nУстановлено: " + date);
    }

    public static Result update(Context context) throws Exception {
        ReleaseAsset geoIp = latestAsset(GEOIP_API, "geoip.dat");
        ReleaseAsset geoSite = latestAsset(GEOSITE_API, "geosite.dat");
        File stage = new File(context.getFilesDir(), "xray-roscom-stage");
        File destination = customDirectory(context);
        File backup = new File(context.getFilesDir(), "xray-roscom-backup");
        deleteTree(stage);
        if (!stage.mkdirs()) {
            throw new IllegalStateException("Не удалось создать временный каталог geo-баз");
        }
        try {
            downloadVerified(geoIp, new File(stage, "geoip.dat"));
            downloadVerified(geoSite, new File(stage, "geosite.dat"));
            File validation = new File(stage, "validate.json");
            write(validation, validationConfig());
            XrayBridge.test(stage.getAbsolutePath(), validation.getAbsolutePath());
            if (backup.exists()) {
                deleteTree(backup);
            }
            if (destination.exists() && !destination.renameTo(backup)) {
                throw new IllegalStateException("Не удалось создать резервную копию geo-баз");
            }
            if (!stage.renameTo(destination)) {
                if (backup.exists()) {
                    backup.renameTo(destination);
                }
                throw new IllegalStateException("Не удалось применить новые geo-базы");
            }
            preferences(context).edit()
                .putString("geoip_tag", geoIp.tag)
                .putString("geosite_tag", geoSite.tag)
                .putString("geoip_sha256", geoIp.sha256)
                .putString("geosite_sha256", geoSite.sha256)
                .putString("installed_at", java.time.LocalDate.now().toString())
                .putLong("checked_at", System.currentTimeMillis())
                .apply();
            return new Result(geoIp.tag, geoSite.tag);
        } catch (Exception error) {
            deleteTree(stage);
            throw error;
        }
    }

    public static void rollback(Context context) {
        File destination = customDirectory(context);
        File backup = new File(context.getFilesDir(), "xray-roscom-backup");
        if (!backup.exists()) {
            throw new IllegalStateException("Резервная копия geo-баз отсутствует");
        }
        File failed = new File(context.getFilesDir(), "xray-roscom-failed");
        deleteTree(failed);
        if (destination.exists() && !destination.renameTo(failed)) {
            throw new IllegalStateException("Не удалось отложить текущие geo-базы");
        }
        if (!backup.renameTo(destination)) {
            if (failed.exists()) {
                failed.renameTo(destination);
            }
            throw new IllegalStateException("Не удалось восстановить geo-базы");
        }
        deleteTree(failed);
    }

    private static ReleaseAsset latestAsset(String api, String name) throws Exception {
        JSONObject release = new JSONObject(new String(
            request(api, MAX_METADATA_BYTES),
            StandardCharsets.UTF_8
        ));
        JSONArray assets = release.getJSONArray("assets");
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            if (!name.equals(asset.optString("name"))) {
                continue;
            }
            String digest = asset.optString("digest", "");
            if (!digest.startsWith("sha256:")) {
                throw new IllegalStateException("GitHub Release не содержит SHA-256 для " + name);
            }
            return new ReleaseAsset(
                release.optString("tag_name", "unknown"),
                asset.getString("browser_download_url"),
                digest.substring("sha256:".length()).toLowerCase(Locale.ROOT)
            );
        }
        throw new IllegalStateException("Файл " + name + " отсутствует в GitHub Release");
    }

    private static void downloadVerified(ReleaseAsset asset, File destination)
        throws Exception {
        byte[] bytes = request(asset.url, MAX_DATA_BYTES);
        String actual = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equals(asset.sha256)) {
            throw new SecurityException("SHA-256 загруженной geo-базы не совпадает");
        }
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static byte[] request(String address, int maxBytes) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "H2Ray-Android");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " при загрузке geo-баз");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IllegalStateException("Файл geo-базы превышает лимит");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String validationConfig() {
        return "{\"log\":{\"loglevel\":\"none\"},\"outbounds\":["
            + "{\"tag\":\"proxy\",\"protocol\":\"freedom\"},"
            + "{\"tag\":\"direct\",\"protocol\":\"freedom\"},"
            + "{\"tag\":\"block\",\"protocol\":\"blackhole\"}],"
            + "\"routing\":{\"rules\":["
            + "{\"type\":\"field\",\"domain\":[\"geosite:private\","
            + "\"geosite:category-ru\",\"geosite:whitelist\","
            + "\"geosite:category-ads\",\"geosite:youtube\"],\"outboundTag\":\"direct\"},"
            + "{\"type\":\"field\",\"ip\":[\"geoip:private\",\"geoip:direct\","
            + "\"geoip:whitelist\"],\"outboundTag\":\"direct\"}]}}";
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean validFile(File file) {
        return file.isFile() && file.length() > 1024;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        file.delete();
    }

    public static final class Result {
        public final String geoIpTag;
        public final String geoSiteTag;

        Result(String geoIpTag, String geoSiteTag) {
            this.geoIpTag = geoIpTag;
            this.geoSiteTag = geoSiteTag;
        }
    }

    private static final class ReleaseAsset {
        final String tag;
        final String url;
        final String sha256;

        ReleaseAsset(String tag, String url, String sha256) {
            this.tag = tag;
            this.url = url;
            this.sha256 = sha256;
        }
    }
}
