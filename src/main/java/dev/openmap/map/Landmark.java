package dev.openmap.map;

import dev.openmap.symbol.Affiliation;
import dev.openmap.symbol.SymbolIcon;

// A named point the player plotted.
public final class Landmark {

    // Longest name drawn, in glyphs.
    public static final int MAX_NAME = 64;

    private static final int ALPHA_SHIFT = 24;

    public static final int OPAQUE_ALPHA = 0xFF000000;

    private String name;
    private int x;
    private int z;

    // ARGB; 0 means the default.
    private int colour;

    // May be null.
    private Affiliation affiliation;

    // Null means a plain point.
    private SymbolIcon icon;

    // Null when the marker is not shared.
    private String shared;

    // For JsonBind.
    @SuppressWarnings("unused")
    public Landmark() {
        this(null, 0, 0, 0, null, null);
    }

    public Landmark(String name, int x, int z, int colour) {
        this(name, x, z, colour, Affiliation.UNKNOWN, SymbolIcon.WAYPOINT);
    }

    public Landmark(String name, int x, int z, int colour,
                    Affiliation affiliation, SymbolIcon icon) {
        this.name = name;
        this.x = x;
        this.z = z;
        this.colour = colour;
        this.affiliation = affiliation;
        this.icon = icon;
        normalise();
    }

    public String name() {
        return name;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public int colour() {
        return colour;
    }

    public Affiliation affiliation() {
        return affiliation;
    }

    public SymbolIcon icon() {
        return icon;
    }

    public String shared() {
        return shared;
    }

    void setName(String newName) {
        name = newName == null ? "" : newName;
        normalise();
    }

    void setColour(int argb) {
        colour = argb;
        normalise();
    }

    void setIcon(SymbolIcon newIcon) {
        icon = newIcon;
        normalise();
    }

    void setAffiliation(Affiliation newAffiliation) {
        affiliation = newAffiliation;
        normalise();
    }

    void setShared(String newShared) {
        shared = newShared;
    }

    // Squared distance in blocks; saturates, never wraps.
    public long distanceSquared(int fromX, int fromZ) {
        long dx = (long) x - fromX;
        long dz = (long) z - fromZ;
        if (saturatesWhenSquared(dx) || saturatesWhenSquared(dz)) {
            return Long.MAX_VALUE;
        }
        long sum = square(dx) + square(dz);
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    // The largest value whose square fits a long.
    private static final long WIDEST_EXACT_ROOT = 3_037_000_499L;

    private static boolean saturatesWhenSquared(long v) {
        return v > WIDEST_EXACT_ROOT || v < -WIDEST_EXACT_ROOT;
    }

    private static long square(long v) {
        return saturatesWhenSquared(v) ? Long.MAX_VALUE : v * v;
    }

    // Makes the entry drawable.
    public Landmark normalise() {
        name = LabelText.clean(name, MAX_NAME, LabelText.UNBOUNDED_READ, false).trim();
        if (name.isBlank()) {
            name = "unnamed";
        }
        if (affiliation == null) {
            affiliation = Affiliation.UNKNOWN;
        }
        if (icon == null) {
            icon = SymbolIcon.WAYPOINT;
        }
        if ((colour >>> ALPHA_SHIFT) == 0) {
            colour = colour == 0 ? DEFAULT_COLOUR : (colour | OPAQUE_ALPHA);
        }
        return this;
    }

    public static final int DEFAULT_COLOUR = 0xFFFFCC33;

    @Override
    public String toString() {
        return name + " (" + x + ", " + z + ")";
    }
}
