package dev.openmap.draw;

import dev.openmap.map.Landmark;
import java.util.Locale;

public final class ColourWord {

    private static final MarkerColour[] PALETTE = MarkerColour.values();
    private static final String[] LOWER = new String[PALETTE.length];
    private static final String NAMES;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final long PALETTE_NAME_LENGTHS;
    private static final int SHORT_HEX_LENGTH = 3;
    private static final int RGB_HEX_LENGTH = 6;
    private static final int ARGB_HEX_LENGTH = 8;

    private static final int ALPHA_SHIFT = 24;

    private static final int CHANNEL_MASK = 0xFF;

    private static final int SHORT_HEX_SPREAD = 0x11;

    private static final int NIBBLE_MASK = 0xF;

    private static final int NIBBLE_BITS = 4;

    private static final int CASE_BIT = 0x20;

    private static final int LETTER_OFFSET = 10;

    private static final int OPAQUE_RED_HIGH_NIBBLE_INDEX = 5;
    private static final int OPAQUE_RED_LOW_NIBBLE_INDEX = 4;
    private static final int OPAQUE_GREEN_HIGH_NIBBLE_INDEX = 3;
    private static final int OPAQUE_GREEN_LOW_NIBBLE_INDEX = 2;
    private static final int OPAQUE_BLUE_HIGH_NIBBLE_INDEX = 1;
    private static final int OPAQUE_BLUE_LOW_NIBBLE_INDEX = 0;
    private static final int ARGB_ALPHA_HIGH_NIBBLE_INDEX = 7;
    private static final int ARGB_ALPHA_LOW_NIBBLE_INDEX = 6;
    private static final int ARGB_RED_HIGH_NIBBLE_INDEX = 5;
    private static final int ARGB_RED_LOW_NIBBLE_INDEX = 4;
    private static final int ARGB_GREEN_HIGH_NIBBLE_INDEX = 3;
    private static final int ARGB_GREEN_LOW_NIBBLE_INDEX = 2;
    private static final int ARGB_BLUE_HIGH_NIBBLE_INDEX = 1;
    private static final int ARGB_BLUE_LOW_NIBBLE_INDEX = 0;
    private static final int SHORT_HEX_RED_NIBBLE_INDEX = 2;
    private static final int SHORT_HEX_GREEN_NIBBLE_INDEX = 1;
    private static final int EXPANDED_SHORT_RED_NIBBLE_INDEX = 4;
    private static final int EXPANDED_SHORT_GREEN_NIBBLE_INDEX = 2;

    static {
        MarkerColour first = PALETTE[0];
        String firstLower = first.name().toLowerCase(Locale.ROOT);
        LOWER[first.ordinal()] = firstLower;
        long lengths = lengthBit(first.name().length());
        StringBuilder out = new StringBuilder(firstLower);
        for (int i = 1; i < PALETTE.length; i++) {
            MarkerColour colour = PALETTE[i];
            String lower = colour.name().toLowerCase(Locale.ROOT);
            LOWER[colour.ordinal()] = lower;
            lengths |= lengthBit(colour.name().length());
            out.append(", ").append(lower);
        }
        NAMES = out.toString();
        PALETTE_NAME_LENGTHS = lengths;
    }

    private ColourWord() {
    }

    private static long lengthBit(int length) {
        return length >= 0 && length < Long.SIZE ? (1L << length) : 0L;
    }

    private static boolean hasPaletteNameLength(int length) {
        return (PALETTE_NAME_LENGTHS & lengthBit(length)) != 0L;
    }

    // ARGB; null when typed names no colour.
    public static Integer parse(String typed) {
        String word = trimmedWord(typed);
        if (word == null) {
            return null;
        }
        int offset = hexOffset(word);
        if (offset != 0) {
            return hex(word, offset);
        }
        MarkerColour paletteColour = null;
        if (hasPaletteNameLength(word.length())) {
            for (int index = 0; paletteColour == null && index < PALETTE.length; index++) {
                MarkerColour colour = PALETTE[index];
                if (colour.name().equalsIgnoreCase(word)) {
                    paletteColour = colour;
                }
            }
        }
        Integer parsed;
        if (paletteColour == null) {
            parsed = hex(word, 0);
        } else {
            parsed = paletteColour.colour();
        }
        return parsed;
    }

    public static boolean canParse(String typed) {
        String word = trimmedWord(typed);
        if (word == null) {
            return false;
        }
        int offset = hexOffset(word);
        if (offset != 0) {
            return hexValid(word, offset);
        }
        boolean parsed = false;
        if (hasPaletteNameLength(word.length())) {
            for (int index = 0; !parsed && index < PALETTE.length; index++) {
                MarkerColour colour = PALETTE[index];
                if (colour.name().equalsIgnoreCase(word)) {
                    parsed = true;
                }
            }
        }
        if (!parsed) {
            parsed = hexValid(word, 0);
        }
        return parsed;
    }

    // ARGB.
    public static int rawParse(String typed) {
        if (typed == null) {
            throw new IllegalArgumentException("not a colour: null");
        }
        String word = trimmedWord(typed);
        if (word == null) {
            throw new IllegalArgumentException("not a colour: \"\"");
        }
        int offset = hexOffset(word);
        if (offset != 0) {
            return rawHex(word, offset);
        }
        MarkerColour paletteColour = null;
        if (hasPaletteNameLength(word.length())) {
            for (int index = 0; paletteColour == null && index < PALETTE.length; index++) {
                MarkerColour colour = PALETTE[index];
                if (colour.name().equalsIgnoreCase(word)) {
                    paletteColour = colour;
                }
            }
        }
        int parsed = paletteColour == null ? rawHex(word, 0) : paletteColour.colour();
        return parsed;
    }

