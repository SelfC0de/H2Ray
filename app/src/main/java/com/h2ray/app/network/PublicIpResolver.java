package com.h2ray.app.network;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class PublicIpResolver {
    private static final String[] JSON_ENDPOINTS = {
        "https://api.ipify.org?format=json",
        "https://api64.ipify.org?format=json"
    };

    private PublicIpResolver() {
    }

    public static String resolve() {
        for (String endpoint : JSON_ENDPOINTS) {
            String body = request(endpoint);
            if (!body.isEmpty()) {
                try {
                    String ip = new JSONObject(body).optString("ip", "");
                    if (isIpAddress(ip)) {
                        return ip;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        String trace = request("https://www.cloudflare.com/cdn-cgi/trace");
        for (String line : trace.split("\\R")) {
            if (line.startsWith("ip=")) {
                String ip = line.substring(3).trim();
                if (isIpAddress(ip)) {
                    return ip;
                }
            }
        }
        return "";
    }

    private static String request(String endpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json,text/plain");
            connection.setRequestProperty("User-Agent", "H2Ray/0.1");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return "";
            }
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null && result.length() < 4096) {
                    result.append(line).append('\n');
                }
            }
            return result.toString().trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isIpAddress(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty() || !candidate.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress() != null;
        } catch (Exception ignored) {
            return false;
        }
    }
}
