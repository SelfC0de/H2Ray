package com.h2ray.app.xray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class XrayConfigFactory {
    private XrayConfigFactory() {
    }

    public static String createRuntimeConfig(String importedConfig) throws JSONException {
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
                .put("dns", new JSONArray().put("1.1.1.1").put("8.8.8.8")));

        config.put("log", new JSONObject().put("loglevel", "warning"));
        config.put("inbounds", new JSONArray().put(tunInbound));
        config.put("outbounds", runtimeOutbounds);
        config.put("routing", new JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", new JSONArray()
                .put(new JSONObject()
                    .put("type", "field")
                    .put("ip", privateNetworks)
                    .put("outboundTag", "direct"))));
        return config.toString();
    }

    private static void normalizeStreamSettings(JSONObject outbound) throws JSONException {
        JSONObject stream = outbound.optJSONObject("streamSettings");
        if (stream == null) {
            return;
        }

        String network = stream.optString("network", "");
        if ("tcp".equalsIgnoreCase(network)) {
            stream.put("network", "raw");
        }

        String security = stream.optString("security", "none");
        if ("reality".equalsIgnoreCase(security)) {
            setDefaultFingerprint(stream, "realitySettings");
        } else if ("tls".equalsIgnoreCase(security)) {
            setDefaultFingerprint(stream, "tlsSettings");
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
