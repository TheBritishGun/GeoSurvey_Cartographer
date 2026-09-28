package dev.openmap.live;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WorldMapping {

    private static final int ASCII_LOWERCASE_BIT = 0x20;

    private static final int URL_SCHEME_DELIMITER_LENGTH = 3;

    private final Map<String, List<String>> worldsByDimension = new LinkedHashMap<>();

    private final Map<String, String> dimensionByWorld = new HashMap<>();

    private final Map<String, List<String>> snapshots = new LinkedHashMap<>();

    private List<String> dimensionsCache;

    private boolean readOnly;

    public static WorldMapping dynmapDefaults() {
        WorldMapping mapping = new WorldMapping();
        mapping.add("minecraft:overworld", "world");
        mapping.add("minecraft:the_nether", "DIM-1");
        mapping.add("minecraft:the_end", "DIM1");
        mapping.markReadOnly();
        return mapping;
    }

    // The vanilla dimensions, with worlds derived from publishedFolder.
    public static WorldMapping openMapDefaults() {
        WorldMapping mapping = new WorldMapping();
        for (String dimension : new String[] {
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            mapping.add(dimension, publishedFolder(dimension));
        }
        mapping.markReadOnly();
        return mapping;
    }

    // The folder name an Open-Map node publishes for a dimension.
    public static String publishedFolder(String dimensionId) {
        int end = retainedLength(dimensionId);
        if (end == 0) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            char c = dimensionId.charAt(i);
            char mapped = storable(c) ? (char) (c | ASCII_LOWERCASE_BIT) : '_';
            out.append(mapped == '.' ? '-' : mapped);
        }
        return out.toString();
    }

    private static int retainedLength(String dimensionId) {
        if (dimensionId == null) {
            return 0;
        }
        int end = dimensionId.length();
        while (end > 0) {
            char c = dimensionId.charAt(end - 1);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-') {
                break;
            }
            end--;
        }
        return end;
    }

    private static boolean storable(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    public synchronized void add(String dimensionId, String world) {
        if (readOnly) {
            throw new UnsupportedOperationException();
        }
        if (dimensionId == null || world == null
                || dimensionId.isBlank() || world.isBlank()) {
            return;
        }
        String existing = dimensionByWorld.get(world);
        List<String> previous = existing == null ? null : worldsByDimension.get(existing);
        if (previous != null) {
            previous.remove(world);
        }
        boolean sameDimension = previous != null && dimensionId.equals(existing);
        List<String> worlds = sameDimension
                ? previous : worldsByDimension.computeIfAbsent(dimensionId, id -> new ArrayList<>());
        worlds.add(world);
        if (!dimensionId.equals(existing)) {
            dimensionByWorld.put(world, dimensionId);
        }
        snapshots.remove(dimensionId);
        if (existing != null) {
            snapshots.remove(existing);
        }
        dimensionsCache = null;
    }

    private synchronized void markReadOnly() {
        readOnly = true;
    }

    public synchronized String dimensionOf(String world) {
        return world == null ? null : dimensionByWorld.get(world);
    }

    public synchronized List<String> worldsFor(String dimensionId) {
        List<String> snapshot = snapshots.get(dimensionId);
        if (snapshot == null) {
            snapshot = List.copyOf(worldsByDimension.getOrDefault(dimensionId, List.of()));
            snapshots.put(dimensionId, snapshot);
        }
        return snapshot;
    }

    public synchronized boolean serves(String dimensionId, String world) {
        return dimensionId != null && dimensionId.equals(dimensionOf(world));
    }

    public synchronized List<String> dimensions() {
        if (dimensionsCache == null) {
            List<String> out = new ArrayList<>(worldsByDimension.size());
            for (Map.Entry<String, List<String>> entry : worldsByDimension.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    out.add(entry.getKey());
                }
            }
            dimensionsCache = List.copyOf(out);
        }
        return dimensionsCache;
    }

    public synchronized boolean isEmpty() {
        return dimensionByWorld.isEmpty();
    }

    // Whether an address somebody typed names the server the client is on.
    public static boolean sameServer(String configured, String connected) {
        String configuredTrimmed = trimmed(configured);
        String host = hostOf(configuredTrimmed);
        if (host.isEmpty()) {
            return false;
        }
        String connectedTrimmed = trimmed(connected);
        String connectedHost = hostOf(connectedTrimmed);
        boolean same = host.equals(connectedHost);
        if (same) {
            String wanted = portOf(configuredTrimmed, host);
            if (!wanted.isEmpty()) {
                String dialled = portOf(connectedTrimmed, connectedHost);
                same = dialled.isEmpty() || wanted.equals(dialled);
            }
        }
        return same;
    }

    // An address as an identity: the host, and the port where one is stated.
    public static String normaliseAddress(String address) {
        String trimmed = trimmed(address);
        String host = hostOf(trimmed);
        int portEnd = identityPortEnd(trimmed, host);
        return portEnd < 0 ? host : trimmed.substring(0, portEnd);
    }

    // The port an address states, or "" if it states none.
    public static String portOf(String address) {
        String trimmed = trimmed(address);
        return portOf(trimmed, hostOf(trimmed));
    }

    private static String portOf(String trimmed, String host) {
        if (host.isEmpty() || trimmed.length() <= host.length()
                || trimmed.charAt(host.length()) != ':') {
            return "";
        }
        int start = host.length() + 1;
        // Digits, or nothing.
        boolean digits = start < trimmed.length();
        for (int i = start; digits && i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            digits = c >= '0' && c <= '9';
        }
        return digits ? trimmed.substring(start) : "";
    }

    private static int identityPortEnd(String trimmed, String host) {
        if (host.isEmpty() || trimmed.length() <= host.length()
                || trimmed.charAt(host.length()) != ':') {
            return -1;
        }
        int start = host.length() + 1;
        int end = start;
        while (end < trimmed.length()) {
            char c = trimmed.charAt(end);
            if (c < '0' || c > '9') {
                break;
            }
            end++;
        }
        return end == start ? -1 : end;
    }

    private static String trimmed(String address) {
        String trimmed = address == null ? "" : address.trim();
        int scheme = trimmed.indexOf("://");
        String tail = scheme > 0 ? trimmed.substring(scheme + URL_SCHEME_DELIMITER_LENGTH) : trimmed;
        return tail.toLowerCase(Locale.ROOT);
    }

    // The host part of an address, lower-cased, with any port taken off.
    public static String normaliseHost(String host) {
        return hostOf(trimmed(host));
    }

    private static String hostOf(String trimmed) {
        String host = trimmed;
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close > 0 && close + 1 < trimmed.length()
                    && trimmed.charAt(close + 1) == ':') {
                host = trimmed.substring(0, close + 1);
            }
        } else {
            int colon = trimmed.indexOf(':');
            if (colon > 0 && trimmed.indexOf(':', colon + 1) < 0) {
                host = trimmed.substring(0, colon);
            }
        }
        return host;
    }
}
