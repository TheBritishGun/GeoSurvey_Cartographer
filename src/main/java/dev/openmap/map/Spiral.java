package dev.openmap.map;

public final class Spiral {

    private static final long[] EMPTY = new long[0];

    private static final int DIAMETER_RADIUS_MULTIPLIER = 2;

    private static final int SQUARED_DISTANCE_STEP_INCREMENT = 2;

    private static final int RING_CELLS_PER_RADIUS = 8;

    private static final long LOW_COORDINATE_MASK = 0xFFFFFFFFL;

    private static final int PACKED_X_SHIFT = 32;

    private Spiral() {
    }

    public static long[] outward(int centreX, int centreZ, int radius) {
        if (radius < 0) {
            return EMPTY;
        }
        long[] out;
        if (radius == 0) {
            out = new long[] {pack(centreX, centreZ)};
        } else {
            int side = radius * DIAMETER_RADIUS_MULTIPLIER + 1;
            int maximumDistance = radius * (side - 1);
            out = new long[side * side];
            fillOutward(centreX, centreZ, radius, side, maximumDistance, out);
        }
        return out;
    }

    public static int outwardInto(int centreX, int centreZ, int radius, long[] into) {
        if (radius < 0) {
            return 0;
        }
        int side = radius * 2 + 1;
        int maximumDistance = radius * (side - 1);
        int cells = side * side;
        if (into.length < cells) {
            throw new IllegalArgumentException("radius " + radius
                    + " needs " + cells + " cells, only "
                    + into.length + " given");
        }
        fillOutward(centreX, centreZ, radius, side, maximumDistance, into);
        return cells;
    }

    private static void fillOutward(int centreX, int centreZ, int radius, int side,
            int maximumDistance, long[] out) {
        int radiusSquared = maximumDistance >>> 1;
        int initialStep = 1 - (side - 1);
        int[] offsets = new int[maximumDistance + 1];
        int dzSquared = radiusSquared;
        int dzStep = initialStep;
        for (int dz = -radius; dz <= radius; dz++) {
            int dxSquared = radiusSquared;
            int dxStep = initialStep;
            for (int dx = -radius; dx <= radius; dx++) {
                offsets[dxSquared + dzSquared]++;
                dxSquared += dxStep;
                dxStep += SQUARED_DISTANCE_STEP_INCREMENT;
            }
            dzSquared += dzStep;
            dzStep += SQUARED_DISTANCE_STEP_INCREMENT;
        }
        int next = 0;
        for (int distance = 0; distance < offsets.length; distance++) {
            int count = offsets[distance];
            offsets[distance] = next;
            next += count;
        }
        int dxSquared = radiusSquared;
        int dxStep = initialStep;
        for (int dx = -radius; dx <= radius; dx++) {
            dzSquared = radiusSquared;
            dzStep = initialStep;
            for (int dz = -radius; dz <= radius; dz++) {
                int distanceSquared = dxSquared + dzSquared;
                out[offsets[distanceSquared]++] = pack(centreX + dx, centreZ + dz);
                dzSquared += dzStep;
                dzStep += SQUARED_DISTANCE_STEP_INCREMENT;
            }
            dxSquared += dxStep;
            dxStep += SQUARED_DISTANCE_STEP_INCREMENT;
        }
    }

    public static long[] ring(int centreX, int centreZ, int radius) {
        if (radius < 0) {
            return EMPTY;
        }
        long[] out;
        if (radius == 0) {
            out = new long[] {pack(centreX, centreZ)};
        } else {
            out = new long[radius * RING_CELLS_PER_RADIUS];
            int at = 0;
            long zLow = (((long) centreZ) - radius) & LOW_COORDINATE_MASK;
            long zHigh = (((long) centreZ) + radius) & LOW_COORDINATE_MASK;
            for (int dx = -radius; dx <= radius; dx++) {
                long column = (((long) centreX) + dx) << PACKED_X_SHIFT;
                out[at++] = column | zLow;
                out[at++] = column | zHigh;
            }
            long xLow = (((long) centreX) - radius) << PACKED_X_SHIFT;
            long xHigh = (((long) centreX) + radius) << PACKED_X_SHIFT;
            for (int dz = -radius + 1; dz <= radius - 1; dz++) {
                long row = (((long) centreZ) + dz) & LOW_COORDINATE_MASK;
                out[at++] = xLow | row;
                out[at++] = xHigh | row;
            }
        }
        return out;
    }

    public static long pack(int x, int z) {
        return ((long) x << PACKED_X_SHIFT) | (z & LOW_COORDINATE_MASK);
    }

    public static int x(long packed) {
        return (int) (packed >> PACKED_X_SHIFT);
    }

    public static int z(long packed) {
        return (int) packed;
    }
}
