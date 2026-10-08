package dev.openmap.share;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record MapCard(String origin, String dimension, int anchorX, int anchorZ,
                      int components, SpawnPrint print) {

    public MapCard {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(print, "print");
        if (origin.isEmpty()) {
            throw new IllegalArgumentException("origin");
        }
        if (dimension.isEmpty()) {
            throw new IllegalArgumentException("dimension");
        }
        if (anchorX < -MAX_ANCHOR || anchorX > MAX_ANCHOR
                || anchorZ < -MAX_ANCHOR || anchorZ > MAX_ANCHOR) {
            throw new IllegalArgumentException("anchor");
        }
        if ((components & SURFACE) == 0) {
            throw new IllegalArgumentException("components");
        }
    }

    // The digit is the field layout.
    public static final String PREFIX = "lnmap1";

    // Between fields.
    public static final char FIELD = '~';

    public static final int FIELDS = 6;

    // The payload layout this build uses.
    public static final int PAYLOAD_VERSION = 1;

    // Mask bit for SpawnPrint.
    public static final int SURFACE = 0x01;

    // Check bytes at the payload end.
    public static final int CHECK_BYTES = 2;

    // Longest card text.
    public static final int MAX_TEXT = 1024;

    public static final int MAX_PAYLOAD = 512;

    // Maximum cards read from one settings field.
    public static final int MAX_CARDS = 16;

    // The world border, in chunks.
    public static final int MAX_ANCHOR = 1_875_000;

    public static final String DEFAULT_NAMESPACE = "minecraft";

    // Longest dimension identifier.
    public static final int MAX_DIMENSION = 64;

    private static final int ORIGIN_FIELD = 1;

    private static final int DIMENSION_FIELD = 2;

    private static final int ANCHOR_X_FIELD = 3;

    private static final int ANCHOR_Z_FIELD = 4;

    private static final int PAYLOAD_FIELD = 5;

    private static final int HEADER_BYTES = 2;

    private static final int SURFACE_AT = HEADER_BYTES + 1;

    private static final int LAST_COMPONENT = 0x80;

    private static final int BYTE_MASK = 0xFF;

    private static final int MAX_ANCHOR_TEXT = 8;

    private static final int MAX_INT_TEXT = 11;

    private static final int DECIMAL_RADIX = 10;

    private static final int LIST_DEFAULT_CAPACITY = 10;

    public enum Fault {

        NONE(""),

        NOT_A_CARD("A map card starts with lnmap1."),

        WRONG_SHAPE("It has the wrong number of parts."),

        BAD_ADDRESS("Its address is missing or has a path."),

        BAD_DIMENSION("It names no dimension."),

        BAD_ANCHOR("It names no place in the world."),

        DAMAGED("Ask for it again and paste the whole line."),

        UNKNOWN_VERSION("Update the mod to read this newer card."),

        NO_COMPONENT("Ask the operator for a card that describes ground."),

        TOO_FLAT("Flat ground identifies no server.");

        private final String reason;

        Fault(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    // card is null unless fault is NONE.
    public record Read(MapCard card, Fault fault, String text) {

        public boolean ok() {
            return card != null && fault == Fault.NONE;
        }
    }

    public record Scan(List<Read> reads, int entries) {
    }

    // Maximum characters quoted from a bad entry.
    public static final int QUOTE_LIMIT = 40;

    // Never throws.
    public static Read read(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return new Read(null, Fault.NOT_A_CARD, "");
        }
        String quoted = quote(trimmed);
        boolean fits = trimmed.length() <= MAX_TEXT;
        boolean cardPrefix = fits && trimmed.length() >= PREFIX.length()
                && trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                && (trimmed.length() == PREFIX.length()
                        || trimmed.charAt(PREFIX.length()) == FIELD);
        Read answer;
        if (!fits) {
            answer = new Read(null, Fault.DAMAGED, quoted);
        } else if (!cardPrefix) {
            answer = new Read(null, Fault.NOT_A_CARD, quoted);
        } else {
            answer = readFields(split(trimmed), quoted);
        }
        return answer;
    }

    private static Read readFields(String[] parts, String quoted) {
        if (fieldsIn(parts) != FIELDS) {
            return new Read(null, Fault.WRONG_SHAPE, quoted);
        }
        String origin = Directory.tidy(parts[ORIGIN_FIELD].toLowerCase(Locale.ROOT));
        Read answer;
        if (origin.isEmpty()) {
            answer = new Read(null, Fault.BAD_ADDRESS, quoted);
        } else {
            String dimension = dimensionOf(parts[DIMENSION_FIELD]);
            if (dimension.isEmpty()) {
                answer = new Read(null, Fault.BAD_DIMENSION, quoted);
            } else {
                int anchorX = anchorOf(parts[ANCHOR_X_FIELD]);
                int anchorZ = anchorOf(parts[ANCHOR_Z_FIELD]);
                if (anchorX == Integer.MIN_VALUE || anchorZ == Integer.MIN_VALUE) {
                    answer = new Read(null, Fault.BAD_ANCHOR, quoted);
                } else {
                    answer = readPayload(origin, dimension, anchorX, anchorZ,
                            parts[PAYLOAD_FIELD], quoted);
                }
            }
        }
        return answer;
    }

    private static Read readPayload(String origin, String dimension, int anchorX, int anchorZ,
                                    String field, String quoted) {
        byte[] payload = Base32.decode(field);
        Read answer;
        if (payload == null || payload.length < HEADER_BYTES + CHECK_BYTES
                || payload.length > MAX_PAYLOAD) {
            answer = new Read(null, Fault.DAMAGED, quoted);
        } else if (!checked(origin, dimension, anchorX, anchorZ, payload)) {
            answer = new Read(null, Fault.DAMAGED, quoted);
        } else if ((payload[0] & BYTE_MASK) != PAYLOAD_VERSION) {
            answer = new Read(null, Fault.UNKNOWN_VERSION, quoted);
        } else {
            int mask = payload[1] & BYTE_MASK;
            long surface = indexOfComponent(payload, mask, SURFACE);
            if (surface < 0) {
                answer = new Read(null, Fault.NO_COMPONENT, quoted);
            } else {
                SpawnPrint print =
                        SpawnPrint.fromBytes(payload, (int) (surface >>> Integer.SIZE), (int) surface);
                if (print == null) {
                    answer = new Read(null, Fault.DAMAGED, quoted);
                } else {
                    if (!print.identifies()) {
                        answer = new Read(null, Fault.TOO_FLAT, quoted);
                    } else {
                        answer = new Read(new MapCard(origin, dimension, anchorX, anchorZ, mask, print),
                                Fault.NONE, quoted);
                    }
                }
            }
        }
        return answer;
    }

    private static final Pattern ENTRY_SEPARATOR = Pattern.compile("[\\s,;]+");

    public static List<Read> readAll(String field) {
        return scan(field).reads();
    }

    public static Scan scan(String field) {
        if (field == null) {
            return new Scan(List.of(), 0);
        }
        ArrayList<Read> out = new ArrayList<>();
        Matcher separators = ENTRY_SEPARATOR.matcher(field);
        int from = 0;
        int entries = 0;
        while (true) {
            boolean more = separators.find();
            int to = more ? separators.start() : field.length();
            if (out.size() < MAX_CARDS) {
                if (out.size() >= LIST_DEFAULT_CAPACITY) {
                    out.ensureCapacity(MAX_CARDS);
                }
                String entry = field.substring(from, to);
                if (!entry.isBlank()) {
                    entries++;
                    out.add(read(entry));
                }
            } else {
                if (!blank(field, from, to)) {
                    entries++;
                }
            }
            if (!more) {
                break;
            }
            from = separators.end();
        }
        return new Scan(out, entries);
    }

    private static boolean blank(String text, int from, int to) {
        boolean white = true;
        for (int i = from; white && i < to; i++) {
            white = Character.isWhitespace(text.charAt(i));
        }
        return white;
    }

    // Round trips through read().
    public String text() {
        String dim = shortDimension();
        String body = Base32.encode(payload());
        StringBuilder out = new StringBuilder(PREFIX.length() + FIELDS - 1 + origin.length()
                + dim.length() + Integer.toString(anchorX).length()
                + Integer.toString(anchorZ).length() + body.length());
        out.append(PREFIX).append(FIELD).append(origin).append(FIELD)
                .append(dim).append(FIELD).append(anchorX)
                .append(FIELD).append(anchorZ).append(FIELD);
        return out.append(body).toString();
    }

    public String shortDimension() {
        String head = DEFAULT_NAMESPACE + ":";
        return dimension.startsWith(head) ? dimension.substring(head.length()) : dimension;
    }

    // Includes the check bytes.
    public byte[] payload() {
        byte[] out = new byte[SURFACE_AT + SpawnPrint.BYTES + CHECK_BYTES];
        out[0] = (byte) PAYLOAD_VERSION;
        out[1] = (byte) components;
        out[HEADER_BYTES] = (byte) SpawnPrint.BYTES;
        print.writeTo(out, SURFACE_AT);
        byte[] check = checkOf(origin, dimension, anchorX, anchorZ, out,
                out.length - CHECK_BYTES);
        System.arraycopy(check, 0, out, out.length - CHECK_BYTES, CHECK_BYTES);
        return out;
    }

    // Null when no card can be made.
    public static MapCard of(String origin, String dimension, int anchorX, int anchorZ,
                             SpawnPrint print) {
        String tidy = Directory.tidy(origin);
        String named = dimensionOf(dimension);
        if (tidy.isEmpty() || named.isEmpty() || print == null || !print.identifies()
                || anchorX < -MAX_ANCHOR || anchorX > MAX_ANCHOR
                || anchorZ < -MAX_ANCHOR || anchorZ > MAX_ANCHOR) {
            return null;
        }
        return new MapCard(tidy, named, anchorX, anchorZ, SURFACE, print);
    }

    // Splits on FIELD, keeping empty trailing fields.
    private static String[] split(String text) {
        String[] parts = new String[FIELDS + 1];
        int count = 0;
        int from = 0;
        while (count < parts.length) {
            int at = text.indexOf(FIELD, from);
            if (at < 0) {
                parts[count++] = text.substring(from);
                break;
            }
            parts[count++] = text.substring(from, at);
            from = at + 1;
        }
        return parts;
    }

    private static int fieldsIn(String[] parts) {
        int fields = 0;
        while (fields < parts.length && parts[fields] != null) {
            fields++;
        }
        return fields;
    }

    // Empty when the field is invalid.
    static String dimensionOf(String field) {
        if (field == null) {
            return "";
        }
        String text = field.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.length() > MAX_DIMENSION) {
            return "";
        }
        int colon = text.indexOf(':');
        if (colon >= 0 && text.indexOf(':', colon + 1) >= 0) {
            return "";
        }
        if (colon < 0) {
            if (!plain(text, 0, text.length(), true)) {
                return "";
            }
            return DEFAULT_NAMESPACE + ":" + text;
        }
        if (colon == 0 || colon == text.length() - 1) {
            return "";
        }
        if (!plain(text, 0, colon, false) || !plain(text, colon + 1, text.length(), true)) {
            return "";
        }
        return text;
    }

    // Resource location characters, except the slash.
    private static boolean plain(String text, int from, int to, boolean path) {
        boolean safe = true;
        for (int i = from; safe && i < to; i++) {
            char c = text.charAt(i);
            safe = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || (path && c == '/');
        }
        return safe;
    }

    // Integer.MIN_VALUE when the field is invalid.
    static int anchorOf(String field) {
        if (field == null || field.isEmpty() || field.length() > MAX_ANCHOR_TEXT) {
            return Integer.MIN_VALUE;
        }
        int at = field.charAt(0) == '-' ? 1 : 0;
        if (at == field.length()) {
            return Integer.MIN_VALUE;
        }
        int value = 0;
        boolean digits = true;
        for (int i = at; digits && i < field.length(); i++) {
            char digit = field.charAt(i);
            if (digit < '0' || digit > '9') {
                digits = false;
            } else {
                value = value * DECIMAL_RADIX + (digit - '0');
            }
        }
        int anchor;
        if (!digits || value > MAX_ANCHOR) {
            anchor = Integer.MIN_VALUE;
        } else {
            anchor = at == 1 ? -value : value;
        }
        return anchor;
    }

    // Null when the component is not found.
    static byte[] componentOf(byte[] payload, int mask, int wanted) {
        long found = indexOfComponent(payload, mask, wanted);
        if (found < 0) {
            return null;
        }
        int length = (int) found;
        byte[] out = new byte[length];
        System.arraycopy(payload, (int) (found >>> Integer.SIZE), out, 0, length);
        return out;
    }

    // High int: offset; low int: length; -1 when not found.
    static long indexOfComponent(byte[] payload, int mask, int wanted) {
        if ((mask & wanted) == 0) {
            return -1;
        }
        int at = HEADER_BYTES;
        int end = payload.length - CHECK_BYTES;
        long found = -1;
        boolean done = false;
        for (int bit = 1; !done && bit <= LAST_COMPONENT; bit <<= 1) {
            if ((mask & bit) == 0) {
                continue;
            }
            if (at >= end) {
                done = true;
            } else {
                int length = payload[at] & BYTE_MASK;
                at++;
                if (at + length > end) {
                    done = true;
                } else if (bit == wanted) {
                    found = ((long) at << Integer.SIZE) | length;
                    done = true;
                } else {
                    at += length;
                }
            }
        }
        return found;
    }

    private static boolean checked(String origin, String dimension, int anchorX,
                                   int anchorZ, byte[] payload) {
        byte[] want = checkOf(origin, dimension, anchorX, anchorZ, payload,
                payload.length - CHECK_BYTES);
        boolean same = true;
        for (int i = 0; same && i < CHECK_BYTES; i++) {
            same = payload[payload.length - CHECK_BYTES + i] == want[i];
        }
        return same;
    }

    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(MapCard::newSha256);

    private static MessageDigest newSha256() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("no SHA-256", impossible);
        }
        return digest;
    }

    // Covers the address, anchor and payload.
    static byte[] checkOf(String origin, String dimension, int anchorX, int anchorZ,
                          byte[] payload, int upTo) {
        int packed = checkPacked(origin, dimension, anchorX, anchorZ, payload, upTo);
        return new byte[] {(byte) (packed >>> Byte.SIZE), (byte) packed};
    }

    static int checkPacked(String origin, String dimension, int anchorX, int anchorZ,
                           byte[] payload, int upTo) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        byte[] buf = new byte[PREFIX.length() + origin.length() + dimension.length()
                + FIELDS - 1 + (MAX_INT_TEXT + MAX_INT_TEXT)];
        int at = 0;
        for (int i = 0; i < PREFIX.length(); i++) {
            buf[at++] = (byte) PREFIX.charAt(i);
        }
        buf[at++] = (byte) FIELD;
        at = asciiInto(buf, at, origin);
        buf[at++] = (byte) FIELD;
        at = asciiInto(buf, at, dimension);
        buf[at++] = (byte) FIELD;
        at = digitsInto(buf, at, anchorX);
        buf[at++] = (byte) FIELD;
        at = digitsInto(buf, at, anchorZ);
        buf[at++] = (byte) FIELD;
        digest.update(buf, 0, at);
        digest.update(payload, 0, Math.max(0, Math.min(upTo, payload.length)));
        byte[] full = digest.digest();
        return ((full[0] & BYTE_MASK) << Byte.SIZE) | (full[1] & BYTE_MASK);
    }

    private static int asciiInto(byte[] buf, int at, String text) {
        int length = text.length();
        for (int i = 0; i < length; i++) {
            buf[at++] = (byte) text.charAt(i);
        }
        return at;
    }

    private static int digitsInto(byte[] buf, int at, int value) {
        if (value < 0) {
            buf[at++] = '-';
        }
        long v = value < 0 ? -(long) value : value;
        int start = at;
        do {
            buf[at++] = (byte) ('0' + (int) (v % DECIMAL_RADIX));
            v /= DECIMAL_RADIX;
        } while (v > 0);
        int j = at - 1;
        for (int i = start; i < j; i++) {
            byte t = buf[i];
            buf[i] = buf[j];
            buf[j] = t;
            j--;
        }
        return at;
    }

    private static String quote(String text) {
        boolean clipped = text.length() > QUOTE_LIMIT;
        String flat = (clipped ? text.substring(0, QUOTE_LIMIT) : text)
                .replace('\r', ' ').replace('\n', ' ');
        return clipped ? flat + "..." : flat;
    }
}
