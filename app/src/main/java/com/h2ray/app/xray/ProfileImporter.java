package com.h2ray.app.xray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProfileImporter {
    private static final Pattern SHARE_LINK = Pattern.compile(
        "(?i)(vless|vmess|trojan|ss)://[^\\s\"'<>]+"
    );

    private ProfileImporter() {
    }

    public static List<ProfileData> importContent(String source, String fallbackName)
        throws Exception {
        String content = source == null ? "" : source.trim();
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1).trim();
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Файл не содержит конфигураций");
        }
        List<ProfileData> profiles = new ArrayList<>();
        if (content.startsWith("{")) {
            profiles.add(importJson(content, fallbackName));
            return profiles;
        }

        Matcher matcher = SHARE_LINK.matcher(content);
        while (matcher.find()) {
            String link = trimTrailingPunctuation(matcher.group());
            String config = XrayBridge.parseShareText(link);
            profiles.add(new ProfileData(
                XrayBridge.detectName(link),
                XrayBridge.detectProtocol(config),
                config,
                link
            ));
        }
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException(
                "Поддерживаемые ссылки VLESS, VMess, Trojan или Shadowsocks не найдены"
            );
        }
        return profiles;
    }

    private static ProfileData importJson(String source, String fallbackName) throws Exception {
        JSONObject root = new JSONObject(source);
        JSONObject proxy;
        if (root.has("outbounds")) {
            proxy = findProxyOutbound(root.optJSONArray("outbounds"));
            if (proxy == null) {
                throw new IllegalArgumentException(
                    "В JSON отсутствует клиентский outbound VLESS/VMess/Trojan/Shadowsocks"
                );
            }
        } else if (isProxyProtocol(root.optString("protocol"))) {
            JSONObject settings = root.optJSONObject("settings");
            if (root.has("listen") || (settings != null && settings.has("clients"))) {
                throw new IllegalArgumentException(
                    "Это серверный inbound. Для подключения нужны адрес сервера и UUID/пароль клиента"
                );
            }
            root = new JSONObject().put("outbounds", new JSONArray().put(root));
            proxy = root.getJSONArray("outbounds").getJSONObject(0);
        } else {
            throw new IllegalArgumentException("Файл не является клиентской конфигурацией Xray");
        }

        if (ServerEndpoint.fromConfig(root.toString()) == null) {
            throw new IllegalArgumentException(
                "В клиентской конфигурации отсутствуют адрес или порт сервера"
            );
        }
        String protocol = proxy.optString("protocol", "Xray").toUpperCase(Locale.ROOT);
        String remarks = root.optString("remarks", "").trim();
        String name = !remarks.isEmpty()
            ? remarks
            : fallbackName == null || fallbackName.trim().isEmpty()
                ? protocol + " JSON"
                : fallbackName.trim();
        return new ProfileData(name, protocol, root.toString(), source);
    }

    private static JSONObject findProxyOutbound(JSONArray outbounds) {
        if (outbounds == null) {
            return null;
        }
        for (int index = 0; index < outbounds.length(); index++) {
            JSONObject outbound = outbounds.optJSONObject(index);
            if (outbound != null && isProxyProtocol(outbound.optString("protocol"))) {
                return outbound;
            }
        }
        return null;
    }

    private static boolean isProxyProtocol(String protocol) {
        String value = protocol == null ? "" : protocol.toLowerCase(Locale.ROOT);
        return "vless".equals(value)
            || "vmess".equals(value)
            || "trojan".equals(value)
            || "shadowsocks".equals(value);
    }

    private static String trimTrailingPunctuation(String value) {
        String result = value;
        while (result.endsWith(",") || result.endsWith(";") || result.endsWith(")")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public static final class ProfileData {
        public final String name;
        public final String protocol;
        public final String config;
        public final String source;

        private ProfileData(String name, String protocol, String config, String source) {
            this.name = name;
            this.protocol = protocol;
            this.config = config;
            this.source = source;
        }
    }
}
