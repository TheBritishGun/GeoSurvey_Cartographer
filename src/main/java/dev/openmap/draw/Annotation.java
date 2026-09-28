package dev.openmap.draw;

import dev.openmap.draw.DrawTool;
import dev.openmap.mgrs.Bounds;

public final class Annotation {

    public static final int MAX_WIDTH = 64;

    public static final int MAX_TEXT = 64;

    public static final int MAX_POINTS = 4096;

    public static final double MAX_COORDINATE = 2 * 29_999_984;

    private static final double[] EMPTY_POINTS = new double[0];

    private static final int MIN_DRAW_COORDINATES = 2;

    private static final int LEADER_MIN_COORDINATES = 4;

    private static final int DEFAULT_WIDTH = 3;

    private static final int COORDINATES_PER_POINT = 2;

    static final int BOUNDS_PER_ANNOTATION = 4;

    static final int MAX_X_BOUND_OFFSET = 2;

    static final int MAX_Z_BOUND_OFFSET = 3;

    private DrawTool tool;
    private MarkerColour colour;

    private int width = DEFAULT_WIDTH;

    private double[] points;

    private String text;

    private transient double minX;
    private transient double minZ;
    private transient double maxX;
    private transient double maxZ;

    @SuppressWarnings("unused")
    public Annotation() {
        this.tool = DrawTool.FREEHAND;
        this.colour = MarkerColour.BLACK;
        this.points = EMPTY_POINTS;
        this.text = "";
    }

    Annotation(DrawTool tool, MarkerColour colour, int width, double[] points) {
        this(tool, colour, width, points, "");
    }

    Annotation(DrawTool tool, MarkerColour colour, int width, double[] points,
               String text) {
        this.tool = tool;
        this.colour = colour;
        this.width = width;
        this.points = points;
        this.text = text;
    }

    public DrawTool tool() {
        return tool;
    }

    public MarkerColour colour() {
        return colour;
    }

    public int width() {
        return width;
    }

    public String text() {
        return text;
    }

    double[] points() {
        return points;
    }

    public int pointCount() {
        return points == null ? 0 : points.length >>> 1;
    }

    public double x(int index) {
        return points[index * COORDINATES_PER_POINT];
    }

    public double z(int index) {
        return points[index * COORDINATES_PER_POINT + 1];
    }

    public double distanceTo(double px, double pz) {
        int count = pointCount();
        double distance = Double.MAX_VALUE;
        if (count == 1) {
            double dx = px - x(0);
            double dz = pz - z(0);
            distance = Math.sqrt(dx * dx + dz * dz);
        } else if (count > 1) {
            double best = Double.MAX_VALUE;
            double[] p = points;
            double ax = p[0];
            double az = p[1];
            int last = count << 1;
            for (int j = COORDINATES_PER_POINT; j < last; j += COORDINATES_PER_POINT) {
                double bx = p[j];
                double bz = p[j + 1];
                double d = distanceToSegmentSquared(px, pz, ax, az, bx, bz);
                if (!(d >= best)) {
                    best = d;
                }
                ax = bx;
                az = bz;
            }
            distance = Math.sqrt(best);
        }
        return distance;
    }

    public boolean isWithin(double px, double pz, double radius) {
        int count = pointCount();
        boolean within = false;
        if (count != 0) {
            double limit = radius * radius;
            if (count == 1) {
                double dx = px - x(0);
                double dz = pz - z(0);
                within = dx * dx + dz * dz <= limit;
            } else {
                double[] p = points;
                double ax = p[0];
                double az = p[1];
                int last = count << 1;
                for (int j = COORDINATES_PER_POINT; j < last && !within;
                     j += COORDINATES_PER_POINT) {
                    double bx = p[j];
                    double bz = p[j + 1];
                    within = distanceToSegmentSquared(px, pz, ax, az, bx, bz) <= limit;
                    ax = bx;
                    az = bz;
                }
            }
        }
        return within;
    }

    private static double distanceToSegmentSquared(double px, double pz, double x1,
                                                    double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= 0.0) {
            double ex = px - x1;
            double ez = pz - z1;
            return ex * ex + ez * ez;
        }
        double dot = (px - x1) * dx + (pz - z1) * dz;
        double t = dot <= 0.0 ? 0.0 : dot >= lengthSquared ? 1.0 : dot / lengthSquared;
        double ex = px - (x1 + t * dx);
        double ez = pz - (z1 + t * dz);
        return ex * ex + ez * ez;
    }

    public Annotation normalise() {
        if (tool == null) {
            tool = DrawTool.FREEHAND;
        }
        if (colour == null) {
            colour = MarkerColour.BLACK;
        }
        width = Bounds.clamp(width, 1, MAX_WIDTH);
        if (text == null) {
            text = "";
        }
        if (tool != DrawTool.TEXT && !text.isEmpty()) {
            text = "";
        }
        if (text.length() > MAX_TEXT) {
            int end = MAX_TEXT;
            if (Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            text = text.substring(0, end);
        }
        if (points == null || points.length < COORDINATES_PER_POINT || (points.length & 1) != 0
                || points.length > MAX_POINTS * COORDINATES_PER_POINT) {
            points = EMPTY_POINTS;
            clearBounds();
        } else {
            double nextMinX = Double.POSITIVE_INFINITY;
            double nextMinZ = Double.POSITIVE_INFINITY;
            double nextMaxX = Double.NEGATIVE_INFINITY;
            double nextMaxZ = Double.NEGATIVE_INFINITY;
            boolean validPoints = true;
            for (int i = 0; i < points.length && validPoints; i += COORDINATES_PER_POINT) {
                double x = points[i];
                double z = points[i + 1];
                if (!(x >= -MAX_COORDINATE && x <= MAX_COORDINATE
                        && z >= -MAX_COORDINATE && z <= MAX_COORDINATE)) {
                    validPoints = false;
                } else {
                    nextMinX = x < nextMinX ? x : nextMinX;
                    nextMinZ = z < nextMinZ ? z : nextMinZ;
                    nextMaxX = x > nextMaxX ? x : nextMaxX;
                    nextMaxZ = z > nextMaxZ ? z : nextMaxZ;
                }
            }
            if (validPoints) {
                minX = nextMinX;
                minZ = nextMinZ;
                maxX = nextMaxX;
                maxZ = nextMaxZ;
            } else {
                points = EMPTY_POINTS;
                clearBounds();
            }
        }
        return this;
    }

    void writeBoundsTo(double[] target, int at) {
        target[at] = minX;
        target[at + 1] = minZ;
        target[at + MAX_X_BOUND_OFFSET] = maxX;
        target[at + MAX_Z_BOUND_OFFSET] = maxZ;
    }

    private void clearBounds() {
        minX = 0;
        minZ = 0;
        maxX = 0;
        maxZ = 0;
    }

    public boolean isDrawable() {
        double[] path = points;
        if (path == null || path.length < MIN_DRAW_COORDINATES) {
            return false;
        }
        return tool != DrawTool.TEXT || !text.isBlank();
    }

    public boolean hasLeader() {
        if (tool != DrawTool.TEXT) {
            return false;
        }
        double[] path = points;
        return path != null && path.length >= LEADER_MIN_COORDINATES;
    }
}
