package dev.openmap.json;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicLong;

public final class JsonParser {

    static final int MAX_DEPTH = 255;

    static final int CHUNK = 8192;

    private static final int MAX_MEMBERS = 4096;

    private static final int NAME_TABLE_CAP = 256;

    private static final int NAME_TABLE_INITIAL_CAP = 8;

    private static final int NAME_TABLE_GROWTH = 2;

    private static final int ESCAPE_SEED_PADDING = 16;

    private static final int ESCAPE_SEED_FLOOR = 256;

    private static final int DECIMAL_BASE = 10;

    private static final int UNICODE_ESCAPE_DIGITS = 4;

    private static final int HEX_DIGIT_BITS = 4;

    private static final int HEX_LETTER_OFFSET = 10;

    private static final int BYTE_ORDER_MARK = 0xFEFF;

    private static final JsonPrimitive NAN = JsonPrimitive.ofNumberToken("NaN");

    private static final JsonPrimitive POSITIVE_INFINITY = JsonPrimitive.ofNumberToken("Infinity");

    private static final JsonPrimitive NEGATIVE_INFINITY = JsonPrimitive.ofNumberToken("-Infinity");

    private static final long MINUS_INFINITY = Long.MAX_VALUE;

    private static final AtomicLong CONTAINERS_BUILT = new AtomicLong();

    private static final ThreadLocal<JsonParser> SINK_PARSERS = ThreadLocal.withInitial(JsonParser::idle);

    private String text;

    private StringBuilder builder;

    // How much of the buffered text is valid to read.
    private int length;

    private int at;

    private String[] nameTable;

    private int nameTableSize;

    // Null except for a Reader-backed parser.
    private final Reader reader;

    private char[] readBuffer;

    private StringBuilder escapeScratch;

    private boolean busy = false;

    private JsonParser(String text) {
        this.text = text;
        this.builder = null;
        this.length = text.length();
        this.reader = null;
    }

    private JsonParser(StringBuilder builder) {
        this.text = null;
        this.builder = builder;
        this.length = builder.length();
        this.reader = null;
    }

    private JsonParser(Reader reader) {
        this.text = null;
        this.builder = null;
        this.length = 0;
        this.reader = reader;
    }

    // Trailing content after the value is an error.
    public static JsonElement parseString(String body) {
        if (body == null) {
            return JsonNull.INSTANCE;
        }
        return parse(new JsonParser(body));
    }

    static JsonElement parseString(CharSequence body) {
        if (body == null) {
            return JsonNull.INSTANCE;
        }
        JsonElement parsed;
        if (body instanceof String text) {
            parsed = parseString(text);
        } else if (body instanceof StringBuilder builder) {
            parsed = parse(new JsonParser(builder));
        } else {
            parsed = parseString(body.toString());
        }
        return parsed;
    }

    // Trailing content after the value is an error.
    static JsonElement parseReader(Reader reader) {
        JsonParser parser = new JsonParser(reader);
        parser.refill();
        return parse(parser);
    }

    public interface Sink {

        void beginObject();

        void endObject();

        void beginArray();

        void endArray();

        void name(String name);

        void stringValue(String value);

        void numberValue(String token);

        default void numberValue(CharSequence document, int start, int end) {
            numberValue(document.subSequence(start, end).toString());
        }

        void booleanValue(boolean value);

        void nullValue();
    }

    public static void parseInto(CharSequence body, Sink sink) {
        if (body == null) {
            sink.nullValue();
            return;
        }
        JsonParser pooled = SINK_PARSERS.get();
        JsonParser parser = pooled.busy ? idle() : pooled;
        parser.open(body);
        try {
            parser.emitDocument(sink);
        } finally {
            parser.release();
        }
    }

    static void parseInto(Reader reader, Sink sink) {
        JsonParser parser = new JsonParser(reader);
        parser.refill();
        parser.emitDocument(sink);
    }

    private void emitDocument(Sink sink) {
        skipByteOrderMark();
        skipWhitespace();
        if (at >= length()) {
            sink.nullValue();
        } else {
            emitAt(0, sink);
            skipWhitespace();
            if (at < length()) {
                throw fail("trailing content after the document");
            }
        }
    }

