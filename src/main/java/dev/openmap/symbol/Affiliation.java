package dev.openmap.symbol;

// Standard identity: a frame shape with a fill colour.
public enum Affiliation {

    // Light blue.
    FRIEND(Frame.RECTANGLE, Fills.FRIEND, "Friendly"),

    // Light red.
    HOSTILE(Frame.DIAMOND, Fills.HOSTILE, "Hostile"),

    // Light green.
    NEUTRAL(Frame.SQUARE, Fills.NEUTRAL, "Neutral"),

    // Light yellow.
    UNKNOWN(Frame.QUATREFOIL, Fills.UNKNOWN, "Unknown");

    // One colour for all frame outlines and icons.
    public static final int LINE = 0xFF101010;

    private static final class Fills {

        private static final int FRIEND = 0xFF80E0FF;
        private static final int HOSTILE = 0xFFFF8080;
        private static final int NEUTRAL = 0xFFAAFFAA;
        private static final int UNKNOWN = 0xFFFFFF80;

        private Fills() {
        }
    }

    private final Frame frame;
    private final int fill;
    private final String label;

    Affiliation(Frame frame, int fill, String label) {
        this.frame = frame;
        this.fill = fill;
        this.label = label;
    }

    // Shown on screen.
    public String label() {
        return label;
    }

    public Frame frame() {
        return frame;
    }

    // Fill colour as ARGB.
    public int fill() {
        return fill;
    }

    public Affiliation next() {
        return switch (this) {
            case FRIEND -> HOSTILE;
            case HOSTILE -> NEUTRAL;
            case NEUTRAL -> UNKNOWN;
            case UNKNOWN -> FRIEND;
        };
    }

    // One shape per identity.
    public enum Frame {
        RECTANGLE, DIAMOND, SQUARE, QUATREFOIL
    }
}
