package dev.openmap.json;

public final class NumberGrammar {

    private static final int DECIMAL_RADIX = 10;
    private static final int HEX_RADIX = 16;
    private static final int HEX_PREFIX_LENGTH = 2;
    private static final int MAX_DECIMAL_DIGITS = 19;
    private static final int WIDEST_TEN_POWER = 18;
    private static final int WIDEST_BIT_POWER = 63;
    private static final long WIDEST_TEN_SCALE = 1_000_000_000_000_000_000L;
    private static final long BINARY_STEP = 2L;
    private static final int SHORT_COLOUR_DIGITS = 3;
    private static final int FULL_COLOUR_DIGITS = 6;
    private static final int BITS_PER_HEX_DIGIT = 4;
    private static final int BITS_PER_COLOUR_CHANNEL = 8;

    private NumberGrammar() {
    }

    public static int webColour(String text) {
        if (text == null) {
            return -1;
        }
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) <= ' ') {
            end--;
        }
        if (start < end && text.charAt(start) == '#') {
            start++;
        }
        int digits = end - start;
        if (digits != SHORT_COLOUR_DIGITS && digits != FULL_COLOUR_DIGITS) {
            return -1;
        }
        boolean shorthand = digits == SHORT_COLOUR_DIGITS;
        int value = 0;
        for (int at = start; at < end; at++) {
            char character = text.charAt(at);
            if (!isHexDigit(character)) {
                return -1;
            }
            int digit = digitOf(character, HEX_RADIX);
            value = shorthand
                    ? (value << BITS_PER_COLOUR_CHANNEL) | (digit << BITS_PER_HEX_DIGIT) | digit
                    : (value << BITS_PER_HEX_DIGIT) | digit;
        }
        return value;
    }

    public static Long decimalLong(String text) {
        long result = decimalLongOr(text, Long.MIN_VALUE);
        return result == Long.MIN_VALUE && !text.equals("-9223372036854775808") ? null : result;
    }

    public static long decimalLongOr(String text, long ifNot) {
        int length = text.length();
        boolean negative = length > 0 && text.charAt(0) == '-';
        int at = negative || (length > 0 && text.charAt(0) == '+') ? 1 : 0;
        int digits = length - at;
        if (digits < 1 || digits > MAX_DECIMAL_DIGITS) {
            return ifNot;
        }
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplyLimit = limit / DECIMAL_RADIX;
        long negated = 0;
        boolean valid = true;
        while (at < length && valid) {
            char character = text.charAt(at);
            if (!isAsciiDigit(character) || negated < multiplyLimit) {
                valid = false;
            } else {
                int digit = character - '0';
                long scaled = negated * DECIMAL_RADIX;
                if (scaled < limit + digit) {
                    valid = false;
                } else {
                    negated = scaled - digit;
                }
            }
            at++;
        }
        return valid ? (negative ? negated : -negated) : ifNot;
    }

    static Long exactLong(String text) {
        int begin = skipBlank(text, 0, text.length());
        int end = unpaddedEnd(text, begin);
        if (begin == end) {
            return null;
        }
        char first = text.charAt(begin);
        boolean negative = first == '-';
        int start = negative || first == '+' ? begin + 1 : begin;
        boolean hex = isHexStart(text, start);
        int radix = hex ? HEX_RADIX : DECIMAL_RADIX;
        boolean plainHex = hex && text.indexOf('p', start) < 0 && text.indexOf('P', start) < 0;
        if (!plainHex && "fFdD".indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        int point = -1;
        int firstNonZero = -1;
        int lastNonZero = -1;
        boolean sawDigit = false;
        int digitsEnd = hex ? start + HEX_PREFIX_LENGTH : start;
        while (digitsEnd < end) {
            char character = text.charAt(digitsEnd);
            if (character == '.') {
                if (point >= 0) {
                    return null;
                }
                point = digitsEnd;
            } else if (digitOf(character, radix) < 0) {
                break;
            } else {
                sawDigit = true;
                if (character != '0') {
                    if (firstNonZero < 0) {
                        firstNonZero = digitsEnd;
                    }
                    lastNonZero = digitsEnd;
                }
            }
            digitsEnd++;
        }
        Long result = null;
        if (sawDigit) {
            try {
                int exponentEnd = hex ? matchBinaryExponent(text, digitsEnd)
                        : matchExponent(text, digitsEnd);
                if (digitsEnd == end || exponentEnd == end) {
                    if (firstNonZero < 0) {
                        result = 0L;
                    } else {
                        int radixPoint = point < 0 ? digitsEnd : point;
                        long exponent = digitsEnd < end ? boundedExponent(text, digitsEnd + 1, end) : 0;
                        long power = powerOf(hex, radixPoint, lastNonZero, exponent);
                        result = exactSignificand(text, hex, negative, point, firstNonZero, lastNonZero, power);
                    }
                }
            } catch (ArithmeticException outOfRange) {
                result = null;
            }
        }
        return result;
    }

    private static int unpaddedEnd(String text, int begin) {
        int end = text.length();
        while (end > begin && text.charAt(end - 1) <= ' ') {
            end--;
        }
        return end;
    }

    private static boolean isHexStart(String text, int start) {
        int length = text.length();
        return start + 1 < length && text.charAt(start) == '0'
                && (text.charAt(start + 1) == 'x' || text.charAt(start + 1) == 'X');
    }

    private static long boundedExponent(String text, int begin, int end) {
        char sign = text.charAt(begin);
        boolean negative = sign == '-';
        int at = sign == '-' || sign == '+' ? begin + 1 : begin;
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplyLimit = limit / DECIMAL_RADIX;
        long negated = 0;
        boolean valid = true;
        while (at < end && valid) {
            int digit = text.charAt(at) - '0';
            if (negated < multiplyLimit) {
                valid = false;
            } else {
                long scaled = negated * DECIMAL_RADIX;
                if (scaled < limit + digit) {
                    valid = false;
                } else {
                    negated = scaled - digit;
                }
            }
            at++;
        }
        return valid ? (negative ? negated : -negated) : (negative ? Long.MIN_VALUE : Long.MAX_VALUE);
    }

    private static Long exactSignificand(String text, boolean hex, boolean negative, int point,
            int firstNonZero, int lastNonZero, long power) {
        if (!hasNoFraction(text, hex, lastNonZero, power)) {
            return null;
        }
        int droppedBits = (int) Math.max(-power, 0);
        long whole = accumulate(text, hex, negative, point, firstNonZero, lastNonZero, droppedBits);
        return Long.valueOf(scaleUp(whole, hex, power));
    }

    private static long powerOf(boolean hex, int radixPoint, int lastNonZero, long exponent) {
        long lastPlace = radixPoint > lastNonZero
                ? radixPoint - lastNonZero - 1 : radixPoint - lastNonZero;
        return Math.addExact(hex ? 4 * lastPlace : lastPlace, exponent);
    }

    private static boolean hasNoFraction(String text, boolean hex, int lastNonZero, long power) {
        if (!hex) {
            return power >= 0;
        }
        int lastDigit = digitOf(text.charAt(lastNonZero), HEX_RADIX);
        return power >= -Integer.numberOfTrailingZeros(lastDigit);
    }

    private static long accumulate(String text, boolean hex, boolean negative, int point,
            int firstNonZero, int lastNonZero, int droppedBits) {
        int radix = hex ? HEX_RADIX : DECIMAL_RADIX;
        long whole = 0;
        for (int i = firstNonZero; i <= lastNonZero; i++) {
            if (i != point) {
                int drop = i == lastNonZero ? droppedBits : 0;
                long scale = radix >> drop;
                long digit = digitOf(text.charAt(i), radix) >> drop;
                whole = Math.multiplyExact(whole, scale);
                whole = negative ? Math.subtractExact(whole, digit) : Math.addExact(whole, digit);
            }
        }
        return whole;
    }

    private static int digitOf(char character, int radix) {
        return isAsciiDigit(character) ? character - '0' : Character.digit(character, radix);
    }

    private static long scaleUp(long whole, boolean hex, long power) {
        if (power <= 0) {
            return whole;
        }
        return hex ? timesTwoToThe(whole, power) : timesTen(whole, power);
    }

    private static long timesTen(long whole, long power) {
        if (power > WIDEST_TEN_POWER) {
            throw new ArithmeticException("long overflow");
        }
        return Math.multiplyExact(whole, tenPower(power));
    }

    private static long tenPower(long power) {
        return switch ((int) Math.min(power, WIDEST_TEN_POWER)) {
            case 0 -> 1L;
            case 1 -> 10L;
            case 2 -> 100L;
            case 3 -> 1_000L;
            case 4 -> 10_000L;
            case 5 -> 100_000L;
            case 6 -> 1_000_000L;
            case 7 -> 10_000_000L;
            case 8 -> 100_000_000L;
            case 9 -> 1_000_000_000L;
            case 10 -> 10_000_000_000L;
            case 11 -> 100_000_000_000L;
            case 12 -> 1_000_000_000_000L;
            case 13 -> 10_000_000_000_000L;
            case 14 -> 100_000_000_000_000L;
            case 15 -> 1_000_000_000_000_000L;
            case 16 -> 10_000_000_000_000_000L;
            case 17 -> 100_000_000_000_000_000L;
            default -> WIDEST_TEN_SCALE;
        };
    }

    private static long timesTwoToThe(long whole, long power) {
        if (power > WIDEST_BIT_POWER) {
            throw new ArithmeticException("long overflow");
        }
        int shift = (int) power;
        long scaled = whole << shift;
        if (scaled >> shift != whole) {
            throw new ArithmeticException("long overflow");
        }
        return scaled;
    }

    public static boolean isJavaDouble(String text) {
        int length = text.length();
        int at = skipBlank(text, 0, length);
        if (at < length && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
            at++;
        }
        int end = matchNaNOrInfinity(text, at);
        if (end < 0) {
            int body = matchHexFloat(text, at);
            if (body < 0) {
                body = matchDecimalFloat(text, at);
            }
            if (body < 0) {
                return false;
            }
            end = body < length && isFloatTypeSuffix(text.charAt(body)) ? body + 1 : body;
        }
        return skipBlank(text, end, length) == length;
    }

    static boolean isNonFiniteJavaDouble(String text) {
        int length = text.length();
        int at = skipBlank(text, 0, length);
        if (at < length && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
            at++;
        }
        int end = matchNaNOrInfinity(text, at);
        return end >= 0 && skipBlank(text, end, length) == length;
    }

    private static int skipBlank(String text, int from, int length) {
        int at = from;
        while (at < length && text.charAt(at) <= ' ') {
            at++;
        }
        return at;
    }

    private static int matchNaNOrInfinity(String text, int from) {
        if (text.startsWith("NaN", from)) {
            return from + 3;
        }
        if (text.startsWith("Infinity", from)) {
            return from + 8;
        }
        return -1;
    }

    private static int matchDecimalFloat(String text, int from) {
        int length = text.length();
        int at = from;
        while (at < length && isAsciiDigit(text.charAt(at))) {
            at++;
        }
        if (at > from) {
            if (at < length && text.charAt(at) == '.') {
                at++;
                while (at < length && isAsciiDigit(text.charAt(at))) {
                    at++;
                }
            }
            int exponentEnd = matchExponent(text, at);
            return exponentEnd >= 0 ? exponentEnd : at;
        }
        if (at < length && text.charAt(at) == '.') {
            int fractionStart = at + 1;
            int fractionEnd = fractionStart;
            while (fractionEnd < length && isAsciiDigit(text.charAt(fractionEnd))) {
                fractionEnd++;
            }
            if (fractionEnd == fractionStart) {
                return -1;
            }
            int exponentEnd = matchExponent(text, fractionEnd);
            return exponentEnd >= 0 ? exponentEnd : fractionEnd;
        }
        return -1;
    }

    private static int matchExponent(String text, int from) {
        int length = text.length();
        if (from >= length || (text.charAt(from) != 'e' && text.charAt(from) != 'E')) {
            return -1;
        }
        int at = from + 1;
        if (at < length && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
            at++;
        }
        int digitsStart = at;
        while (at < length && isAsciiDigit(text.charAt(at))) {
            at++;
        }
        return at > digitsStart ? at : -1;
    }

    private static int matchHexFloat(String text, int from) {
        int length = text.length();
        if (from + 1 >= length || text.charAt(from) != '0'
                || (text.charAt(from + 1) != 'x' && text.charAt(from + 1) != 'X')) {
            return -1;
        }
        int at = from + HEX_PREFIX_LENGTH;
        int digitsStart = at;
        while (at < length && isHexDigit(text.charAt(at))) {
            at++;
        }
        boolean hasLeadingDigits = at > digitsStart;
        int afterSignificand;
        if (at < length && text.charAt(at) == '.') {
            int fractionStart = at + 1;
            int fractionEnd = fractionStart;
            while (fractionEnd < length && isHexDigit(text.charAt(fractionEnd))) {
                fractionEnd++;
            }
            if (!hasLeadingDigits && fractionEnd == fractionStart) {
                return -1;
            }
            afterSignificand = fractionEnd;
        } else if (hasLeadingDigits) {
            afterSignificand = at;
        } else {
            return -1;
        }
        return matchBinaryExponent(text, afterSignificand);
    }

    private static int matchBinaryExponent(String text, int from) {
        int length = text.length();
        if (from >= length || (text.charAt(from) != 'p' && text.charAt(from) != 'P')) {
            return -1;
        }
        int at = from + 1;
        if (at < length && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
            at++;
        }
        int digitsStart = at;
        while (at < length && isAsciiDigit(text.charAt(at))) {
            at++;
        }
        return at > digitsStart ? at : -1;
    }

    private static boolean isAsciiDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isHexDigit(char character) {
        return isAsciiDigit(character)
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }

    private static boolean isFloatTypeSuffix(char character) {
        return character == 'f' || character == 'F' || character == 'd' || character == 'D';
    }
}
