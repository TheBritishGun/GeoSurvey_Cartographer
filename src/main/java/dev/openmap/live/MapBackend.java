package dev.openmap.live;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public enum MapBackend {

    DYNMAP("Dynmap", "dynmap"),

    BLUEMAP("BlueMap", "bluemap"),

    PL3XMAP("Pl3xMap", "pl3xmap"),

    SQUAREMAP("Squaremap", "squaremap"),

    OPENMAP("Open-Map", "openmap");

    private final String label;

    private final String bareLabel;

    MapBackend(String label, String bareLabel) {
        this.label = label;
        this.bareLabel = bareLabel;
    }

    public String label() {
        return label;
    }

    public static final String LIVE_FILE = "live.json";

    public static final String STATUS_PATH = "status";

    // Identifies the backend.
    public String configurationPath() {
        return switch (this) {
            case DYNMAP -> "up/configuration";
            case BLUEMAP -> "settings.json";
            case PL3XMAP -> "tiles/settings.json";
            case SQUAREMAP -> "tiles/settings.json";
            case OPENMAP -> STATUS_PATH;
        };
    }

    public String playersPath(String world) {
        return playersPathEscaped(world, escaped(world));
    }

    private String playersPathEscaped(String world, String esc) {
        return switch (this) {
            case DYNMAP -> "up/world/" + esc + "/0";
            case BLUEMAP -> "maps/" + esc + "/live/players.json";
            case PL3XMAP, SQUAREMAP -> "tiles/players.json";
            case OPENMAP -> LIVE_FILE;
        };
    }

    public String markersPath(String world) {
        return markersPathEscaped(world, escaped(world));
    }

    private String markersPathEscaped(String world, String esc) {
        return switch (this) {
            case DYNMAP -> "tiles/_markers_/marker_" + esc + ".json";
            case BLUEMAP -> "maps/" + esc + "/live/markers.json";
            case PL3XMAP, SQUAREMAP -> "tiles/" + esc + "/markers.json";
            case OPENMAP -> LIVE_FILE;
        };
    }

    private static String escapedSegment(String world) {
        return escaped(world);
    }

    private static final String HEX_DIGITS = "0123456789ABCDEF";

    private static final int ASCII_LIMIT = 0x80;

    private static final int ESCAPED_OCTET_LENGTH = 3;

    private static final int TWO_BYTE_UTF8_MAXIMUM = 0x7FF;

    private static final int TWO_BYTE_UTF8_ESCAPED_LENGTH = 6;

    private static final int THREE_BYTE_UTF8_MAXIMUM = 0xFFFF;

    private static final int THREE_BYTE_UTF8_ESCAPED_LENGTH = 9;

    private static final int FOUR_BYTE_UTF8_ESCAPED_LENGTH = 12;

    private static final int TWO_BYTE_UTF8_CONTINUATION_SHIFT = 6;

    private static final int THREE_BYTE_UTF8_FIRST_CONTINUATION_SHIFT = 12;

    private static final int FOUR_BYTE_UTF8_FIRST_CONTINUATION_SHIFT = 18;

    private static final int TWO_BYTE_UTF8_PREFIX = 0xC0;

    private static final int THREE_BYTE_UTF8_PREFIX = 0xE0;

    private static final int FOUR_BYTE_UTF8_PREFIX = 0xF0;

    private static final int UTF8_CONTINUATION_PREFIX = 0x80;

    private static final int UTF8_TRAILING_BITS_MASK = 0x3F;

    private static final int HEX_DIGIT_SHIFT = 4;

    private static final int HEX_DIGIT_INDEX = 2;

    private static final int HEX_DIGIT_MASK = 0x0F;

    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    private static final int ESCAPED_OCTET_EXTRA_CHARACTERS = 2;

    private static final int LOW_KEPT_RANGE_LIMIT = 64;

    private static final int ASCII_CASE_OFFSET = 32;

    static final int ESCAPED_CACHE_CAPACITY = 16;

    private static final Map<String, String> ESCAPED =
            Collections.synchronizedMap(new BoundedSegments());

    private static final AtomicLong NORMALISATIONS = new AtomicLong();

    private static final AtomicLong ESCAPE_CALLS = new AtomicLong();

    private static final class BoundedSegments extends LinkedHashMap<String, String> {

        private static final long serialVersionUID = 1L;

        BoundedSegments() {
            super(ESCAPED_CACHE_CAPACITY, 1.0f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > ESCAPED_CACHE_CAPACITY;
        }
    }

    static int escapedCacheSize() {
        return ESCAPED.size();
    }

    static long escapeNormalisations() {
        return NORMALISATIONS.get();
    }

    private static String escaped(String world) {
        ESCAPE_CALLS.incrementAndGet();
        String name = world == null ? "null" : world;
        int plain = 0;
        final int len = name.length();
        while (plain < len && keptInPath(name.charAt(plain))) {
            plain++;
        }
        String segment;
        if (plain == len) {
            segment = name;
        } else {
            synchronized (ESCAPED) {
                String cached = ESCAPED.get(name);
                if (cached != null) {
                    segment = cached;
                } else {
                    String normalised = isAsciiFrom(name, plain) ? name : normalisedForm(name);
                    int safe = plain > 0 ? plain - 1 : 0;
                    int length = normalised.length();
                    StringBuilder out = new StringBuilder(safe + escapedLength(normalised, safe, length));
                    if (safe > 0) {
                        out.append(name, 0, safe);
                    }
                    char[] hex = new char[ESCAPED_OCTET_LENGTH];
                    hex[0] = '%';
                    int i = safe;
                    while (i < length) {
                        int codePoint = normalised.codePointAt(i);
                        i += Character.charCount(codePoint);
                        appendEscaped(out, codePoint, hex);
                    }
                    segment = out.toString();
                    ESCAPED.put(name, segment);
                }
            }
        }
        return segment;
    }

    static int escapedLength(String normalised, int from, int to) {
        int length = 0;
        int i = from;
        while (i < to) {
            int codePoint = normalised.codePointAt(i);
            i += Character.charCount(codePoint);
            length += escapedLength(codePoint);
        }
        return length;
    }

    private static int escapedLength(int codePoint) {
        int length;
        if (codePoint < ASCII_LIMIT) {
            length = keptInPath((char) codePoint) ? 1 : ESCAPED_OCTET_LENGTH;
        } else if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
            length = ESCAPED_OCTET_LENGTH;
        } else if (codePoint <= TWO_BYTE_UTF8_MAXIMUM) {
            length = TWO_BYTE_UTF8_ESCAPED_LENGTH;
        } else if (codePoint <= THREE_BYTE_UTF8_MAXIMUM) {
            length = THREE_BYTE_UTF8_ESCAPED_LENGTH;
        } else {
            length = FOUR_BYTE_UTF8_ESCAPED_LENGTH;
        }
        return length;
    }

    private static void appendEscaped(StringBuilder out, int codePoint, char[] hex) {
        if (codePoint < ASCII_LIMIT) {
            char c = (char) codePoint;
            if (keptInPath(c)) {
                out.append(c);
            } else {
                appendOctet(out, codePoint, hex);
            }
        } else if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
            appendOctet(out, '?', hex);
        } else if (codePoint <= TWO_BYTE_UTF8_MAXIMUM) {
            appendOctet(out, TWO_BYTE_UTF8_PREFIX | (codePoint >>> TWO_BYTE_UTF8_CONTINUATION_SHIFT), hex);
            appendOctet(out, UTF8_CONTINUATION_PREFIX | (codePoint & UTF8_TRAILING_BITS_MASK), hex);
        } else if (codePoint <= THREE_BYTE_UTF8_MAXIMUM) {
            appendOctet(out, THREE_BYTE_UTF8_PREFIX | (codePoint >>> THREE_BYTE_UTF8_FIRST_CONTINUATION_SHIFT), hex);
            appendOctet(out, UTF8_CONTINUATION_PREFIX
                    | ((codePoint >>> TWO_BYTE_UTF8_CONTINUATION_SHIFT) & UTF8_TRAILING_BITS_MASK), hex);
            appendOctet(out, UTF8_CONTINUATION_PREFIX | (codePoint & UTF8_TRAILING_BITS_MASK), hex);
        } else {
            appendOctet(out, FOUR_BYTE_UTF8_PREFIX | (codePoint >>> FOUR_BYTE_UTF8_FIRST_CONTINUATION_SHIFT), hex);
            appendOctet(out, UTF8_CONTINUATION_PREFIX
                    | ((codePoint >>> THREE_BYTE_UTF8_FIRST_CONTINUATION_SHIFT) & UTF8_TRAILING_BITS_MASK), hex);
            appendOctet(out, UTF8_CONTINUATION_PREFIX
                    | ((codePoint >>> TWO_BYTE_UTF8_CONTINUATION_SHIFT) & UTF8_TRAILING_BITS_MASK), hex);
            appendOctet(out, UTF8_CONTINUATION_PREFIX | (codePoint & UTF8_TRAILING_BITS_MASK), hex);
        }
    }

    private static void appendOctet(StringBuilder out, int octet, char[] hex) {
        hex[1] = HEX_DIGITS.charAt(octet >>> HEX_DIGIT_SHIFT);
        hex[HEX_DIGIT_INDEX] = HEX_DIGITS.charAt(octet & HEX_DIGIT_MASK);
        out.append(hex, 0, ESCAPED_OCTET_LENGTH);
    }

    private static boolean isAsciiFrom(String name, int from) {
        boolean ascii = true;
        for (int i = from; ascii && i < name.length(); i++) {
            if (name.charAt(i) >= ASCII_LIMIT) {
                ascii = false;
            }
        }
        return ascii;
    }

    private static String normalisedForm(String name) {
        NORMALISATIONS.incrementAndGet();
        return Normalizer.normalize(name, Normalizer.Form.NFC);
    }

    static int capacityFor(byte[] bytes) {
        int escaped = 0;
        for (byte b : bytes) {
            int octet = b & UNSIGNED_BYTE_MASK;
            if (octet >= ASCII_LIMIT || !keptInPath((char) octet)) {
                escaped++;
            }
        }
        return bytes.length + ESCAPED_OCTET_EXTRA_CHARACTERS * escaped;
    }

    private static final long KEPT_LO = 0x03FFE00000000000L;
    private static final long KEPT_HI = 0x47FFFFFE87FFFFFEL;

    private static boolean keptInPath(char c) {
        return c < ASCII_LIMIT && (((c < LOW_KEPT_RANGE_LIMIT ? KEPT_LO : KEPT_HI) >>> c) & 1L) != 0;
    }

    // Whether the endpoint lists players of all worlds.
    public boolean playersAreGlobal() {
        return this == PL3XMAP || this == SQUAREMAP || this == OPENMAP;
    }

    private static final class DynmapDefaultsHolder {

        static final WorldMapping VALUE = WorldMapping.dynmapDefaults();
    }

    private static final WorldMapping OPENMAP_DEFAULT_WORLDS = WorldMapping.openMapDefaults();

    // Which remote world serves which dimension.
    public WorldMapping defaultWorlds() {
        return this == OPENMAP ? OPENMAP_DEFAULT_WORLDS : DynmapDefaultsHolder.VALUE;
    }

    public boolean playersAndMarkersShareADocument() {
        return this == OPENMAP;
    }

    public static String join(String baseUrl, String path) {
        String base = normalisedBase(baseUrl);
        String tail = trimmedTail(path);
        return base == null ? "" : base + "/" + tail.replace(" ", "%20");
    }

    private static String joinTrustedTail(String baseUrl, String trustedTail) {
        String base = normalisedBase(baseUrl);
        String tail = trimmedTail(trustedTail);
        return base == null ? "" : base + "/" + tail;
    }

    private static String normalisedBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String base = baseUrl.trim();
        String lower = base.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            base = "https://" + base;
        }
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        return base.substring(0, end);
    }

    private static String trimmedTail(String path) {
        String tail = path == null ? "" : path.trim();
        int start = 0;
        while (start < tail.length() && tail.charAt(start) == '/') {
            start++;
        }
        return tail.substring(start);
    }

    public static String withCacheBuster(String url, long stamp) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + "_=" + stamp;
    }

    private static final MapBackend[] ALL = values();

    private static final String[] BARE_LABELS = bareLabels();

    static final int LONGEST_BARE_LABEL = longestBareLabel();

    private static String[] bareLabels() {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            out[i] = ALL[i].bareLabel;
        }
        return out;
    }

    private static int longestBareLabel() {
        int longest = 0;
        for (int i = 0; i < BARE_LABELS.length; i++) {
            if (BARE_LABELS[i].length() > longest) {
                longest = BARE_LABELS[i].length();
            }
        }
        return longest;
    }

    // The named backend, or Dynmap.
    public static MapBackend fromName(String name) {
        MapBackend found = match(name);
        return found == null ? DYNMAP : found;
    }

    // The named backend, or null.
    public static MapBackend match(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        MapBackend exact = exactMatch(trimmed);
        MapBackend found;
        if (exact == null && retainedLength(trimmed) <= LONGEST_BARE_LABEL) {
            found = bareMatch(trimmed);
        } else {
            found = exact;
        }
        return found;
    }

    private static MapBackend exactMatch(String trimmed) {
        int index = 0;
        while (index < ALL.length
                && !ALL[index].name().equalsIgnoreCase(trimmed)
                && !ALL[index].label.equalsIgnoreCase(trimmed)) {
            index++;
        }
        return index < ALL.length ? ALL[index] : null;
    }

    private static MapBackend bareMatch(String trimmed) {
        int index = 0;
        while (index < ALL.length && !bareEquals(trimmed, BARE_LABELS[index])) {
            index++;
        }
        return index < ALL.length ? ALL[index] : null;
    }

    private static int retainedLength(String text) {
        int retained = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '-' && c != '_' && c != ' ') {
                retained++;
                if (retained > LONGEST_BARE_LABEL) {
                    break;
                }
            }
        }
        return retained;
    }

    private static boolean bareEquals(String text, String label) {
        int at = 0;
        boolean equal = true;
        for (int i = 0; equal && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' || c == '_' || c == ' ') {
                continue;
            }
            char lower = c < ASCII_LIMIT
                    ? (c >= 'A' && c <= 'Z' ? (char) (c + ASCII_CASE_OFFSET) : c)
                    : Character.toLowerCase(c);
            if (at == label.length() || lower != label.charAt(at)) {
                equal = false;
            } else {
                at++;
            }
        }
        return equal && at == label.length();
    }

    private static String bare(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '-' && c != '_' && c != ' ') {
                char lower = c < ASCII_LIMIT
                        ? (c >= 'A' && c <= 'Z' ? (char) (c + ASCII_CASE_OFFSET) : c)
                        : Character.toLowerCase(c);
                out.append(lower);
            }
        }
        return out.toString();
    }
}
