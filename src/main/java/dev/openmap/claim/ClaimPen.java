package dev.openmap.claim;

import dev.openmap.draw.MarkerColour;
import java.util.Arrays;
import java.util.function.Supplier;

public final class ClaimPen {

    private final double[] xs = new double[Claim.MAX_CORNERS];

    private final double[] zs = new double[Claim.MAX_CORNERS];

    private int count;

    private String name = "";

    private String dimension = "";

    private MarkerColour colour = MarkerColour.BLACK;

    // True once begin() has been called and clear() has not.
    public boolean drawing() {
        return !dimension.isEmpty();
    }

    public String name() {
        return name;
    }

    public String dimension() {
        return dimension;
    }

    public MarkerColour colour() {
        return colour;
    }

    public int size() {
        return count;
    }

    public void begin(String claimName, String inDimension, double x, double z) {
        count = 0;
        name = claimName == null ? "" : claimName.trim();
        dimension = inDimension == null ? "" : inDimension.trim();
        colour = MarkerColour.BLACK;
        if (dimension.isEmpty()) {
            clear();
        } else {
            cornerHere(x, z);
        }
    }

    public void clear() {
        count = 0;
        name = "";
        dimension = "";
        colour = MarkerColour.BLACK;
    }

    public void colour(MarkerColour other) {
        colour = other == null ? MarkerColour.BLACK : other;
    }

    public void name(String other) {
        name = other == null ? "" : other.trim();
    }

    // corner()'s refusal reason, or NONE.
    public enum Refusal {

        NONE,

        // A corner in a dimension the boundary did not start in.
        ELSEWHERE,

        // The same block as the corner before it.
        REPEATED,

        // Claim.MAX_CORNERS already.
        FULL,

        OUTSIDE_BORDER
    }

    public Refusal corner(String inDimension, double x, double z) {
        if (!dimension.isEmpty()) {
            String where = inDimension == null ? "" : inDimension.trim();
            if (!dimension.equals(where)) {
                return Refusal.ELSEWHERE;
            }
        }
        return cornerHere(x, z);
    }

    private Refusal cornerHere(double x, double z) {
        int n = count;
        if (n >= Claim.MAX_CORNERS) {
            return Refusal.FULL;
        }
        if (!(x >= -Claim.LIMIT && x <= Claim.LIMIT
                && z >= -Claim.LIMIT && z <= Claim.LIMIT)) {
            return Refusal.OUTSIDE_BORDER;
        }
        if (n > 0 && xs[n - 1] == x && zs[n - 1] == z) {
            return Refusal.REPEATED;
        }
        xs[n] = x;
        zs[n] = z;
        count = n + 1;
        return Refusal.NONE;
    }

    // Drop the last corner. False when there was none to drop.
    public boolean undo() {
        int n = count;
        if (n == 0) {
            return false;
        }
        n--;
        count = n;
        return true;
    }

    public boolean enough() {
        return count >= Claim.MIN_CORNERS;
    }

    boolean closeable() {
        return count >= Claim.MIN_CORNERS && !name.isEmpty() && !dimension.isEmpty()
                && !Claim.enclosesNothing(xs, zs, count);
    }

    public Claim finish(String id, String owner, String ownerId, long now) {
        if (!enough()) {
            throw new IllegalStateException(count + " corners is not a shape");
        }
        return Claim.fromPen(new Claim.Identity(id, owner, ownerId, dimension), name,
                Arrays.copyOf(xs, count), Arrays.copyOf(zs, count), colour, now);
    }

    public Claim finish(Supplier<String> id, String owner, String ownerId, long now) {
        if (!enough()) {
            throw new IllegalStateException(count + " corners is not a shape");
        }
        String minted = closeable() ? id.get() : PENDING_ID;
        return Claim.fromPen(new Claim.Identity(minted, owner, ownerId, dimension),
                name, Arrays.copyOf(xs, count), Arrays.copyOf(zs, count), colour, now);
    }

    private static final String PENDING_ID = "pending";

    // Box corners for 2 opposite points, clockwise from north-west. Returns {xs, zs}.
    public static double[][] rectangle(double x1, double z1, double x2, double z2) {
        double westX;
        double eastX;
        if (Double.isNaN(x1)) {
            westX = x1;
            eastX = x1;
        } else if (Double.isNaN(x2)) {
            westX = x2;
            eastX = x2;
        } else if (Double.compare(x1, x2) <= 0) {
            westX = x1;
            eastX = x2;
        } else {
            westX = x2;
            eastX = x1;
        }
        double northZ;
        double southZ;
        if (Double.isNaN(z1)) {
            northZ = z1;
            southZ = z1;
        } else if (Double.isNaN(z2)) {
            northZ = z2;
            southZ = z2;
        } else if (Double.compare(z1, z2) <= 0) {
            northZ = z1;
            southZ = z2;
        } else {
            northZ = z2;
            southZ = z1;
        }
        return new double[][] {
            {westX, eastX, eastX, westX},
            {northZ, northZ, southZ, southZ},
        };
    }
}
