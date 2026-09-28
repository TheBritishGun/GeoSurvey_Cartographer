package dev.openmap.config;

public enum HudAnchor {
    TOP_LEFT(true, true),
    TOP_RIGHT(false, true),
    BOTTOM_LEFT(true, false),
    BOTTOM_RIGHT(false, false);

    private final boolean left;
    private final boolean top;

    HudAnchor(boolean left, boolean top) {
        this.left = left;
        this.top = top;
    }

    public boolean isTop() {
        return top;
    }

    public int resolveX(int offsetX, int screenWidth, int elementWidth) {
        return left ? offsetX : screenWidth - offsetX - elementWidth;
    }

    public int resolveY(int offsetY, int screenHeight, int elementHeight) {
        return top ? offsetY : screenHeight - offsetY - elementHeight;
    }

    public int toOffsetX(int screenX, int screenWidth, int elementWidth) {
        return left ? screenX : screenWidth - screenX - elementWidth;
    }

    public int toOffsetY(int screenY, int screenHeight, int elementHeight) {
        return top ? screenY : screenHeight - screenY - elementHeight;
    }

    public static HudAnchor nearest(int x, int y, int screenWidth, int screenHeight) {
        boolean left = x < (screenWidth >> 1);
        boolean top = y < (screenHeight >> 1);
        return top
                ? (left ? TOP_LEFT : TOP_RIGHT)
                : (left ? BOTTOM_LEFT : BOTTOM_RIGHT);
    }
}
