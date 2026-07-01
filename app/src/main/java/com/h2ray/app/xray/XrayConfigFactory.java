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

        JSONArray runtimeOutbounds = new JSONArray();
        String proxyTag = "";
        for (int index = 0; index < outbounds.length(); index++) {
            JSONObject outbound = outbounds.optJSONObject(index);
            if (outbound == null) {
                continue;
            }
            String protocol = outbound.optString("protocol", "").toLowerCase(Locale.ROOT);
            if (!isRuntimeProtocol(protocol)) {
                continue;
            }
            if (isProxyProtocol(protocol)) {
                if (proxyTag.isEmpty()) {
                    proxyTag = ensureTag(outbound, "proxy");
                } else {
                    ensureTag(outbound, "proxy-" + (index + 1));
                }
                outbound.remove("sendThrough");
                normalizeStreamSettings(outbound);
                applySocketPolicy(outbound, settings);
            } else if ("freedom".equals(protocol)) {
                ensureTag(outbound, "direct");
            } else if ("blackhole".equals(protocol)) {
                ensureTag(outbound, "block");
            }
            runtimeOutbounds.put(outbound);
        }
        if (proxyTag.isEmpty()) {
            throw new JSONException(
                "В конфигурации отсутствует поддерживаемый outbound VLESS/VMess/Trojan/Shadowsocks"
            );
        }
        if (!hasOutboundTag(runtimeOutbounds, "direct")) {
            runtimeOutbounds.put(new JSONObject()
                .put("tag", "direct")
                .put("protocol", "freedom"));
        }
        if (!hasOutboundTag(runtimeOutbounds, "block")) {
            runtimeOutbounds.put(new JSONObject()
                .put("tag", "block")
                .put("protocol", "blackhole"));
        }

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
        if (settings.ipv6()) {
            privateNetworks
                .put("::1/128")
                .put("fc00::/7")
                .put("fe80::/10");
        }

        JSONObject tunInbound = new JSONObject()
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put("settings", new JSONObject()
                .put("name", "h2ray0")
                .put("mtu", settings.mtu())
                .put("gateway", new JSONArray()
                    .put("10.10.0.1/30")
                    .put("fd00:10:10::1/126"))
                .put("dns", new JSONArray().put(settings.dns())));
        JSONArray sniffers = new JSONArray().put("http").put("tls").put("quic");
        if (settings.fakeDns()) {
            sniffers.put("fakedns");
        }
        tunInbound.put("sniffing", new JSONObject()
            .put("enabled", settings.sniffing())
            .put("routeOnly", !settings.fakeDns())
            .put("destOverride", sniffers));

        config.put("log", new JSONObject().put("loglevel", "warning"));
        config.remove("remarks");
        JSONArray dnsServers = new JSONArray();
        if (settings.fakeDns()) {
            dnsServers.put("fakedns");
            config.put("fakedns", new JSONObject()
                .put("ipPool", "198.18.0.0/15")
                .put("poolSize", 65535));
        } else {
            config.remove("fakedns");
        }
        dnsServers.put(settings.xrayDns());
        config.put("dns", new JSONObject()
            .put("queryStrategy", settings.ipv6() ? "UseIP" : "UseIPv4")
            .put("enableParallelQuery", settings.parallelDns())
            .put("servers", dnsServers));
        config.put("inbounds", new JSONArray().put(tunInbound));
        config.put("outbounds", runtimeOutbounds);
        JSONObject importedRouting = config.optJSONObject("routing");
        JSONArray importedRules = importedRouting == null
            ? null
            : importedRouting.optJSONArray("rules");
        JSONArray rules = new JSONArray();
        String preset = settings.routingPreset();
        boolean customRuPreset = GeoDataManager.DEFAULT_RU.equals(preset)
            || GeoDataManager.WHITELIST_RU.equals(preset);
        String routingMode = customRuPreset ? "global" : settings.routingMode();
        if (!settings.ipv6()) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("ip", new JSONArray().put("::/0"))
                .put("outboundTag", "block"));
        }
        if (importedRules != null) {
            for (int index = 0; index < importedRules.length(); index++) {
                JSONObject rule = importedRules.optJSONObject(index);
                if (rule != null && hasValidTarget(rule, runtimeOutbounds, importedRouting)) {
                    rules.put(rule);
                }
            }
        }
        if (customRuPreset) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("domain", new JSONArray()
                    .put("geosite:win-spy")
                    .put("geosite:torrent")
                    .put("geosite:category-ads"))
                .put("outboundTag", "block"));
            JSONArray directDomains = new JSONArray()
                .put("geosite:private")
                .put("geosite:whitelist");
            JSONArray directIps = new JSONArray()
                .put("geoip:private")
                .put("geoip:whitelist");
            if (GeoDataManager.DEFAULT_RU.equals(preset)) {
                directDomains
                    .put("geosite:category-ru")
                    .put("geosite:microsoft")
                    .put("geosite:apple")
                    .put("geosite:epicgames")
                    .put("geosite:riot")
                    .put("geosite:escapefromtarkov")
                    .put("geosite:steam")
                    .put("geosite:twitch")
                    .put("geosite:pinterest")
                    .put("geosite:faceit");
                directIps.put("geoip:direct");
            }
            rules.put(new JSONObject()
                .put("type", "field")
                .put("domain", directDomains)
                .put("outboundTag", "direct"));
            rules.put(new JSONObject()
                .put("type", "field")
                .put("ip", directIps)
                .put("outboundTag", "direct"));
        } else if (settings.blockAds()) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("domain", new JSONArray().put("geosite:category-ads-all"))
                .put("outboundTag", "block"));
        }
        if (settings.blockQuic()) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("network", "udp")
                .put("port", "443")
                .put("outboundTag", "block"));
        }
        JSONArray customRules = customRules(
            settings.customDomains(),
            "proxy_only".equals(routingMode) ? proxyTag : "direct"
        );
        for (int index = 0; index < customRules.length(); index++) {
            rules.put(customRules.getJSONObject(index));
        }
        if ("direct".equals(routingMode)) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("network", "tcp,udp")
                .put("outboundTag", "direct"));
        } else if ("rules".equals(routingMode) && settings.bypassPrivate()) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("ip", privateNetworks)
                .put("outboundTag", "direct"));
        }
        if ("proxy_only".equals(routingMode)) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("network", "tcp,udp")
                .put("outboundTag", "direct"));
        }
        if ("rules".equals(routingMode) && settings.bypassRu()) {
            rules.put(new JSONObject()
                .put("type", "field")
                .put("domain", new JSONArray()
                    .put("geosite:category-ru")
                    .put("regexp:\\.ru$"))
                .put("outboundTag", "direct"));
            rules.put(new JSONObject()
                .put("type", "field")
                .put("ip", new JSONArray().put("geoip:ru"))
                .put("outboundTag", "direct"));
        }
        JSONObject runtimeRouting = importedRouting == null
            ? new JSONObject()
            : new JSONObject(importedRouting.toString());
        runtimeRouting.put("domainStrategy", "IPIfNonMatch");
        runtimeRouting.put("rules", rules);
        config.put("routing", runtimeRouting);
        return config.toString();
    }

    private static boolean isProxyProtocol(String protocol) {
        return "vless".equals(protocol)
            || "vmess".equals(protocol)
            || "trojan".equals(protocol)
            || "shadowsocks".equals(protocol);
    }

    private static boolean isRuntimeProtocol(String protocol) {
        return isProxyProtocol(protocol)
            || "socks".equals(protocol)
            || "freedom".equals(protocol)
            || "blackhole".equals(protocol);
    }

    private static String ensureTag(JSONObject outbound, String fallback) throws JSONException {
        String tag = outbound.optString("tag", "").trim();
        if (tag.isEmpty()) {
            tag = fallback;
            outbound.put("tag", tag);
        }
        return tag;
    }

    private static boolean hasValidTarget(
        JSONObject rule,
        JSONArray outbounds,
        JSONObject routing
    ) {
        String outboundTag = rule.optString("outboundTag", "").trim();
        if (!outboundTag.isEmpty() && !hasOutboundTag(outbounds, outboundTag)) {
            return false;
        }
        String balancerTag = rule.optString("balancerTag", "").trim();
        return balancerTag.isEmpty() || hasBalancerTag(routing, balancerTag);
    }

    private static boolean hasOutboundTag(JSONArray outbounds, String tag) {
        for (int index = 0; index < outbounds.length(); index++) {
            JSONObject outbound = outbounds.optJSONObject(index);
            if (outbound != null && tag.equals(outbound.optString("tag"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBalancerTag(JSONObject routing, String tag) {
        if (routing == null) {
            return false;
        }
        JSONArray balancers = routing.optJSONArray("balancers");
        if (balancers == null) {
            return false;
        }
        for (int index = 0; index < balancers.length(); index++) {
            JSONObject balancer = balancers.optJSONObject(index);
            if (balancer != null && tag.equals(balancer.optString("tag"))) {
                return true;
            }
        }
        return false;
    }

    private static JSONArray customRules(String source, String outboundTag)
        throws JSONException {
        JSONArray result = new JSONArray();
        if (source == null || source.trim().isEmpty()) {
            return result;
        }
        JSONArray domains = new JSONArray();
        JSONArray ips = new JSONArray();
        for (String raw : source.split("\\R")) {
            String value = raw.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("geoip:")
                || value.matches("[0-9a-fA-F:.]+(?:/\\d{1,3})?")) {
                ips.put(value);
            } else {
                domains.put(value.contains(":") ? value : "domain:" + value);
            }
        }
        if (domains.length() == 0 && ips.length() == 0) {
            return result;
        }
        if (domains.length() > 0) {
            result.put(new JSONObject()
                .put("type", "field")
                .put("outboundTag", outboundTag)
                .put("domain", domains));
        }
        if (ips.length() > 0) {
            result.put(new JSONObject()
                .put("type", "field")
                .put("outboundTag", outboundTag)
                .put("ip", ips));
        }
        return result;
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
            normalizeRealitySettings(outbound, stream);
            if (!"raw".equals(network) && !"xhttp".equals(network) && !"grpc".equals(network)) {
                throw new JSONException("REALITY поддерживает только RAW, XHTTP и gRPC");
            }
        } else if ("tls".equalsIgnoreCase(security)) {
            setDefaultFingerprint(stream, "tlsSettings");
            JSONObject tls = stream.getJSONObject("tlsSettings");
            tls.remove("allowInsecure");
            tls.remove("verifyPeerCertInNames");
        }
    }

    private static void applySocketPolicy(JSONObject outbound, AppSettings settings)
        throws JSONException {
        JSONObject stream = outbound.optJSONObject("streamSettings");
        if (stream == null) {
            stream = new JSONObject();
            outbound.put("streamSettings", stream);
        }
        JSONObject sockopt = stream.optJSONObject("sockopt");
        if (sockopt == null) {
            sockopt = new JSONObject();
            stream.put("sockopt", sockopt);
        }
        sockopt.put("tcpUserTimeout", settings.connectionTimeoutSeconds() * 1000);
        if (settings.happyEyeballs() && settings.ipv6()) {
            sockopt.put("domainStrategy", "UseIP");
            sockopt.put("happyEyeballs", new JSONObject()
                .put("tryDelayMs", 250)
                .put("prioritizeIPv6", false)
                .put("interleave", 1)
                .put("maxConcurrentTry", 4));
        } else {
            sockopt.remove("happyEyeballs");
        }
    }

    private static void normalizeRealitySettings(
        JSONObject outbound,
        JSONObject stream
    ) throws JSONException {
        JSONObject source = stream.optJSONObject("realitySettings");
        if (source == null) {
            source = new JSONObject();
        }

        String password = stringValue(source, "password");
        if (password.trim().isEmpty()) {
            password = stringValue(source, "publicKey");
        }
        String serverName = firstNonBlank(
            stringValue(source, "serverName"),
            stringValue(source, "server_name"),
            stringValue(source, "sni"),
            firstString(source.optJSONArray("serverNames")),
            outboundServerAddress(outbound)
        );
        if (serverName.isEmpty()) {
            throw new JSONException(
                "REALITY: отсутствует SNI/serverName. Укажите домен маскировки сервера"
            );
        }

        JSONObject client = new JSONObject(source.toString());
        client.put("fingerprint", valueOrDefault(source, "fingerprint", "chrome"));
        client.put("serverName", serverName);
        client.put("password", password);
        client.put("shortId", stringValue(source, "shortId"));
        client.remove("publicKey");
        client.remove("target");
        client.remove("dest");
        client.remove("type");
        client.remove("xver");
        client.remove("serverNames");
        client.remove("privateKey");
        client.remove("minClientVer");
        client.remove("maxClientVer");
        client.remove("maxTimeDiff");
        client.remove("shortIds");
        client.remove("mldsa65Seed");
        client.remove("limitFallbackUpload");
        client.remove("limitFallbackDownload");
        stream.put("realitySettings", client);
    }

    private static String outboundServerAddress(JSONObject outbound) {
        JSONObject settings = outbound.optJSONObject("settings");
        if (settings == null) {
            return "";
        }
        JSONArray vnext = settings.optJSONArray("vnext");
        if (vnext != null && vnext.length() > 0) {
            JSONObject server = vnext.optJSONObject(0);
            return server == null ? "" : server.optString("address", "").trim();
        }
        JSONArray servers = settings.optJSONArray("servers");
        if (servers != null && servers.length() > 0) {
            JSONObject server = servers.optJSONObject(0);
            return server == null ? "" : server.optString("address", "").trim();
        }
        return "";
    }

    private static String firstString(JSONArray values) {
        if (values == null) {
            return "";
        }
        for (int index = 0; index < values.length(); index++) {
            String value = values.optString(index, "").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String valueOrDefault(JSONObject source, String key, String fallback) {
        String value = stringValue(source, key);
        return value.trim().isEmpty() ? fallback : value;
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
