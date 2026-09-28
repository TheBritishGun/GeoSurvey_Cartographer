package dev.openmap.json;

import java.io.IOException;
import java.io.Writer;

// Non-ASCII and lone surrogates are written unescaped.
public final class JsonText {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final int CONTROL_ESCAPE_LIMIT = 0x20;

    private static final int NIBBLE_SHIFT = 4;

    private static final int HEX_DIGIT_MASK = 0xF;

    private static final int ASCII_LIMIT = 128;

    private static final char NEXT_LINE = 0x0085;

    public static final char ARABIC_LETTER_MARK = 0x061C;

    public static final char LEFT_TO_RIGHT_MARK = 0x200E;

    public static final char RIGHT_TO_LEFT_MARK = 0x200F;

    public static final char LEFT_TO_RIGHT_EMBEDDING = 0x202A;

    public static final char RIGHT_TO_LEFT_OVERRIDE = 0x202E;

    public static final char LEFT_TO_RIGHT_ISOLATE = 0x2066;

    public static final char POP_DIRECTIONAL_ISOLATE = 0x2069;

    private static final int INDENT_SPACES_PER_LEVEL = 2;

    private static final int HIGH_NIBBLE_SHIFT = 12;

    private static final int MIDDLE_NIBBLE_SHIFT = 8;

    private static final int HIGH_DIGIT_INDEX = 2;

    private static final int UPPER_MIDDLE_DIGIT_INDEX = 3;

    private static final int LOWER_MIDDLE_DIGIT_INDEX = 4;

    private static final int LOW_DIGIT_INDEX = 5;

    private static final int UNICODE_ESCAPE_LENGTH = LOW_DIGIT_INDEX + 1;

    public static final char LINE_SEPARATOR = 0x2028;

    public static final char PARAGRAPH_SEPARATOR = 0x2029;

    // Escapes for U+0000 to U+001F plus the two JSON always requires.
    private static final String[] ESCAPES = new String[CONTROL_ESCAPE_LIMIT];

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    static {
        for (int c = 0; c < CONTROL_ESCAPE_LIMIT; c++) {
            ESCAPES[c] = "\\u00" + HEX[(c >>> NIBBLE_SHIFT) & HEX_DIGIT_MASK]
                    + HEX[c & HEX_DIGIT_MASK];
        }
        ESCAPES['\t'] = "\\t";
        ESCAPES['\b'] = "\\b";
        ESCAPES['\n'] = "\\n";
        ESCAPES['\r'] = "\\r";
        ESCAPES['\f'] = "\\f";
    }

    // PLAIN escapes control characters, the quote and the backslash; HTML also escapes < > & = and the apostrophe.
    private static final boolean[] NEEDS_ESCAPE_PLAIN = new boolean[ASCII_LIMIT];

    private static final boolean[] NEEDS_ESCAPE_HTML = new boolean[ASCII_LIMIT];

    static {
        for (int c = 0; c < CONTROL_ESCAPE_LIMIT; c++) {
            NEEDS_ESCAPE_PLAIN[c] = true;
            NEEDS_ESCAPE_HTML[c] = true;
        }
        NEEDS_ESCAPE_PLAIN['"'] = true;
        NEEDS_ESCAPE_PLAIN['\\'] = true;
        NEEDS_ESCAPE_HTML['"'] = true;
        NEEDS_ESCAPE_HTML['\\'] = true;
        NEEDS_ESCAPE_HTML['<'] = true;
        NEEDS_ESCAPE_HTML['>'] = true;
        NEEDS_ESCAPE_HTML['&'] = true;
        NEEDS_ESCAPE_HTML['='] = true;
        NEEDS_ESCAPE_HTML['\''] = true;
    }

    private static final String INDENT = " ".repeat(INDENT_SPACES_PER_LEVEL
            * (JsonParser.MAX_DEPTH + 1));

    private static final int SHOWN_CAPACITY = 256;

    private JsonText() {
    }

    static void write(JsonElement value, StringBuilder out, boolean pretty,
            boolean htmlSafe) {
        write(value, out, pretty, htmlSafe, false);
    }

    // Compact text: no spaces, no HTML escaping.
    static String shown(JsonElement value) {
        StringBuilder out = new StringBuilder(SHOWN_CAPACITY);
        write(value, out, false, false, true);
        return out.toString();
    }

    private static void write(JsonElement value, StringBuilder out, boolean pretty,
            boolean htmlSafe, boolean lenient) {
        Emitter emitter = SCRATCH.get().emitter;
        emitter.reset(out, null, pretty, htmlSafe, lenient);
        try {
            write(value, emitter);
        } catch (IOException impossible) {
            // Never reached: this emitter has no sink to drain.
            throw new AssertionError(impossible);
        } finally {
            emitter.release();
        }
    }

