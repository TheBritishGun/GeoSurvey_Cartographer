package dev.openmap.map;

public final class MapRegion {

    public static final int CHUNKS = 32;

    public static final int BLOCKS = CHUNKS * 16;

    private static final int SHIFT = Integer.numberOfTrailingZeros(CHUNKS);

    private static final int BLOCK_COORDINATE_SHIFT = 4;

    private static final int REGION_KEY_SHIFT = 32;

    private static final long REGION_Z_MASK = 0xFFFFFFFFL;

    private static final int DECIMAL_RADIX = 10;

    private static final long NOT_A_NUMBER = Long.MAX_VALUE;

    private static final int PREFIX_LENGTH = 2;

    private static final int SUFFIX_LENGTH = 8;

    private static final long WIDEST = -(long) Integer.MIN_VALUE;

    private MapRegion() {
    }

    public static int of(int chunkCoord) {
        return chunkCoord >> SHIFT;
    }

    public static int ofBlock(int blockCoord) {
        return blockCoord >> (SHIFT + BLOCK_COORDINATE_SHIFT);
    }

    public static int firstChunk(int region) {
        return region << SHIFT;
    }

    public static long key(int regionX, int regionZ) {
        return ((long) regionX << REGION_KEY_SHIFT) | (regionZ & REGION_Z_MASK);
    }

    public static int x(long key) {
        return (int) (key >> REGION_KEY_SHIFT);
    }

    public static int z(long key) {
        return (int) key;
    }

    public static long keyOfChunk(int chunkX, int chunkZ) {
        return key(of(chunkX), of(chunkZ));
    }

    public static String fileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".landnav";
    }

    public static int[] fromFileName(String name) {
        int split = separator(name);
        if (split < 0) {
            return null;
        }
        long x = number(name, PREFIX_LENGTH, split);
        if (x == NOT_A_NUMBER) {
            return null;
        }
        long z = number(name, split + 1, name.length() - SUFFIX_LENGTH);
        return z == NOT_A_NUMBER ? null : new int[] {(int) x, (int) z};
    }

    public static long keyFromFileName(String name) {
        int split = separator(name);
        if (split < 0) {
            return Long.MIN_VALUE;
        }
        long x = number(name, PREFIX_LENGTH, split);
        if (x == NOT_A_NUMBER) {
            return Long.MIN_VALUE;
        }
        long z = number(name, split + 1, name.length() - SUFFIX_LENGTH);
        return z == NOT_A_NUMBER ? Long.MIN_VALUE : key((int) x, (int) z);
    }

    private static int separator(String name) {
        if (name == null || !name.startsWith("r.") || !name.endsWith(".landnav")) {
            return -1;
        }
        int end = name.length() - SUFFIX_LENGTH;
        if (end <= PREFIX_LENGTH) {
            return -1;
        }
        int split = name.indexOf('.', PREFIX_LENGTH);
        return split <= PREFIX_LENGTH || split >= end ? -1 : split;
    }

    private static long number(String name, int from, int to) {
        if (from >= to) {
            return NOT_A_NUMBER;
        }
        int at = from;
        char sign = name.charAt(at);
        boolean negative = sign == '-';
        long bound = Integer.MAX_VALUE;
        if (sign < '0') {
            if (negative) {
                bound = WIDEST;
            } else {
                if (sign != '+') {
                    return NOT_A_NUMBER;
                }
            }
            if (++at == to) {
                return NOT_A_NUMBER;
            }
        }
        long total = 0;
        boolean parses = true;
        while (parses && at < to) {
            char c = name.charAt(at++);
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else {
                digit = Character.digit(c, DECIMAL_RADIX);
                parses = digit >= 0;
            }
            if (parses) {
                total = total * DECIMAL_RADIX + digit;
                parses = total <= bound;
            }
        }
        return parses ? (negative ? -total : total) : NOT_A_NUMBER;
    }
}
