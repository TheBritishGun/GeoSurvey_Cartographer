package dev.openmap.json;

import java.io.IOException;
import java.math.BigDecimal;

public final class JsonPrimitive implements JsonElement {

    private static final int LONGEST_EXPONENT = 9;

    private static final int DECIMAL_RADIX = 10;

    static final int MAX_EXACT_LONG_DIGITS = 18;

    static final int LONG_MAX_DIGITS = 19;

    private static final int LONGEST_PLAIN_LONG_TEXT = LONG_MAX_DIGITS;

    private static final int LONGEST_NUMBER_TEXT = 10_000;

    private static final int SMALLEST_REFUSED_SCALE = 10_000;

    private static final JsonPrimitive TRUE = new JsonPrimitive("true", Kind.BOOLEAN);

    private static final JsonPrimitive FALSE = new JsonPrimitive("false", Kind.BOOLEAN);

    static final long NOT_CARRIED = Long.MIN_VALUE;

    enum Kind {
        STRING, NUMBER, BOOLEAN
    }

    private final String text;

    private final Kind kind;

    // The plain-integer value, or NOT_CARRIED.
    private final long carried;

    private JsonPrimitive(String text, Kind kind) {
        this(text, kind, NOT_CARRIED);
    }

    private JsonPrimitive(String text, Kind kind, long carried) {
        this.text = text;
        this.kind = kind;
        this.carried = carried;
    }

    static JsonPrimitive ofString(String value) {
        return new JsonPrimitive(value, Kind.STRING);
    }

    static JsonPrimitive ofNumberToken(String token) {
        return new JsonPrimitive(token, Kind.NUMBER);
    }

    // Token must be plain digits matching value; not checked.
    static JsonPrimitive ofNumberToken(String token, long value) {
        return new JsonPrimitive(token, Kind.NUMBER, value);
    }

    static JsonPrimitive ofNumber(Number value) {
        return new JsonPrimitive(value.toString(), Kind.NUMBER);
    }

    static JsonPrimitive ofNumber(long value) {
        return new JsonPrimitive(Long.toString(value), Kind.NUMBER);
    }

    static JsonPrimitive ofNumber(double value) {
        return new JsonPrimitive(Double.toString(value), Kind.NUMBER);
    }

