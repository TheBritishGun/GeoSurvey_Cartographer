package dev.openmap.share;

import java.nio.charset.StandardCharsets;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorldPrint {

    // Matches bedrock-probe.
    public static final int VARIABLE_LAYERS = 4;

    // Matches bedrock-probe.
    public static final int SIGNATURE_CHARS = 16;

    private static final int CHUNK_BLOCK_SHIFT = 4;

    private WorldPrint() {
    }

    private static final class Fingerprints {

        private Fingerprints() {
        }

        private static final String ZERO_SIGNATURE = "0000000000000000";

        private static final SecureRandom NONCES = new SecureRandom();

        private static final int NONCE_SLOTS = 8;

        private static final int ROLLED_PRINTS = 64;

        private static final int PREFIX_BYTES = 24;

        private static final int SHA_256_BYTES = 32;

        private static final int BITS_PER_BYTE = 8;

        private static final long UNSIGNED_BYTE_MASK = 0xFFL;

        private static final int DECIMAL_RADIX = 10;

        private static final int HEX_DIGIT_BITS = 4;

        private static final int FIRST_HEX_DIGIT_SHIFT = Long.SIZE - HEX_DIGIT_BITS;

        private static final int HEX_DIGIT_MASK = 0xF;

        private static final char[] HEX = "0123456789abcdef".toCharArray();

        private static final class Digester {
            private final MessageDigest sha;
            private final byte[] prefix = new byte[PREFIX_BYTES];
            private final byte[] out = new byte[SHA_256_BYTES];
            private byte[] rolled;

            private Digester() {
                try {
                    sha = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException impossible) {
                    throw new IllegalStateException("no SHA-256",
                            impossible);
                }
            }
        }

        private static final ThreadLocal<char[]> HEX_SCRATCH =
                ThreadLocal.withInitial(Fingerprints::newHexScratch);

        private static final ThreadLocal<Digester> DIGESTER =
                ThreadLocal.withInitial(Digester::new);

        private static final ThreadLocal<NonceSource> NONCE_SOURCE =
                ThreadLocal.withInitial(NonceSource::new);

        private static final class NonceSource {
            private final byte[] block = new byte[NONCE_SLOTS * Long.BYTES];
            private int at = NONCE_SLOTS;

            private long next() {
                if (at == NONCE_SLOTS) {
                    NONCES.nextBytes(block);
                    at = 0;
                }
                int from = at * Long.BYTES;
                at = at + 1;
                long value = 0L;
                for (int i = 0; i < Long.BYTES; i++) {
                    value = (value << BITS_PER_BYTE)
                            | (block[from + i] & UNSIGNED_BYTE_MASK);
                }
                return value;
            }
        }

        static long nonce() {
            return NONCE_SOURCE.get().next();
        }

        static long digest(byte[] bits, int x, int z) {
            return digest(bits, null, x, z);
        }

        static long digest(byte[] bits, String biomes, int x, int z) {
            Digester d = begun(x, z);
            d.sha.update(bits);
            if (biomes != null) {
                d.sha.update((byte) ':');
                d.sha.update(biomes.getBytes(StandardCharsets.UTF_8));
            }
            return finished(d);
        }

        static long digest(byte[] bits, byte[] biomes, int biomeLen, int x, int z) {
            Digester d = begun(x, z);
            d.sha.update(bits);
            if (biomes != null) {
                d.sha.update((byte) ':');
                d.sha.update(biomes, 0, biomeLen);
            }
            return finished(d);
        }

        private static Digester begun(int x, int z) {
            Digester d = DIGESTER.get();
            d.sha.reset();
            d.sha.update(d.prefix, 0, prefix(d.prefix, x, z));
            return d;
        }

        private static int prefix(byte[] buf, int x, int z) {
            int at = digits(buf, 0, x);
            buf[at++] = ':';
            at = digits(buf, at, z);
            buf[at++] = ':';
            return at;
        }

        private static int digits(byte[] buf, int at, int value) {
            boolean negative = value < 0;
            long v = negative ? -(long) value : value;
            if (negative) {
                buf[at++] = '-';
            }
            int start = at;
            do {
                buf[at++] = (byte) ('0' + (int) (v % DECIMAL_RADIX));
                v /= DECIMAL_RADIX;
            } while (v > 0);
            int i = start;
            int j = at - 1;
            while (i < j) {
                byte t = buf[i];
                buf[i] = buf[j];
                buf[j] = t;
                i++;
                j--;
            }
            return at;
        }

        private static long finished(Digester d) {
            try {
                d.sha.digest(d.out, 0, d.out.length);
            } catch (DigestException impossible) {
                throw new IllegalStateException("SHA-256 buffer is too small", impossible);
            }
            long value = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << BITS_PER_BYTE) | (d.out[i] & UNSIGNED_BYTE_MASK);
            }
            return value;
        }

        static String signature(long[] prints, int minX, int minZ) {
            return signature(prints, prints.length, minX, minZ);
        }

        static String signature(long[] prints, int length, int minX, int minZ) {
            if (length < 0 || length > prints.length) {
                throw new IndexOutOfBoundsException("read " + length + " of "
                        + prints.length + " prints");
            }
            if (length == 0) {
                return ZERO_SIGNATURE;
            }
            Digester d = begun(minX, minZ);
            byte[] rolled = d.rolled;
            if (rolled == null) {
                byte[] newRolled = new byte[ROLLED_PRINTS * Long.BYTES];
                d.rolled = newRolled;
                rolled = newRolled;
            }
            int limit = Math.min(length, ROLLED_PRINTS) * Long.BYTES;
            int at = 0;
            for (int i = 0; i < length; i++) {
                at = WireWrite.putLong(rolled, at, prints[i]);
                if (at == limit) {
                    d.sha.update(rolled, 0, at);
                    at = 0;
                }
            }
            if (at > 0) {
                d.sha.update(rolled, 0, at);
            }
            return hex(finished(d));
        }

        private static char[] newHexScratch() {
            return new char[SIGNATURE_CHARS];
        }

        static String hex(long value) {
            char[] out = HEX_SCRATCH.get();
            int i = 0;
            for (int shift = FIRST_HEX_DIGIT_SHIFT; shift >= 0; shift -= HEX_DIGIT_BITS) {
                out[i++] = HEX[(int) (value >>> shift) & HEX_DIGIT_MASK];
            }
            return new String(out);
        }
    }

    private static final class DimensionNames {

        private DimensionNames() {
        }

        private static final int BARE_CACHE_LIMIT = 64;

        private static final float DEFAULT_HASH_LOAD_FACTOR = 0.75f;

        private static final int ASCII_LIMIT = 0x80;

        private static final Map<String, String> BARE_CACHE =
                Collections.synchronizedMap(new BoundedCache());

        private static final class BoundedCache extends LinkedHashMap<String, String> {

            private static final long serialVersionUID = 1L;

            private BoundedCache() {
                super(BARE_CACHE_LIMIT, DEFAULT_HASH_LOAD_FACTOR, true);
            }

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > BARE_CACHE_LIMIT;
            }
        }

        static boolean sameDimension(String expected, String actual) {
            if (expected == null || expected.isBlank()) {
                return true;
            }
            if (expected.equals(actual)) {
                return true;
            }
            return barememo(expected).equals(barememo(actual));
        }

        private static String barememo(String id) {
            if (id == null) {
                return "";
            }
            String cached = BARE_CACHE.get(id);
            if (cached != null) {
                return cached;
            }
            String flat = bare(id);
            BARE_CACHE.put(id, flat);
            return flat;
        }

        private static String bare(String id) {
            if (id == null) {
                return "";
            }
            StringBuilder out = new StringBuilder(id.length());
            boolean split = false;
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                boolean kept;
                if (c < ASCII_LIMIT) {
                    if (c >= 'A' && c <= 'Z') {
                        c = (char) (c + ('a' - 'A'));
                    }
                    kept = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
                } else {
                    c = Character.toLowerCase(c);
                    kept = Character.isLetterOrDigit(c);
                }
                if (!kept) {
                    if (out.length() > 0 || !Character.isWhitespace(c)) {
                        split = true;
                    }
                } else {
                    if (split) {
                        out.append(':');
                        split = false;
                    }
                    out.append(c);
                }
            }
            String flat = out.toString();
            String wrapper = "resourcekey:minecraft:dimension:";
            if (flat.startsWith(wrapper)) {
                flat = flat.substring(wrapper.length());
            } else {
                if (flat.length() == wrapper.length() - 1 && wrapper.startsWith(flat)) {
                    flat = "";
                }
            }

            String defaulted = "minecraft:";
            return flat.length() > defaulted.length() && flat.startsWith(defaulted)
                    ? flat.substring(defaulted.length())
                    : flat;
        }
    }

    public static final int SCHEME_BEDROCK = 1;

    public static final int SCHEME_BEDROCK_AND_BIOME = 2;

    public static final int SCHEME_NEWEST = SCHEME_BEDROCK_AND_BIOME;

    public record Region(String name, String dimension,
                         int minX, int minZ, int maxX, int maxZ, int scheme) {

        public Region {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dimension, "dimension");
            if (name.isBlank()) {
                throw new IllegalArgumentException(
                        "a region needs a name");
            }
            if (maxX < minX || maxZ < minZ) {
                throw new IllegalArgumentException("region " + name
                        + " has corners out of order");
            }
        }

        public Region(String name, String dimension,
                      int minX, int minZ, int maxX, int maxZ) {
            this(name, dimension, minX, minZ, maxX, maxZ, SCHEME_BEDROCK);
        }

        public boolean computable() {
            return scheme >= SCHEME_BEDROCK && scheme <= SCHEME_NEWEST;
        }

        public boolean withBiome() {
            return scheme >= SCHEME_BEDROCK_AND_BIOME;
        }

        public boolean holds(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public int columns() {
            return (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    public record Reading(String signature, int read, int columns, int obfuscated) {

        public boolean complete() {
            return columns > 0 && read == columns && obfuscated == 0;
        }
    }

    public static long nonce() {
        return Fingerprints.nonce();
    }

    public static boolean sameDimension(String expected, String actual) {
        return DimensionNames.sameDimension(expected, actual);
    }

    public static List<Region> owing(List<Region> asked, List<String> remaining) {
        if (remaining == null) {
            return List.copyOf(asked);
        }
        if (remaining.isEmpty()) {
            return List.of();
        }
        List<Region> owed = new ArrayList<>();
        int askedRows = asked.size();
        int nameRows = remaining.size();
        for (int i = 0; i < askedRows; i++) {
            Region region = asked.get(i);
            for (int j = 0; j < nameRows; j++) {
                if (region.name().equalsIgnoreCase(remaining.get(j))) {
                    owed.add(region);
                    break;
                }
            }
        }
        return List.copyOf(owed);
    }

    public static boolean owesNothing(List<Region> asked, List<String> remaining) {
        if (remaining == null) {
            return asked.isEmpty();
        }
        if (remaining.isEmpty()) {
            return true;
        }
        int askedRows = asked.size();
        int nameRows = remaining.size();
        boolean noMatch = true;
        for (int i = 0; i < askedRows && noMatch; i++) {
            Region region = asked.get(i);
            for (int j = 0; j < nameRows; j++) {
                if (region.name().equalsIgnoreCase(remaining.get(j))) {
                    noMatch = false;
                    break;
                }
            }
        }
        return noMatch;
    }

    public static String saying(List<Region> owed) {
        return HandshakeWords.saying(owed);
    }

    public static String accepted(String region, List<Region> left) {
        return HandshakeWords.accepted(region, left);
    }

    public static String acceptedAt(String region) {
        return HandshakeWords.acceptedAt(region);
    }

    public static String leftToWalk(List<Region> left) {
        return HandshakeWords.leftToWalk(left);
    }

    public static int chunkOf(int block) {
        return block >> CHUNK_BLOCK_SHIFT;
    }

    // Walk order must match the digest's chunk order.
    public static int walkX(int n, int wide) {
        return n % wide;
    }

    public static int walkZ(int n, int wide) {
        return n / wide;
    }

    public static long digest(byte[] bits, int x, int z) {
        return Fingerprints.digest(bits, x, z);
    }

    // Null biomes when unused, else each column's key, newline joined, in bits' walk order.
    public static long digest(byte[] bits, String biomes, int x, int z) {
        return Fingerprints.digest(bits, biomes, x, z);
    }

    public static long digest(byte[] bits, byte[] biomes, int biomeLen, int x, int z) {
        return Fingerprints.digest(bits, biomes, biomeLen, x, z);
    }

    // minX and minZ are block coordinates.
    public static String signature(long[] prints, int minX, int minZ) {
        return Fingerprints.signature(prints, minX, minZ);
    }

    // minX and minZ are block coordinates; length counts prints, not bytes.
    public static String signature(long[] prints, int length, int minX, int minZ) {
        return Fingerprints.signature(prints, length, minX, minZ);
    }

    public static String hex(long value) {
        return Fingerprints.hex(value);
    }
}