    public static String names() {
        return NAMES;
    }

    public static String spell(int argb) {
        String paletteWord = null;
        for (int index = 0; (paletteWord == null) && (index < PALETTE.length); index++) {
            MarkerColour colour = PALETTE[index];
            if (colour.colour() == argb) {
                paletteWord = LOWER[colour.ordinal()];
            }
        }
        String spelling;
        if (paletteWord != null) {
            spelling = paletteWord;
        } else if ((argb >>> ALPHA_SHIFT) == CHANNEL_MASK) {
            spelling = "#" + HEX[(argb >>> (NIBBLE_BITS * OPAQUE_RED_HIGH_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * OPAQUE_RED_LOW_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * OPAQUE_GREEN_HIGH_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * OPAQUE_GREEN_LOW_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * OPAQUE_BLUE_HIGH_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * OPAQUE_BLUE_LOW_NIBBLE_INDEX)) & NIBBLE_MASK];
        } else {
            spelling = "#" + HEX[(argb >>> (NIBBLE_BITS * ARGB_ALPHA_HIGH_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * ARGB_ALPHA_LOW_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * ARGB_RED_HIGH_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * ARGB_RED_LOW_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * ARGB_GREEN_HIGH_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * ARGB_GREEN_LOW_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * ARGB_BLUE_HIGH_NIBBLE_INDEX)) & NIBBLE_MASK]
                    + HEX[(argb >>> (NIBBLE_BITS * ARGB_BLUE_LOW_NIBBLE_INDEX)) & NIBBLE_MASK];
        }
        return spelling;
    }

    public static boolean isZeroAlphaHex(String typed) {
        String word = trimmedWord(typed);
        if (word == null) {
            return false;
        }
        int offset = hexOffset(word);
        if (word.length() - offset != ARGB_HEX_LENGTH) {
            return false;
        }
        long value = rawDigits(word, offset, ARGB_HEX_LENGTH);
        return value >= 0 && isRefusedZeroAlpha(ARGB_HEX_LENGTH, value);
    }

    private static String trimmedWord(String typed) {
        if (typed == null) {
            return null;
        }
        String trimmed = typed.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int hexOffset(String word) {
        return word.charAt(0) == '#' ? 1 : 0;
    }

    private static Integer hex(String word, int offset) {
        int length = word.length() - offset;
        if (length != SHORT_HEX_LENGTH && length != RGB_HEX_LENGTH && length != ARGB_HEX_LENGTH) {
            return null;
        }
        long value = rawDigits(word, offset, length);
        if (value < 0 || isRefusedZeroAlpha(length, value)) {
            return null;
        }
        return argbOf(length, value);
    }

    private static boolean hexValid(String word, int offset) {
        int length = word.length() - offset;
        if (length != SHORT_HEX_LENGTH && length != RGB_HEX_LENGTH && length != ARGB_HEX_LENGTH) {
            return false;
        }
        long value = rawDigits(word, offset, length);
        return value >= 0 && !isRefusedZeroAlpha(length, value);
    }

    private static int rawHex(String word, int offset) {
        int length = word.length() - offset;
        if (length != SHORT_HEX_LENGTH && length != RGB_HEX_LENGTH && length != ARGB_HEX_LENGTH) {
            throw new IllegalArgumentException("not a hex colour: " + word);
        }
        long value = rawDigits(word, offset, length);
        if (value < 0 || isRefusedZeroAlpha(length, value)) {
            throw new IllegalArgumentException("not a hex colour: " + word);
        }
        return argbOf(length, value);
    }

    private static boolean isRefusedZeroAlpha(int length, long value) {
        return length == ARGB_HEX_LENGTH && ((value >>> ALPHA_SHIFT) & CHANNEL_MASK) == 0;
    }

    private static long rawDigits(String word, int offset, int length) {
        long value = 0L;
        for (int i = 0; (i < length) && (value >= 0L); i++) {
            int nibble = nibbleOf(word.charAt(offset + i));
            value = nibble < 0 ? -1L : (value << NIBBLE_BITS) | nibble;
        }
        return value;
    }

    private static int argbOf(int length, long value) {
        int argb;
        switch (length) {
            case SHORT_HEX_LENGTH -> {
                // Doubles each hex digit.
                int blue = (int) (value & NIBBLE_MASK);
                int green = (int) ((value >> (NIBBLE_BITS * SHORT_HEX_GREEN_NIBBLE_INDEX)) & NIBBLE_MASK);
                int red = (int) ((value >> (NIBBLE_BITS * SHORT_HEX_RED_NIBBLE_INDEX)) & NIBBLE_MASK);
                argb = Landmark.OPAQUE_ALPHA
                        | ((red * SHORT_HEX_SPREAD) << (NIBBLE_BITS * EXPANDED_SHORT_RED_NIBBLE_INDEX))
                        | ((green * SHORT_HEX_SPREAD) << (NIBBLE_BITS * EXPANDED_SHORT_GREEN_NIBBLE_INDEX))
                        | (blue * SHORT_HEX_SPREAD);
            }
            case RGB_HEX_LENGTH -> {
                argb = Landmark.OPAQUE_ALPHA | (int) value;
            }
            case ARGB_HEX_LENGTH -> {
                argb = (int) value;
            }
            default -> throw new IllegalStateException("unreachable hex length: " + length);
        }
        return argb;
    }

    private static int nibbleOf(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        int folded = c | CASE_BIT;
        if (folded >= 'a' && folded <= 'f') {
            return folded - 'a' + LETTER_OFFSET;
        }
        return -1;
    }
}
