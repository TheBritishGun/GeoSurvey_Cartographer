package dev.openmap.claim;

import dev.openmap.draw.MarkerColour;
import java.util.Arrays;
import java.util.Objects;

public final class Claim {

    public static final int MIN_CORNERS = 3;

    // Must equal LiveMapRenderer.MAX_CORNERS and cairn's CORNERS_KEPT.
    public static final int MAX_CORNERS = 4096;

    // World border, in blocks. Matches Observation.CHUNK_LIMIT in chunks.
    public static final double LIMIT = 29_999_984;

    public static final int FILL_ALPHA = 0x55;

    private static final int ALPHA_SHIFT = 24;

    private static final int RGB_MASK = 0x00FFFFFF;

    private static final int FIRST_REMAINING_CORNER = 2;

    private static final int HASH_MULTIPLIER = 31;

    record Identity(String id, String owner, String ownerId, String dimension) {
    }

    private final String id;

    private final String name;

    // Descriptive only; not authorisation.
    private final String owner;

    // Empty when the client had none to give.
    private final String ownerId;

    // e.g. minecraft:overworld.
    private final String dimension;

    // Block coordinates.
    private final double[] xs;

    private final double[] zs;

    private final double minX;

    private final double minZ;

    private final double maxX;

    private final double maxZ;

    private final MarkerColour colour;

    // Epoch milliseconds UTC.
    private final long revised;

    public Claim(String id, String name, String owner, String ownerId,
                 String dimension, double[] xs, double[] zs,
                 MarkerColour colour, long revised) {
        this(id, name, owner, ownerId, dimension, xs, zs, colour, revised, true);
    }

    static Claim fromPen(Identity who, String name, double[] xs, double[] zs,
                         MarkerColour colour, long revised) {
        return fromOwnedArrays(who, name, xs, zs, colour, revised);
    }

    // Keeps the given arrays; the caller must not read or write them again.
    static Claim fromOwnedArrays(Identity who, String name, double[] xs, double[] zs,
                                 MarkerColour colour, long revised) {
        return new Claim(who.id(), name, who.owner(), who.ownerId(), who.dimension(),
                xs, zs, colour, revised, false);
    }

    private Claim(String id, String name, String owner, String ownerId,
                  String dimension, double[] xs, double[] zs,
                  MarkerColour colour, long revised, boolean copyCorners) {
        Objects.requireNonNull(xs, "xs");
        Objects.requireNonNull(zs, "zs");
        this.id = trimmed(id, "id");
        this.name = trimmed(name, "name");
        this.owner = owner == null ? "" : owner.trim();
        this.ownerId = ownerId == null ? "" : ownerId.trim();
        this.dimension = trimmed(dimension, "dimension");
        this.colour = colour == null ? MarkerColour.BLACK : colour;
        if (xs.length != zs.length) {
            throw new IllegalArgumentException("a boundary with " + xs.length
                    + " x values and " + zs.length + " z values is not a boundary"
                    + "");
        }
        if (xs.length < MIN_CORNERS) {
            throw new IllegalArgumentException(xs.length + " corners cannot enclose"
                    + " anything. A claim wants at least " + MIN_CORNERS);
        }
        if (xs.length > MAX_CORNERS) {
            throw new IllegalArgumentException(xs.length + " corners is more than the"
                    + " " + MAX_CORNERS + " the map viewer will draw");
        }
        double[] copyX = copyCorners ? xs.clone() : xs;
        checked(copyX[0], 0, "x");
        double minX = copyX[0];
        double maxX = copyX[0];
        for (int i = 1; i < copyX.length; i++) {
            double value = copyX[i];
            checked(value, i, "x");
            if (value < minX) {
                minX = value;
            } else if (value > maxX) {
                maxX = value;
            } else {
            }
        }
        double[] copyZ = copyCorners ? zs.clone() : zs;
        checked(copyZ[0], 0, "z");
        double minZ = copyZ[0];
        double maxZ = copyZ[0];
        for (int i = 1; i < copyZ.length; i++) {
            double value = copyZ[i];
            checked(value, i, "z");
            if (value < minZ) {
                minZ = value;
            } else if (value > maxZ) {
                maxZ = value;
            } else {
            }
        }
        this.xs = copyX;
        this.zs = copyZ;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.revised = revised;
        if (enclosesNothing(copyX, copyZ, copyX.length)) {
            throw new IllegalArgumentException("these " + copyX.length
                    + " corners enclose no ground. A claim wants an inside");
        }
    }

