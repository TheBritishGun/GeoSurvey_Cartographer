package dev.openmap.live;

import dev.openmap.map.LabelText;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public record LiveSnapshot(List<Player> players, List<Marker> markers,
                           List<Area> areas, long fetchedAtMillis,
                           long fetchedAtNanos, long playersFetchedAtNanos,
                           long markersFetchedAtNanos, long areasFetchedAtNanos) {

    public static final long STALE_AFTER_NANOS = TimeUnit.MINUTES.toNanos(3);

    public static final LiveSnapshot EMPTY =
            new LiveSnapshot(List.of(), List.of(), List.of(), 0L);

    public LiveSnapshot(List<Player> players, List<Marker> markers,
                        List<Area> areas, long fetchedAtMillis) {
        this(players, markers, areas, fetchedAtMillis, Long.MIN_VALUE,
                Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);
    }

    public LiveSnapshot(List<Player> players, List<Marker> markers,
                        List<Area> areas, long fetchedAtMillis, long fetchedAtNanos) {
        this(players, markers, areas, fetchedAtMillis, fetchedAtNanos,
                fetchedAtNanos, fetchedAtNanos, fetchedAtNanos);
    }

    public LiveSnapshot {
        players = List.copyOf(players);
        markers = List.copyOf(markers);
        areas = List.copyOf(areas);
    }

    public List<Player> players() {
        return isFresh(playersFetchedAtNanos) ? players : List.of();
    }

    public List<Marker> markers() {
        return isFresh(markersFetchedAtNanos) ? markers : List.of();
    }

    public List<Area> areas() {
        return isFresh(areasFetchedAtNanos) ? areas : List.of();
    }

    public boolean hasFreshData(long nowNanos) {
        return isTimedFresh(playersFetchedAtNanos, nowNanos)
                || isTimedFresh(markersFetchedAtNanos, nowNanos)
                || isTimedFresh(areasFetchedAtNanos, nowNanos);
    }

    private static boolean isFresh(long fetchedAtNanos, long nowNanos) {
        return fetchedAtNanos == Long.MIN_VALUE
                || nowNanos - fetchedAtNanos <= STALE_AFTER_NANOS;
    }

    private static boolean isTimedFresh(long fetchedAtNanos, long nowNanos) {
        return fetchedAtNanos != Long.MIN_VALUE
                && nowNanos - fetchedAtNanos <= STALE_AFTER_NANOS;
    }

    private static boolean isFresh(long fetchedAtNanos) {
        return fetchedAtNanos == Long.MIN_VALUE || isFresh(fetchedAtNanos, System.nanoTime());
    }

    public record Player(String name, String world, double x, double y, double z) {
    }

    public record Marker(String id, String label, String set, String icon,
                         String world, double x, double y, double z,
                         double[] lineXs, double[] lineZs, int lineColour) {

        private static final double[] NO_LINE = new double[0];

        public Marker(String id, String label, String set, String icon,
                      String world, double x, double y, double z) {
            this(id, label, set, icon, world, x, y, z, NO_LINE, NO_LINE,
                    0xFFCBD5E1);
        }

        public Marker {
            lineXs = lineXs.length == 0 ? NO_LINE : Arrays.copyOf(lineXs, lineXs.length);
            lineZs = lineZs.length == 0 ? NO_LINE : Arrays.copyOf(lineZs, lineZs.length);
        }
    }

    public record Area(String id, String label, String set, String world,
                       double[] xs, double[] zs,
                       int lineColour, int fillColour,
                       double[][] holeXs, double[][] holeZs,
                       double[] bounds) {

        public Area(String id, String label, String set, String world,
                    double[] xs, double[] zs, int lineColour, int fillColour) {
            this(id, label, set, world, xs, zs, lineColour, fillColour,
                    NO_HOLES, NO_HOLES);
        }

        public Area(String id, String label, String set, String world,
                    double[] xs, double[] zs, int lineColour, int fillColour,
                    double[][] holeXs, double[][] holeZs) {
            this(id, label, set, world, xs, zs, lineColour, fillColour,
                    holeXs, holeZs, null);
        }

        static final int MIN_CORNERS = 3;

        private static final int MAX_LABEL_READ = 256;

        public Area {
            xs = Arrays.copyOf(xs, xs.length);
            zs = Arrays.copyOf(zs, zs.length);
            holeXs = copyRings(holeXs);
            holeZs = copyRings(holeZs);
            label = label == null ? null
                    : LabelText.clean(label, LabelText.UNBOUNDED_READ, MAX_LABEL_READ, false);
            bounds = boundsOf(xs, zs);
        }

        public boolean contains(double x, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(z)
                    || x < bounds[0] || x > bounds[2] || z < bounds[1] || z > bounds[3]) {
                return false;
            }
            if (!containsRing(xs, zs, x, z)) {
                return false;
            }
            int holes = Math.min(holeXs.length, holeZs.length);
            for (int hole = 0; hole < holes; hole++) {
                if (containsRing(holeXs[hole], holeZs[hole], x, z)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean containsRing(double[] xs, double[] zs, double x, double z) {
            int count = Math.min(xs.length, zs.length);
            if (count < MIN_CORNERS) {
                return false;
            }
            boolean inside = false;
            int j = count - 1;
            for (int i = 0; i < count; i++) {
                if ((zs[i] > z) != (zs[j] > z)) {
                    if (crossingIsRight(xs[i], zs[i], xs[j], zs[j], x, z)) {
                        inside = !inside;
                    }
                }
                j = i;
            }
            return inside;
        }

        private static boolean crossingIsRight(double firstX, double firstZ, double secondX, double secondZ,
                double x, double z) {
            double xDifference = secondX - firstX;
            double zDifference = z - firstZ;
            double zSpan = secondZ - firstZ;
            double numerator = xDifference * zDifference;
            double crossing = numerator / zSpan + firstX;
            if (Double.isFinite(xDifference) && Double.isFinite(zDifference)
                    && Double.isFinite(zSpan) && Double.isFinite(numerator)
                    && Double.isFinite(crossing)) {
                return x < crossing;
            }
            double zScale = Math.max(Math.max(Math.abs(firstZ), Math.abs(secondZ)), Math.abs(z));
            double firstScaledZ = firstZ / zScale;
            double secondScaledZ = secondZ / zScale;
            double zFraction = (z / zScale - firstScaledZ) / (secondScaledZ - firstScaledZ);
            double xScale = Math.max(Math.max(Math.abs(firstX), Math.abs(secondX)), Math.abs(x));
            if (!(xScale > 0)) {
                return false;
            }
            double firstScaledX = firstX / xScale;
            double scaledCrossing = Math.fma(secondX / xScale - firstScaledX, zFraction, firstScaledX);
            return x / xScale < scaledCrossing;
        }

        private static final double[][] NO_HOLES = new double[0][];

        private static double[][] copyRings(double[][] rings) {
            if (rings.length == 0) {
                return NO_HOLES;
            }
            double[][] copy = new double[rings.length][];
            for (int ring = 0; ring < rings.length; ring++) {
                copy[ring] = Arrays.copyOf(rings[ring], rings[ring].length);
            }
            return copy;
        }

        private static double[] boundsOf(double[] xs, double[] zs) {
            int count = Math.min(xs.length, zs.length);
            if (count == 0) {
                return new double[] {0, 0, 0, 0};
            }
            double minX = xs[0];
            double maxX = xs[0];
            double minZ = zs[0];
            double maxZ = zs[0];
            for (int i = 1; i < count; i++) {
                minX = Math.min(minX, xs[i]);
                maxX = Math.max(maxX, xs[i]);
                minZ = Math.min(minZ, zs[i]);
                maxZ = Math.max(maxZ, zs[i]);
            }
            return new double[] {minX, minZ, maxX, maxZ};
        }
    }
}
