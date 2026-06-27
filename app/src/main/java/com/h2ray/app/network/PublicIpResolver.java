package com.h2ray.app.network;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PublicIpResolver {
    private static final long RESOLVE_TIMEOUT_MILLIS = 4500;
    private static final Endpoint[] ENDPOINTS = {
        new Endpoint("https://api.ipify.org?format=json", ResponseType.JSON),
        new Endpoint("https://api64.ipify.org?format=json", ResponseType.JSON),
        new Endpoint("https://checkip.amazonaws.com", ResponseType.TEXT),
        new Endpoint("https://icanhazip.com", ResponseType.TEXT),
        new Endpoint("https://ifconfig.me/ip", ResponseType.TEXT),
        new Endpoint("https://www.cloudflare.com/cdn-cgi/trace", ResponseType.TRACE)
    };

    private PublicIpResolver() {
    }

    public static String resolve() {
        ExecutorService pool = Executors.newFixedThreadPool(ENDPOINTS.length);
        CompletionService<String> completion = new ExecutorCompletionService<>(pool);
        List<Future<String>> requests = new ArrayList<>();
        try {
            for (Endpoint endpoint : ENDPOINTS) {
                requests.add(completion.submit(() -> resolve(endpoint)));
            }
            long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(RESOLVE_TIMEOUT_MILLIS);
            for (int completed = 0; completed < ENDPOINTS.length; completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                Future<String> request = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (request == null) {
                    break;
                }
                String ip = request.get();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        } finally {
            for (Future<String> request : requests) {
                request.cancel(true);
            }
            pool.shutdownNow();
        }
        return "";
    }

    private static String resolve(Endpoint endpoint) {
        String body = request(endpoint.url);
        if (body.isEmpty()) {
            return "";
        }
        try {
            String candidate;
            if (endpoint.type == ResponseType.JSON) {
                candidate = new JSONObject(body).optString("ip", "");
            } else if (endpoint.type == ResponseType.TRACE) {
                candidate = traceIp(body);
            } else {
                candidate = body.trim();
            }
            return isIpAddress(candidate) ? candidate.trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String traceIp(String body) {
        for (String line : body.split("\\R")) {
            if (line.startsWith("ip=")) {
                return line.substring(3).trim();
            }
        }
        return "";
    }

    private static String request(String endpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json,text/plain");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("User-Agent", "H2Ray/1.0");
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

    private enum ResponseType {
        JSON,
        TEXT,
        TRACE
    }

    private static final class Endpoint {
        private final String url;
        private final ResponseType type;

        private Endpoint(String url, ResponseType type) {
            this.url = url;
            this.type = type;
        }
    }
}
