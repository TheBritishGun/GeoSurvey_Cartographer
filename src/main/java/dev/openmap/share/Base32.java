package dev.openmap.share;

// RFC 4648 base 32, upper case, without padding.
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final char[] ALPHABET_CHARS = ALPHABET.toCharArray();

    private static final byte[] ALPHABET_BYTES = ALPHABET.getBytes(
            java.nio.charset.StandardCharsets.ISO_8859_1);

    private static final byte[] EMPTY = new byte[0];

    private static final int BITS_PER_SYMBOL = 5;

    private static final int INVALID_SPARE_LENGTHS = 0x4A;

    private static final int LOWERCASE_ASCII_BIT = 0x20;

    private static final int ASCII_VALUE_LIMIT = 128;

    private static final int BITS_PER_BYTE = 8;

    private static final int BYTE_REMAINDER_MASK = 7;

    private static final int BITS_PER_BYTE_SHIFT = 3;

    private static final int ENCODE_LENGTH_ROUND_UP_BIAS = 4;

    private static final int BYTES_PER_GROUP = 5;

    private static final int SECOND_BYTE_OFFSET = 1;

    private static final int THIRD_BYTE_OFFSET = 2;

    private static final int FOURTH_BYTE_OFFSET = 3;

    private static final int LAST_BYTE_OFFSET = 4;

    private static final int FIRST_BYTE_SHIFT = 32;

    private static final int SECOND_BYTE_SHIFT = 24;

    private static final int THIRD_BYTE_SHIFT = 16;

    private static final int FOURTH_BYTE_SHIFT = 8;

    private static final int SYMBOLS_PER_GROUP = 8;

    private static final int SECOND_SYMBOL_OFFSET = 1;

    private static final int THIRD_SYMBOL_OFFSET = 2;

    private static final int FOURTH_SYMBOL_OFFSET = 3;

    private static final int FIFTH_SYMBOL_OFFSET = 4;

    private static final int SIXTH_SYMBOL_OFFSET = 5;

    private static final int SEVENTH_SYMBOL_OFFSET = 6;

    private static final int LAST_SYMBOL_OFFSET = 7;

    private static final int SYMBOLS_PER_GROUP_MASK = 7;

    private static final int FIRST_SYMBOL_SHIFT = 35;

    private static final int SECOND_SYMBOL_SHIFT = 30;

    private static final int THIRD_SYMBOL_SHIFT = 25;

    private static final int FOURTH_SYMBOL_SHIFT = 20;

    private static final int FIFTH_SYMBOL_SHIFT = 15;

    private static final int SIXTH_SYMBOL_SHIFT = 10;

    private static final int SEVENTH_SYMBOL_SHIFT = 5;

    private static final int SYMBOL_VALUE_MASK = 0x1F;

    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    private static final byte[] VALUE = new byte[ASCII_VALUE_LIMIT];

    static {
        java.util.Arrays.fill(VALUE, (byte) -1);
        for (int i = 0; i < ALPHABET_CHARS.length; i++) {
            char c = ALPHABET_CHARS[i];
            VALUE[c] = (byte) i;
            if (c >= 'A') {
                VALUE[c | LOWERCASE_ASCII_BIT] = (byte) i;
            }
        }
    }

    private Base32() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        byte[] out = new byte[(data.length * BITS_PER_BYTE + ENCODE_LENGTH_ROUND_UP_BIAS)
                / BITS_PER_SYMBOL];
        int buffer = 0;
        int bits = 0;
        int at = 0;
        int i = 0;
        while (i + LAST_BYTE_OFFSET < data.length) {
            long group = ((long) (data[i] & UNSIGNED_BYTE_MASK) << FIRST_BYTE_SHIFT)
                    | ((long) (data[i + SECOND_BYTE_OFFSET] & UNSIGNED_BYTE_MASK) << SECOND_BYTE_SHIFT)
                    | ((long) (data[i + THIRD_BYTE_OFFSET] & UNSIGNED_BYTE_MASK) << THIRD_BYTE_SHIFT)
                    | ((long) (data[i + FOURTH_BYTE_OFFSET] & UNSIGNED_BYTE_MASK) << FOURTH_BYTE_SHIFT)
                    | (data[i + LAST_BYTE_OFFSET] & UNSIGNED_BYTE_MASK);
            out[at++] = ALPHABET_BYTES[(int) (group >>> FIRST_SYMBOL_SHIFT) & SYMBOL_VALUE_MASK];
            out[at++] = ALPHABET_BYTES[(int) (group >>> SECOND_SYMBOL_SHIFT) & SYMBOL_VALUE_MASK];
            out[at++] = ALPHABET_BYTES[(int) (group >>> THIRD_SYMBOL_SHIFT) & SYMBOL_VALUE_MASK];
            out[at++] = ALPHABET_BYTES[(int) (group >>> FOURTH_SYMBOL_SHIFT) & SYMBOL_VALUE_MASK];
            out[at++] = ALPHABET_BYTES[(int) (group >>> FIFTH_SYMBOL_SHIFT) & SYMBOL_VALUE_MASK];
            out[at++] = ALPHABET_BYTES[(int) (group >>> SIXTH_SYMBOL_SHIFT) & SYMBOL_VALUE_MASK];
            out[at++] = ALPHABET_BYTES[(int) (group >>> SEVENTH_SYMBOL_SHIFT) & SYMBOL_VALUE_MASK];
            out[at++] = ALPHABET_BYTES[(int) group & SYMBOL_VALUE_MASK];
            i += BYTES_PER_GROUP;
        }
        while (i < data.length) {
            byte b = data[i];
            buffer = (buffer << BITS_PER_BYTE) | (b & UNSIGNED_BYTE_MASK);
            bits += BITS_PER_BYTE;
            while (bits >= BITS_PER_SYMBOL) {
                out[at++] = ALPHABET_BYTES[(buffer >>> (bits - BITS_PER_SYMBOL)) & SYMBOL_VALUE_MASK];
                bits -= BITS_PER_SYMBOL;
            }
            i++;
        }
        if (bits > 0) {
            out[at++] = ALPHABET_BYTES[(buffer << (BITS_PER_SYMBOL - bits)) & SYMBOL_VALUE_MASK];
        }
        return new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // The bytes, or null if this cannot read the text.
    public static byte[] decode(String text) {
        if (text == null) {
            return null;
        }
        int length = text.length();
        if (length == 0) {
            return EMPTY;
        }

        byte[] result = null;
        int spare = length & SYMBOLS_PER_GROUP_MASK;
        boolean valid = ((INVALID_SPARE_LENGTHS >>> spare) & 1) == 0;
        int pad = (spare * BITS_PER_SYMBOL) & BYTE_REMAINDER_MASK;
        if (valid && pad != 0) {
            char last = text.charAt(length - 1);
            int lastValue = last < ASCII_VALUE_LIMIT ? VALUE[last] : -1;
            valid = lastValue >= 0 && (lastValue & ((1 << pad) - 1)) == 0;
        }
        if (!valid) {
            result = null;
        } else {
            byte[] out = new byte[(length * BITS_PER_SYMBOL) >> BITS_PER_BYTE_SHIFT];
            int buffer = 0;
            int bits = 0;
            int at = 0;
            int i = 0;
            while (valid && i + LAST_SYMBOL_OFFSET < length) {
                char c0 = text.charAt(i);
                char c1 = text.charAt(i + SECOND_SYMBOL_OFFSET);
                char c2 = text.charAt(i + THIRD_SYMBOL_OFFSET);
                char c3 = text.charAt(i + FOURTH_SYMBOL_OFFSET);
                char c4 = text.charAt(i + FIFTH_SYMBOL_OFFSET);
                char c5 = text.charAt(i + SIXTH_SYMBOL_OFFSET);
                char c6 = text.charAt(i + SEVENTH_SYMBOL_OFFSET);
                char c7 = text.charAt(i + LAST_SYMBOL_OFFSET);
                int v0 = c0 < ASCII_VALUE_LIMIT ? VALUE[c0] : -1;
                int v1 = c1 < ASCII_VALUE_LIMIT ? VALUE[c1] : -1;
                int v2 = c2 < ASCII_VALUE_LIMIT ? VALUE[c2] : -1;
                int v3 = c3 < ASCII_VALUE_LIMIT ? VALUE[c3] : -1;
                int v4 = c4 < ASCII_VALUE_LIMIT ? VALUE[c4] : -1;
                int v5 = c5 < ASCII_VALUE_LIMIT ? VALUE[c5] : -1;
                int v6 = c6 < ASCII_VALUE_LIMIT ? VALUE[c6] : -1;
                int v7 = c7 < ASCII_VALUE_LIMIT ? VALUE[c7] : -1;
                valid = (v0 | v1 | v2 | v3 | v4 | v5 | v6 | v7) >= 0;
                if (valid) {
                    long group = ((long) v0 << FIRST_SYMBOL_SHIFT) | ((long) v1 << SECOND_SYMBOL_SHIFT)
                            | ((long) v2 << THIRD_SYMBOL_SHIFT) | ((long) v3 << FOURTH_SYMBOL_SHIFT)
                            | ((long) v4 << FIFTH_SYMBOL_SHIFT) | ((long) v5 << SIXTH_SYMBOL_SHIFT)
                            | ((long) v6 << SEVENTH_SYMBOL_SHIFT) | v7;
                    out[at++] = (byte) (group >>> FIRST_BYTE_SHIFT);
                    out[at++] = (byte) (group >>> SECOND_BYTE_SHIFT);
                    out[at++] = (byte) (group >>> THIRD_BYTE_SHIFT);
                    out[at++] = (byte) (group >>> FOURTH_BYTE_SHIFT);
                    out[at++] = (byte) group;
                    i += SYMBOLS_PER_GROUP;
                }
            }
            while (valid && i < length) {
                char c = text.charAt(i);
                int value = c < ASCII_VALUE_LIMIT ? VALUE[c] : -1;
                valid = value >= 0;
                if (valid) {
                    buffer = (buffer << BITS_PER_SYMBOL) | value;
                    bits += BITS_PER_SYMBOL;
                    if (bits >= BITS_PER_BYTE) {
                        out[at++] = (byte) (buffer >>> (bits - BITS_PER_BYTE));
                        bits -= BITS_PER_BYTE;
                    }
                    i++;
                }
            }
            if (valid && bits > 0 && (buffer & ((1 << bits) - 1)) != 0) {
                valid = false;
            }
            if (valid) {
                result = out;
            }
        }
        return result;
    }
}
