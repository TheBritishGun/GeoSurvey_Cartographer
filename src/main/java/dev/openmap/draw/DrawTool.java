package dev.openmap.draw;

public enum DrawTool {

    FREEHAND("Freehand", true),
    LINE("Line", false),
    ARROW("Arrow", false),
    RECTANGLE("Box", false),
    ELLIPSE("Circle", false),

    TEXT("Label", false),

    ERASER("Eraser", false);

    private static final DrawTool[] VALUES = values();

    private final String label;
    private final boolean continuous;

    DrawTool(String label, boolean continuous) {
        this.label = label;
        this.continuous = continuous;
    }

    public String label() {
        return label;
    }

    public boolean isContinuous() {
        return continuous;
    }

    public boolean marks() {
        return this != ERASER;
    }

    public DrawTool next() {
        DrawTool[] values = VALUES;
        int next = ordinal() + 1;
        return values[next == values.length ? 0 : next];
    }
}
