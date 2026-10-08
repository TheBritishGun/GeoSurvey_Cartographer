package dev.openmap.share;

import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonElement;
import dev.openmap.json.NumberGrammar;
import dev.openmap.json.JsonObject;
import dev.openmap.json.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class Directory {

    // Matches Gossip.PATH on the collector.
    public static final String PEERS_PATH = "/peers";

    // Matches PeerBook.MAX_PEERS on the collector.
    public static final int MAX_PEERS = 64;

    private static final int MAX_ENTRIES = MAX_PEERS + 1;

    private static final int MAP_CAPACITY_NUMERATOR = 4;

    private static final int MAP_CAPACITY_DENOMINATOR = 3;

    private static final int HTTP_SCHEME_LENGTH = 4;

    private static final int HTTPS_SCHEME_LENGTH = 5;

    // Matches PeerBook.MAX_URL on the collector.
    public static final int MAX_URL = 300;

    // Matches Gossip.MAX_BODY on the collector.
    public static final int MAX_BODY = 32 * 1024;

    public static final int VERSION = 1;

    public static final long MAX_ROUND_MILLIS = 60_000L;

    static final long NO_EXACT_INTEGER = Long.MIN_VALUE;

    private static final long MAX_ROUND_NANOS = MAX_ROUND_MILLIS * 1_000_000L;

    public interface Ask {

        // Null when nothing is readable.
        String get(String address) throws IOException;
    }

    // published excludes a seed's own address.
    public record Found(List<String> published, List<String> answered,
                        List<String> contributedNothing, boolean cutShort) {

        public Found(List<String> published, List<String> answered, boolean cutShort) {
            this(published, answered, answered, cutShort);
        }

        public Found(List<String> published, List<String> answered) {
            this(published, answered, false);
        }

        public static Found nothing() {
            return new Found(List.of(), List.of());
        }

        public boolean isEmpty() {
            return published.isEmpty() && answered.isEmpty();
        }
    }

    private static final BooleanSupplier NOT_STOPPED = () -> false;

    private static final AtomicLong SCHEME_SCANS = new AtomicLong();

    private Directory() {
    }

    public static Found from(List<String> seeds, Ask ask) {
        return from(seeds, ask, System::nanoTime, MAX_ROUND_NANOS, NOT_STOPPED);
    }

    public static Found from(List<String> seeds, Ask ask, BooleanSupplier stop) {
        return from(seeds, ask, System::nanoTime, MAX_ROUND_NANOS, stop);
    }

    public static Found from(List<String> seeds, Ask ask, LongSupplier clock,
                             long budgetNanos) {
        return from(seeds, ask, clock, budgetNanos, NOT_STOPPED);
    }

    public static Found from(List<String> seeds, Ask ask, LongSupplier clock,
                             long budgetNanos, BooleanSupplier stop) {
        if (seeds == null) {
            return Found.nothing();
        }
        BooleanSupplier stopping = stop == null ? NOT_STOPPED : stop;
        long startedAt = clock.getAsLong();
        Map<String, String> found = new LinkedHashMap<>();
        Set<String> live = new LinkedHashSet<>();
        Set<String> silent = new LinkedHashSet<>();
        Set<String> asked = new LinkedHashSet<>();
        boolean cutShort = false;
        for (String seed : seeds) {
            if (asked.size() >= MAX_PEERS || found.size() >= MAX_PEERS) {
                cutShort = true;
                break;
            }
            if (Thread.currentThread().isInterrupted() || stopping.getAsBoolean()
                    || clock.getAsLong() - startedAt >= budgetNanos) {
                cutShort = true;
                break;
            }
            String at = tidy(seed);
            if (at.isEmpty()) {
                continue;
            }
            if (!asked.add(originKey(at))) {
                continue;
            }
            String body;
            try {
                body = ask.get(at + PEERS_PATH);
            } catch (IOException nothingThere) {
                body = null;
            }
            JsonArray listed = peersArray(document(body));
            if (listed != null) {
                live.add(at);
                List<String> addresses = addressesIn(listed);
                if (addresses.isEmpty()) {
                    silent.add(at);
                }
                for (String peer : addresses) {
                    if (found.size() >= MAX_PEERS) {
                        cutShort = true;
                        break;
                    }
                    found.putIfAbsent(originKey(peer), peer);
                }
            }
        }
        return new Found(List.copyOf(found.values()), List.copyOf(live),
                List.copyOf(silent), cutShort);
    }

    public static boolean answersAsCollector(String body) {
        return peersArray(document(body)) != null;
    }

    public static List<String> read(String body) {
        return pool(document(body));
    }

    static List<String> pool(JsonObject document) {
        JsonArray listed = peersArray(document);
        return listed == null ? List.of() : addressesIn(listed);
    }

    private static JsonObject document(String body) {
        if (body == null || body.length() > MAX_BODY || body.isBlank()) {
            return null;
        }
        JsonObject document;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject parsedDocument = parsed.getAsJsonObject();
                JsonElement version = parsedDocument.get("version");
                if (WireVersion.is(version, VERSION)) {
                    document = parsedDocument;
                } else {
                    document = null;
                }
            } else {
                document = null;
            }
        } catch (RuntimeException notAPoolDocument) {
            document = null;
        }
        return document;
    }

    static long exactIntegerOf(String text) {
        if (text == null) {
            return NO_EXACT_INTEGER;
        }
        String whole = wholeNumberText(text);
        return whole == null ? NO_EXACT_INTEGER
                : NumberGrammar.decimalLongOr(whole, NO_EXACT_INTEGER);
    }

    private static String wholeNumberText(String text) {
        int length = text.length();
        int wholeStart = length > 0 && text.charAt(0) == '-' ? 1 : 0;
        int at = wholeStart;
        while (at < length && isDigit(text.charAt(at))) {
            at++;
        }
        String whole = null;
        if (at != wholeStart) {
            String candidate = text.substring(0, at);
            if (at == length) {
                whole = candidate;
            } else if (text.charAt(at) == '.' && at + 1 != length) {
                boolean zeroFraction = true;
                int fractionAt = at + 1;
                while (fractionAt < length && zeroFraction) {
                    zeroFraction = text.charAt(fractionAt) == '0';
                    fractionAt++;
                }
                if (zeroFraction) {
                    whole = candidate;
                }
            }
        }
        return whole;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static JsonArray peersArray(JsonObject document) {
        if (document == null) {
            return null;
        }
        JsonElement peers = document.get("peers");
        return peers != null && peers.isJsonArray() ? peers.getAsJsonArray() : null;
    }

    private static List<String> addressesIn(JsonArray listed) {
        Map<String, String> out = new LinkedHashMap<>(
                Math.min(listed.size(), MAX_PEERS) * MAP_CAPACITY_NUMERATOR
                        / MAP_CAPACITY_DENOMINATOR + 1);
        int examined = 0;
        for (JsonElement entry : listed) {
            if (out.size() >= MAX_PEERS || examined >= MAX_ENTRIES) {
                break;
            }
            examined++;
            if (entry != null && entry.isJsonPrimitive()) {
                String written = entry.getAsString().trim();
                String address = namesAScheme(written) ? tidied(written) : "";
                if (!address.isEmpty()) {
                    out.putIfAbsent(originKey(address), address);
                }
            }
        }
        return List.copyOf(out.values());
    }

    // An http(s) origin only; trailing slash removed.
    public static String tidy(String address) {
        return tidied(withScheme(address));
    }

    private static String tidied(String schemeQualified) {
        String trimmed = schemeQualified;
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        trimmed = trimmed.substring(0, end);
        String address = "";
        if (!trimmed.isEmpty() && trimmed.length() <= MAX_URL) {
            URI parsed = parsedUri(trimmed);
            if (parsed != null) {
                String scheme = parsed.getScheme();
                if (scheme != null) {
                    scheme = scheme.toLowerCase(Locale.ROOT);
                    if ((scheme.equals("http") || scheme.equals("https"))
                            && parsed.getHost() != null && !parsed.getHost().isEmpty()
                            && parsed.getRawUserInfo() == null && parsed.getRawQuery() == null
                            && parsed.getRawFragment() == null) {
                        String path = parsed.getRawPath();
                        if (path == null || path.isEmpty()) {
                            address = trimmed.toLowerCase(Locale.ROOT);
                        }
                    }
                }
            }
        }
        return address;
    }

    private static URI parsedUri(String address) {
        URI parsed;
        try {
            parsed = new URI(address);
        } catch (java.net.URISyntaxException notAnAddress) {
            parsed = null;
        }
        return parsed;
    }

    public static String withScheme(String address) {
        String trimmed = address == null ? "" : address.trim();
        if (trimmed.isEmpty() || namesAScheme(trimmed)) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    static long namesASchemeCalls() {
        return SCHEME_SCANS.get();
    }

    private static boolean namesAScheme(String address) {
        SCHEME_SCANS.incrementAndGet();
        int name = 0;
        while (name < address.length()
                && schemeCharacter(address.charAt(name), name == 0)) {
            name++;
        }
        boolean atEnd = name == address.length();
        boolean namesScheme;
        if ((name == HTTP_SCHEME_LENGTH || name == HTTPS_SCHEME_LENGTH)
                && address.regionMatches(true, 0, "https", 0, name)
                && (atEnd || isSchemeDelimiter(address.charAt(name)))) {
            namesScheme = true;
        } else if (name == 0 || atEnd || address.charAt(name) != ':') {
            namesScheme = false;
        } else {
            int port = name + 1;
            while (port < address.length() && address.charAt(port) >= '0'
                    && address.charAt(port) <= '9') {
                port++;
            }
            namesScheme = port == name + 1
                    || (port < address.length() && !isPathDelimiter(address.charAt(port)));
        }
        return namesScheme;
    }

    private static boolean isSchemeDelimiter(char c) {
        return c == ':' || c == '/' || c == '?' || c == '#';
    }

    private static boolean isPathDelimiter(char c) {
        return c == '/' || c == '?' || c == '#';
    }

    private static boolean schemeCharacter(char letter, boolean first) {
        if ((letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z')) {
            return true;
        }
        return !first && ((letter >= '0' && letter <= '9')
                || letter == '+' || letter == '-' || letter == '.');
    }

    private static String originKey(String address) {
        int colon = address.lastIndexOf(':');
        if (colon == address.indexOf(':') || address.indexOf(']', colon) >= 0) {
            return address;
        }
        if (address.length() == colon + 1) {
            return address.substring(0, colon);
        }
        String def = address.startsWith("https:") ? "443" : "80";
        if (address.length() == colon + 1 + def.length()
                && address.startsWith(def, colon + 1)) {
            return address.substring(0, colon);
        }
        return address;
    }

    public static List<String> seeds(String... candidates) {
        if (candidates == null) {
            return List.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String each : candidates) {
            String at = tidy(each);
            if (!at.isEmpty()) {
                out.putIfAbsent(originKey(at), at);
            }
        }
        return List.copyOf(out.values());
    }
}
