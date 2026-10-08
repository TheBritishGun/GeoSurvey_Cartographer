package dev.openmap.claim;

import dev.openmap.live.WorldMapping;

public final class ServerConfirmation {

    public enum Ground {

        PROVEN,

        OWED,

        ASKING,

        NO_REGIONS,

        NOT_WALKING
    }

    // Client thread only.
    private static String cachedMinecraftServer = null;

    private static String cachedConnected = null;

    private static boolean cachedServerMatch = false;

    private static boolean hasCachedServerMatch = false;

    private ServerConfirmation() {
    }

    // minecraftServer and connected may be null.
    public static boolean confirmed(Ground ground, String minecraftServer, String connected) {
        return switch (ground) {
            case PROVEN -> true;
            case OWED -> false;
            case ASKING -> false;
            case NOT_WALKING -> cachedSameServer(minecraftServer, connected);
            case NO_REGIONS -> cachedSameServer(minecraftServer, connected);
        };
    }

    private static boolean cachedSameServer(String minecraftServer, String connected) {
        if (hasCachedServerMatch
                && sameAddress(minecraftServer, cachedMinecraftServer)
                && sameAddress(connected, cachedConnected)) {
            return cachedServerMatch;
        }
        boolean matches = WorldMapping.sameServer(minecraftServer, connected);
        cachedMinecraftServer = minecraftServer;
        cachedConnected = connected;
        cachedServerMatch = matches;
        hasCachedServerMatch = true;
        return matches;
    }

    private static boolean sameAddress(String current, String cached) {
        return current == cached || current != null && current.equals(cached);
    }
}
