package dev.openmap.client;

import dev.openmap.map.LabelText;
import java.net.IDN;
import java.util.Locale;
import net.minecraft.client.Minecraft;

// A guess only; the player must see it before anything uses it.
public final class CollectorGuess {

    static final int HOME_PORT = 8123;

    private static final int DEL = 0x7F;
    private static final int FIRST_NON_ASCII = 0x80;
    private static final int SECTION_SIGN = LabelText.FORMATTING;
    private static final int SOFT_HYPHEN = LabelText.SOFT_HYPHEN;
    private static final int ZERO_WIDTH_SPACE = LabelText.ZERO_WIDTH_SPACE;
    private static final int ZERO_WIDTH_NON_JOINER = LabelText.ZERO_WIDTH_NON_JOINER;
    private static final int ZERO_WIDTH_JOINER = LabelText.ZERO_WIDTH_JOINER;
    private static final int WORD_JOINER = LabelText.WORD_JOINER;
    private static final int ZERO_WIDTH_NO_BREAK_SPACE = LabelText.ZERO_WIDTH_NO_BREAK_SPACE;
    private static final int PRIVATE_172_LOW = 16;
    private static final int PRIVATE_172_HIGH = 31;
    private static final int SHARED_100_LOW = 64;
    private static final int SHARED_100_HIGH = 127;
    private static final int DOT_COUNT = 3;
    private static final int OCTET_MAX_DIGITS = 3;
    private static final int DECIMAL_RADIX = 10;
    private static final int MAX_OCTET_VALUE = 255;
    private static final int PORT_MAX_DIGITS = 5;

    private CollectorGuess() {
    }

