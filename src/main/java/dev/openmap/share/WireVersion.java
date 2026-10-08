package dev.openmap.share;

import dev.openmap.json.JsonElement;

final class WireVersion {

    private static final int MAX_DIGITS = 9;

    private static final int DECIMAL_RADIX = 10;

    private WireVersion() {
    }

    static boolean is(JsonElement value, int want) {
        return SharedRecord.isNumber(value) && is(value.getAsString(), want);
    }

    static boolean is(CharSequence value, int want) {
        int length = value.length();
        boolean valid = length > 0 && length <= MAX_DIGITS;
        int number = 0;
        for (int at = 0; at < length && valid; at++) {
            char digit = value.charAt(at);
            if (digit < '0' || digit > '9') {
                valid = false;
            } else {
                number = number * DECIMAL_RADIX + digit - '0';
            }
        }
        return valid && number == want;
    }
}
