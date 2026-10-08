package dev.openmap.map;

import java.util.Arrays;
import java.util.UUID;

public final class TabName {

    // Longest tab-list name drawn, in glyphs.
    public static final int MAX_CHARS = 48;

    // How far into a name to read, in characters.
    public static final int MAX_SCAN = MAX_CHARS * 3;

    // Longest name a marker filter reads, in characters.
    public static final int MAX_FILTER_CHARS = 1024;

    public static boolean pastReading(String shown) {
        return shown != null && shown.length() > MAX_FILTER_CHARS;
    }

    // Shortest UUID text: 32 hex digits, no dashes.
    private static final int SHORTEST_ID = 32;

    private static final int HEX_DIGIT_BITS = 4;
    private static final int HALF_ID_DIGITS = Long.SIZE / HEX_DIGIT_BITS;
    private static final int TOP_DIGIT_SHIFT = Long.SIZE - HEX_DIGIT_BITS;

    private static final int FIRST_GROUP_DIGITS = 8;
    private static final int MIDDLE_GROUP_DIGITS = 4;
    private static final int LAST_GROUP_DIGITS = 12;
    private static final int FIRST_DASH = FIRST_GROUP_DIGITS;
    private static final int SECOND_GROUP = FIRST_DASH + 1;
    private static final int SECOND_DASH = SECOND_GROUP + MIDDLE_GROUP_DIGITS;
    private static final int THIRD_GROUP = SECOND_DASH + 1;
    private static final int THIRD_DASH = THIRD_GROUP + MIDDLE_GROUP_DIGITS;
    private static final int FOURTH_GROUP = THIRD_DASH + 1;
    private static final int FOURTH_DASH = FOURTH_GROUP + MIDDLE_GROUP_DIGITS;
    private static final int LAST_GROUP = FOURTH_DASH + 1;
    private static final int DASHED_ID_LENGTH = LAST_GROUP + LAST_GROUP_DIGITS;

    private static long dashedProbeCount;

    private TabName() {
    }

    static long dashedProbeCount() {
        return dashedProbeCount;
    }

    // Whether shown carries id's account id. id can be null.
    public static boolean damaged(String shown, UUID id) {
        if (shown == null || shown.length() < SHORTEST_ID) {
            return false;
        }
        int scanTo = Math.min(shown.length(), MAX_FILTER_CHARS);
        boolean hasDash = scanTo >= DASHED_ID_LENGTH && hasDashInRange(shown, scanTo);
        if (hasDash) {
            dashedProbeCount++;
        }
        boolean damaged = hasDash && hasDashedId(shown, scanTo);
        if (!damaged) {
            damaged = id != null && hasUndashedId(shown, scanTo, id);
        }
        return damaged;
    }

    private static boolean hasDashInRange(String shown, int scanTo) {
        boolean hasDash = false;
        for (int index = 0; index < scanTo && !hasDash; index++) {
            hasDash = shown.charAt(index) == '-';
        }
        return hasDash;
    }

    private static boolean hasDashedId(String shown, int scanTo) {
        boolean hasDashedId = false;
        for (int start = 0; start + DASHED_ID_LENGTH <= scanTo && !hasDashedId; start++) {
            hasDashedId = matchesDashedIdAt(shown, start);
        }
        return hasDashedId;
    }

    private static boolean matchesDashedIdAt(String shown, int start) {
        return allHex(shown, start, FIRST_GROUP_DIGITS)
                && shown.charAt(start + FIRST_DASH) == '-'
                && allHex(shown, start + SECOND_GROUP, MIDDLE_GROUP_DIGITS)
                && shown.charAt(start + SECOND_DASH) == '-'
                && allHex(shown, start + THIRD_GROUP, MIDDLE_GROUP_DIGITS)
                && shown.charAt(start + THIRD_DASH) == '-'
                && allHex(shown, start + FOURTH_GROUP, MIDDLE_GROUP_DIGITS)
                && shown.charAt(start + FOURTH_DASH) == '-'
                && allHex(shown, start + LAST_GROUP, LAST_GROUP_DIGITS);
    }

    private static boolean allHex(String shown, int from, int count) {
        boolean allHex = true;
        for (int index = 0; index < count && allHex; index++) {
            allHex = hexNibble(shown.charAt(from + index)) >= 0;
        }
        return allHex;
    }

    private static boolean hasUndashedId(String shown, int scanTo, UUID id) {
        long hi = id.getMostSignificantBits();
        long lo = id.getLeastSignificantBits();
        long recentHi = 0;
        long recentLo = 0;
        int run = 0;
        boolean hasUndashedId = false;
        for (int index = 0; index < scanTo && !hasUndashedId; index++) {
            int nibble = hexNibble(shown.charAt(index));
            if (nibble < 0) {
                run = 0;
            } else {
                if (run < HALF_ID_DIGITS) {
                    recentHi = (recentHi << HEX_DIGIT_BITS) | nibble;
                } else if (run < SHORTEST_ID) {
                    recentLo = (recentLo << HEX_DIGIT_BITS) | nibble;
                } else {
                    recentHi = (recentHi << HEX_DIGIT_BITS) | (recentLo >>> TOP_DIGIT_SHIFT);
                    recentLo = (recentLo << HEX_DIGIT_BITS) | nibble;
                }
                run++;
                if (run >= SHORTEST_ID && recentHi == hi && recentLo == lo) {
                    hasUndashedId = true;
                }
            }
        }
        return hasUndashedId;
    }

    private static final int HEX_TABLE_SIZE = 128;
    private static final int DECIMAL_DIGITS = 10;

    private static final byte[] HEX = new byte[HEX_TABLE_SIZE];

    static {
        Arrays.fill(HEX, (byte) -1);
        for (char c = '0'; c <= '9'; c++) {
            HEX[c] = (byte) (c - '0');
        }
        for (char c = 'a'; c <= 'f'; c++) {
            HEX[c] = (byte) (c - 'a' + DECIMAL_DIGITS);
        }
        for (char c = 'A'; c <= 'F'; c++) {
            HEX[c] = (byte) (c - 'A' + DECIMAL_DIGITS);
        }
    }

    private static int hexNibble(char c) {
        return c < HEX_TABLE_SIZE ? HEX[c] : -1;
    }

    // shown, cut to MAX_CHARS glyphs, with an ellipsis if cut.
    public static String bounded(String shown) {
        return LabelText.clean(shown, MAX_CHARS, MAX_SCAN, true);
    }
}
