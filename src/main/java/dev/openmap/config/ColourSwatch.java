package dev.openmap.config;

import java.nio.charset.StandardCharsets;

public record ColourSwatch(Preset preset, int custom) {

    private static final int AMBER_ARGB = 0xDDE0A33A;
    private static final int RED_ARGB = 0xDDD62A2A;
    private static final int TEAL_ARGB = 0xDD46DCC6;
    private static final int CLEAR_ARGB = 0x00000000;
    private static final int BLACK_ARGB = 0xDD1A1A1A;
    private static final int WHITE_ARGB = 0xDDEAF6F8;
    private static final int GREY_ARGB = 0xDD808080;
    private static final int RED_COMPONENT_OFFSET = 1;
    private static final int GREEN_COMPONENT_OFFSET = 3;
    private static final int BLUE_COMPONENT_OFFSET = 5;
    private static final int ALPHA_COMPONENT_OFFSET = 7;
    private static final int RED_COMPONENT_SHIFT = 16;
    private static final int GREEN_COMPONENT_SHIFT = 8;
    private static final int ALPHA_SHIFT = 24;
    private static final int COMPONENT_BYTE_MASK = 0xFF;
    private static final int BITS_PER_HEX_DIGIT = 4;
    private static final int HEX_NIBBLE_MASK = 0x0F;
    private static final int SHORT_HEX_DIGITS = 6;
    private static final int FULL_HEX_DIGITS = 8;
    private static final int ASCII_CASE_BIT = 0x20;
    private static final int FIRST_LETTER_HEX_VALUE = 10;
    private static final int HEX_RADIX = 16;
    private static final int ARGB_ROTATE_BITS = 8;
    private static final int ALPHA_BYTE_MASK = 0xFF;
    private static final int OPAQUE_ALPHA = 0xFF;

    public enum Preset {

        AMBER(AMBER_ARGB, "Amber"),

        RED(RED_ARGB, "Red"),

        TEAL(TEAL_ARGB, "Teal"),

        CLEAR(CLEAR_ARGB, "Clear"),

        BLACK(BLACK_ARGB, "Black"),

        WHITE(WHITE_ARGB, "White"),

        GREY(GREY_ARGB, "Grey"),

        // Takes the caller's shipped colour.
        DEFAULT(0, "Default"),

        // The colour is in the record's custom field.
        CUSTOM(0, "Custom");

        private final int argb;
        private final String label;
        private final int nameLength;

        Preset(int argb, String label) {
            this.argb = argb;
            this.label = label;
            this.nameLength = name().length();
        }

        // The text the config file stores.
        public String label() {
            return label;
        }
    }

    public static final ColourSwatch DEFAULT = new ColourSwatch(Preset.DEFAULT, 0);

    public ColourSwatch {
        preset = preset == null ? Preset.DEFAULT : preset;
        custom = preset == Preset.CUSTOM ? custom : 0;
    }

    public static ColourSwatch of(Preset preset) {
        return INTERNED[(preset == null ? Preset.DEFAULT : preset).ordinal()];
    }

    public static ColourSwatch custom(int argb) {
        return new ColourSwatch(Preset.CUSTOM, argb);
    }

    // The packed ARGB to draw.
    public int resolve(int shipped) {
        if (preset == Preset.DEFAULT) {
            return shipped;
        }
        if (preset == Preset.CUSTOM) {
            return custom;
        }
        return preset.argb;
    }

    private static final int SWATCH_TEXT_LENGTH = 9;

    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.ISO_8859_1);

    // The preset's label, or #RRGGBBAA.
    public String format() {
        if (preset != Preset.CUSTOM) {
            return preset.label();
        }
        byte[] out = new byte[SWATCH_TEXT_LENGTH];
        writeCustom(out, custom);
        return new String(out, StandardCharsets.ISO_8859_1);
    }

    // #RRGGBBAA for a packed ARGB.
    public static String formatCustom(int argb) {
        byte[] out = new byte[SWATCH_TEXT_LENGTH];
        writeCustom(out, argb);
        return new String(out, StandardCharsets.ISO_8859_1);
    }

    private static void writeCustom(byte[] out, int argb) {
        out[0] = '#';
        hex(out, RED_COMPONENT_OFFSET, argb >> RED_COMPONENT_SHIFT);
        hex(out, GREEN_COMPONENT_OFFSET, argb >> GREEN_COMPONENT_SHIFT);
        hex(out, BLUE_COMPONENT_OFFSET, argb);
        hex(out, ALPHA_COMPONENT_OFFSET, argb >>> ALPHA_SHIFT);
    }

    private static void hex(byte[] out, int at, int component) {
        int b = component & COMPONENT_BYTE_MASK;
        out[at] = HEX[b >>> BITS_PER_HEX_DIGIT];
        out[at + 1] = HEX[b & HEX_NIBBLE_MASK];
    }

    private static final Preset[] VALUES = Preset.values();
    private static final Preset[] PARSEABLE_PRESETS = parseablePresets();
    private static final ColourSwatch[] INTERNED = interned();

    private static Preset[] parseablePresets() {
        Preset[] parseable = new Preset[VALUES.length - 1];
        int at = 0;
        for (Preset preset : VALUES) {
            if (preset != Preset.CUSTOM) {
                parseable[at++] = preset;
            }
        }
        return parseable;
    }

    private static ColourSwatch[] interned() {
        ColourSwatch[] interned = new ColourSwatch[VALUES.length];
        for (Preset preset : VALUES) {
            interned[preset.ordinal()] = preset == Preset.DEFAULT
                    ? DEFAULT
                    : new ColourSwatch(preset, 0);
        }
        return interned;
    }

    // A preset name or 6/8 hex digits; null if unknown. alphaWhenAbsent fills a missing alpha (0 becomes FF).
    public static ColourSwatch parse(String text, int alphaWhenAbsent) {
        if (text == null) {
            return null;
        }
        ColourSwatch settled = null;
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) <= ' ') {
            end--;
        }
        if (start < end) {
            boolean hashed = text.charAt(start) == '#';
            if (hashed) {
                start++;
            }
            int n = end - start;
            if (!hashed) {
                for (Preset candidate : PARSEABLE_PRESETS) {
                    if (settled == null && n == candidate.nameLength
                            && text.regionMatches(true, start, candidate.name(), 0, n)) {
                        settled = of(candidate);
                    }
                }
            }
            if (settled == null && (n == SHORT_HEX_DIGITS || n == FULL_HEX_DIGITS)) {
                int all = 0;
                boolean rejected = false;
                for (int i = 0; i < n; i++) {
                    char c = text.charAt(start + i);
                    int nibble;
                    if (c >= '0' && c <= '9') {
                        nibble = c - '0';
                    } else {
                        int lower = c | ASCII_CASE_BIT;
                        nibble = lower >= 'a' && lower <= 'f'
                                ? lower - 'a' + FIRST_LETTER_HEX_VALUE
                                : Character.digit(c, HEX_RADIX);
                    }
                    if (nibble < 0) {
                        rejected = true;
                        break;
                    }
                    all = (all << BITS_PER_HEX_DIGIT) | nibble;
                }
                if (!rejected) {
                    if (n == FULL_HEX_DIGITS) {
                        settled = custom(Integer.rotateRight(all, ARGB_ROTATE_BITS));
                    } else {
                        int kept = alphaWhenAbsent & ALPHA_BYTE_MASK;
                        int alpha = kept == 0 ? OPAQUE_ALPHA : kept;
                        settled = custom((alpha << ALPHA_SHIFT) | all);
                    }
                }
            }
        }
        return settled;
    }
}