    static JsonPrimitive ofBoolean(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public JsonPrimitive getAsJsonPrimitive() {
        return this;
    }

    public boolean isBoolean() {
        return kind == Kind.BOOLEAN;
    }

    public boolean isString() {
        return kind == Kind.STRING;
    }

    Kind kind() {
        return kind;
    }

    String text() {
        return text;
    }

    @Override
    public String getAsString() {
        return text;
    }

    @Override
    public String toString() {
        if (kind == Kind.STRING) {
            return JsonText.shown(this);
        }
        return text;
    }

    void writeTo(JsonText.Emitter out) throws IOException {
        Kind written = kind;
        String token = text;
        if (written == Kind.STRING) {
            out.value(token);
            return;
        }
        if (written == Kind.NUMBER && JsonText.nonFinite(token) && !out.lenient()) {
            throw new IllegalArgumentException("not a JSON number: " + token);
        }
        out.bare(token);
    }

    @Override
    public double getAsDouble() {
        if (kind == Kind.NUMBER) {
            double fast = fastIntegerDouble(text);
            if (!Double.isNaN(fast)) {
                return fast;
            }
        }
        return Double.parseDouble(text);
    }

    // Truncates toward zero; 1e19 wraps negative; 1e400 reads as 0.
    @Override
    public long getAsLong() {
        long result;
        if (kind != Kind.NUMBER) {
            result = Long.parseLong(text);
        } else if (carried != NOT_CARRIED) {
            result = carried;
        } else {
            result = truncatedLong(text);
        }
        return result;
    }

    // Keeps the low 32 bits.
    @Override
    public int getAsInt() {
        int result;
        if (kind != Kind.NUMBER) {
            result = Integer.parseInt(text);
        } else if (carried != NOT_CARRIED) {
            result = (int) carried;
        } else {
            result = truncatedInt(text);
        }
        return result;
    }

    @Override
    public boolean getAsBoolean() {
        if (kind == Kind.BOOLEAN) {
            return "true".equals(text);
        }
        return Boolean.parseBoolean(text);
    }

    private static long truncatedLong(String token) {
        int length = token.length();
        if (length > LONGEST_NUMBER_TEXT) {
            throw tooLong(length);
        }
        int wholeStart = length > 0 && token.charAt(0) == '-' ? 1 : 0;
        int wholeEnd = wholeStart;
        long whole = 0;
        while (wholeEnd < length) {
            char c = token.charAt(wholeEnd);
            if (!isDigit(c)) {
                break;
            }
            whole = whole * DECIMAL_RADIX + (c - '0');
            wholeEnd++;
        }
        long result;
        if (wholeEnd == length && wholeEnd != wholeStart) {
            result = wholeStart == 0 ? whole : -whole;
        } else {
            result = truncatedDecimal(token, wholeStart, wholeEnd, whole);
        }
        return result;
    }

    private static long truncatedDecimal(String token, int wholeStart, int wholeEnd,
            long whole) {
        int length = token.length();
        int fractionEnd = wholeEnd;
        if (wholeEnd < length && token.charAt(wholeEnd) == '.') {
            fractionEnd = digitsEnd(token, wholeEnd + 1);
        }
        int at = fractionEnd;
        int exponent;
        boolean validExponent = true;
        if (at < length) {
            char marker = token.charAt(at);
            if (marker == 'e' || marker == 'E') {
                at++;
                boolean negativeExponent;
                if (at < length) {
                    char sign = token.charAt(at);
                    negativeExponent = sign == '-';
                    if (negativeExponent || sign == '+') {
                        at++;
                    }
                } else {
                    negativeExponent = false;
                }
                int significant = 0;
                boolean exponentOverflow = false;
                int scannedExponent = 0;
                int scan = at;
                while (scan < length && isDigit(token.charAt(scan))) {
                    char c = token.charAt(scan);
                    if (c != '0' || significant > 0) {
                        significant++;
                        if (significant > LONGEST_EXPONENT) {
                            exponentOverflow = true;
                            break;
                        }
                    }
                    scannedExponent = scannedExponent * DECIMAL_RADIX + (c - '0');
                    scan++;
                }
                if (scan == at || exponentOverflow) {
                    validExponent = false;
                    exponent = 0;
                } else {
                    at = scan;
                    exponent = negativeExponent ? -scannedExponent : scannedExponent;
                }
            } else {
                exponent = 0;
            }
        } else {
            exponent = 0;
        }
        long result;
        if (!validExponent || wholeEnd == wholeStart || at != length) {
            result = longViaBigDecimal(token);
        } else {
            requireReadableScale(wholeEnd, fractionEnd, exponent);
            long magnitude = exponent == 0
                    ? whole : pointMoved(token, wholeStart, wholeEnd, fractionEnd, exponent);
            result = wholeStart == 0 ? magnitude : -magnitude;
        }
        return result;
    }

    private static long pointMoved(String token, int wholeStart, int wholeEnd,
            int fractionEnd, int exponent) {
        long wholeDigits = wholeEnd - wholeStart;
        long digits = fractionEnd == wholeEnd ? wholeDigits : fractionEnd - wholeStart - 1;
        long point = wholeDigits + exponent;
        if (point - digits >= Long.SIZE) {
            return 0;
        }
        long magnitude = 0;
        for (long place = Math.max(0, point - Long.SIZE); place < point; place++) {
            int digit;
            if (place < digits) {
                long at = place < wholeDigits ? wholeStart + place : wholeStart + place + 1;
                digit = token.charAt((int) at) - '0';
            } else {
                digit = 0;
            }
            magnitude = magnitude * DECIMAL_RADIX + digit;
        }
        return magnitude;
    }

    private static int truncatedInt(String token) {
        int length = token.length();
        if (length > LONGEST_NUMBER_TEXT) {
            throw tooLong(length);
        }
        int wholeStart = length > 0 && token.charAt(0) == '-' ? 1 : 0;
        int wholeEnd = wholeStart;
        int whole = 0;
        while (wholeEnd < length) {
            char c = token.charAt(wholeEnd);
            if (!isDigit(c)) {
                break;
            }
            whole = whole * DECIMAL_RADIX + (c - '0');
            wholeEnd++;
        }
        int result;
        if (wholeEnd == length && wholeEnd != wholeStart) {
            result = wholeStart == 0 ? whole : -whole;
        } else {
            result = truncatedDecimalInt(token, wholeStart, wholeEnd, whole);
        }
        return result;
    }

    private static int truncatedDecimalInt(String token, int wholeStart, int wholeEnd,
            int whole) {
        int length = token.length();
        int fractionEnd = wholeEnd;
        if (wholeEnd < length && token.charAt(wholeEnd) == '.') {
            fractionEnd = digitsEnd(token, wholeEnd + 1);
        }
        int at = fractionEnd;
        int exponent;
        boolean validExponent = true;
        if (at < length) {
            char marker = token.charAt(at);
            if (marker == 'e' || marker == 'E') {
                at++;
                boolean negativeExponent;
                if (at < length) {
                    char sign = token.charAt(at);
                    negativeExponent = sign == '-';
                    if (negativeExponent || sign == '+') {
                        at++;
                    }
                } else {
                    negativeExponent = false;
                }
                int significant = 0;
                boolean exponentOverflow = false;
                int scannedExponent = 0;
                int scan = at;
                while (scan < length && isDigit(token.charAt(scan))) {
                    char c = token.charAt(scan);
                    if (c != '0' || significant > 0) {
                        significant++;
                        if (significant > LONGEST_EXPONENT) {
                            exponentOverflow = true;
                            break;
                        }
                    }
                    scannedExponent = scannedExponent * DECIMAL_RADIX + (c - '0');
                    scan++;
                }
                if (scan == at || exponentOverflow) {
                    validExponent = false;
                    exponent = 0;
                } else {
                    at = scan;
                    exponent = negativeExponent ? -scannedExponent : scannedExponent;
                }
            } else {
                exponent = 0;
            }
        } else {
            exponent = 0;
        }
        int result;
        if (!validExponent || wholeEnd == wholeStart || at != length) {
            result = parseBigDecimal(token).intValue();
        } else {
            requireReadableScale(wholeEnd, fractionEnd, exponent);
            int magnitude = exponent == 0
                    ? whole : pointMovedInt(token, wholeStart, wholeEnd, fractionEnd, exponent);
            result = wholeStart == 0 ? magnitude : -magnitude;
        }
        return result;
    }

    private static int pointMovedInt(String token, int wholeStart, int wholeEnd,
            int fractionEnd, int exponent) {
        int wholeDigits = wholeEnd - wholeStart;
        int digits = fractionEnd == wholeEnd ? wholeDigits : fractionEnd - wholeStart - 1;
        int point = wholeDigits + exponent;
        if (point - digits >= Integer.SIZE) {
            return 0;
        }
        int magnitude = 0;
        for (int place = Math.max(0, point - Integer.SIZE); place < point; place++) {
            int digit;
            if (place < digits) {
                int at = place < wholeDigits ? wholeStart + place : wholeStart + place + 1;
                digit = token.charAt(at) - '0';
            } else {
                digit = 0;
            }
            magnitude = magnitude * DECIMAL_RADIX + digit;
        }
        return magnitude;
    }

    private static long longViaBigDecimal(String token) {
        if (isPlainLongShape(token)) {
            long viaGrammar = NumberGrammar.decimalLongOr(token, Long.MIN_VALUE);
            if (viaGrammar != Long.MIN_VALUE) {
                return viaGrammar;
            }
        }
        return parseBigDecimal(token).longValue();
    }

    static boolean isPlainLongShape(String token) {
        int length = token.length();
        boolean plain = length != 0 && length <= LONGEST_PLAIN_LONG_TEXT;
        if (plain) {
            int at = token.charAt(0) == '-' || token.charAt(0) == '+' ? 1 : 0;
            plain = at != length;
            while (at < length && plain) {
                plain = isDigit(token.charAt(at));
                at++;
            }
        }
        return plain;
    }

    private static BigDecimal parseBigDecimal(String token) {
        BigDecimal decimal = new BigDecimal(token);
        long scale = decimal.scale();
        if (Math.abs(scale) >= SMALLEST_REFUSED_SCALE) {
            throw unsupportedScale(scale);
        }
        return decimal;
    }

    private static void requireReadableScale(int wholeEnd, int fractionEnd, int exponent) {
        long fractionDigits = fractionEnd == wholeEnd ? 0 : fractionEnd - wholeEnd - 1;
        long scale = fractionDigits - exponent;
        if (Math.abs(scale) >= SMALLEST_REFUSED_SCALE) {
            throw unsupportedScale(scale);
        }
    }

    static double fastIntegerDouble(String token) {
        int length = token.length();
        boolean negative = length > 0 && token.charAt(0) == '-';
        int at = negative ? 1 : 0;
        double result = Double.NaN;
        if (at != length) {
            long accumulated = 0;
            int digits = 0;
            boolean valid = true;
            while (at < length && valid) {
                char c = token.charAt(at);
                if (!isDigit(c) || digits == MAX_EXACT_LONG_DIGITS) {
                    valid = false;
                } else {
                    accumulated = accumulated * DECIMAL_RADIX + (c - '0');
                    digits++;
                    at++;
                }
            }
            if (valid) {
                double value = (double) accumulated;
                result = negative ? -value : value;
            }
        }
        return result;
    }

    private static NumberFormatException tooLong(int length) {
        return new NumberFormatException("number text of " + length
                + " characters is longer than " + LONGEST_NUMBER_TEXT);
    }

    private static NumberFormatException unsupportedScale(long scale) {
        return new NumberFormatException("number scale of " + scale + " is "
                + SMALLEST_REFUSED_SCALE + " or more from zero");
    }

    private static int digitsEnd(String token, int from) {
        int end = token.length();
        int at = from;
        while (at < end && isDigit(token.charAt(at))) {
            at++;
        }
        return at;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