    private static JsonParser idle() {
        return new JsonParser("");
    }

    private void open(CharSequence body) {
        if (body instanceof String whole) {
            text = whole;
            builder = null;
            length = whole.length();
        } else if (body instanceof StringBuilder growing) {
            text = null;
            builder = growing;
            length = growing.length();
        } else {
            text = body.toString();
            builder = null;
            length = text.length();
        }
        at = 0;
        nameTableSize = 0;
        busy = true;
    }

    private void release() {
        String[] names = nameTable;
        if (names != null) {
            java.util.Arrays.fill(names, 0, nameTableSize, null);
        }
        nameTableSize = 0;
        text = "";
        builder = null;
        length = 0;
        at = 0;
        busy = false;
    }

    private static JsonElement parse(JsonParser parser) {
        parser.skipByteOrderMark();
        parser.skipWhitespace();
        JsonElement value;
        if (parser.at >= parser.length()) {
            value = JsonNull.INSTANCE;
        } else {
            value = parser.valueAt(0);
            parser.skipWhitespace();
            if (parser.at < parser.length()) {
                throw parser.fail("trailing content after the document");
            }
        }
        return value;
    }

    private JsonElement value(int open) {
        skipWhitespace();
        return valueAt(open);
    }

    private JsonElement valueAt(int open) {
        char c = peek();
        return switch (c) {
            case '{' -> object(deepen(open));
            case '[' -> array(deepen(open));
            case '"' -> JsonPrimitive.ofString(string());
            case 't' -> {
                literal("true");
                yield JsonPrimitive.ofBoolean(true);
            }
            case 'f' -> {
                literal("false");
                yield JsonPrimitive.ofBoolean(false);
            }
            case 'n' -> {
                literal("null");
                yield JsonNull.INSTANCE;
            }
            case 'N' -> {
                literal("NaN");
                yield NAN;
            }
            case 'I' -> {
                literal("Infinity");
                yield POSITIVE_INFINITY;
            }
            default -> {
                int start = at;
                long carried = number(c);
                yield carried == MINUS_INFINITY ? NEGATIVE_INFINITY
                        : JsonPrimitive.ofNumberToken(slice(start, at), carried);
            }
        };
    }

    private void emit(int open, Sink sink) {
        skipWhitespace();
        emitAt(open, sink);
    }

    private void emitAt(int open, Sink sink) {
        char c = peek();
        if (c == '{') {
            emitObject(deepen(open), sink);
            return;
        }
        if (c == '[') {
            emitArray(deepen(open), sink);
            return;
        }
        emitScalar(c, sink);
    }

    private void emitScalar(char c, Sink sink) {
        switch (c) {
            case '"' -> sink.stringValue(string());
            case 't' -> {
                literal("true");
                sink.booleanValue(true);
            }
            case 'f' -> {
                literal("false");
                sink.booleanValue(false);
            }
            case 'n' -> {
                literal("null");
                sink.nullValue();
            }
            case 'N' -> {
                literal("NaN");
                sink.numberValue(NAN.text());
            }
            case 'I' -> {
                literal("Infinity");
                sink.numberValue(POSITIVE_INFINITY.text());
            }
            default -> {
                int start = at;
                long carried = number(c);
                if (carried == MINUS_INFINITY) {
                    sink.numberValue(NEGATIVE_INFINITY.text());
                } else {
                    sink.numberValue(source(), start, at);
                }
            }
        }
    }

