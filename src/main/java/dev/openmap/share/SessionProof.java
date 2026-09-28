package dev.openmap.share;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;

public final class SessionProof {

    // How much entropy a challenge carries.
    public static final int CHALLENGE_BYTES = 32;

    public static final int CHALLENGE_CHARACTERS = CHALLENGE_BYTES * 2;

    // How long a collector keeps a challenge it has issued.
    public static final Duration LIFETIME = Duration.ofMinutes(2);

    private static final int HEX_CHARACTERS_PER_BYTE = 2;
    private static final int UNSIGNED_BYTE_MASK = 0xFF;
    private static final int HEX_NIBBLE_SHIFT = 4;
    private static final int LOW_NIBBLE_MASK = 0x0F;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static final ThreadLocal<MessageDigest> SHA_1 = ThreadLocal.withInitial(() -> {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("no SHA-1", impossible);
        }
        return digest;
    });

    private SessionProof() {
    }

    // A fresh challenge, lowercase hex.
    public static String challenge(SecureRandom random) {
        byte[] bytes = new byte[CHALLENGE_BYTES];
        random.nextBytes(bytes);
        return hex(bytes);
    }

    // Whether a string is shaped like a challenge this class issued.
    public static boolean readable(String challenge) {
        if (challenge == null || challenge.length() != CHALLENGE_CHARACTERS) {
            return false;
        }
        int i = 0;
        while (i < CHALLENGE_CHARACTERS && isLowercaseHex(challenge.charAt(i))) {
            i++;
        }
        return i == CHALLENGE_CHARACTERS;
    }

    private static boolean isLowercaseHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    // The string both sides hand to Mojang, derived from the challenge and the fingerprint.
    public static String serverId(String challenge, String fingerprint) {
        if (challenge == null || fingerprint == null) {
            throw new IllegalArgumentException("a session proof needs both halves");
        }
        MessageDigest digest = SHA_1.get();
        digest.reset();
        digest.update(challenge.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return signedHex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        byte[] out = new byte[bytes.length * HEX_CHARACTERS_PER_BYTE];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & UNSIGNED_BYTE_MASK;
            out[i * HEX_CHARACTERS_PER_BYTE] = (byte) HEX_DIGITS[b >>> HEX_NIBBLE_SHIFT];
            out[i * HEX_CHARACTERS_PER_BYTE + 1] = (byte) HEX_DIGITS[b & LOW_NIBBLE_MASK];
        }
        return new String(out, StandardCharsets.ISO_8859_1);
    }

    private static String signedHex(byte[] bytes) {
        boolean negative = bytes[0] < 0;
        if (negative) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) ~bytes[i];
            }
            for (int i = bytes.length - 1; i >= 0; i--) {
                bytes[i]++;
                if (bytes[i] != 0) {
                    break;
                }
            }
        }
        byte[] out = new byte[bytes.length * HEX_CHARACTERS_PER_BYTE + (negative ? 1 : 0)];
        int at = 0;
        if (negative) {
            out[at++] = (byte) '-';
        }
        boolean leading = true;
        for (byte value : bytes) {
            int valueInt = value & UNSIGNED_BYTE_MASK;
            int high = valueInt >>> HEX_NIBBLE_SHIFT;
            if (!leading || high != 0) {
                out[at++] = (byte) HEX_DIGITS[high];
                leading = false;
            }
            int low = valueInt & LOW_NIBBLE_MASK;
            if (!leading || low != 0) {
                out[at++] = (byte) HEX_DIGITS[low];
                leading = false;
            }
        }
        return leading ? "0" : new String(out, 0, at, StandardCharsets.ISO_8859_1);
    }
}
