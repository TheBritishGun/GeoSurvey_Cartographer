package dev.openmap.share;

final class WireWrite {

    private static final int MODIFIED_UTF_MIN = 0x0001;

    private static final int MODIFIED_UTF_ONE_BYTE_MAX = 0x007F;

    private static final int MODIFIED_UTF_TWO_BYTE_MAX = 0x07FF;

    private static final int MODIFIED_UTF_TWO_BYTE_LENGTH = 2;

    private static final int UTF_LENGTH_PREFIX_BYTES = 2;

    private static final int UTF_LENGTH_HIGH_BYTE_SHIFT = 8;

    private static final int UTF_TWO_BYTE_PREFIX = 0xC0;

    private static final int UTF_TWO_BYTE_LEADING_SHIFT = 6;

    private static final int UTF_CONTINUATION_PREFIX = 0x80;

    private static final int UTF_CONTINUATION_PAYLOAD_MASK = 0x3F;

    private static final int UTF_THREE_BYTE_PREFIX = 0xE0;

    private static final int UTF_THREE_BYTE_LEADING_SHIFT = 12;

    private static final int UTF_THREE_BYTE_MIDDLE_SHIFT = 6;

    private WireWrite() {
    }

    static int modifiedUtfLength(String value) {
        int length = 0;
        for (int index = 0; index < value.length(); index++) {
            char each = value.charAt(index);
            if (each >= MODIFIED_UTF_MIN && each <= MODIFIED_UTF_ONE_BYTE_MAX) {
                length++;
            } else if (each <= MODIFIED_UTF_TWO_BYTE_MAX) {
                length += MODIFIED_UTF_TWO_BYTE_LENGTH;
            } else {
                length += Presence.MAX_UTF8_BYTES_PER_CHAR;
            }
        }
        return length;
    }

    static int writeUtf(byte[] message, int at, String value, int length) {
        at = putUtfLength(message, at, length);
        return encodeChars(message, at, value);
    }

    static int putUtf(byte[] message, int at, String value) {
        int lengthAt = at;
        int start = at + UTF_LENGTH_PREFIX_BYTES;
        int end = encodeChars(message, start, value);
        int length = end - start;
        putUtfLength(message, lengthAt, length);
        return end;
    }

    static int putUtfLength(byte[] message, int at, int length) {
        message[at] = (byte) (length >>> UTF_LENGTH_HIGH_BYTE_SHIFT);
        message[at + 1] = (byte) length;
        return at + UTF_LENGTH_PREFIX_BYTES;
    }

    private static int encodeChars(byte[] message, int at, String value) {
        for (int index = 0; index < value.length(); index++) {
            char each = value.charAt(index);
            if (each >= MODIFIED_UTF_MIN && each <= MODIFIED_UTF_ONE_BYTE_MAX) {
                message[at++] = (byte) each;
            } else if (each <= MODIFIED_UTF_TWO_BYTE_MAX) {
                message[at++] = (byte) (UTF_TWO_BYTE_PREFIX
                        | (each >> UTF_TWO_BYTE_LEADING_SHIFT));
                message[at++] = (byte) (UTF_CONTINUATION_PREFIX
                        | (each & UTF_CONTINUATION_PAYLOAD_MASK));
            } else {
                message[at++] = (byte) (UTF_THREE_BYTE_PREFIX
                        | (each >> UTF_THREE_BYTE_LEADING_SHIFT));
                message[at++] = (byte) (UTF_CONTINUATION_PREFIX
                        | ((each >> UTF_THREE_BYTE_MIDDLE_SHIFT)
                        & UTF_CONTINUATION_PAYLOAD_MASK));
                message[at++] = (byte) (UTF_CONTINUATION_PREFIX
                        | (each & UTF_CONTINUATION_PAYLOAD_MASK));
            }
        }
        return at;
    }

    static int putInt(byte[] message, int at, int value) {
        for (int shift = Integer.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            message[at++] = (byte) (value >>> shift);
        }
        return at;
    }

    static int putLong(byte[] message, int at, long value) {
        for (int shift = Long.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            message[at++] = (byte) (value >>> shift);
        }
        return at;
    }
}
