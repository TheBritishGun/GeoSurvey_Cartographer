package dev.openmap.map;

public final class CaveProjection {

    public enum View {
        PLAN,
        FRONT,
        SIDE
    }

    private CaveProjection() {
    }

    public static double across(View view, double x, double z) {
        java.util.Objects.requireNonNull(view);
        return view == View.SIDE ? -z : x;
    }

    public static double down(View view, double y, double z) {
        java.util.Objects.requireNonNull(view);
        return view == View.PLAN ? z : -y;
    }

    public static boolean sharesAcross(View a, View b) {
        return (a == View.SIDE) == (b == View.SIDE);
    }

    public static boolean sharesDown(View a, View b) {
        return (a == View.PLAN) == (b == View.PLAN);
    }

    public static int depthBand(int height, int entranceHeight, int bandSize) {
        if (bandSize <= 0) {
            throw new IllegalArgumentException("bandSize must be positive: " + bandSize);
        }
        int below = entranceHeight - height;
        if (below < bandSize) {
            return 0;
        }
        return (bandSize & (bandSize - 1)) == 0
                ? below >>> Integer.numberOfTrailingZeros(bandSize)
                : below / bandSize;
    }

    public static final class DepthBandDescriptor {

        private final int entranceHeight;
        private final int bandSize;
        private final int shift;

        private DepthBandDescriptor(int entranceHeight, int bandSize, int shift) {
            this.entranceHeight = entranceHeight;
            this.bandSize = bandSize;
            this.shift = shift;
        }

        public static DepthBandDescriptor of(int entranceHeight, int bandSize) {
            if (bandSize <= 0) {
                throw new IllegalArgumentException("bandSize must be positive: " + bandSize);
            }
            int shift = (bandSize & (bandSize - 1)) == 0 ? Integer.numberOfTrailingZeros(bandSize) : -1;
            return new DepthBandDescriptor(entranceHeight, bandSize, shift);
        }

        public int bandAt(int height) {
            int below = entranceHeight - height;
            if (below < bandSize) {
                return 0;
            }
            return shift >= 0 ? below >>> shift : below / bandSize;
        }
    }
}