    public static String forCurrentServer() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? "" : forServer(ChunkCapture.shareServer(client));
    }

    public static String forServer(String serverAddress) {
        String host = hostOf(serverAddress);
        if (host.isEmpty()) {
            return "";
        }
        String guess = atHome(host) ? "http://" + host + ":" + HOME_PORT
                : "https://" + host;
        return ShareSender.endpointOf(guess, ShareSender.OBSERVE_PATH) == null
                ? "" : guess;
    }

    static String hostOf(String serverAddress) {
        if (serverAddress == null) {
            return "";
        }
        String address = serverAddress.trim().toLowerCase(Locale.ROOT);
        if (address.isEmpty()) {
            return "";
        }
        if (!charsAllowed(address)) {
            return "";
        }
        return address.charAt(0) == '[' ? bracketHost(address) : hostWithoutPort(address);
    }

    private static boolean charsAllowed(String address) {
        int length = address.length();
        int i = 0;
        boolean allowed = true;
        while (allowed && i < length) {
            char letter = address.charAt(i);
            if (letter <= ' ' || letter == '/' || letter == '@' || letter == '?'
                    || letter == '#' || letter == '%' || letter == '\\') {
                allowed = false;
            }
            if (letter >= DEL) {
                int glyph = address.codePointAt(i);
                allowed = drawnAsItself(glyph) || droppedOnJoin(glyph);
                i += Character.charCount(glyph);
            } else {
                i++;
            }
        }
        return allowed;
    }

    private static String bracketHost(String address) {
        int close = address.indexOf(']');
        if (close < 0) {
            return "";
        }
        String rest = address.substring(close + 1);
        if (!rest.isEmpty() && !(rest.charAt(0) == ':' && allDigits(rest.substring(1)))) {
            return "";
        }
        return address.substring(0, close + 1);
    }

    private static String hostWithoutPort(String address) {
        int colon = address.indexOf(':');
        if (colon < 0) {
            return address;
        }
        if (address.indexOf(':', colon + 1) >= 0) {
            return "";
        }
        return allDigits(address.substring(colon + 1)) ? address.substring(0, colon) : "";
    }

    static String asciiForm(String key) {
        if (!hasNonAscii(key)) {
            return "";
        }
        String ascii;
        try {
            ascii = IDN.toASCII(key);
        } catch (IllegalArgumentException refused) {
            ascii = "";
        }
        return ascii;
    }

    private static boolean hasNonAscii(String key) {
        boolean nonAscii = false;
        for (int i = 0; i < key.length() && !nonAscii; i++) {
            nonAscii = key.charAt(i) >= FIRST_NON_ASCII;
        }
        return nonAscii;
    }

    static boolean asciiRefused(String key) {
        boolean rejected;
        try {
            IDN.toASCII(key);
            rejected = false;
        } catch (IllegalArgumentException refused) {
            rejected = true;
        }
        return rejected;
    }

    static boolean drawnAsItself(int glyph) {
        return switch (Character.getType(glyph)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE,
                 Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false;
            default -> glyph != SECTION_SIGN;
        };
    }

    private static boolean droppedOnJoin(int glyph) {
        return glyph == SOFT_HYPHEN || glyph == ZERO_WIDTH_SPACE
                || glyph == ZERO_WIDTH_NON_JOINER || glyph == ZERO_WIDTH_JOINER
                || glyph == WORD_JOINER || glyph == ZERO_WIDTH_NO_BREAK_SPACE;
    }

    static boolean atHome(String host) {
        if (host.isEmpty()) {
            return false;
        }
        if (host.charAt(0) == '[') {
            return homeV6(host);
        }
        if (host.length() > 1 && host.charAt(host.length() - 1) == '.') {
            host = host.substring(0, host.length() - 1);
        }
        return localName(host) || privateV4(host);
    }

    private static boolean homeV6(String host) {
        return loopbackV6(host) || host.startsWith("[fc") || host.startsWith("[fd")
                || host.startsWith("[fe8") || host.startsWith("[fe9")
                || host.startsWith("[fea") || host.startsWith("[feb");
    }

    private static boolean localName(String host) {
        return host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".home.arpa")
                || (host.indexOf('.') < 0 && host.indexOf('\u3002') < 0
                && host.indexOf('\uff0e') < 0 && host.indexOf('\uff61') < 0
                && !allAsciiDigits(host));
    }

    private static boolean privateV4(String host) {
        if (host.indexOf('.') < 0 || !ipv4Literal(host)) {
            return false;
        }
        return host.startsWith("127.") || host.startsWith("10.")
                || host.startsWith("192.168.") || host.startsWith("169.254.")
                || secondOctetBetween(host, "172.", PRIVATE_172_LOW, PRIVATE_172_HIGH)
                || secondOctetBetween(host, "100.", SHARED_100_LOW, SHARED_100_HIGH);
    }

    private static boolean loopbackV6(String host) {
        if (host.indexOf(':') < 0 || !host.endsWith("]")) {
            return false;
        }
        boolean loopback;
        try {
            loopback = java.net.InetAddress.getByName(host.substring(1, host.length() - 1))
                    .isLoopbackAddress();
        } catch (java.net.UnknownHostException refused) {
            loopback = false;
        }
        return loopback;
    }

    private static boolean allAsciiDigits(String host) {
        if (host.isEmpty()) {
            return false;
        }
        boolean valid = true;
        for (int i = 0; i < host.length() && valid; i++) {
            char c = host.charAt(i);
            if (c < '0' || c > '9') {
                valid = false;
            }
        }
        return valid;
    }

    // Four dot-separated decimal octets, 0-255 each, no leading zeros.
    private static boolean ipv4Literal(String host) {
        int length = host.length();
        int octets = 0;
        int digits = 0;
        int value = 0;
        boolean valid = true;
        for (int i = 0; i < length && valid; i++) {
            char c = host.charAt(i);
            if (c == '.') {
                if (digits == 0 || octets == DOT_COUNT) {
                    valid = false;
                } else {
                    octets++;
                    digits = 0;
                    value = 0;
                }
            } else if (c >= '0' && c <= '9') {
                if (digits == OCTET_MAX_DIGITS || (digits == 1 && value == 0)) {
                    valid = false;
                } else {
                    digits++;
                    value = value * DECIMAL_RADIX + (c - '0');
                    if (value > MAX_OCTET_VALUE) {
                        valid = false;
                    }
                }
            } else {
                valid = false;
            }
        }
        return valid && octets == DOT_COUNT && digits > 0;
    }

    private static boolean secondOctetBetween(String host, String prefix, int from, int to) {
        if (!host.startsWith(prefix)) {
            return false;
        }
        int dot = host.indexOf('.', prefix.length());
        final boolean inRange;
        if (dot < 0) {
            inRange = false;
        } else {
            String second = host.substring(prefix.length(), dot);
            if (allDigits(second)) {
                int octet = Integer.parseInt(second);
                inRange = octet >= from && octet <= to;
            } else {
                inRange = false;
            }
        }
        return inRange;
    }

    private static boolean allDigits(String digits) {
        if (digits.isEmpty() || digits.length() > PORT_MAX_DIGITS) {
            return false;
        }
        boolean valid = true;
        for (int i = 0; i < digits.length() && valid; i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                valid = false;
            }
        }
        return valid;
    }
}
