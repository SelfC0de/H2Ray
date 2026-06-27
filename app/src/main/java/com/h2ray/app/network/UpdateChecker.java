package com.h2ray.app.network;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class UpdateChecker {
    private static final String LATEST_RELEASE_URL =
        "https://api.github.com/repos/SelfC0de/H2Ray/releases/latest";
    private static final int MAX_RESPONSE_CHARS = 1_000_000;

    private UpdateChecker() {
    }

    public static Result check(Context context) {
        HttpURLConnection connection = null;
        try {
            String currentVersion = currentVersion(context);
            connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "H2Ray/" + currentVersion);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return Result.failed();
            }

            JSONObject release = new JSONObject(readResponse(connection.getInputStream()));
            String latestVersion = normalize(release.optString("tag_name"));
            String apkUrl = findApkUrl(release.optJSONArray("assets"));
            if (latestVersion.isEmpty() || apkUrl.isEmpty()) {
                return Result.failed();
            }
            return new Result(
                compareVersions(latestVersion, currentVersion) > 0,
                latestVersion,
                apkUrl,
                false
            );
        } catch (Exception error) {
            return Result.failed();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static String currentVersion(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return info.versionName == null ? "0" : info.versionName;
    }

    private static String findApkUrl(JSONArray assets) {
        if (assets == null) {
            return "";
        }
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset != null && "H2Ray.apk".equals(asset.optString("name"))) {
                return asset.optString("browser_download_url");
            }
        }
        return "";
    }

    private static String readResponse(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (result.length() + read > MAX_RESPONSE_CHARS) {
                    throw new IllegalStateException("Release response is too large");
                }
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    static int compareVersions(String left, String right) {
        String[] leftParts = normalize(left).split("\\.");
        String[] rightParts = normalize(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            long leftValue = numberAt(leftParts, index);
            long rightValue = numberAt(rightParts, index);
            if (leftValue != rightValue) {
                return Long.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static long numberAt(String[] parts, int index) {
        if (index >= parts.length || !parts[index].matches("\\d+")) {
            return 0;
        }
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException error) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalize(String version) {
        String value = version == null ? "" : version.trim();
        return value.startsWith("v") || value.startsWith("V") ? value.substring(1) : value;
    }

    public static final class Result {
        public final boolean updateAvailable;
        public final String latestVersion;
        public final String apkUrl;
        public final boolean failed;

        private Result(
            boolean updateAvailable,
            String latestVersion,
            String apkUrl,
            boolean failed
        ) {
            this.updateAvailable = updateAvailable;
            this.latestVersion = latestVersion;
            this.apkUrl = apkUrl;
            this.failed = failed;
        }

        private static Result failed() {
            return new Result(false, "", "", true);
        }
    }
}
