package dev.openmap.draw;

public enum MarkerColour {

    BLACK(Argb.BLACK, "Black", "Friendly, boundaries and man-made features"),
    BLUE(Argb.BLUE, "Blue", "Friendly forces"),
    RED(Argb.RED, "Red", "Enemy forces and danger areas"),
    YELLOW(Argb.YELLOW, "Yellow", "Contaminated areas"),
    GREEN(Argb.GREEN, "Green", "Obstacles and engineer works"),
    BROWN(Argb.BROWN, "Brown", "Terrain features not shown on the map"),
    ORANGE(Argb.ORANGE, "Orange", "No assigned meaning; yours to use");

    private static final class Argb {

        private static final int BLACK = 0xFF1A1A1A;
        private static final int BLUE = 0xFF2A6FD6;
        private static final int RED = 0xFFD62A2A;
        private static final int YELLOW = 0xFFE0BC1E;
        private static final int GREEN = 0xFF2E9E4F;
        private static final int BROWN = 0xFF8A5A2B;
        private static final int ORANGE = 0xFFE07820;

        private Argb() {
        }
    }

    private final int colour;
    private final String label;
    private final String meaning;

    MarkerColour(int colour, String label, String meaning) {
        this.colour = colour;
        this.label = label;
        this.meaning = meaning;
    }

    public int colour() {
        return colour;
    }

    public String label() {
        return label;
    }

    public String meaning() {
        return meaning;
    }

    private static final MarkerColour[] VALUES = values();

    public MarkerColour next() {
        MarkerColour[] values = VALUES;
        int n = ordinal() + 1;
        return values[n == values.length ? 0 : n];
    }

    public static MarkerColour byName(String name) {
        MarkerColour matched = BLACK;
        if (name != null) {
            boolean found = false;
            for (int index = 0; index < VALUES.length && !found; index++) {
                MarkerColour candidate = VALUES[index];
                if (candidate.name().equalsIgnoreCase(name)) {
                    matched = candidate;
                    found = true;
                }
            }
        }
        return matched;
    }
}
