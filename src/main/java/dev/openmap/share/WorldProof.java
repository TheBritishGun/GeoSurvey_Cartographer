package dev.openmap.share;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

public record WorldProof(String server, String region, String by, String nonce,
                         String response, long sent) {

    // CRF1.
    public static final int MAGIC = 0x43524631;

    // Longest name or identifier.
    public static final int MAX_NAME = Presence.MAX_NAME;

    // Hex characters a signature carries.
    public static final int SIGNATURE_CHARS = 16;

    public static final int MIN_NONCE_CHARS = 16;

    // Longest message accepted.
    public static final int MAX_BYTES = 2048;

    private static final int FRAMING_BYTES =
            Integer.BYTES + 5 * Short.BYTES + Long.BYTES;

    private static final int UUID_CHARS = 36;

    private static final int UUID_SEPARATOR_ONE = 8;

    private static final int UUID_SEPARATOR_TWO = 13;

    private static final int UUID_SEPARATOR_THREE = 18;

    private static final int UUID_SEPARATOR_FOUR = 23;

    private static final int UUID_GROUP_TWO_START = 9;

    private static final int UUID_GROUP_THREE_START = 14;

    private static final int UUID_GROUP_FOUR_START = 19;

    private static final int UUID_GROUP_FIVE_START = 24;

    private static final int SHA_256_BYTES = 32;

    private static final int HEX_DIGIT_BITS = 4;

    private static final int HEX_CHARS_PER_BYTE = 2;

    private static final int LOW_FOUR_BITS = 0x0F;

    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    private static final int NONCE_CHARS = Long.SIZE / HEX_DIGIT_BITS;

    private static final int TEXT_CHUNK = 64;

    private static final int WIDEST_UTF8 = 4;

    private static final int UTF8_SINGLE_BYTE_LIMIT = 0x80;

    private static final int UTF8_THREE_BYTE_LIMIT = 0x800;

    private static final int UTF8_TWO_BYTE_PREFIX = 0xC0;

    private static final int UTF8_THREE_BYTE_PREFIX = 0xE0;

    private static final int UTF8_FOUR_BYTE_PREFIX = 0xF0;

    private static final int UTF8_CONTINUATION_PREFIX = 0x80;

    private static final int UTF8_CONTINUATION_SHIFT = 6;

    private static final int UTF8_THREE_BYTE_SHIFT = 12;

    private static final int UTF8_FOUR_BYTE_SHIFT = 18;

    private static final int UTF8_LOW_SIX_BITS = 0x3F;

    private static final int SURROGATE_PAIR_LENGTH = 2;

    public WorldProof {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(response, "response");
        bounded(server, "server");
        bounded(region, "region");
        bounded(by, "contributor id");
        boundedNonce(nonce);
        boundedResponse(response);
    }

    private static final class ShaState {

        private final java.security.MessageDigest digest;

        private final byte[] scratch = new byte[SHA_256_BYTES];

        private final byte[] hex = new byte[SIGNATURE_CHARS];

        private final byte[] text = new byte[TEXT_CHUNK];

        private ShaState(java.security.MessageDigest digest) {
            this.digest = digest;
        }
    }

    private static final ThreadLocal<ShaState> SHA =
            ThreadLocal.withInitial(WorldProof::newShaState);

    private static ShaState newShaState() {
        ShaState state;
        try {
            state = new ShaState(java.security.MessageDigest.getInstance("SHA-256"));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "no SHA-256", impossible);
        }
        return state;
    }

    private static final byte[] HEX_DIGITS =
            "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    // The response to send, not the signature.
    public static String responseFor(String signature, String nonce) {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(nonce, "nonce");
        ShaState state = SHA.get();
        respond(state, signature, nonce);
        return new String(state.hex, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public byte[] encode() throws IOException {
        int serverBytes = WireWrite.modifiedUtfLength(server);
        int regionBytes = WireWrite.modifiedUtfLength(region);
        int byBytes = WireWrite.modifiedUtfLength(by);
        int nonceBytes = WireWrite.modifiedUtfLength(nonce);
        int responseBytes = WireWrite.modifiedUtfLength(response);
        int size = FRAMING_BYTES + serverBytes + regionBytes + byBytes + nonceBytes
                + responseBytes;
        ceiling(size);
        byte[] message = new byte[size];
        int at = WireWrite.putInt(message, 0, MAGIC);
        at = WireWrite.writeUtf(message, at, server, serverBytes);
        at = WireWrite.writeUtf(message, at, region, regionBytes);
        at = WireWrite.writeUtf(message, at, by, byBytes);
        at = WireWrite.writeUtf(message, at, nonce, nonceBytes);
        at = WireWrite.writeUtf(message, at, response, responseBytes);
        WireWrite.putLong(message, at, sent);
        return message;
    }

    public static byte[] encodeInto(byte[] reuse, String server, String region, UUID by,
            long nonce, String signature, long sent) throws IOException {
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(signature, "signature");
        ShaState state = SHA.get();
        respondTo(state, signature, nonce);
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(region, "region");
        bounded(server, "server");
        bounded(region, "region");
        int serverBytes = WireWrite.modifiedUtfLength(server);
        int regionBytes = WireWrite.modifiedUtfLength(region);
        int size = FRAMING_BYTES + serverBytes + regionBytes + UUID_CHARS + NONCE_CHARS
                + SIGNATURE_CHARS;
        ceiling(size);
        byte[] message = reuse != null && reuse.length == size ? reuse : new byte[size];
        int at = WireWrite.putInt(message, 0, MAGIC);
        at = WireWrite.writeUtf(message, at, server, serverBytes);
        at = WireWrite.writeUtf(message, at, region, regionBytes);
        at = putUuid(message, at, by);
        at = putNonce(message, at, nonce);
        at = putResponse(message, at, state.hex);
        WireWrite.putLong(message, at, sent);
        return message;
    }

    public static WorldProof decode(byte[] message) throws IOException {
        Objects.requireNonNull(message, "message");
        if (message.length > MAX_BYTES) {
            throw new IOException("proof is " + message.length + " bytes");
        }
        if (message.length < Integer.BYTES) {
            throw new IOException("not a world proof");
        }
        WorldProof proof;
        try {
            WireCursor body = new WireCursor(message);
            if (body.readInt() != MAGIC) {
                throw new IOException("not a world proof");
            }
            String server = body.readUtf("server", MAX_BYTES);
            bounded(server, "server");
            String region = body.readUtf("region", MAX_BYTES);
            bounded(region, "region");
            String by = body.readUtf("contributor id", MAX_BYTES);
            bounded(by, "contributor id");
            String nonce = body.readUtf("nonce", MAX_BYTES);
            boundedNonce(nonce);
            String response = body.readUtf("response", MAX_BYTES);
            boundedResponse(response);
            long sent = body.readLong();
            int left = body.remaining();
            if (left != 0) {
                throw new IOException(left
                        + " extra bytes"
                        + " after the proof.");
            }
            proof = new WorldProof(server, region, by, nonce, response, sent);
        } catch (IllegalArgumentException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        return proof;
    }

    public static String accountText(UUID player) {
        byte[] text = new byte[UUID_CHARS];
        putUuidText(text, 0, player);
        return new String(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int putUuid(byte[] message, int at, UUID id) {
        int next = WireWrite.putUtfLength(message, at, UUID_CHARS);
        return putUuidText(message, next, id);
    }

    private static int putUuidText(byte[] message, int at, UUID id) {
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        int next = putDigits(message, at, most >>> Integer.SIZE, Integer.SIZE);
        message[next] = (byte) '-';
        next = putDigits(message, next + 1, most >>> Short.SIZE, Short.SIZE);
        message[next] = (byte) '-';
        next = putDigits(message, next + 1, most, Short.SIZE);
        message[next] = (byte) '-';
        next = putDigits(message, next + 1, least >>> (Long.SIZE - Short.SIZE), Short.SIZE);
        message[next] = (byte) '-';
        return putDigits(message, next + 1, least, Long.SIZE - Short.SIZE);
    }

    static boolean matchesAccountText(UUID player, String text) {
        if (text.length() != UUID_CHARS) {
            return false;
        }
        if (text.charAt(UUID_SEPARATOR_ONE) != '-'
                || text.charAt(UUID_SEPARATOR_TWO) != '-'
                || text.charAt(UUID_SEPARATOR_THREE) != '-'
                || text.charAt(UUID_SEPARATOR_FOUR) != '-') {
            return false;
        }
        long most = player.getMostSignificantBits();
        long least = player.getLeastSignificantBits();
        return matchesDigits(text, 0, most >>> Integer.SIZE, Integer.SIZE)
                && matchesDigits(text, UUID_GROUP_TWO_START, most >>> Short.SIZE, Short.SIZE)
                && matchesDigits(text, UUID_GROUP_THREE_START, most, Short.SIZE)
                && matchesDigits(text, UUID_GROUP_FOUR_START,
                        least >>> (Long.SIZE - Short.SIZE), Short.SIZE)
                && matchesDigits(text, UUID_GROUP_FIVE_START, least, Long.SIZE - Short.SIZE);
    }

    private static boolean matchesDigits(String text, int at, long value, int bits) {
        boolean same = true;
        for (int shift = bits - HEX_DIGIT_BITS; same && shift >= 0; shift -= HEX_DIGIT_BITS) {
            if (text.charAt(at++) != (char) HEX_DIGITS[(int) (value >>> shift) & LOW_FOUR_BITS]) {
                same = false;
            }
        }
        return same;
    }

    private static int putDigits(byte[] message, int at, long value, int bits) {
        int next = at;
        for (int shift = bits - HEX_DIGIT_BITS; shift >= 0; shift -= HEX_DIGIT_BITS) {
            message[next++] = HEX_DIGITS[(int) (value >>> shift) & LOW_FOUR_BITS];
        }
        return next;
    }

    private static int putResponse(byte[] message, int at, byte[] hex) {
        int next = WireWrite.putUtfLength(message, at, SIGNATURE_CHARS);
        System.arraycopy(hex, 0, message, next, SIGNATURE_CHARS);
        return next + SIGNATURE_CHARS;
    }

    private static int putNonce(byte[] message, int at, long nonce) {
        int next = WireWrite.putUtfLength(message, at, NONCE_CHARS);
        return putDigits(message, next, nonce, Long.SIZE);
    }

    private static void respond(ShaState state, String signature, String nonce) {
        java.security.MessageDigest sha = state.digest;
        feed(state, signature);
        sha.update((byte) ':');
        feed(state, nonce);
        digested(state);
    }

    private static void respondTo(ShaState state, String signature, long nonce) {
        java.security.MessageDigest sha = state.digest;
        feed(state, signature);
        sha.update((byte) ':');
        sha.update(state.text, 0, putDigits(state.text, 0, nonce, Long.SIZE));
        digested(state);
    }

    private static void digested(ShaState state) {
        try {
            state.digest.digest(state.scratch, 0, state.scratch.length);
        } catch (java.security.DigestException impossible) {
            throw new IllegalStateException("SHA-256 buffer is too small", impossible);
        }
        byte[] hex = state.hex;
        for (int i = 0; i < SIGNATURE_CHARS / HEX_CHARS_PER_BYTE; i++) {
            int b = state.scratch[i] & UNSIGNED_BYTE_MASK;
            hex[i * HEX_CHARS_PER_BYTE] = HEX_DIGITS[b >>> HEX_DIGIT_BITS];
            hex[i * HEX_CHARS_PER_BYTE + 1] = HEX_DIGITS[b & LOW_FOUR_BITS];
        }
    }

    private static void feed(ShaState state, String value) {
        byte[] out = state.text;
        int length = value.length();
        int filled = 0;
        int i = 0;
        while (i < length) {
            if (filled > out.length - WIDEST_UTF8) {
                state.digest.update(out, 0, filled);
                filled = 0;
            }
            char c = value.charAt(i);
            if (c < UTF8_SINGLE_BYTE_LIMIT) {
                out[filled++] = (byte) c;
                i++;
            } else if (c < UTF8_THREE_BYTE_LIMIT) {
                out[filled++] = (byte) (UTF8_TWO_BYTE_PREFIX
                        | (c >> UTF8_CONTINUATION_SHIFT));
                out[filled++] = (byte) (UTF8_CONTINUATION_PREFIX
                        | (c & UTF8_LOW_SIX_BITS));
                i++;
            } else if (Character.isHighSurrogate(c) && i + 1 < length
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                int point = Character.toCodePoint(c, value.charAt(i + 1));
                out[filled++] = (byte) (UTF8_FOUR_BYTE_PREFIX
                        | (point >> UTF8_FOUR_BYTE_SHIFT));
                out[filled++] = (byte) (UTF8_CONTINUATION_PREFIX
                        | ((point >> UTF8_THREE_BYTE_SHIFT) & UTF8_LOW_SIX_BITS));
                out[filled++] = (byte) (UTF8_CONTINUATION_PREFIX
                        | ((point >> UTF8_CONTINUATION_SHIFT) & UTF8_LOW_SIX_BITS));
                out[filled++] = (byte) (UTF8_CONTINUATION_PREFIX
                        | (point & UTF8_LOW_SIX_BITS));
                i += SURROGATE_PAIR_LENGTH;
            } else if (Character.isSurrogate(c)) {
                out[filled++] = (byte) '?';
                i++;
            } else {
                out[filled++] = (byte) (UTF8_THREE_BYTE_PREFIX
                        | (c >> UTF8_THREE_BYTE_SHIFT));
                out[filled++] = (byte) (UTF8_CONTINUATION_PREFIX
                        | ((c >> UTF8_CONTINUATION_SHIFT) & UTF8_LOW_SIX_BITS));
                out[filled++] = (byte) (UTF8_CONTINUATION_PREFIX
                        | (c & UTF8_LOW_SIX_BITS));
                i++;
            }
        }
        state.digest.update(out, 0, filled);
    }

    private static void ceiling(int size) throws IOException {
        if (size > MAX_BYTES) {
            throw new IOException("a proof is " + size
                    + " bytes, over the " + MAX_BYTES + " ceiling");
        }
    }

    private static void bounded(String value, String what) {
        if (value.length() > MAX_NAME) {
            throw new IllegalArgumentException(what + " is " + value.length() + " characters");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(what + " is blank");
        }
    }

    private static void boundedNonce(String nonce) {
        bounded(nonce, "nonce");
        if (nonce.length() < MIN_NONCE_CHARS) {
            throw new IllegalArgumentException("nonce is " + nonce.length()
                    + " characters; the minimum is "
                    + MIN_NONCE_CHARS);
        }
    }

    private static void boundedResponse(String response) {
        if (response.length() != SIGNATURE_CHARS) {
            throw new IllegalArgumentException("response is " + response.length()
                    + " characters; expected "
                    + SIGNATURE_CHARS);
        }
    }
}
