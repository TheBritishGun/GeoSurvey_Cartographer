package dev.openmap.config;

// Chat format of a shared waypoint.
public enum ChatShareFormat {

    LANDNAV("Grid reference"),

    XAERO("Xaero's"),

    JOURNEYMAP("JourneyMap");

    private final String label;

    ChatShareFormat(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public ChatShareFormat next() {
        return switch (this) {
            case LANDNAV -> XAERO;
            case XAERO -> JOURNEYMAP;
            case JOURNEYMAP -> LANDNAV;
        };
    }
}
