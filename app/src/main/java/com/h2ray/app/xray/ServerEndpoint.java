package com.h2ray.app.xray;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ServerEndpoint {
    public final String address;
    public final int port;

    private ServerEndpoint(String address, int port) {
        this.address = address;
        this.port = port;
    }

    public static ServerEndpoint fromConfig(String config) {
        try {
            JSONArray outbounds = new JSONObject(config).optJSONArray("outbounds");
            if (outbounds == null) {
                return null;
            }
            for (int index = 0; index < outbounds.length(); index++) {
                JSONObject outbound = outbounds.optJSONObject(index);
                if (outbound == null) {
                    continue;
                }
                JSONObject settings = outbound.optJSONObject("settings");
                ServerEndpoint endpoint = fromSettings(settings);
                if (endpoint != null) {
                    return endpoint;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ServerEndpoint fromSettings(JSONObject settings) {
        if (settings == null) {
            return null;
        }
        ServerEndpoint direct = create(
            settings.optString("address", ""),
            settings.optInt("port", 0)
        );
        if (direct != null) {
            return direct;
        }
        ServerEndpoint vnext = firstFromArray(settings.optJSONArray("vnext"));
        return vnext != null ? vnext : firstFromArray(settings.optJSONArray("servers"));
    }

    private static ServerEndpoint firstFromArray(JSONArray entries) {
        if (entries == null) {
            return null;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            ServerEndpoint endpoint = create(
                entry.optString("address", ""),
                entry.optInt("port", 0)
            );
            if (endpoint != null) {
                return endpoint;
            }
        }
        return null;
    }

    private static ServerEndpoint create(String address, int port) {
        String host = address == null ? "" : address.trim();
        return host.isEmpty() || port < 1 || port > 65535
            ? null
            : new ServerEndpoint(host, port);
    }

    public String displayName() {
        return address.contains(":") ? "[" + address + "]:" + port : address + ":" + port;
    }
}
