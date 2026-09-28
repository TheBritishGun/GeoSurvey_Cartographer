package dev.openmap.map;

public final class StructureProbe {

    public enum Kind {

        WOOD,

        // Reads as a roof, not a floor.
        CANOPY,

        AIR,

        FURNITURE,

        NATURAL,

        WORKED,

        OTHER
    }

    public static final int DEPTH = 12;

    private StructureProbe() {
    }

    public static boolean isTimber(Kind kind) {
        return kind == Kind.WOOD || kind == Kind.CANOPY;
    }

    public static boolean isStructure(Kind[] column) {
        if (column == null) {
            return false;
        }
        int at = 0;
        boolean canopy = false;
        while (at < column.length) {
            Kind kind = column[at];
            if (kind == Kind.CANOPY) {
                canopy = true;
            } else if (kind != Kind.WOOD) {
                break;
            } else {
            }
            at++;
        }

        boolean gap = at < column.length && column[at] == Kind.AIR;
        boolean closed = false;
        boolean naturalSeen = false;
        boolean found = false;
        // Canopy is not a floor.
        // Furniture below already-read natural ground does not settle the
        // column.
        for (int i = at; i < column.length && !found; i++) {
            Kind kind = column[i];
            boolean natural = kind == Kind.NATURAL;
            if (natural) {
                naturalSeen = true;
            }
            if (kind == Kind.FURNITURE) {
                found = !naturalSeen;
            } else if (!closed) {
                if (natural || kind == Kind.CANOPY || (canopy && kind == Kind.WOOD)) {
                    closed = true;
                } else if (kind == Kind.AIR) {
                    gap = true;
                } else if (gap && (kind == Kind.WORKED || (!canopy && kind == Kind.WOOD))) {
                    found = true;
                } else {
                }
            } else {
            }
        }
        return found;
    }
}
