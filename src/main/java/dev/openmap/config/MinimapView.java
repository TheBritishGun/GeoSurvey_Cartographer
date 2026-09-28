package dev.openmap.config;

public enum MinimapView {

    SURFACE("Surface", null),

    CAVE_PLAN("Cave plan", dev.openmap.map.CaveProjection.View.PLAN),

    CAVE_FRONT("Cave elevation, north", dev.openmap.map.CaveProjection.View.FRONT),

    // The compass word is the direction the observer faces.
    CAVE_SIDE("Cave elevation, west", dev.openmap.map.CaveProjection.View.SIDE);

    private final String label;

    private final dev.openmap.map.CaveProjection.View projection;

    MinimapView(String label, dev.openmap.map.CaveProjection.View projection) {
        this.label = label;
        this.projection = projection;
    }

    public String label() {
        return label;
    }

    public boolean isCave() {
        return this != SURFACE;
    }

    public dev.openmap.map.CaveProjection.View projection() {
        return projection;
    }
}
