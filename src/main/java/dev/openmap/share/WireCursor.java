package dev.openmap.share;

import java.io.EOFException;
import java.io.IOException;
import java.io.UTFDataFormatException;

final class WireCursor {

    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    private static final int BITS_PER_BYTE = 8;

    private static final int INT_HIGH_BYTE_SHIFT = 24;

    private static final int INT_MIDDLE_HIGH_BYTE_SHIFT = 16;

    private static final int LONG_HIGHEST_BYTE_SHIFT = 56;

    private static final int LONG_SECOND_BYTE_SHIFT = 48;

    private static final int LONG_THIRD_BYTE_SHIFT = 40;

    private static final int LONG_FOURTH_BYTE_SHIFT = 32;

    private static final int UTF_LEADING_NIBBLE_SHIFT = 4;

    private static final int UTF_ASCII_NIBBLE_TWO = 2;

    private static final int UTF_ASCII_NIBBLE_THREE = 3;

    private static final int UTF_ASCII_NIBBLE_FOUR = 4;

    private static final int UTF_ASCII_NIBBLE_FIVE = 5;

    private static final int UTF_ASCII_NIBBLE_SIX = 6;

    private static final int UTF_CONTINUATION_PAYLOAD_BITS = 6;

    private static final int UTF_ASCII_NIBBLE_SEVEN = 7;

    private static final int UTF_TWO_BYTE_NIBBLE_LOW = 12;

    private static final int UTF_THREE_BYTE_LEAD_SHIFT = 12;

    private static final int UTF_TWO_BYTE_NIBBLE_HIGH = 13;

    private static final int UTF_THREE_BYTE_NIBBLE = 14;

    private static final int UTF_CONTINUATION_MASK = 0xC0;

    private static final int UTF_CONTINUATION_VALUE = 0x80;

    private static final int UTF_TWO_BYTE_PAYLOAD_MASK = 0x1F;

    private static final int UTF_THREE_BYTE_PAYLOAD_MASK = 0x0F;

    private static final int UTF_CONTINUATION_PAYLOAD_MASK = 0x3F;

    private final byte[] message;
    private int at = 0;

    WireCursor(byte[] message) {
        this.message = message;
    }

    int remaining() {
        return message.length - at;
    }

    int readUnsignedByte() throws EOFException {
        if (at == message.length) {
            throw new EOFException();
        }
        return message[at++] & UNSIGNED_BYTE_MASK;
    }

    int readInt() throws EOFException {
        return (readUnsignedByte() << INT_HIGH_BYTE_SHIFT)
                | (readUnsignedByte() << INT_MIDDLE_HIGH_BYTE_SHIFT)
                | (readUnsignedByte() << BITS_PER_BYTE) | readUnsignedByte();
    }

    long readLong() throws EOFException {
        return ((long) readUnsignedByte() << LONG_HIGHEST_BYTE_SHIFT)
                | ((long) readUnsignedByte() << LONG_SECOND_BYTE_SHIFT)
                | ((long) readUnsignedByte() << LONG_THIRD_BYTE_SHIFT)
                | ((long) readUnsignedByte() << LONG_FOURTH_BYTE_SHIFT)
                | ((long) readUnsignedByte() << INT_HIGH_BYTE_SHIFT)
                | ((long) readUnsignedByte() << INT_MIDDLE_HIGH_BYTE_SHIFT)
                | ((long) readUnsignedByte() << BITS_PER_BYTE) | readUnsignedByte();
    }

    boolean readBoolean() throws EOFException {
        return readUnsignedByte() != 0;
    }

    byte[] readBytes(int maximum, String what) throws IOException {
        int length = readInt();
        if (length <= 0 || length > maximum) {
            throw new IOException(what + " claims " + length + " bytes");
        }
        if (length > remaining()) {
            throw new IOException("message ended inside the " + what);
        }
        byte[] value = new byte[length];
        System.arraycopy(message, at, value, 0, length);
        at += length;
        return value;
    }

    String readUtf(String what, int maximum) throws IOException {
        int claimed = (readUnsignedByte() << BITS_PER_BYTE) | readUnsignedByte();
        int left = remaining();
        if (claimed > left) {
            throw new IOException(what + " claims " + claimed + " bytes and only "
                    + left + " are left in the message");
        }
        if (claimed > maximum) {
            throw new IOException(what + " claims " + claimed + " bytes, over the "
                    + maximum + "-byte ceiling");
        }
        int end = at + claimed;
        int ascii = at;
        while (ascii < end && message[ascii] >= 0) {
            ascii++;
        }
        String value;
        if (ascii == end) {
            value = new String(message, at, claimed,
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            at = end;
        } else {
            char[] decoded = new char[claimed];
            int count = 0;
            while (at < end) {
                int first = readUnsignedByte();
                switch (first >> UTF_LEADING_NIBBLE_SHIFT) {
                    case 0, 1, UTF_ASCII_NIBBLE_TWO, UTF_ASCII_NIBBLE_THREE,
                            UTF_ASCII_NIBBLE_FOUR, UTF_ASCII_NIBBLE_FIVE,
                            UTF_ASCII_NIBBLE_SIX, UTF_ASCII_NIBBLE_SEVEN ->
                            decoded[count++] = (char) first;
                    case UTF_TWO_BYTE_NIBBLE_LOW, UTF_TWO_BYTE_NIBBLE_HIGH -> {
                        if (at >= end) {
                            throw malformed(what);
                        }
                        int second = readUnsignedByte();
                        if ((second & UTF_CONTINUATION_MASK) != UTF_CONTINUATION_VALUE) {
                            throw malformed(what);
                        }
                        decoded[count++] = (char) (((first & UTF_TWO_BYTE_PAYLOAD_MASK)
                                << UTF_CONTINUATION_PAYLOAD_BITS)
                                | (second & UTF_CONTINUATION_PAYLOAD_MASK));
                    }
                    case UTF_THREE_BYTE_NIBBLE -> {
                        if (at + 1 >= end) {
                            throw malformed(what);
                        }
                        int second = readUnsignedByte();
                        int third = readUnsignedByte();
                        if ((second & UTF_CONTINUATION_MASK) != UTF_CONTINUATION_VALUE
                                || (third & UTF_CONTINUATION_MASK) != UTF_CONTINUATION_VALUE) {
                            throw malformed(what);
                        }
                        decoded[count++] = (char) (((first & UTF_THREE_BYTE_PAYLOAD_MASK)
                                << UTF_THREE_BYTE_LEAD_SHIFT)
                                | ((second & UTF_CONTINUATION_PAYLOAD_MASK)
                                << UTF_CONTINUATION_PAYLOAD_BITS)
                                | (third & UTF_CONTINUATION_PAYLOAD_MASK));
                    }
                    default -> throw malformed(what);
                }
            }
            value = new String(decoded, 0, count);
        }
        return value;
    }

    private static UTFDataFormatException malformed(String what) {
        return new UTFDataFormatException(what + " has malformed modified UTF-8");
    }
}