    private Claim(Claim source, String name, MarkerColour colour, long revised) {
        this.id = source.id;
        this.name = trimmed(name, "name");
        this.owner = source.owner;
        this.ownerId = source.ownerId;
        this.dimension = source.dimension;
        this.xs = source.xs;
        this.zs = source.zs;
        this.minX = source.minX;
        this.minZ = source.minZ;
        this.maxX = source.maxX;
        this.maxZ = source.maxZ;
        this.colour = colour == null ? MarkerColour.BLACK : colour;
        this.revised = revised;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String owner() {
        return owner;
    }

    public String ownerId() {
        return ownerId;
    }

    public String dimension() {
        return dimension;
    }

    public double[] xs() {
        return xs.clone();
    }

    public double[] zs() {
        return zs.clone();
    }

    double[] rawXs() {
        return xs;
    }

    double[] rawZs() {
        return zs;
    }

    public MarkerColour colour() {
        return colour;
    }

    public long revised() {
        return revised;
    }

    public int corners() {
        return xs.length;
    }

    // The stroke colour, opaque.
    public int lineColour() {
        return colour.colour();
    }

    public int fillColour() {
        return (FILL_ALPHA << ALPHA_SHIFT) | (colour.colour() & RGB_MASK);
    }

    // minX, minZ, maxX, maxZ.
    public double[] bounds() {
        return new double[] {minX, minZ, maxX, maxZ};
    }

    // Copy with a new name; same id and corners.
    public Claim named(String other, long now) {
        return new Claim(this, other, colour, now);
    }

    public Claim coloured(MarkerColour other, long now) {
        return new Claim(this, name, other, now);
    }

    // Case-insensitive and padding-insensitive.
    public boolean isCalled(String handle) {
        return handle != null && isCalledTrimmed(handle.trim());
    }

    boolean isCalledTrimmed(String trimmedHandle) {
        return trimmedHandle != null && name.equalsIgnoreCase(trimmedHandle);
    }

    private static String trimmed(String value, String field) {
        String out = value == null ? "" : value.trim();
        if (out.isEmpty()) {
            throw new IllegalArgumentException("a claim needs a " + field
                    + "; a blank one names nothing");
        }
        return out;
    }

    private static void checked(double value, int corner, String axis) {
        if (!(Math.abs(value) <= LIMIT)) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("corner " + corner + " has " + axis
                        + " = " + value + ": not a place");
            }
            throw new IllegalArgumentException("corner " + corner + " has " + axis
                    + " = " + value + ", outside the world border at "
                    + (long) LIMIT + "; no tile is ever painted there");
        }
    }

    static boolean enclosesNothing(double[] xs, double[] zs, int count) {
        if (count < MIN_CORNERS) {
            return true;
        }
        final double x0 = xs[0];
        final double z0 = zs[0];
        double sum = 0;
        double px = xs[1] - x0;
        double pz = zs[1] - z0;
        for (int i = FIRST_REMAINING_CORNER; i < count; i++) {
            double nx = xs[i] - x0;
            double nz = zs[i] - z0;
            sum += px * nz - nx * pz;
            px = nx;
            pz = nz;
        }
        return Double.compare(sum, 0.0) == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Claim that)) {
            return false;
        }
        return revised == that.revised
                && id.equals(that.id)
                && name.equals(that.name)
                && owner.equals(that.owner)
                && ownerId.equals(that.ownerId)
                && dimension.equals(that.dimension)
                && Arrays.equals(xs, that.xs)
                && Arrays.equals(zs, that.zs)
                && colour == that.colour;
    }

    @Override
    public int hashCode() {
        int hash = id.hashCode();
        hash = HASH_MULTIPLIER * hash + name.hashCode();
        hash = HASH_MULTIPLIER * hash + owner.hashCode();
        hash = HASH_MULTIPLIER * hash + ownerId.hashCode();
        hash = HASH_MULTIPLIER * hash + dimension.hashCode();
        hash = HASH_MULTIPLIER * hash + Arrays.hashCode(xs);
        hash = HASH_MULTIPLIER * hash + Arrays.hashCode(zs);
        hash = HASH_MULTIPLIER * hash + colour.hashCode();
        return HASH_MULTIPLIER * hash + Long.hashCode(revised);
    }

    @Override
    public String toString() {
        return "Claim[id=" + id + ", name=" + name + ", owner=" + owner
                + ", ownerId=" + ownerId + ", dimension=" + dimension
                + ", xs=" + xs + ", zs=" + zs + ", colour=" + colour
                + ", revised=" + revised + "]";
    }
}
