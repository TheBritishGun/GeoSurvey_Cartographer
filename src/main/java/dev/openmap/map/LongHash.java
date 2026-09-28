package dev.openmap.map;

final class LongHash {

    static final int MINIMUM_CAPACITY = 4;

    static final int DEFAULT_CAPACITY = 16;

    // Largest power of two an int-indexed array can have.
    static final int MAXIMUM_CAPACITY = 1 << 30;

    private static final long MIX_MULTIPLIER = 0x9E3779B97F4A7C15L;

    private static final int MIX_FIRST_SHIFT = 32;

    private static final int MIX_SECOND_SHIFT = 16;

    private static final int LOAD_FACTOR_NUMERATOR = 3;

    private static final int LOAD_FACTOR_DENOMINATOR = 4;

    private LongHash() {
    }

    static long mix(long key) {
        long h = key * MIX_MULTIPLIER;
        h ^= h >>> MIX_FIRST_SHIFT;
        return h ^ (h >>> MIX_SECOND_SHIFT);
    }

    // The smallest power-of-two table that holds expected keys without growing, at 0.75 load factor.
    static int tableSizeFor(int expected) {
        if (expected < 0) {
            throw new IllegalArgumentException("expected must be >= 0, was " + expected);
        }
        long needed = ((long) expected * LOAD_FACTOR_DENOMINATOR
                + LOAD_FACTOR_NUMERATOR - 1) / LOAD_FACTOR_NUMERATOR;
        if (needed > MAXIMUM_CAPACITY) {
            return MAXIMUM_CAPACITY;
        }
        int capacity = Math.max(MINIMUM_CAPACITY, Integer.highestOneBit((int) (needed - 1)) << 1);
        return capacity;
    }

    // The occupancy at which a table of capacity slots is grown: three quarters, exact for a power of two.
    static int thresholdFor(int capacity) {
        return capacity - capacity / LOAD_FACTOR_DENOMINATOR;
    }
}
