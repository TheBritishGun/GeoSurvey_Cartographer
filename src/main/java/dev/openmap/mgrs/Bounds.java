package dev.openmap.mgrs;

public final class Bounds {

    private Bounds() {
    }

    public static int clamp(int value, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return Math.min(max, Math.max(value, min));
    }

    public static int clamp(long value, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return (int) Math.min(max, Math.max(value, min));
    }

    public static long clamp(long value, long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return Math.min(max, Math.max(value, min));
    }

    public static double clamp(double value, double min, double max) {
        if (min < value && value < max) {
            return value;
        }
        if (!(min < max)) {
            requireOrdered(min, max);
        }
        return Math.min(max, Math.max(value, min));
    }

    private static void requireOrdered(double min, double max) {
        if (min >= max) {
            if (min > max || (Double.doubleToRawLongBits(min) == 0L
                    && Double.doubleToRawLongBits(max) != 0L)) {
                throw new IllegalArgumentException(min + " > " + max);
            }
        } else {
            if (Double.isNaN(min)) {
                throw new IllegalArgumentException("min is NaN");
            }
            if (Double.isNaN(max)) {
                throw new IllegalArgumentException("max is NaN");
            }
            throw new IllegalArgumentException(min + " > " + max);
        }
    }

    public static float clamp(float value, float min, float max) {
        if (min < value && value < max) {
            return value;
        }
        if (!(min < max)) {
            requireOrdered(min, max);
        }
        return Math.min(max, Math.max(value, min));
    }

    private static void requireOrdered(float min, float max) {
        if (min >= max) {
            if (min > max || (Float.floatToRawIntBits(min) == 0 && Float.floatToRawIntBits(max) != 0)) {
                throw new IllegalArgumentException(min + " > " + max);
            }
        } else {
            if (Float.isNaN(min)) {
                throw new IllegalArgumentException("min is NaN");
            }
            if (Float.isNaN(max)) {
                throw new IllegalArgumentException("max is NaN");
            }
            throw new IllegalArgumentException(min + " > " + max);
        }
    }
}
