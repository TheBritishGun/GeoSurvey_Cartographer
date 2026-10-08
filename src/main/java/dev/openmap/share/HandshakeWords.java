package dev.openmap.share;

import java.util.Iterator;
import java.util.List;

public final class HandshakeWords {

    private static final String HANDSHAKE_COMPLETE = "Handshake complete. You are contributing.";

    private static final String OVERWORLD = "minecraft:overworld";

    private static final String NETHER = "minecraft:the_nether";

    private static final String GO_ONCE = "Go to ";

    private static final String UNTIL_DONE = " and walk around.";

    private static final String TO_CONTRIBUTE = "First, ";

    private static final String GO_TO = "go to ";

    private static final String WALK_AROUND = " and walk around.";

    private static final String ACCEPTED_PREFIX = "Ground accepted at ";

    private static final String ACCEPTED_MID = ". ";

    private static final String ACCEPTED_BREAK = ".\n";

    private static final String ACCEPTED_SUFFIX = " left: ";

    private static final int ROUTE_SHOWN = 3;

    private static final String WALK_AROUND_MORE = " and walk around; ";

    private static final String MORE_FOLLOW = " more follow.";

    private static final int HALVES = 2;

    private static final int PLACE_CHARS = 64;

    private static final int ROUTE_CHARS = 32;

    private HandshakeWords() {
    }

    public static String saying(List<WorldPrint.Region> owed) {
        int count = owed.size();
        if (count == 0) {
            return HANDSHAKE_COMPLETE;
        }
        if (count == 1) {
            WorldPrint.Region only = owed.get(0);
            StringBuilder out = new StringBuilder(GO_ONCE.length() + only.name().length()
                    + PLACE_CHARS + UNTIL_DONE.length()).append(GO_ONCE);
            return appendPlace(out, only).append(UNTIL_DONE).toString();
        }
        StringBuilder out = new StringBuilder(TO_CONTRIBUTE.length() + routeCapacity(count));
        out.append(TO_CONTRIBUTE);
        return appendWalkTo(out, owed, count);
    }

    public static String accepted(String region, List<WorldPrint.Region> left) {
        int count = left.size();
        if (count == 0) {
            return HANDSHAKE_COMPLETE;
        }
        int regionLength = region == null ? "null".length() : region.length();
        StringBuilder out = new StringBuilder(ACCEPTED_PREFIX.length() + regionLength
                + ACCEPTED_MID.length() + decimalDigits(count) + ACCEPTED_SUFFIX.length()
                + routeCapacity(count));
        out.append(ACCEPTED_PREFIX).append(region).append(count == 1 ? ACCEPTED_MID : ACCEPTED_BREAK)
                .append(count).append(ACCEPTED_SUFFIX);
        return appendWalkTo(out, left, count);
    }

    // The first line of the report that more than 1 region is left: the ground that was accepted.
    public static String acceptedAt(String region) {
        return ACCEPTED_PREFIX + region + ".";
    }

    // The second line: how many regions are left and the way to them.
    public static String leftToWalk(List<WorldPrint.Region> left) {
        int count = left.size();
        if (count == 0) {
            return HANDSHAKE_COMPLETE;
        }
        StringBuilder out = new StringBuilder(decimalDigits(count) + ACCEPTED_SUFFIX.length()
                + routeCapacity(count));
        out.append(count).append(ACCEPTED_SUFFIX);
        return appendWalkTo(out, left, count);
    }

    static int decimalDigits(int value) {
        int digits = 1;
        long threshold = 10L;
        while (value >= threshold) {
            digits++;
            threshold *= 10L;
        }
        return digits;
    }

    private static String appendWalkTo(StringBuilder out, List<WorldPrint.Region> owed, int count) {
        out.append(GO_TO);
        Iterator<WorldPrint.Region> regions = owed.iterator();
        int shown = Math.min(count, ROUTE_SHOWN);
        int last = shown - 1;
        for (int i = 0; i < shown && regions.hasNext(); i++) {
            if (i > 0) {
                out.append(i == last ? ". Then go to " : ", ");
            }
            appendPlace(out, regions.next());
        }
        if (count > shown) {
            out.append(WALK_AROUND_MORE).append(count - shown).append(MORE_FOLLOW);
        } else {
            out.append(WALK_AROUND);
        }
        return out.toString();
    }

    private static int routeCapacity(int count) {
        return ROUTE_CHARS + PLACE_CHARS * Math.min(count, ROUTE_SHOWN);
    }

    private static StringBuilder appendPlace(StringBuilder out, WorldPrint.Region region) {
        return out.append(region.name()).append(" (").append(dimension(region.dimension()))
                .append(", around x ").append(centre(region.minX(), region.maxX()))
                .append(" z ").append(centre(region.minZ(), region.maxZ())).append(')');
    }

    private static String dimension(String named) {
        if (WorldPrint.sameDimension(OVERWORLD, named)) {
            return "overworld";
        }
        if (WorldPrint.sameDimension(NETHER, named)) {
            return "nether";
        }
        return named;
    }

    private static long centre(int from, int to) {
        return Math.floorDiv((long) from + to, HALVES);
    }
}