    static void write(JsonElement value, Emitter out) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        switch (value) {
            case JsonPrimitive primitive -> primitive.writeTo(out);
            case JsonObject object -> object.writeTo(out);
            case JsonArray array -> array.writeTo(out);
            case JsonNull literal -> literal.writeTo(out);
        }
    }

    static String describe(JsonElement value) {
        return switch (value) {
            case JsonObject object -> "object";
            case JsonArray array -> "array";
            case JsonPrimitive primitive -> "primitive";
            case JsonNull literal -> "null";
        };
    }

    private static boolean isNelOrBidiControl(char c) {
        return c == NEXT_LINE || c == ARABIC_LETTER_MARK
                || (c >= LEFT_TO_RIGHT_MARK && c <= RIGHT_TO_LEFT_MARK)
                || (c >= LEFT_TO_RIGHT_EMBEDDING && c <= RIGHT_TO_LEFT_OVERRIDE)
                || (c >= LEFT_TO_RIGHT_ISOLATE && c <= POP_DIRECTIONAL_ISOLATE);
    }

    public static boolean breaksALineOrSteers(char c) {
        return c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR || isNelOrBidiControl(c);
    }

    static boolean nonFinite(String token) {
        int last = token.length() - 1;
        if (last < 0) {
            return false;
        }
        char end = token.charAt(last);
        if (end != 'N' && end != 'y') {
            return false;
        }
        return "NaN".equals(token) || "Infinity".equals(token)
                || "-Infinity".equals(token);
    }

    // Two spaces per level, a bare \n, never \r\n.
    private static void newline(StringBuilder out, boolean pretty, int depth) {
        if (!pretty) {
            return;
        }
        out.append('\n');
        out.append(INDENT, 0, INDENT_SPACES_PER_LEVEL * depth);
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append(SCRATCH.get().escape.set(c));
    }

    private static String unicodeEscape(char c) {
        return switch (c) {
            case '<' -> "\\u003c";
            case '>' -> "\\u003e";
            case '&' -> "\\u0026";
            case '=' -> "\\u003d";
            case '\'' -> "\\u0027";
            case LINE_SEPARATOR -> "\\u2028";
            case PARAGRAPH_SEPARATOR -> "\\u2029";
            default -> "\\u" + HEX[c >>> HIGH_NIBBLE_SHIFT]
                    + HEX[(c >>> MIDDLE_NIBBLE_SHIFT) & HEX_DIGIT_MASK]
                    + HEX[(c >>> NIBBLE_SHIFT) & HEX_DIGIT_MASK]
                    + HEX[c & HEX_DIGIT_MASK];
        };
    }

    interface Tokens<E extends Exception> {

        void beginArray();

        void endArray();

        void beginObject();

        void endObject();

        void name(String name) throws E;

        void value(String text) throws E;

        void value(Number number);

        default void value(int number) {
            value((Number) Integer.valueOf(number));
        }

        default void value(long number) {
            value((Number) Long.valueOf(number));
        }

        default void value(float number) {
            value((Number) Float.valueOf(number));
        }

        default void value(double number) {
            value((Number) Double.valueOf(number));
        }

        void value(boolean flag);

        void nullValue();

        void spill() throws E;
    }

    static final class TooDeep extends RuntimeException {

        TooDeep(String message) {
            super(message);
        }
    }

    static final class Emitter implements Tokens<IOException> {

        private static final int SPILL_AT = 8192;

        private StringBuilder out;

        private Appendable sink;

        private boolean pretty;

        private boolean htmlSafe;

        private boolean lenient;

        private int depth;

        private boolean empty;

        private boolean named;

        private char[] chunk;

        Emitter(Appendable sink, boolean pretty, boolean htmlSafe) {
            this(new StringBuilder(), sink, pretty, htmlSafe, false);
        }

        private Emitter() {
        }

        private Emitter(StringBuilder out, Appendable sink, boolean pretty,
                boolean htmlSafe, boolean lenient) {
            reset(out, sink, pretty, htmlSafe, lenient);
        }

        private void reset(StringBuilder out, Appendable sink, boolean pretty,
                boolean htmlSafe, boolean lenient) {
            this.out = out;
            this.sink = sink;
            this.pretty = pretty;
            this.htmlSafe = htmlSafe;
            this.lenient = lenient;
            depth = 0;
            empty = false;
            named = false;
        }

        private void release() {
            out = null;
            sink = null;
            depth = 0;
            empty = false;
            named = false;
        }

        // Drains mid-string too, never splitting a surrogate pair.
        private void string(String value, StringBuilder out, boolean htmlSafe)
                throws IOException {
            boolean[] table = htmlSafe ? NEEDS_ESCAPE_HTML : NEEDS_ESCAPE_PLAIN;
            out.append('"');
            final int n = value.length();
            for (int i = 0; i < n; i++) {
                char c = value.charAt(i);
                boolean escape = c < ASCII_LIMIT ? table[c] : breaksALineOrSteers(c);
                if (escape) {
                    if (c < CONTROL_ESCAPE_LIMIT) {
                        out.append(ESCAPES[c]);
                    } else if (c == '"') {
                        out.append("\\\"");
                    } else if (c == '\\') {
                        out.append("\\\\");
                    } else {
                        appendUnicodeEscape(out, c);
                    }
                } else {
                    out.append(c);
                }
                if (sink != null && out.length() >= SPILL_AT && !Character.isHighSurrogate(c)) {
                    drain();
                }
            }
            out.append('"');
        }

        @Override
        public void beginArray() {
            open('[');
        }

        @Override
        public void endArray() {
            close(']');
        }

        @Override
        public void beginObject() {
            open('{');
        }

        @Override
        public void endObject() {
            close('}');
        }

        @Override
        public void name(String name) throws IOException {
            next();
            string(name, out, htmlSafe);
            out.append(':');
            if (pretty) {
                out.append(' ');
            }
            named = true;
        }

        @Override
        public void value(String text) throws IOException {
            separate();
            string(text, out, htmlSafe);
        }

        @Override
        public void value(Number number) {
            // A boxed Float is not widened to double before formatting.
            if (number instanceof Integer boxed) {
                separate();
                out.append(boxed.intValue());
            } else if (number instanceof Long boxed) {
                separate();
                out.append(boxed.longValue());
            } else if (number instanceof Double boxed) {
                double asDouble = boxed.doubleValue();
                if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
                    throw nonFiniteDouble(asDouble);
                }
                separate();
                out.append(asDouble);
            } else {
                String text = number.toString();
                if (nonFinite(text)) {
                    throw new IllegalArgumentException("not a legal JSON number: " + text);
                }
                bare(text);
            }
        }

        @Override
        public void value(int number) {
            separate();
            out.append(number);
        }

        @Override
        public void value(long number) {
            separate();
            out.append(number);
        }

        @Override
        public void value(float number) {
            if (Float.isNaN(number) || Float.isInfinite(number)) {
                throw nonFiniteFloat(number);
            }
            separate();
            out.append(number);
        }

        @Override
        public void value(double number) {
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw nonFiniteDouble(number);
            }
            separate();
            out.append(number);
        }

        private static IllegalArgumentException nonFiniteFloat(float number) {
            return new IllegalArgumentException("not a legal JSON number: " + number);
        }

        private static IllegalArgumentException nonFiniteDouble(double number) {
            return new IllegalArgumentException("not a legal JSON number: " + number);
        }

        @Override
        public void value(boolean flag) {
            bare(flag ? "true" : "false");
        }

        @Override
        public void nullValue() {
            bare("null");
        }

        @Override
        public void spill() throws IOException {
            if (out.length() >= SPILL_AT) {
                drain();
            }
        }

        void finish() throws IOException {
            drain();
        }

        void bare(String text) {
            separate();
            out.append(text);
        }

        boolean lenient() {
            return lenient;
        }

        private void open(char bracket) {
            if (depth >= JsonParser.MAX_DEPTH) {
                throw new TooDeep("nesting deeper than " + JsonParser.MAX_DEPTH);
            }
            separate();
            out.append(bracket);
            depth++;
            empty = true;
        }

        private void close(char bracket) {
            depth--;
            if (!empty) {
                newline(out, pretty, depth);
            }
            out.append(bracket);
            empty = false;
        }

        private void separate() {
            if (named) {
                named = false;
            } else if (depth > 0) {
                next();
            } else {
            }
        }

        private void next() {
            if (!empty) {
                out.append(',');
            }
            empty = false;
            newline(out, pretty, depth);
        }

        private void drain() throws IOException {
            if (sink instanceof Writer writer) {
                final int length = out.length();
                int piece = Math.min(SPILL_AT, length);
                if (chunk == null || chunk.length < piece) {
                    chunk = new char[piece];
                }
                for (int from = 0; from < length; from += chunk.length) {
                    int to = Math.min(length, from + chunk.length);
                    out.getChars(from, to, chunk, 0);
                    writer.write(chunk, 0, to - from);
                }
            } else {
                sink.append(out);
            }
            out.setLength(0);
        }
    }

    private static final class Scratch {

        private final Emitter emitter = new Emitter();

        private final UnicodeEscape escape = new UnicodeEscape();
    }

    private static final class UnicodeEscape implements CharSequence {

        private char value;

        UnicodeEscape set(char value) {
            this.value = value;
            return this;
        }

        @Override
        public int length() {
            return UNICODE_ESCAPE_LENGTH;
        }

        @Override
        public char charAt(int index) {
            return switch (index) {
            case 0 -> '\\';
            case 1 -> 'u';
            case HIGH_DIGIT_INDEX -> HEX[value >>> HIGH_NIBBLE_SHIFT];
            case UPPER_MIDDLE_DIGIT_INDEX -> HEX[(value >>> MIDDLE_NIBBLE_SHIFT)
                    & HEX_DIGIT_MASK];
            case LOWER_MIDDLE_DIGIT_INDEX -> HEX[(value >>> NIBBLE_SHIFT) & HEX_DIGIT_MASK];
            case LOW_DIGIT_INDEX -> HEX[value & HEX_DIGIT_MASK];
            default -> throw new IndexOutOfBoundsException("index: " + index);
            };
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            StringBuilder out = new StringBuilder(end - start);
            for (int index = start; index < end; index++) {
                out.append(charAt(index));
            }
            return out.toString();
        }
    }

}
