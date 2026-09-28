package dev.openmap.share;

import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

public final class StandingAsk {

    // The route, outside the /fingerprint prefix.
    public static final String PATH = "/owed";

    // Longest a body this reads may be.
    public static final int MAX_BYTES = 512;

    private static final byte[] SENT_MEMBER = ",\"sent\":".getBytes(StandardCharsets.UTF_8);

    private static final byte[] NONCE_MEMBER = ",\"nonce\":\"".getBytes(StandardCharsets.UTF_8);

    private static final byte[] BODY_END = "\"}".getBytes(StandardCharsets.UTF_8);

    private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final int OBJECT_END_BYTES = 1;

    private static final int LONGEST_DECIMAL = Long.toString(Long.MIN_VALUE).length();

    private static final long DECIMAL_RADIX = 10L;

    private static final int HEX_DIGIT_BITS = 4;

    private static final int HEX_DIGIT_MASK = 0xF;

    private static final int NONCE_DIGITS = Long.SIZE / HEX_DIGIT_BITS;

    // sent is wall-clock milliseconds.
    public record Ask(String server, long sent, String nonce) {
    }

    public static final class Body {

        private byte[] bytes = new byte[MAX_BYTES];

        private int length;

        private String serverOf;

        private byte[] opening;

        public byte[] bytes() {
            return bytes;
        }

        public int length() {
            return length;
        }
    }

    private StandingAsk() {
    }

    public static void body(String server, long sentMillis, long nonce, Body into) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(into, "into");
        if (!server.equals(into.serverOf)) {
            JsonObject opened = new JsonObject();
            opened.addProperty("server", server);
            into.opening = opened.toJson(MAX_BYTES).getBytes(StandardCharsets.UTF_8);
            into.serverOf = server;
        }
        int openingLength = into.opening.length - OBJECT_END_BYTES;
        int room = openingLength + SENT_MEMBER.length + LONGEST_DECIMAL + NONCE_MEMBER.length
                + NONCE_DIGITS + BODY_END.length;
        if (into.bytes.length < room) {
            into.bytes = new byte[room];
        }
        byte[] out = into.bytes;
        System.arraycopy(into.opening, 0, out, 0, openingLength);
        int at = put(out, openingLength, SENT_MEMBER);
        at = putDecimal(out, at, sentMillis);
        at = put(out, at, NONCE_MEMBER);
        at = putHex(out, at, nonce);
        into.length = put(out, at, BODY_END);
    }

    private static int put(byte[] out, int at, byte[] text) {
        System.arraycopy(text, 0, out, at, text.length);
        return at + text.length;
    }

    private static int putDecimal(byte[] out, int at, long value) {
        int next = at;
        if (value < 0L) {
            out[next++] = (byte) '-';
        }
        long negative = value < 0L ? value : -value;
        int digits = 1;
        long rest = negative / DECIMAL_RADIX;
        while (rest != 0L) {
            digits++;
            rest = rest / DECIMAL_RADIX;
        }
        int end = next + digits;
        long left = negative;
        for (int place = end - 1; place >= next; place--) {
            long quotient = left / DECIMAL_RADIX;
            out[place] = (byte) ('0' + (int) (quotient * DECIMAL_RADIX - left));
            left = quotient;
        }
        return end;
    }

    private static int putHex(byte[] out, int at, long value) {
        int next = at;
        for (int shift = Long.SIZE - HEX_DIGIT_BITS; shift >= 0; shift -= HEX_DIGIT_BITS) {
            out[next++] = HEX_DIGITS[(int) (value >>> shift) & HEX_DIGIT_MASK];
        }
        return next;
    }

    // The ask inside an already-verified body, or null when it cannot be read.
    public static Ask ask(byte[] verifiedBody) {
        if (verifiedBody == null || verifiedBody.length == 0
                || verifiedBody.length > MAX_BYTES) {
            return null;
        }
        Ask read;
        try {
            JsonElement parsed = JsonParser.parseString(
                    new String(verifiedBody, StandardCharsets.UTF_8));
            read = parsed != null && parsed.isJsonObject()
                    ? usable(parsed.getAsJsonObject())
                    : null;
        } catch (RuntimeException notAnAsk) {
            read = null;
        }
        return read;
    }

    // Whether an ask's sent falls inside the presence window, in both directions.
    public static boolean current(Ask ask, Instant now) {
        if (ask == null || now == null) {
            return false;
        }
        return SignedBatch.inPresenceWindow(now.toEpochMilli(), ask.sent());
    }

    // The ask a parsed document names, or null when a field is missing or unusable.
    private static Ask usable(JsonObject document) {
        String server = string(document, "server");
        String nonce = string(document, "nonce");
        long sent = Directory.exactInteger(document.get("sent"));
        boolean readable = server != null && !server.isBlank()
                && server.length() <= WorldProof.MAX_NAME
                && nonce != null && nonce.length() >= WorldProof.MIN_NONCE_CHARS
                && nonce.length() <= WorldProof.MAX_NAME
                && sent != Directory.NO_EXACT_INTEGER;
        return readable ? new Ask(server, sent, nonce) : null;
    }

    // A primitive member's text, or null when the member is absent or not a primitive.
    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
