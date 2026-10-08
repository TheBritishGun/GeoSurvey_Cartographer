package dev.openmap.claim;

import java.net.URI;

public final class NodeAddress {

    private static final String NODE_HOST_VALUE = "avience.live";

    public static final String NODE = "https://" + NODE_HOST_VALUE;

    public static final String NODE_HOST = nodeHost();

    private static final String HOST = "avience.uk";

    // Client thread only.
    private static String cachedUrl;

    private static String cachedNode = "";

    private NodeAddress() {
    }

    // liveMapUrl may be null.
    public static String forMapLink(String liveMapUrl) {
        if (liveMapUrl == cachedUrl
                || liveMapUrl != null && liveMapUrl.equals(cachedUrl)) {
            return cachedNode;
        }
        String node = HOST.equalsIgnoreCase(host(liveMapUrl)) ? NODE : "";
        cachedNode = node;
        cachedUrl = liveMapUrl;
        return node;
    }

    public static String withoutRootDot(String host) {
        if (host == null || host.length() < 2 || host.charAt(host.length() - 1) != '.') {
            return host;
        }
        return host.substring(0, host.length() - 1);
    }

    private static String nodeHost() {
        return NODE_HOST_VALUE;
    }

    public static String host(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String text = url.trim();
        String withScheme = text.contains("://") ? text : "https://" + text;
        String host;
        try {
            String parsed = URI.create(withScheme).getHost();
            host = parsed == null ? "" : parsed;
        } catch (IllegalArgumentException malformed) {
            host = "";
        }
        return withoutRootDot(host);
    }
}
