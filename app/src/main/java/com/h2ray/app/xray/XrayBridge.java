package com.h2ray.app.xray;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

import libXray.DialerController;
import libXray.LibXray;

public final class XrayBridge {
    public interface SocketProtector {
        boolean protect(int fileDescriptor);
    }

    private XrayBridge() {
    }

    public static String parseShareText(String source) throws Exception {
        String encoded = Base64.encodeToString(
            source.getBytes(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        );
        return responseDataAsObject(LibXray.convertShareLinksToXrayJson(encoded)).toString();
    }

    public static void registerSocketProtection(SocketProtector protector) {
        LibXray.registerDialerController(new DialerController() {
            @Override
            public boolean protectFd(long fileDescriptor) {
                return protector.protect((int) fileDescriptor);
            }
        });
    }

    public static void clearSocketProtection() {
        registerSocketProtection(fileDescriptor -> false);
    }

    public static void setTunFd(int fileDescriptor) {
        LibXray.setTunFd(fileDescriptor);
    }

    public static void start(String dataDirectory, String configJson) throws Exception {
        JSONObject request = new JSONObject()
            .put("datDir", dataDirectory)
            .put("configJSON", configJson);
        requireSuccess(LibXray.runXrayFromJSON(encodeJson(request)));
        if (!LibXray.getXrayState()) {
            throw new IllegalStateException("Xray-core не перешёл в запущенное состояние");
        }
    }

    public static void test(String dataDirectory, String configPath) throws Exception {
        JSONObject request = new JSONObject()
            .put("datDir", dataDirectory)
            .put("configPath", configPath);
        requireSuccess(LibXray.testXray(encodeJson(request)));
    }

    public static void stop() throws Exception {
        requireSuccess(LibXray.stopXray());
    }

    public static boolean isRunning() {
        return LibXray.getXrayState();
    }

    public static String version() {
        try {
            return responseData(LibXray.xrayVersion());
        } catch (Exception error) {
            return "unknown";
        }
    }

    public static String detectProtocol(String config) {
        try {
            JSONArray outbounds = new JSONObject(config).optJSONArray("outbounds");
            if (outbounds == null || outbounds.length() == 0) {
                return "Xray";
            }
            return outbounds.getJSONObject(0)
                .optString("protocol", "Xray")
                .toUpperCase(Locale.ROOT);
        } catch (JSONException error) {
            return "Xray";
        }
    }

    public static String detectName(String source) {
        String firstLine = source.trim().split("\\R", 2)[0];
        int fragment = firstLine.indexOf('#');
        if (fragment >= 0 && fragment + 1 < firstLine.length()) {
            try {
                return java.net.URLDecoder.decode(firstLine.substring(fragment + 1), "UTF-8");
            } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
                return firstLine.substring(fragment + 1);
            }
        }
        String protocol = firstLine.contains("://")
            ? firstLine.substring(0, firstLine.indexOf("://")).toUpperCase(Locale.ROOT)
            : "Xray";
        return protocol + " profile";
    }

    private static String encodeJson(JSONObject json) {
        return Base64.encodeToString(
            json.toString().getBytes(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        );
    }

    private static JSONObject decodeResponse(String encoded) throws Exception {
        byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
        return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
    }

    private static JSONObject requireSuccess(String encoded) throws Exception {
        JSONObject response = decodeResponse(encoded);
        if (!response.optBoolean("success")) {
            String error = response.optString("error", "Неизвестная ошибка Xray-core");
            throw new IllegalStateException(error);
        }
        return response;
    }

    private static JSONObject responseDataAsObject(String encoded) throws Exception {
        Object data = requireSuccess(encoded).opt("data");
        if (data instanceof JSONObject) {
            return (JSONObject) data;
        }
        throw new IllegalStateException("Xray-core вернул конфигурацию неизвестного формата");
    }

    private static String responseData(String encoded) throws Exception {
        return requireSuccess(encoded).optString("data", "");
    }
}