    private JsonObject object(int depth) {
        at++;
        CONTAINERS_BUILT.incrementAndGet();
        JsonObject out = new JsonObject();
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return out;
        }
        int members = 0;
        while (true) {
            if (members >= MAX_MEMBERS) {
                throw fail("object has more than " + MAX_MEMBERS + " members");
            }
            if (peek() != '"') {
                throw fail("a member name must be a quoted string");
            }
            String name = string(true);
            skipWhitespace();
            expect(':');
            out.add(name, value(depth));
            members++;
            skipWhitespace();
            char c = next();
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw fail("expected ',' or '}' in an object");
            }
            skipWhitespace();
            if (peek() == '}') {
                throw fail("trailing comma before '}'");
            }
        }
        return out;
    }

    private void emitObject(int depth, Sink sink) {
        at++;
        sink.beginObject();
        skipWhitespace();
        if (peek() == '}') {
            at++;
            sink.endObject();
            return;
        }
        int members = 0;
        while (true) {
            if (members >= MAX_MEMBERS) {
                throw fail("object has more than " + MAX_MEMBERS + " members");
            }
            if (peek() != '"') {
                throw fail("a member name must be a quoted string");
            }
            String name = string(true);
            skipWhitespace();
            expect(':');
            sink.name(name);
            emit(depth, sink);
            members++;
            skipWhitespace();
            char c = next();
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw fail("expected ',' or '}' in an object");
            }
            skipWhitespace();
            if (peek() == '}') {
                throw fail("trailing comma before '}'");
            }
        }
        sink.endObject();
    }

    private JsonArray array(int depth) {
        at++;
        CONTAINERS_BUILT.incrementAndGet();
        JsonArray out = new JsonArray();
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            out.add(valueAt(depth));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw fail("expected ',' or ']' in an array");
            }
            skipWhitespace();
            if (peek() == ']') {
                throw fail("trailing comma before ']'");
            }
        }
        return out;
    }

    private void emitArray(int depth, Sink sink) {
        at++;
        sink.beginArray();
        skipWhitespace();
        if (peek() == ']') {
            at++;
            sink.endArray();
            return;
        }
        while (true) {
            emitAt(depth, sink);
            skipWhitespace();
            char c = next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw fail("expected ',' or ']' in an array");
            }
            skipWhitespace();
            if (peek() == ']') {
                throw fail("trailing comma before ']'");
            }
        }
        sink.endArray();
    }

    // Counts containers held open, not values.
    private int deepen(int open) {
        if (open + 1 > MAX_DEPTH) {
            throw fail("nesting deeper than " + MAX_DEPTH);
        }
        return open + 1;
    }

    private long number(char first) {
        int start = at;
        char head = first;
        boolean signed = head == '-';
        if (signed) {
            at++;
            head = peek();
        }
        boolean negativeInfinity = signed && head == 'I';
        long carried;
        if (negativeInfinity) {
            literal("Infinity");
            carried = MINUS_INFINITY;
        } else {
            int digits = at;
            long whole;
            boolean carriesWhole;
            if (head == '0') {
                at++;
                refillToCursor();
                if (at < length() && isDigit(characterAt(at))) {
                    throw fail("a number may not have a leading zero");
                }
                whole = 0;
                carriesWhole = true;
            } else {
                whole = wholeRun(digits);
                carriesWhole = at - digits <= JsonPrimitive.MAX_EXACT_LONG_DIGITS;
            }
            if (at == digits) {
                throw fail("expected a value");
            }
            if (at < length() && characterAt(at) == '.') {
                carriesWhole = false;
                at++;
                int fraction = at;
                digitRun(fraction);
                if (at == fraction) {
                    throw fail("a number must have a digit after '.'");
                }
            }
            if (at < length() && isExponentMarker(characterAt(at))) {
                carriesWhole = false;
                at++;
                refillToCursor();
                if (at < length() && isExponentSign(characterAt(at))) {
                    at++;
                }
                int exponent = at;
                digitRun(exponent);
                if (at == exponent) {
                    throw fail("a number must have a digit in its exponent");
                }
            }
            if (carriesWhole) {
                carried = digits == start ? whole : -whole;
            } else {
                carried = JsonPrimitive.NOT_CARRIED;
            }
        }
        return carried;
    }

    private long wholeRun(int from) {
        long whole = 0;
        int scan = from;
        while (true) {
            while (scan < length) {
                char c = characterAt(scan);
                if (!isDigit(c)) {
                    break;
                }
                whole = withDigit(whole, c, scan - from);
                scan++;
            }
            if (scan < length) {
                break;
            }
            if (!refill()) {
                break;
            }
        }
        at = scan;
        return whole;
    }

    private void digitRun(int from) {
        int scan = from;
        while (true) {
            while (scan < length && isDigit(characterAt(scan))) {
                scan++;
            }
            if (scan < length) {
                break;
            }
            if (!refill()) {
                break;
            }
        }
        at = scan;
    }

    private static boolean isExponentMarker(char c) {
        return c == 'e' || c == 'E';
    }

    private static boolean isExponentSign(char c) {
        return c == '+' || c == '-';
    }

    private static long withDigit(long whole, char digit, int taken) {
        return taken < JsonPrimitive.MAX_EXACT_LONG_DIGITS ? whole * DECIMAL_BASE + (digit - '0') : whole;
    }

    private String string() {
        return string(false);
    }

    private String string(boolean internMemberName) {
        expect('"');
        int start = at;
        int cursor = start;
        String plain = null;
        while (plain == null) {
            if (cursor >= length) {
                at = cursor;
                if (!refill()) {
                    throw fail("unterminated string");
                }
            } else {
                char c = characterAt(cursor);
                if (c == '"') {
                    plain = internMemberName ? internName(start, cursor) : slice(start, cursor);
                    at = cursor + 1;
                } else if (c == '\\') {
                    at = cursor;
                    plain = escaped(start);
                } else {
                    cursor++;
                }
            }
        }
        return plain;
    }

    private String internName(int start, int end) {
        int len = end - start;
        String[] table = nameTable;
        int size = nameTableSize;
        String name = null;
        for (int i = 0; name == null && i < size; i++) {
            String existing = table[i];
            if (existing.length() == len && sameChars(start, existing, len)) {
                name = existing;
            }
        }
        if (name == null) {
            name = slice(start, end);
            if (size < NAME_TABLE_CAP) {
                String[] room;
                if (table == null) {
                    room = new String[NAME_TABLE_INITIAL_CAP];
                    nameTable = room;
                } else if (size == table.length) {
                    room = java.util.Arrays.copyOf(table,
                            Math.min(table.length * NAME_TABLE_GROWTH, NAME_TABLE_CAP));
                    nameTable = room;
                } else {
                    room = table;
                }
                room[size] = name;
                nameTableSize = size + 1;
            }
        }
        return name;
    }

    private boolean sameChars(int start, String existing, int len) {
        if (text != null) {
            return text.regionMatches(start, existing, 0, len);
        }
        for (int i = 0; i < len; i++) {
            if (builder.charAt(start + i) != existing.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int escapeSeed(int prefix, int room) {
        return Math.min(room, Math.max(prefix + ESCAPE_SEED_PADDING, ESCAPE_SEED_FLOOR));
    }

    private String escaped(int start) {
        StringBuilder out = escapeBuffer(escapeSeed(at - start, length() - start));
        if (text != null) {
            out.append(text, start, at);
        } else {
            out.append(builder, start, at);
        }
        while (true) {
            int runStart = at;
            at = escapeRunEnd(runStart);
            if (at > runStart) {
                if (text != null) {
                    out.append(text, runStart, at);
                } else {
                    out.append(builder, runStart, at);
                }
            }
            while (at >= length()) {
                if (!refill()) {
                    throw fail("unterminated string");
                }
            }
            char c = characterAt(at++);
            if (c == '"') {
                break;
            }
            while (at >= length()) {
                if (!refill()) {
                    throw fail("unterminated escape");
                }
            }
            out.append(escapeValue(characterAt(at++)));
        }
        return out.toString();
    }

    private StringBuilder escapeBuffer(int seed) {
        StringBuilder out = escapeScratch;
        if (out == null) {
            out = new StringBuilder(seed);
            escapeScratch = out;
        } else {
            out.setLength(0);
            out.ensureCapacity(seed);
        }
        return out;
    }

    private int escapeRunEnd(int from) {
        int cursor = from;
        while (true) {
            while (cursor < length) {
                char pending = characterAt(cursor);
                if (pending == '"' || pending == '\\') {
                    break;
                }
                cursor++;
            }
            if (cursor < length) {
                break;
            }
            if (!refill()) {
                break;
            }
        }
        return cursor;
    }

    private char escapeValue(char escape) {
        return switch (escape) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicodeEscape();
            default -> throw fail("unknown escape");
        };
    }

    private char unicodeEscape() {
        while (at + UNICODE_ESCAPE_DIGITS > length()) {
            if (!refill()) {
                throw fail("truncated unicode escape");
            }
        }
        int code = 0;
        for (int i = 0; i < UNICODE_ESCAPE_DIGITS; i++) {
            char digit = characterAt(at + i);
            int value;
            if (digit >= '0' && digit <= '9') {
                value = digit - '0';
            } else if (digit >= 'a' && digit <= 'f') {
                value = digit - 'a' + HEX_LETTER_OFFSET;
            } else if (digit >= 'A' && digit <= 'F') {
                value = digit - 'A' + HEX_LETTER_OFFSET;
            } else {
                throw fail("a unicode escape needs four hex digits");
            }
            code = (code << HEX_DIGIT_BITS) | value;
        }
        at += UNICODE_ESCAPE_DIGITS;
        return (char) code;
    }

    private void literal(String word) {
        int start = at;
        int n = word.length();
        for (int i = 1; i < n; i++) {
            while (start + i >= length) {
                if (!refill()) {
                    throw fail("expected " + word);
                }
            }
            if (characterAt(start + i) != word.charAt(i)) {
                throw fail("expected " + word);
            }
        }
        at = start + n;
    }

    private void skipByteOrderMark() {
        if (length() != 0 && characterAt(0) == BYTE_ORDER_MARK) {
            at = 1;
        }
    }

    // Space, tab, newline and carriage return only.
    private void skipWhitespace() {
        int cursor = at;
        boolean scanning = true;
        while (scanning) {
            if (cursor < length) {
                char c = characterAt(cursor);
                if (c > ' ') {
                    scanning = false;
                } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    cursor++;
                } else {
                    scanning = false;
                }
            } else {
                scanning = refill();
            }
        }
        at = cursor;
    }

    // Returns false once nothing more can be read.
    private boolean refill() {
        if (reader == null) {
            return false;
        }
        char[] buffer = readBuffer;
        if (buffer == null) {
            buffer = new char[CHUNK];
            readBuffer = buffer;
        }
        int arrived;
        try {
            arrived = reader.read(buffer);
        } catch (IOException cannotRead) {
            throw new JsonParseException("could not read the JSON: " + cannotRead,
                    cannotRead);
        }
        if (arrived > buffer.length) {
            throw new JsonParseException("could not read the JSON: the reader answered " + arrived
                    + " characters for buffer " + buffer.length + " at offset " + length);
        }
        if (arrived == 0) {
            throw new JsonParseException("could not read the JSON: the reader answered 0"
                    + ", neither text nor its end, offset " + length);
        }
        boolean grew = arrived > 0;
        if (grew) {
            if (builder == null) {
                builder = new StringBuilder(arrived);
            }
            builder.append(buffer, 0, arrived);
            length += arrived;
        }
        return grew;
    }

    private void refillToCursor() {
        boolean more = true;
        while (more && at >= length()) {
            more = refill();
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private char peek() {
        while (at >= length()) {
            if (!refill()) {
                throw fail("unexpected end of document");
            }
        }
        return characterAt(at);
    }

    private int length() {
        return length;
    }

    private char characterAt(int index) {
        return text != null ? text.charAt(index) : builder.charAt(index);
    }

    private String slice(int start, int end) {
        return text != null ? text.substring(start, end) : builder.substring(start, end);
    }

    private CharSequence source() {
        return (text != null) ? text : builder;
    }

    private char next() {
        char c = peek();
        at++;
        return c;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw fail("expected '" + c + "'");
        }
        at++;
    }

    // The message includes the offset, never the document text.
    private JsonParseException fail(String why) {
        return new JsonParseException(why + " at offset " + at, false);
    }
}
