package com.h2ray.app.xray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.h2ray.app.data.AppSettings;

import java.util.Locale;

public final class XrayConfigFactory {
    private XrayConfigFactory() {
    }

    public static String createRuntimeConfig(String importedConfig, AppSettings settings)
        throws JSONException {
        JSONObject config = new JSONObject(importedConfig);
        JSONArray outbounds = config.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) {
            throw new JSONException("В конфигурации отсутствуют outbounds");
        }

        JSONObject proxy = outbounds.getJSONObject(0);
        proxy.remove("sendThrough");
        proxy.put("tag", "proxy");
        normalizeStreamSettings(proxy);

        JSONArray runtimeOutbounds = new JSONArray().put(proxy);
        runtimeOutbounds.put(new JSONObject()
            .put("tag", "direct")
            .put("protocol", "freedom"));
        runtimeOutbounds.put(new JSONObject()
            .put("tag", "block")
            .put("protocol", "blackhole"));

        JSONArray privateNetworks = new JSONArray()
            .put("0.0.0.0/8")
            .put("10.0.0.0/8")
            .put("100.64.0.0/10")
            .put("127.0.0.0/8")
            .put("169.254.0.0/16")
            .put("172.16.0.0/12")
            .put("192.0.0.0/24")
            .put("192.0.2.0/24")
            .put("192.168.0.0/16")
            .put("198.18.0.0/15")
            .put("198.51.100.0/24")
            .put("203.0.113.0/24")
            .put("224.0.0.0/4")
            .put("240.0.0.0/4");

        JSONObject tunInbound = new JSONObject()
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put("settings", new JSONObject()
                .put("name", "h2ray0")
                .put("mtu", 1500)
                .put("gateway", new JSONArray().put("10.10.0.1/30"))
                .put("dns", new JSONArray().put(settings.dns())));
        tunInbound.put("sniffing", new JSONObject()
            .put("enabled", true)
            .put("routeOnly", true)
            .put("destOverride", new JSONArray().put("http").put("tls").put("quic")));

        config.put("log", new JSONObject().put("loglevel", "warning"));
        config.put("dns", new JSONObject()
            .put("queryStrategy", settings.ipv6() ? "UseIP" : "UseIPv4")
            .put("servers", new JSONArray().put(settings.dns())));
        config.put("inbounds", new JSONArray().put(tunInbound));
        config.put("outbounds", runtimeOutbounds);
        JSONArray rules = new JSONArray();
        if (settings.bypassPrivate()) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("ip", privateNetworks)
                .put("outboundTag", "direct"));
        }
        if (settings.bypassRu()) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("domain", new JSONArray()
                    .put("geosite:category-bank-ru")
                    .put("geosite:category-gov-ru")
                    .put("geosite:category-ru")
                    .put("regexp:\\.ru$"))
                .put("outboundTag", "direct"));
            rules.put(new JSONObject()
                .put("type", "field")
                .put("ip", new JSONArray().put("geoip:ru"))
                .put("outboundTag", "direct"));
        }
        config.put("routing", new JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", rules));
        return config.toString();
    }

    private static void normalizeStreamSettings(JSONObject outbound) throws JSONException {
        JSONObject stream = outbound.optJSONObject("streamSettings");
        if (stream == null) {
            return;
        }

        String network = normalizeNetwork(stream.optString("network", "raw"));
        stream.put("network", network);

        String security = stream.optString("security", "none");
        if ("reality".equalsIgnoreCase(security)) {
            normalizeRealitySettings(stream);
            if (!"raw".equals(network) && !"xhttp".equals(network) && !"grpc".equals(network)) {
                throw new JSONException("REALITY поддерживает только RAW, XHTTP и gRPC");
            }
        } else if ("tls".equalsIgnoreCase(security)) {
            setDefaultFingerprint(stream, "tlsSettings");
            JSONObject tls = stream.getJSONObject("tlsSettings");
            tls.remove("allowInsecure");
            tls.remove("verifyPeerCertInNames");
            tls.remove("echForceQuery");
        }
    }

    private static void normalizeRealitySettings(JSONObject stream) throws JSONException {
        JSONObject source = stream.optJSONObject("realitySettings");
        if (source == null) {
            source = new JSONObject();
        }

        String password = stringValue(source, "password");
        if (password.trim().isEmpty()) {
            password = stringValue(source, "publicKey");
        }

        JSONObject client = new JSONObject()
            .put("fingerprint", valueOrDefault(source, "fingerprint", "chrome"))
            .put("serverName", stringValue(source, "serverName"))
            .put("password", password)
            .put("shortId", stringValue(source, "shortId"));

        copyNonEmpty(source, client, "mldsa65Verify");
        copyNonEmpty(source, client, "spiderX");
        copyNonEmpty(source, client, "masterKeyLog");
        stream.put("realitySettings", client);
    }

    private static String valueOrDefault(JSONObject source, String key, String fallback) {
        String value = stringValue(source, key);
        return value.trim().isEmpty() ? fallback : value;
    }

    private static void copyNonEmpty(JSONObject source, JSONObject destination, String key)
        throws JSONException {
        String value = stringValue(source, key);
        if (!value.trim().isEmpty()) {
            destination.put(key, value);
        }
    }

    private static String stringValue(JSONObject source, String key) {
        return source.has(key) && !source.isNull(key) ? source.optString(key, "") : "";
    }

    private static String normalizeNetwork(String network) {
        switch (network.toLowerCase(Locale.ROOT)) {
            case "tcp":
                return "raw";
            case "ws":
                return "websocket";
            case "kcp":
                return "mkcp";
            case "splithttp":
                return "xhttp";
            default:
                return network.toLowerCase(Locale.ROOT);
        }
    }

    private static void setDefaultFingerprint(JSONObject stream, String settingsName)
        throws JSONException {
        JSONObject securitySettings = stream.optJSONObject(settingsName);
        if (securitySettings == null) {
            securitySettings = new JSONObject();
            stream.put(settingsName, securitySettings);
        }
        if (securitySettings.optString("fingerprint", "").trim().isEmpty()) {
            securitySettings.put("fingerprint", "chrome");
        }
    }
}
